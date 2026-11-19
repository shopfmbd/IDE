package com.fahim.cloudide;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * File handling for the AI sandbox workspace. Plain JDK (no Android), so it is tested on a plain JVM.
 * Everything the assistant asks for goes through {@link #resolve}: a path stays inside the workspace, whatever it contains
 * (.., absolute paths, links that point outside). Unpacking a zip refuses entries that would leave the folder, links, and archives
 * that are too big or have too many files (zip bombs).
 */
final class SandboxFiles {
    private SandboxFiles() { }

    static final int MAX_ENTRIES = 20000;
    static final long MAX_UNZIPPED = 300L * 1024 * 1024, MAX_ZIP_OUT = 100L * 1024 * 1024, MAX_READ = 400 * 1024;

    /** What one finished command came to. */
    static final class Exec {
        final String out;
        final int code;
        final boolean timedOut;

        Exec(String out, int code, boolean timedOut) {
            this.out = out == null ? "" : out;
            this.code = code;
            this.timedOut = timedOut;
        }

        boolean ok() { return code == 0 && !timedOut; }
    }

    /** What an unzip did. */
    static final class Unzipped {
        int files, dirs, skipped;
        long bytes;

        String summary() {
            return files + (files == 1 ? " file" : " files") + (dirs > 0 ? ", " + dirs + (dirs == 1 ? " folder" : " folders") : "") + ", " + size(bytes)
                    + (skipped > 0 ? ", " + skipped + " entries skipped (unsafe path or link)" : "");
        }
    }

    /** A path inside {@code root}; "" and "." mean the root itself. Throws IllegalArgumentException for anything that leaves it. */
    static File resolve(File root, String rel) throws IOException {
        String p = rel == null ? "" : rel.trim().replace('\\', '/');
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*")) throw new IllegalArgumentException("the path \"" + rel + "\" must be relative to the sandbox folder");
        for (String seg : p.split("/")) if (seg.equals("..")) throw new IllegalArgumentException("the path \"" + rel + "\" leaves the sandbox folder");
        if (p.indexOf('\0') >= 0) throw new IllegalArgumentException("the path has an invalid character");
        File base = root.getCanonicalFile();
        File f = p.isEmpty() || p.equals(".") ? base : new File(base, p).getCanonicalFile();
        if (!f.equals(base) && !f.getPath().startsWith(base.getPath() + File.separator))
            throw new IllegalArgumentException("the path \"" + rel + "\" leaves the sandbox folder");
        return f;
    }

    /** The same, as a path relative to the root, for messages. */
    static String rel(File root, File f) {
        try {
            String b = root.getCanonicalPath(), p = f.getCanonicalPath();
            if (p.equals(b)) return ".";
            if (p.startsWith(b + File.separator)) return p.substring(b.length() + 1).replace(File.separatorChar, '/');
            return p;
        } catch (IOException e) {
            return f.getPath();
        }
    }

    /** Unpacks {@code zip} into {@code dest} (created). Entry names are checked one by one; nothing can land outside {@code dest}. */
    static Unzipped unzip(File zip, File dest) throws IOException {
        Unzipped u = new Unzipped();
        if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("Could not create " + dest.getName());
        File base = dest.getCanonicalFile();
        try (ZipFile zf = new ZipFile(zip)) {
            if (zf.size() > MAX_ENTRIES) throw new IOException("The zip has " + zf.size() + " entries (the limit is " + MAX_ENTRIES + ").");
            Enumeration<? extends ZipEntry> en = zf.entries();
            byte[] buf = new byte[32 * 1024];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName().replace('\\', '/');
                if (name.startsWith("/") || name.matches("^[A-Za-z]:.*") || name.indexOf('\0') >= 0 || Arrays.asList(name.split("/")).contains("..")) {
                    u.skipped++;
                    continue;
                }
                File out = new File(base, name).getCanonicalFile();
                if (!out.getPath().startsWith(base.getPath() + File.separator) && !out.equals(base)) {
                    u.skipped++;
                    continue;
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                    u.dirs++;
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create " + parent.getName());
                // the parent chain is real folders inside base (canonical path was checked), so a file can be written there
                try (InputStream in = zf.getInputStream(e); OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        u.bytes += n;
                        if (u.bytes > MAX_UNZIPPED) throw new IOException("The zip is larger than " + size(MAX_UNZIPPED) + " unpacked.");
                        os.write(buf, 0, n);
                    }
                }
                u.files++;
            }
        }
        return u;
    }

    /** Writes {@code src} (a file or a folder) into {@code out} as a zip. Links are not followed. Returns the number of files. */
    static int zip(File src, File out) throws IOException {
        int[] n = {0};
        long[] total = {0};
        File outCanon = out.getCanonicalFile();
        try (ZipOutputStream zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            if (src.isDirectory()) add(zo, src, src, outCanon, n, total);
            else {
                put(zo, src, src.getName(), total);
                n[0]++;
            }
        }
        return n[0];
    }

    private static void add(ZipOutputStream zo, File root, File dir, File skip, int[] n, long[] total) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        Arrays.sort(kids);
        for (File f : kids) {
            if (Files.isSymbolicLink(f.toPath()) || f.getCanonicalFile().equals(skip)) continue;
            String name = root.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/');
            if (f.isDirectory()) add(zo, root, f, skip, n, total);
            else if (f.isFile()) {
                if (n[0] >= MAX_ENTRIES) throw new IOException("Too many files to zip (the limit is " + MAX_ENTRIES + ").");
                put(zo, f, name, total);
                n[0]++;
            }
        }
    }

    private static void put(ZipOutputStream zo, File f, String name, long[] total) throws IOException {
        total[0] += f.length();
        if (total[0] > MAX_ZIP_OUT) throw new IOException("The result is larger than " + size(MAX_ZIP_OUT) + ".");
        ZipEntry e = new ZipEntry(name);
        e.setTime(f.lastModified());
        zo.putNextEntry(e);
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] buf = new byte[32 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) zo.write(buf, 0, r);
        }
        zo.closeEntry();
    }

    /** Folder listing, one entry per line: "dir/" or "name  (size)". Sub-folders are shown down to {@code depth}. */
    static String list(File root, File dir, int depth, int max) {
        StringBuilder b = new StringBuilder();
        int[] count = {0};
        listInto(b, root, dir, depth, max, count, 0);
        if (count[0] > max) b.append("... and more (").append(count[0] - max).append(" not shown)\n");
        if (b.length() == 0) b.append("(empty)\n");
        return b.toString();
    }

    private static void listInto(StringBuilder b, File root, File dir, int depth, int max, int[] count, int level) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        Arrays.sort(kids, (x, y) -> {
            if (x.isDirectory() != y.isDirectory()) return x.isDirectory() ? -1 : 1;
            return x.getName().compareToIgnoreCase(y.getName());
        });
        for (File f : kids) {
            count[0]++;
            boolean show = count[0] <= max;
            if (show) {
                for (int i = 0; i < level; i++) b.append("  ");
                if (f.isDirectory()) b.append(f.getName()).append("/\n");
                else b.append(f.getName()).append("  (").append(size(f.length())).append(")\n");
            }
            if (f.isDirectory() && level + 1 < depth && !Files.isSymbolicLink(f.toPath())) listInto(b, root, f, depth, max, count, level + 1);
        }
    }

    /** The file as text, cut at {@code max} characters. Throws for a binary file. */
    static String readText(File f, long max) throws IOException {
        if (!f.isFile()) throw new IllegalArgumentException("not a file: " + f.getName());
        long len = f.length();
        int take = (int) Math.min(len, Math.min(max, MAX_READ) * 4);
        byte[] b = new byte[take];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < take) {
                int r = in.read(b, off, take - off);
                if (r < 0) break;
                off += r;
            }
        }
        for (int i = 0; i < Math.min(b.length, 8000); i++)
            if (b[i] == 0) throw new IllegalArgumentException(f.getName() + " is a binary file (" + size(len) + "); use sandbox_run to inspect it, or sandbox_export to hand it to the person");
        String s = new String(b, StandardCharsets.UTF_8);
        if (s.length() > max) s = s.substring(0, (int) max) + "\n[cut: " + size(len) + " in all]";
        else if (len > take) s += "\n[cut: " + size(len) + " in all]";
        return s;
    }

    /** The command that runs one code file, or null for a language this does not know. */
    static String runLine(String lang, String file) {
        String l = lang == null ? "" : lang.trim().toLowerCase(Locale.ROOT);
        String f = "'" + file.replace("'", "'\\''") + "'";
        switch (l) {
            case "python": case "python3": case "py": return "python3 " + f;
            case "node": case "nodejs": case "javascript": case "js": return "node " + f;
            case "java": return "java " + f; // single-file source launch (JDK 11+)
            case "bash": case "sh": case "shell": return "sh " + f;
            case "ruby": case "rb": return "ruby " + f;
            case "php": return "php " + f;
            case "go": return "go run " + f;
            case "rust": case "rs": return "rustc -o /tmp/sbx_rs_out " + f + " && /tmp/sbx_rs_out";
            case "c": return "cc -o /tmp/sbx_c_out " + f + " && /tmp/sbx_c_out";
            case "cpp": case "c++": return "c++ -o /tmp/sbx_cpp_out " + f + " && /tmp/sbx_cpp_out";
            default: return null;
        }
    }

    /** The file name a code snippet is saved under. */
    static String codeName(String lang) {
        String l = lang == null ? "" : lang.trim().toLowerCase(Locale.ROOT);
        switch (l) {
            case "python": case "python3": case "py": return "main.py";
            case "node": case "nodejs": case "javascript": case "js": return "main.js";
            case "java": return "Main.java";
            case "bash": case "sh": case "shell": return "main.sh";
            case "ruby": case "rb": return "main.rb";
            case "php": return "main.php";
            case "go": return "main.go";
            case "rust": case "rs": return "main.rs";
            case "c": return "main.c";
            case "cpp": case "c++": return "main.cpp";
            default: return null;
        }
    }

    /** A zip's name without the extension, safe as a folder name. */
    static String folderFor(String zipName) {
        String n = zipName == null ? "upload" : zipName;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        if (n.toLowerCase(Locale.ROOT).endsWith(".zip")) n = n.substring(0, n.length() - 4);
        n = n.replaceAll("[^A-Za-z0-9._-]", "_");
        while (n.startsWith(".")) n = n.substring(1);
        return n.isEmpty() ? "upload" : n.length() > 60 ? n.substring(0, 60) : n;
    }

    /** A file name safe to store: no folders, no odd characters. */
    static String safeName(String name) {
        String o = name == null ? "" : name;
        int slash = Math.max(o.lastIndexOf('/'), o.lastIndexOf('\\'));
        if (slash >= 0) o = o.substring(slash + 1);
        o = o.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        while (o.startsWith(".")) o = o.substring(1);
        if (o.isEmpty()) return "file";
        return o.length() > 100 ? o.substring(o.length() - 100) : o;
    }

    static String size(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        return String.format(Locale.US, "%.1f MB", n / (1024.0 * 1024));
    }

    /** Deletes a file or a folder and everything in it. Links are removed, never followed. */
    static void deleteTree(File f) {
        if (f == null || !f.exists() && !Files.isSymbolicLink(f.toPath())) return;
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        f.delete();
    }
}
