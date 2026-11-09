package com.fahim.cloudide;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * The assistant's sandbox: a separate workspace where it runs commands and code, unpacks zips and prepares files for the person.
 * Two backends: a folder on the phone (commands run in the app's own shell, same as run_command) or the person's own sandbox
 * server ({@link SandboxClient}, see sandbox-server/). The assistant sees the same actions either way.
 *
 * <p>The sandbox is not the project: files only move between them when the assistant asks (sandbox_import, sandbox_export), and
 * imports leave out credentials, binaries and build output. Paths always stay inside the workspace ({@link SandboxFiles#resolve}).
 */
final class SandboxTools {
    static boolean handles(String name) { return AiActions.isSandbox(name); }

    private final Context app;
    private final AgentTools tools;

    SandboxTools(Context c, AgentTools tools) {
        this.app = c.getApplicationContext();
        this.tools = tools;
    }

    boolean remote() { return AiConfig.sandboxMode(app).equals("remote"); }

    /** The workspace folder on the phone. */
    File localRoot() {
        File d = new File(app.getFilesDir(), "sandbox");
        d.mkdirs();
        return d;
    }

    private SandboxClient client() {
        return new SandboxClient(AiConfig.sandboxUrl(app), AiConfig.sandboxToken(app), AiConfig.sandboxSession(app));
    }

    private static String need(AiActions.Action a, String k) {
        String v = a.str(k);
        if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException("\"" + k + "\" is needed for " + a.name);
        return v;
    }

    private static int timeout(AiActions.Action a) { return (int) Math.max(5, Math.min(600, a.num("timeout", 120))); }

    // ------------------------------------------------------------ dispatch
    AiActions.Result run(AiActions.Action a) {
        String missing = AiConfig.sandboxMissing(app);
        if (missing != null) return AiActions.Result.fail(a, missing);
        try {
            switch (a.name) {
                case "sandbox_run": return runCmd(a);
                case "sandbox_code": return code(a);
                case "sandbox_list": return list(a);
                case "sandbox_read": return read(a);
                case "sandbox_write": return write(a);
                case "sandbox_unzip": return unzip(a);
                case "sandbox_import": return importFrom(a);
                case "sandbox_export": return export(a);
                case "sandbox_reset": return reset(a);
                default: return AiActions.Result.fail(a, "unknown action " + a.name);
            }
        } catch (IllegalArgumentException e) {
            return AiActions.Result.fail(a, String.valueOf(e.getMessage()));
        } catch (IOException e) {
            return AiActions.Result.fail(a, "sandbox: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        } catch (Throwable e) {
            AppLog.e("Sandbox", a.name + " failed", e);
            return AiActions.Result.fail(a, "failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    // ------------------------------------------------------------ running
    private SandboxFiles.Exec exec(String command, String cwd, int timeout) throws Exception {
        if (remote()) return client().exec(command, cwd, timeout);
        File dir = SandboxFiles.resolve(localRoot(), cwd);
        if (!dir.isDirectory()) throw new IllegalArgumentException("cwd is not a folder: " + cwd);
        return tools.execIn(dir, command, timeout);
    }

    private AiActions.Result finish(AiActions.Action a, SandboxFiles.Exec ex, int timeout) {
        String text = ex.out;
        if (ex.timedOut) text += "\n[stopped: the command ran longer than " + timeout + " seconds]";
        else if (tools.cancelled()) text += "\n[stopped by the person]";
        else if (ex.code != 0) text += "\n[exit " + ex.code + "]";
        if (!remote() && (ex.code == 127 || text.contains("not found")) && !ex.ok())
            text += "\n[hint: on the phone only the installed language plugins exist; install one with the plugins action, or ask the person to use a sandbox server]";
        if (text.trim().isEmpty()) text = "(no output, exit " + ex.code + ")";
        return new AiActions.Result(a, ex.ok() && !tools.cancelled(), text, null);
    }

    private AiActions.Result runCmd(AiActions.Action a) throws Exception {
        String cmd = need(a, "command").trim();
        int to = timeout(a);
        return finish(a, exec(cmd, a.str("cwd"), to), to);
    }

    private AiActions.Result code(AiActions.Action a) throws Exception {
        String lang = need(a, "language");
        String src = a.str("code");
        if (src == null || src.trim().isEmpty()) throw new IllegalArgumentException("\"code\" is needed for sandbox_code");
        String name = SandboxFiles.codeName(lang);
        if (name == null) throw new IllegalArgumentException("language \"" + lang + "\" is not supported: use python, node, java, bash, ruby, php, go, rust, c or cpp");
        String rel = "snippets/" + name;
        putText(rel, src);
        String line = SandboxFiles.runLine(lang, rel);
        String args = a.str("args");
        if (args != null && !args.trim().isEmpty()) line += " " + args.trim();
        int to = timeout(a);
        return finish(a, exec(line, "", to), to);
    }

    // ------------------------------------------------------------ files
    private void putText(String rel, String text) throws Exception {
        if (remote()) {
            client().write(rel, text);
            return;
        }
        File f = SandboxFiles.resolve(localRoot(), rel);
        if (f.equals(localRoot().getCanonicalFile()) || f.isDirectory()) throw new IllegalArgumentException("give a file path");
        File p = f.getParentFile();
        if (p != null && !p.isDirectory() && !p.mkdirs()) throw new IOException("Could not create the folder for " + rel);
        Io.write(f, text);
    }

    private AiActions.Result list(AiActions.Action a) throws Exception {
        String path = a.str("path") == null ? "" : a.str("path");
        int depth = (int) Math.max(1, Math.min(6, a.num("depth", 2)));
        if (remote()) return AiActions.Result.ok(a, client().list(path, depth));
        File d = SandboxFiles.resolve(localRoot(), path);
        if (!d.isDirectory()) return AiActions.Result.fail(a, "not a folder: " + path);
        return AiActions.Result.ok(a, SandboxFiles.list(localRoot(), d, depth, 400));
    }

    private AiActions.Result read(AiActions.Action a) throws Exception {
        String path = need(a, "path");
        int max = (int) Math.max(1000, Math.min(SandboxFiles.MAX_READ, a.num("max", 100000)));
        if (remote()) return AiActions.Result.ok(a, client().read(path, max));
        return AiActions.Result.ok(a, SandboxFiles.readText(SandboxFiles.resolve(localRoot(), path), max));
    }

    private AiActions.Result write(AiActions.Action a) throws Exception {
        String path = need(a, "path");
        String content = a.str("content");
        if (content == null) throw new IllegalArgumentException("\"content\" is needed for sandbox_write");
        putText(path, content);
        return AiActions.Result.ok(a, "wrote " + path + " (" + content.length() + " characters)");
    }

    private AiActions.Result unzip(AiActions.Action a) throws Exception {
        String path = need(a, "path");
        String to = a.str("to");
        if (to == null || to.trim().isEmpty()) to = SandboxFiles.folderFor(path);
        if (remote()) return AiActions.Result.ok(a, "unpacked " + path + " into " + to + ": " + client().unzip(path, to));
        File z = SandboxFiles.resolve(localRoot(), path);
        if (!z.isFile()) return AiActions.Result.fail(a, "no such file in the sandbox: " + path);
        SandboxFiles.Unzipped u = SandboxFiles.unzip(z, SandboxFiles.resolve(localRoot(), to));
        return AiActions.Result.ok(a, "unpacked " + path + " into " + to + ": " + u.summary());
    }

    // ------------------------------------------------------------ between sandbox, project and the person
    /** Puts the zip's contents into the sandbox folder {@code to} (on the phone or on the server). Returns a summary. */
    String deliverZip(File zip, String to) throws Exception {
        if (!remote()) return SandboxFiles.unzip(zip, SandboxFiles.resolve(localRoot(), to)).summary();
        SandboxClient c = client();
        String tmp = "uploads/.incoming-" + System.nanoTime() + ".zip";
        c.upload(tmp, zip);
        try {
            return c.unzip(tmp, to);
        } finally {
            try {
                c.exec("rm -f '" + tmp + "'", "", 20);
            } catch (Exception ignored) {
                // a leftover zip is harmless
            }
        }
    }

    /**
     * A file the person attached to the chat. A zip is unpacked into uploads/&lt;name&gt;/, anything else is stored in uploads/.
     * Returns the sentence that tells the assistant where it is. Blocking: call it off the UI thread.
     */
    String upload(File file, String displayName) throws Exception {
        String miss = AiConfig.sandboxMissing(app);
        if (miss != null) throw new IOException(miss);
        String name = SandboxFiles.safeName(displayName == null || displayName.isEmpty() ? file.getName() : displayName);
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            String dir = "uploads/" + SandboxFiles.folderFor(name);
            String sum = deliverZip(file, dir);
            return "The person uploaded " + name + " to the sandbox: unpacked into " + dir + "/ (" + sum + ").";
        }
        if (file.length() > SandboxFiles.MAX_UNZIPPED) throw new IOException("That file is larger than " + SandboxFiles.size(SandboxFiles.MAX_UNZIPPED) + ".");
        String rel = "uploads/" + name;
        if (remote()) client().upload(rel, file);
        else {
            File dst = SandboxFiles.resolve(localRoot(), rel);
            File p = dst.getParentFile();
            if (p != null) p.mkdirs();
            Io.copy(file, dst);
        }
        return "The person uploaded " + name + " to the sandbox: it is at " + rel + " (" + SandboxFiles.size(file.length()) + ").";
    }

    private AiActions.Result importFrom(AiActions.Action a) throws Exception {
        File proj = tools.currentProject(a.str("project"));
        String from = a.str("from");
        File src = proj;
        if (from != null && !from.trim().isEmpty() && !from.trim().equals(".")) {
            String p = from.trim().replace('\\', '/');
            while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
            if (p.startsWith("/") || p.contains("..")) throw new IllegalArgumentException("\"from\" must be a path inside the project");
            src = new File(proj, p).getCanonicalFile();
            if (!src.getPath().startsWith(proj.getCanonicalPath() + File.separator)) throw new IllegalArgumentException("\"from\" leaves the project");
        }
        if (!src.exists()) return AiActions.Result.fail(a, "no such file or folder in the project: " + from);
        String to = a.str("to");
        if (to == null || to.trim().isEmpty()) to = "project/" + SandboxFiles.safeName(proj.getName());
        File tmp = File.createTempFile("sbximport", ".zip", app.getCacheDir());
        try {
            int[] n = {0, 0};
            writeProjectZip(proj, src, tmp, n);
            if (n[0] == 0) return AiActions.Result.fail(a, "nothing to import: no text files there (credentials, binaries and build output are left out)");
            String sum = deliverZip(tmp, to);
            return AiActions.Result.ok(a, "imported " + n[0] + " files into " + to + (n[1] > 0 ? " (" + n[1] + " left out: credentials, binaries, build output)" : "") + ": " + sum);
        } finally {
            tmp.delete();
        }
    }

    /** Zips the allowed files under {@code src}, named relative to the project, so the folder structure survives. */
    private static void writeProjectZip(File proj, File src, File out, int[] counts) throws IOException {
        try (java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(out)))) {
            addProject(zo, proj, src, counts, new long[]{0});
        }
    }

    private static void addProject(java.util.zip.ZipOutputStream zo, File proj, File f, int[] counts, long[] total) throws IOException {
        if (java.nio.file.Files.isSymbolicLink(f.toPath())) return;
        if (f.isDirectory()) {
            if (AiProtocol.skippedDir(f.getName()) && !f.equals(proj)) {
                counts[1]++;
                return;
            }
            File[] kids = f.listFiles();
            if (kids == null) return;
            java.util.Arrays.sort(kids);
            for (File k : kids) addProject(zo, proj, k, counts, total);
            return;
        }
        String rel = proj.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/');
        String ok = AiProtocol.cleanPath(rel);
        if (ok == null || f.length() > AiZip.MAX_FILE) {
            counts[1]++;
            return;
        }
        total[0] += f.length();
        if (total[0] > SandboxFiles.MAX_ZIP_OUT) throw new IOException("The project part is larger than " + SandboxFiles.size(SandboxFiles.MAX_ZIP_OUT) + ".");
        zo.putNextEntry(new java.util.zip.ZipEntry(ok));
        java.nio.file.Files.copy(f.toPath(), zo);
        zo.closeEntry();
        counts[0]++;
    }

    private AiActions.Result export(AiActions.Action a) throws Exception {
        String path = need(a, "path");
        File outDir = new File(Prefs.root(app), "sandbox-exports");
        if (!outDir.isDirectory() && !outDir.mkdirs()) throw new IOException("Could not create " + outDir);
        String base = SandboxFiles.safeName(a.str("name") != null && !a.str("name").trim().isEmpty() ? a.str("name") : new File(path.replace('\\', '/')).getName());
        if (base.equals("file") && (path.trim().isEmpty() || path.trim().equals("."))) base = "sandbox";
        File dest;
        if (remote()) {
            File tmp = File.createTempFile("sbxdl", ".bin", app.getCacheDir());
            try {
                client().download(path, tmp);
                boolean isZip = looksZip(tmp);
                // a folder comes back as a zip; a single file keeps its own name
                String n = isZip && !base.toLowerCase(Locale.ROOT).endsWith(".zip") && isFolderRequest(path) ? base + ".zip" : base;
                dest = unique(outDir, n);
                Io.copy(tmp, dest);
            } finally {
                tmp.delete();
            }
        } else {
            File src = SandboxFiles.resolve(localRoot(), path);
            if (!src.exists()) return AiActions.Result.fail(a, "no such file or folder in the sandbox: " + path);
            if (src.isDirectory()) {
                dest = unique(outDir, base.toLowerCase(Locale.ROOT).endsWith(".zip") ? base : base + ".zip");
                int n = SandboxFiles.zip(src, dest);
                if (n == 0) {
                    dest.delete();
                    return AiActions.Result.fail(a, "that folder has no files");
                }
            } else {
                dest = unique(outDir, base);
                Io.copy(src, dest);
            }
        }
        return AiActions.Result.ok(a, "saved for the person: " + dest.getAbsolutePath() + " (" + SandboxFiles.size(dest.length()) + "). Tell them it is in the projects folder under sandbox-exports.");
    }

    private static boolean isFolderRequest(String path) {
        String n = new File(path.replace('\\', '/')).getName();
        return !n.contains(".") || path.trim().isEmpty() || path.trim().equals(".");
    }

    private static boolean looksZip(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            return in.read() == 'P' && in.read() == 'K';
        } catch (IOException e) {
            return false;
        }
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            f = new File(dir, stem + "-" + i + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, stem + "-" + System.currentTimeMillis() + ext);
    }

    private AiActions.Result reset(AiActions.Action a) throws Exception {
        if (remote()) client().reset();
        else {
            File r = localRoot();
            File[] kids = r.listFiles();
            if (kids != null) for (File k : kids) SandboxFiles.deleteTree(k);
        }
        return AiActions.Result.ok(a, "the sandbox is empty now");
    }

    /** What Settings shows after "Test": whether the sandbox is reachable. Blocking. */
    String test() throws Exception {
        String miss = AiConfig.sandboxMissing(app);
        if (miss != null) throw new IOException(miss);
        if (remote()) return client().health();
        return "On this phone: " + localRoot().getAbsolutePath();
    }
}
