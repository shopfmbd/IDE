package com.fahim.cloudide;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The Android SDK tools as terminal commands, so an APK is built by typing (or scripting) the same commands you
 * would use on a computer, instead of Gradle and the Android Gradle plugin:
 *
 *   aapt2 compile|link ...     the real aapt2 (native arm64)
 *   ecj ...                    Eclipse Java compiler, in this app
 *   kotlinc ...                Kotlin compiler (Kotlin pack), in this app
 *   d8 ...                     D8 dexer, in this app
 *   apkpack, apksigner, zipalign, mkjar, unjar, mkmanifest, mkbuildconfig   the small glue steps
 *   deps                       downloads the libraries named in build.gradle / pom.xml (replaces Gradle's download)
 *   apkbuild [file]            runs a command script, build-apk.cmd by default (sdk init writes one)
 *
 * A Session keeps the working folder and the variables ($PKG, $ANDROID_JAR, ...) between lines, understands
 * "a && b" and "a ; b", quotes, $VAR, ${VAR} and * / ** wildcards. Lines that are not SDK commands go to the Android shell.
 */
final class SdkCmd {
    private SdkCmd() { }

    /** Variables that hold exactly one path (the DEPS_* ones hold lists and are split into several arguments). */
    private static final Set<String> PATH_VARS = new HashSet<>(Arrays.asList("PROJ", "ANDROID_JAR", "WORK", "OUT", "KOTLIN_STDLIB", "MODULE",
            "MANIFEST", "SRC", "RES", "ASSETS", "JNI", "DEPS_MANIFESTS"));

    static final Set<String> NAMES = new HashSet<>(Arrays.asList("aapt2", "aapt", "aidl", "dexdump", "split-select", "ecj", "kotlinc", "d8", "apksigner", "zipalign", "mkjar", "unjar",
            "apkpack", "mkmanifest", "mkbuildconfig", "deps", "sdk", "apkbuild", "set", "echo"));

    static final class Session {
        final Context ctx;
        final File proj;
        final Shell shell;
        final Map<String, String> vars = new LinkedHashMap<>();
        private final Set<String> userKeys = new HashSet<>();
        volatile boolean cancelled;

        Session(Context c, File proj, Shell shell) {
            this.ctx = c.getApplicationContext();
            this.proj = proj;
            this.shell = shell;
            refreshVars();
        }

        /** Re-reads the project (after the person edited build.gradle) without losing variables they set by hand. */
        void refreshVars() {
            Map<String, String> user = new LinkedHashMap<>(vars);
            vars.clear();
            vars.put("PROJ", proj.getAbsolutePath());
            vars.put("ANDROID_JAR", Toolchain.file(ctx, "android.jar").getAbsolutePath());
            vars.put("WORK", new File(ctx.getCacheDir(), "sdk/" + proj.getName().replaceAll("[^A-Za-z0-9._-]+", "_")).getAbsolutePath());
            vars.put("OUT", LocalLang.outDir(ctx, proj).getAbsolutePath() + "/apk");
            vars.put("NAME", proj.getName().replaceAll("[^A-Za-z0-9._-]+", "_"));
            if (KotlinTool.ready(ctx)) vars.put("KOTLIN_STDLIB", KotlinTool.stdlib(ctx).getAbsolutePath());
            vars.putAll(LocalBuild.vars(proj));
            vars.putAll(user.isEmpty() ? new LinkedHashMap<String, String>() : keepUser(user));
        }

        private Map<String, String> keepUser(Map<String, String> old) {
            Map<String, String> k = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : old.entrySet()) if (e.getKey().startsWith("DEPS_") || userKeys.contains(e.getKey())) k.put(e.getKey(), e.getValue());
            return k;
        }

        /** True when any command in the line is an SDK command (then this Session must run the whole line). */
        boolean wants(String line) {
            for (String seg : splitChain(line)) {
                List<String> w = words(seg);
                if (!w.isEmpty() && NAMES.contains(w.get(0)) && !w.get(0).equals("echo") && !w.get(0).equals("set")) return true;
            }
            return false;
        }

        /** Runs "a && b ; c". Stops at the first failure after && (like sh). Returns the last exit code. */
        int runLine(String line, NativeExec.Out out) {
            int code = 0;
            List<String> segs = splitChain(line);
            for (int i = 0; i < segs.size(); i++) {
                String seg = segs.get(i);
                boolean and = seg.startsWith("&&");
                if (and && code != 0) continue;
                if (seg.startsWith("&&") || seg.startsWith(";")) seg = seg.substring(seg.startsWith("&&") ? 2 : 1);
                if (cancelled) return 130;
                code = one(seg.trim(), out);
            }
            return code;
        }

        private int one(String seg, NativeExec.Out out) {
            if (seg.isEmpty() || seg.startsWith("#")) return 0;
            List<String> w = expand(words(seg));
            if (w.isEmpty()) return 0;
            String cmd = w.get(0);
            List<String> a = w.subList(1, w.size());
            try {
                switch (cmd) {
                    case "echo": out.line(Toolchain.join(a, " ")); return 0;
                    case "set": return set(a, out);
                    case "aapt2": return text(Toolchain.aapt2(ctx, a, shell.cwd, sb()), out);
                    case "aapt": case "aidl": case "dexdump": case "split-select":
                        return text(Toolchain.runTool(ctx, cmd, a, shell.cwd, sb()), out);
                    case "ecj": return tool(Toolchain.ecj(ctx, inCwd(a), last = new StringBuilder()), out);
                    case "d8": return tool(Toolchain.d8(ctx, inCwd(a), last = new StringBuilder()), out);
                    case "kotlinc": return tool(KotlinTool.compile(ctx, inCwd(a), last = new StringBuilder()), out);
                    case "apksigner": return apksigner(a, out);
                    case "zipalign": return zipalign(a, out);
                    case "mkjar": return mkjar(a, out);
                    case "unjar": return unjar(a, out);
                    case "apkpack": return apkpack(a, out);
                    case "mkmanifest": return mkmanifest(a, out);
                    case "mkbuildconfig": return mkbuildconfig(a, out);
                    case "deps": return deps(a, out);
                    case "sdk": return sdk(a, out);
                    case "apkbuild": return apkbuild(a, out);
                    default: return shell(seg, out);
                }
            } catch (Throwable t) {
                out.line(cmd + ": " + Toolchain.clip(Toolchain.describe(t), 600));
                return 1;
            }
        }

        private StringBuilder last = new StringBuilder();
        private StringBuilder aaptOut;

        private StringBuilder sb() {
            aaptOut = new StringBuilder();
            last = aaptOut;
            return aaptOut;
        }

        private int text(int code, NativeExec.Out out) {
            print(last, out);
            return code;
        }

        private int tool(boolean ok, NativeExec.Out out) {
            print(last, out);
            return ok ? 0 : 1;
        }

        private static void print(StringBuilder b, NativeExec.Out out) {
            String t = b.toString().trim();
            if (t.isEmpty()) return;
            for (String l : t.split("\n")) out.line(l);
        }

        private int shell(String seg, NativeExec.Out out) {
            // The Android shell does not know our variables, so $VAR is filled in here. Quotes and operators stay exactly as typed:
            // the old approach split the line into words, dropped the quotes and glued the words back together, which turned
            // find . -name "*.java"  into  find . -name *.java  (expanded by the shell) and  sed 's/a/b/;s/c/d/'  into two commands.
            String line = substShell(seg);
            return shell.run(line, s -> {
                for (String l : s.split("\n", -1)) if (!l.isEmpty()) out.line(l);
            });
        }

        // ------------------------------------------------------------ words, variables, wildcards

        private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}|\\$([A-Za-z_][A-Za-z0-9_]*)");

        /** Single-quotes a value for the shell unless it only holds characters that are safe anywhere. */
        private static String shq(String v) {
            if (v.matches("[A-Za-z0-9_./:=@%+,-]+")) return v;
            return "'" + v.replace("'", "'\\''") + "'";
        }

        /** Replaces $VAR and ${VAR} outside single quotes, keeping every quote, operator and escape of the line as written. */
        private String substShell(String seg) {
            StringBuilder b = new StringBuilder();
            char q = 0;
            for (int i = 0; i < seg.length(); i++) {
                char c = seg.charAt(i);
                if (q == '\'') {
                    b.append(c);
                    if (c == '\'') q = 0;
                    continue;
                }
                if (c == '\\' && i + 1 < seg.length()) {
                    b.append(c).append(seg.charAt(++i));
                    continue;
                }
                if (c == '\'' && q == 0) {
                    q = '\'';
                    b.append(c);
                    continue;
                }
                if (c == '"') {
                    q = q == '"' ? 0 : '"';
                    b.append(c);
                    continue;
                }
                if (c == '$') {
                    java.util.regex.Matcher m = VAR.matcher(seg);
                    m.region(i, seg.length());
                    if (m.lookingAt()) {
                        String k = m.group(1) != null ? m.group(1) : m.group(2);
                        String v = vars.get(k);
                        if (v == null) v = "";
                        if (q == '"') b.append(v.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`"));
                        else if (PATH_VARS.contains(k)) b.append(shq(v)); // one path, never cut at a space
                        else b.append(v); // a list: the shell splits it into several arguments
                        i = m.end() - 1;
                        continue;
                    }
                }
                b.append(c);
            }
            return b.toString();
        }

        /**
         * ecj, d8 and kotlinc run inside this process, whose working folder is not the terminal's folder. Relative paths the
         * person typed are made absolute against the terminal's folder first (like aapt2 and zipalign, which get the folder).
         */
        private static final Set<String> PATH_OPTS = new HashSet<>(Arrays.asList("-d", "-o", "--output", "--lib", "-classpath", "-cp", "--classpath",
                "--class-path", "-bootclasspath", "-sourcepath", "--source-path", "-processorpath", "-extdirs", "-endorseddirs", "--pg-conf",
                "--main-dex-list"));

        private List<String> inCwd(List<String> a) {
            List<String> r = new ArrayList<>();
            boolean pathNext = false;
            for (String x : a) {
                if (pathNext) {
                    pathNext = false;
                    List<String> ps = new ArrayList<>();
                    for (String part : x.split(File.pathSeparator, -1)) ps.add(part.isEmpty() ? part : file(part).getPath());
                    r.add(Toolchain.join(ps, File.pathSeparator));
                } else if (x.startsWith("-")) {
                    r.add(x);
                    pathNext = PATH_OPTS.contains(x);
                } else if (!new File(x).isAbsolute() && file(x).exists()) {
                    r.add(file(x).getPath());
                } else {
                    r.add(x);
                }
            }
            return r;
        }

        private String subst(String w) {
            java.util.regex.Matcher m = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}|\\$([A-Za-z_][A-Za-z0-9_]*)").matcher(w);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String k = m.group(1) != null ? m.group(1) : m.group(2);
                String v = vars.get(k);
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v == null ? "" : v));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        List<String> expand(List<String> ws) {
            List<String> out = new ArrayList<>();
            for (String w : ws) {
                Matcher1 m = new Matcher1(w);
                if (m.whole != null) {
                    String v = vars.get(m.whole);
                    if (v == null) continue; // unset: no argument at all
                    if (PATH_VARS.contains(m.whole)) { // one path, never a list: do not cut it at a space in the project name
                        if (!v.isEmpty()) out.add(v);
                        continue;
                    }
                    for (String p : v.trim().split("\\s+")) if (!p.isEmpty()) out.add(p);
                    continue;
                }
                String x = subst(w);
                if (x.indexOf('*') >= 0 || x.indexOf('?') >= 0) out.addAll(glob(x));
                else out.add(x);
            }
            return out;
        }

        private List<String> glob(String pat) {
            File base = new File(pat).isAbsolute() ? new File("/") : shell.cwd;
            String[] seg = pat.split("/");
            StringBuilder prefix = new StringBuilder(pat.startsWith("/") ? "/" : "");
            int i = 0;
            for (; i < seg.length; i++) {
                if (seg[i].indexOf('*') >= 0 || seg[i].indexOf('?') >= 0) break;
                if (!seg[i].isEmpty()) prefix.append(seg[i]).append('/');
            }
            File root = prefix.length() == 0 ? base : (new File(prefix.toString()).isAbsolute() ? new File(prefix.toString()) : new File(shell.cwd, prefix.toString()));
            StringBuilder re = new StringBuilder();
            for (int k = i; k < seg.length; k++) {
                String s = seg[k];
                if (s.equals("**")) re.append(k == seg.length - 1 ? ".*" : "(?:[^/]+/)*");
                else {
                    // every character except * and ? is literal (a name like "lib+x.c" or "a(1).java" must not become a regex)
                    StringBuilder lit = new StringBuilder();
                    for (int n = 0; n < s.length(); n++) {
                        char ch = s.charAt(n);
                        if (ch == '*' || ch == '?') {
                            if (lit.length() > 0) re.append(Pattern.quote(lit.toString()));
                            lit.setLength(0);
                            re.append(ch == '*' ? "[^/]*" : "[^/]");
                        } else lit.append(ch);
                    }
                    if (lit.length() > 0) re.append(Pattern.quote(lit.toString()));
                    if (k < seg.length - 1) re.append('/');
                }
            }
            List<File> found = new ArrayList<>();
            walk(root, found, 0);
            Pattern p = Pattern.compile(re.toString());
            List<String> out = new ArrayList<>();
            String rp = root.getPath().endsWith("/") ? root.getPath() : root.getPath() + "/";
            for (File f : found) {
                String rel = f.getPath().startsWith(rp) ? f.getPath().substring(rp.length()) : f.getPath();
                if (f.isFile() && p.matcher(rel).matches()) out.add(f.getPath());
            }
            Collections.sort(out);
            if (out.isEmpty()) out.add(pat);
            return out;
        }

        private static void walk(File d, List<File> out, int depth) {
            File[] k = d.listFiles();
            if (k == null || depth > 12) return;
            for (File f : k) {
                out.add(f);
                if (f.isDirectory()) walk(f, out, depth + 1);
            }
        }

        // ------------------------------------------------------------ commands

        private int set(List<String> a, NativeExec.Out out) {
            if (a.isEmpty()) {
                for (Map.Entry<String, String> e : vars.entrySet()) out.line(e.getKey() + "=" + e.getValue());
                return 0;
            }
            String t = Toolchain.join(a, " ");
            int eq = t.indexOf('=');
            String k, v;
            if (eq > 0) {
                k = t.substring(0, eq).trim();
                v = t.substring(eq + 1).trim();
            } else {
                k = a.get(0);
                v = Toolchain.join(a.subList(1, a.size()), " ");
            }
            if (!k.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                out.line("set: bad name " + k);
                return 1;
            }
            vars.put(k, v);
            userKeys.add(k);
            return 0;
        }

        private File file(String p) {
            File f = new File(p);
            return f.isAbsolute() ? f : new File(shell.cwd, p);
        }

        private int apksigner(List<String> a, NativeExec.Out out) throws Exception {
            int min = 24;
            List<String> r = new ArrayList<>();
            for (int i = 0; i < a.size(); i++) {
                if (a.get(i).equals("--min-sdk") && i + 1 < a.size()) min = Integer.parseInt(a.get(++i));
                else r.add(a.get(i));
            }
            if (r.size() != 2) {
                out.line("usage: apksigner [--min-sdk N] <in.apk> <out.apk>   (signs with the debug key, v1 + v2 + v3)");
                return 2;
            }
            File o = file(r.get(1));
            o.getParentFile().mkdirs();
            o.delete();
            Toolchain.sign(ctx, file(r.get(0)), o, min);
            out.line("signed " + o.getName() + " (" + o.length() / 1024 + " KB)");
            return o.isFile() ? 0 : 1;
        }

        private int zipalign(List<String> a, NativeExec.Out out) throws Exception {
            // Real SDK syntax (zipalign -f -p 4 in out, zipalign -c 4 file) goes to the bundled native zipalign.
            boolean sdkSyntax = a.size() > 2 || (!a.isEmpty() && a.get(0).startsWith("-"));
            if (sdkSyntax && Toolchain.nativeTool(ctx, "zipalign") != null) {
                List<String> args = new ArrayList<>();
                for (String x : a) args.add(x.startsWith("-") || x.matches("\\d+") ? x : file(x).getPath());
                return text(Toolchain.runTool(ctx, "zipalign", args, shell.cwd, sb()), out);
            }
            if (a.size() != 2) {
                out.line("usage: zipalign <in.zip> <out.zip>   (4-byte aligns stored entries, rebuilds the zip)\n       zipalign [-f] [-p] [-c] [-v] 4 <in.apk> <out.apk>   (the Android SDK tool)");
                return 2;
            }
            File o = file(a.get(1));
            o.getParentFile().mkdirs();
            try (LocalBuild.ApkWriter w = new LocalBuild.ApkWriter(o); ZipFile zf = new ZipFile(file(a.get(0)))) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    boolean store = e.getMethod() == ZipEntry.STORED;
                    w.add(e.getName(), Io.bytes(zf.getInputStream(e)), store, store ? 4 : 0);
                }
            }
            out.line("aligned " + o.getName());
            return 0;
        }

        private int mkjar(List<String> a, NativeExec.Out out) throws Exception {
            if (a.size() != 2) {
                out.line("usage: mkjar <out.jar> <classes-dir>");
                return 2;
            }
            File o = file(a.get(0));
            o.getParentFile().mkdirs();
            o.delete();
            LocalBuild.zipDir(file(a.get(1)), o);
            out.line(o.getName() + " (" + o.length() / 1024 + " KB)");
            return 0;
        }

        private int unjar(List<String> a, NativeExec.Out out) throws Exception {
            if (a.size() != 2) {
                out.line("usage: unjar <file.zip|jar|aar> <dir>");
                return 2;
            }
            LocalBuild.unzipTo(file(a.get(0)), file(a.get(1)));
            return 0;
        }

        /** apkpack out.apk base.apk dexdir [jniLibsDir ...]: resources from aapt2, classes*.dex, native libraries. */
        private int apkpack(List<String> a, NativeExec.Out out) throws Exception {
            if (a.size() < 3) {
                out.line("usage: apkpack <out.apk> <base.apk from aapt2 link> <dex-dir> [jniLibs-dir ...]");
                return 2;
            }
            File o = file(a.get(0));
            o.getParentFile().mkdirs();
            o.delete();
            int n = 0;
            try (LocalBuild.ApkWriter w = new LocalBuild.ApkWriter(o); ZipFile zf = new ZipFile(file(a.get(1)))) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory() || e.getName().startsWith("META-INF/")) continue;
                    boolean store = e.getName().equals("resources.arsc") || e.getMethod() == ZipEntry.STORED;
                    w.add(e.getName(), Io.bytes(zf.getInputStream(e)), store, store ? 4 : 0);
                    n++;
                }
                File dd = file(a.get(2));
                for (int i = 1; ; i++) {
                    File d = new File(dd, i == 1 ? "classes.dex" : "classes" + i + ".dex");
                    if (!d.isFile()) break;
                    try (FileInputStream in = new FileInputStream(d)) {
                        w.add(d.getName(), Io.bytes(in), false, 0);
                    }
                    n++;
                }
                for (int i = 3; i < a.size(); i++) {
                    File[] abis = file(a.get(i)).listFiles();
                    if (abis == null) continue;
                    for (File abi : abis) {
                        File[] sos = abi.listFiles();
                        if (sos != null) for (File so : sos) if (so.getName().endsWith(".so")) {
                            try (FileInputStream in = new FileInputStream(so)) {
                                w.add("lib/" + abi.getName() + "/" + so.getName(), Io.bytes(in), false, 0);
                            }
                            n++;
                        }
                    }
                }
            }
            out.line(o.getName() + ": " + n + " entries");
            return 0;
        }

        /** mkmanifest src dst pkg [appId]: adds package=, merges the manifests of the libraries found by deps. */
        private int mkmanifest(List<String> a, NativeExec.Out out) throws Exception {
            if (a.size() < 3) {
                out.line("usage: mkmanifest <AndroidManifest.xml> <out.xml> <package> [applicationId]");
                return 2;
            }
            String text = Io.read(file(a.get(0)));
            String pkg = a.get(2), appId = a.size() > 3 ? a.get(3) : pkg;
            text = text.replace("${applicationId}", appId); // Gradle fills this in the app's own manifest too
            if (!Pattern.compile("<manifest\\b[^>]*?\\bpackage\\s*=").matcher(text).find()) text = text.replaceFirst("<manifest\\b", "<manifest package=\"" + pkg + "\"");
            List<String[]> libs = new ArrayList<>();
            String dir = vars.get("DEPS_MANIFESTS");
            if (dir != null) {
                File[] ms = new File(dir).listFiles();
                if (ms != null) {
                    Arrays.sort(ms);
                    for (File m : ms) libs.add(new String[]{"", Io.read(m)});
                }
            }
            List<String> notes = new ArrayList<>();
            text = ManifestMerge.merge(text, appId, libs, notes);
            for (String n : notes) out.line(n);
            File o = file(a.get(1));
            o.getParentFile().mkdirs();
            Io.write(o, text);
            return 0;
        }

        private int mkbuildconfig(List<String> a, NativeExec.Out out) throws Exception {
            if (a.size() < 5) {
                out.line("usage: mkbuildconfig <gen-dir> <package> <applicationId> <versionCode> <versionName>");
                return 2;
            }
            String vcode = a.get(3).trim();
            if (!vcode.matches("-?\\d+")) {
                out.line("mkbuildconfig: versionCode must be a number, got \"" + vcode + "\"");
                return 2;
            }
            Io.write(new File(file(a.get(0)), a.get(1).replace('.', '/') + "/BuildConfig.java"), "package " + a.get(1) + ";\n\npublic final class BuildConfig {\n"
                    + "    public static final boolean DEBUG = true;\n    public static final String APPLICATION_ID = \"" + javaStr(a.get(2)) + "\";\n"
                    + "    public static final String BUILD_TYPE = \"debug\";\n    public static final int VERSION_CODE = " + vcode + ";\n"
                    + "    public static final String VERSION_NAME = \"" + javaStr(a.get(4)) + "\";\n}\n");
            return 0;
        }

        /**
         * deps: resolves the libraries of build.gradle / pom.xml, unpacks the AARs, compiles their resources with aapt2 and
         * sets DEPS_JARS, DEPS_CP, DEPS_RES, DEPS_EXTRA, DEPS_ASSETS and DEPS_MANIFESTS for the lines that follow.
         */
        /** Text for the inside of a Java string literal (backslash first, then the quote). */
        private static String javaStr(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
        }

        private int deps(List<String> a, NativeExec.Out out) throws Exception {
            File module = vars.containsKey("MODULE") ? new File(vars.get("MODULE")) : proj;
            File work = new File(vars.get("WORK"), "deps");
            Io.delete(work);
            work.mkdirs();
            List<File> mods = LocalDeps.hasBuildFile(module) ? LocalDeps.localModules(proj, module) : new ArrayList<File>();
            if (!LocalDeps.hasBuildFile(module)) out.line("no build.gradle or pom.xml in " + module.getName() + ", nothing to download");
            LocalDeps.Out d = LocalDeps.hasBuildFile(module)
                    ? LocalDeps.resolve(ctx, proj, module, mods, KotlinTool.ready(ctx) && hasKotlin(module), s -> out.line(s))
                    : new LocalDeps.Out();
            for (String n : d.notes) out.line("note: " + n);
            List<String> jars = new ArrayList<>(), cp = new ArrayList<>(), res = new ArrayList<>(), assets = new ArrayList<>(), pkgs = new ArrayList<>();
            File mdir = new File(work, "manifests");
            mdir.mkdirs();
            int i = 0;
            for (File aar : d.aars) {
                File ad = new File(work, "aar/" + (i++) + "_" + aar.getName().replaceAll("[^A-Za-z0-9._-]", "_"));
                LocalBuild.unzipTo(aar, ad);
                File cj = new File(ad, "classes.jar");
                if (cj.isFile()) jars.add(cj.getPath());
                File[] inner = new File(ad, "libs").listFiles();
                if (inner != null) for (File f : inner) if (f.getName().endsWith(".jar")) jars.add(f.getPath());
                File rd = new File(ad, "res");
                if (rd.isDirectory()) {
                    File z = new File(work, "res" + i + ".zip");
                    StringBuilder o = new StringBuilder();
                    int c = Toolchain.aapt2(ctx, Arrays.asList("compile", "--dir", rd.getPath(), "-o", z.getPath()), work, o);
                    if (c != 0) {
                        print(o, out);
                        return c;
                    }
                    if (z.isFile()) { res.add("-R"); res.add(z.getPath()); }
                }
                File as = new File(ad, "assets");
                if (as.isDirectory()) { assets.add("-A"); assets.add(as.getPath()); }
                File mf = new File(ad, "AndroidManifest.xml");
                if (mf.isFile()) {
                    String t = Io.read(mf);
                    java.util.regex.Matcher m = Pattern.compile("package\\s*=\\s*\"([^\"]+)\"").matcher(t);
                    if (m.find() && !m.group(1).equals(vars.get("PKG")) && !pkgs.contains(m.group(1))) pkgs.add(m.group(1));
                    Io.write(new File(mdir, String.format(java.util.Locale.ROOT, "%04d.xml", i)), t);
                }
            }
            jars.addAll(paths(d.jars));
            cp.addAll(jars);
            cp.addAll(paths(d.compileOnly));
            int co = 0;
            for (File aar : d.compileOnlyAars) { // compileOnly AARs: classes for compiling, nothing for the APK
                File ad = new File(work, "aar-co/" + (co++) + "_" + aar.getName().replaceAll("[^A-Za-z0-9._-]", "_"));
                LocalBuild.unzipTo(aar, ad);
                File cj = new File(ad, "classes.jar");
                if (cj.isFile()) cp.add(cj.getPath());
            }
            vars.put("DEPS_JARS", Toolchain.join(jars, " "));
            vars.put("DEPS_CP", Toolchain.join(cp, File.pathSeparator));
            vars.put("DEPS_RES", Toolchain.join(res, " "));
            vars.put("DEPS_ASSETS", Toolchain.join(assets, " "));
            vars.put("DEPS_EXTRA", pkgs.isEmpty() ? "" : "--extra-packages " + Toolchain.join(pkgs, ":"));
            vars.put("DEPS_MANIFESTS", mdir.getPath());
            out.line(d.aars.size() + " aar, " + d.jars.size() + " jar. Variables: DEPS_JARS DEPS_CP DEPS_RES DEPS_EXTRA DEPS_ASSETS DEPS_MANIFESTS");
            return 0;
        }

        private static boolean hasKotlin(File dir) {
            List<File> l = new ArrayList<>();
            LocalBuild.walk(new File(dir, "src"), ".kt", l);
            return !l.isEmpty();
        }

        private static List<String> paths(List<File> l) {
            List<String> r = new ArrayList<>();
            for (File f : l) r.add(f.getPath());
            return r;
        }

        private int sdk(List<String> a, NativeExec.Out out) throws Exception {
            String sub = a.isEmpty() ? "status" : a.get(0);
            switch (sub) {
                case "init": {
                    File f = new File(proj, "build-apk.cmd");
                    if (f.exists() && !a.contains("--force")) {
                        out.line("build-apk.cmd already exists. Edit it, or sdk init --force to write a fresh one.");
                        return 1;
                    }
                    Io.write(f, template());
                    out.line("wrote build-apk.cmd. Run it with: apkbuild");
                    return 0;
                }
                case "vars":
                    refreshVars();
                    for (Map.Entry<String, String> e : vars.entrySet()) out.line(e.getKey() + "=" + e.getValue());
                    return 0;
                default: {
                    out.line("Android SDK tools on this phone:");
                    out.line("  aapt2      " + (Toolchain.aapt2(ctx) != null ? "ready" : "missing"));
                    for (String n : new String[]{"ecj.dex.jar", "d8.dex.jar", "sign.dex.jar", "android.jar"})
                        out.line("  " + n + "  " + (Toolchain.file(ctx, n).isFile() ? "ready" : "missing"));
                    out.line("  kotlinc    " + (KotlinTool.ready(ctx) ? "ready" : "not installed (plugin install kotlin)"));
                    out.line("Commands: aapt2 ecj kotlinc d8 apksigner zipalign mkjar unjar apkpack mkmanifest mkbuildconfig deps");
                    out.line("Scripts:  sdk init  (writes build-apk.cmd),  apkbuild  (runs it),  sdk vars  (shows $VARIABLES)");
                    if (!Toolchain.ready(ctx)) out.line("Missing tools: " + Toolchain.join(Toolchain.missing(ctx), ", ") + ". App settings > Build tools.");
                    return 0;
                }
            }
        }

        /** Runs a command script line by line. Every line is shown, then run. Stops at the first failing line. */
        private int apkbuild(List<String> a, NativeExec.Out out) throws Exception {
            File f = a.isEmpty() ? new File(proj, "build-apk.cmd") : file(a.get(0));
            if (!f.isFile()) {
                out.line(f.getName() + " not found. Type sdk init to write one for this project.");
                return 1;
            }
            refreshVars();
            long t0 = System.currentTimeMillis();
            int n = 0, lineNo = 0;
            for (String raw : Io.read(f).split("\n")) {
                lineNo++;
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (cancelled) return 130;
                out.line("$ " + line);
                int c = runLine(line, out);
                n++;
                if (c != 0) {
                    out.line("[stopped at line " + lineNo + " of " + f.getName() + ", exit " + c + "]"); // the line in the file, not the command count
                    return c;
                }
            }
            out.line("[" + n + " commands, " + Human.dur(System.currentTimeMillis() - t0) + "]");
            return 0;
        }

        static String template() {
            return "# CloudIDE APK build script. Every line is a terminal command; run it with:  apkbuild\n"
                    + "# This is what the Android Gradle plugin does for assembleDebug, written out so you can change any step.\n"
                    + "# Variables: $PROJ $MODULE $SRC $RES $ASSETS $JNI $PKG $APPID $MINSDK $TARGETSDK $VERSIONCODE $VERSIONNAME $JAVA\n"
                    + "#            $ANDROID_JAR $WORK $OUT $NAME $KOTLIN_STDLIB and, after deps: $DEPS_JARS $DEPS_CP $DEPS_RES $DEPS_EXTRA $DEPS_ASSETS\n"
                    + "# Change a value with:  set JAVA=11\n\n"
                    + "echo == clean\n"
                    + "rm -rf $WORK\n"
                    + "mkdir -p $WORK/gen $WORK/classes $WORK/dex $OUT\n\n"
                    + "echo == libraries (Maven, cached after the first download)\n"
                    + "deps\n\n"
                    + "echo == manifest\n"
                    + "mkmanifest $MANIFEST $WORK/AndroidManifest.xml $PKG $APPID\n\n"
                    + "echo == resources (aapt2)\n"
                    + "aapt2 compile --dir $RES -o $WORK/res.zip\n"
                    + "aapt2 link -o $WORK/base.apk -I $ANDROID_JAR --manifest $WORK/AndroidManifest.xml --java $WORK/gen --auto-add-overlay --min-sdk-version $MINSDK --target-sdk-version $TARGETSDK --version-code $VERSIONCODE --version-name $VERSIONNAME $DEPS_EXTRA $DEPS_ASSETS $DEPS_RES -R $WORK/res.zip\n"
                    + "mkbuildconfig $WORK/gen $PKG $APPID $VERSIONCODE $VERSIONNAME\n\n"
                    + "echo == Java (ECJ)\n"
                    + "# Kotlin app? Compile it first, then keep the ecj line:\n"
                    + "# kotlinc -no-stdlib -no-jdk -no-reflect -nowarn -jvm-target 17 -classpath $ANDROID_JAR:$DEPS_CP:$KOTLIN_STDLIB -d $WORK/classes $MODULE/src/main/kotlin $SRC $WORK/gen\n"
                    + "ecj -d $WORK/classes -source $JAVA -target $JAVA -encoding UTF-8 -proc:none -nowarn -g -bootclasspath $ANDROID_JAR -classpath $ANDROID_JAR:$DEPS_CP $SRC $WORK/gen\n"
                    + "mkjar $WORK/classes.jar $WORK/classes\n\n"
                    + "echo == dex (D8)\n"
                    + "d8 --min-api $MINSDK --lib $ANDROID_JAR --output $WORK/dex $WORK/classes.jar $DEPS_JARS\n\n"
                    + "echo == package and sign\n"
                    + "apkpack $WORK/unsigned.apk $WORK/base.apk $WORK/dex $JNI\n"
                    + "apksigner --min-sdk $MINSDK $WORK/unsigned.apk $OUT/$NAME-debug.apk\n"
                    + "echo APK: $OUT/$NAME-debug.apk\n";
        }
    }

    /** "$NAME" or "${NAME}" as the whole word. */
    private static final class Matcher1 {
        String whole;

        Matcher1(String w) {
            java.util.regex.Matcher m = Pattern.compile("^\\$(?:\\{([A-Za-z_][A-Za-z0-9_]*)\\}|([A-Za-z_][A-Za-z0-9_]*))$").matcher(w);
            if (m.matches()) whole = m.group(1) != null ? m.group(1) : m.group(2);
        }
    }

    /** Quote-aware split into words. Quotes group but are not kept; a $VAR inside double quotes stays one word. */
    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean in = false;
        char q = 0;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (in) {
                if (c == q) in = false;
                else cur.append(c);
            } else if (c == '"' || c == '\'') {
                in = true;
                q = c;
                any = true;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0 || any) out.add(cur.toString());
                cur.setLength(0);
                any = false;
            } else cur.append(c);
        }
        if (cur.length() > 0 || any) out.add(cur.toString());
        return out;
    }

    /** Splits at top-level && and ; (outside quotes). Each part after the first keeps its leading operator. */
    static List<String> splitChain(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean in = false;
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (in) {
                cur.append(c);
                if (c == q) in = false;
            } else if (c == '#' && (i == 0 || Character.isWhitespace(s.charAt(i - 1)))) {
                break; // a comment runs to the end of the line: "# note; ecj x" must not run ecj
            } else if (c == '"' || c == '\'') {
                in = true;
                q = c;
                cur.append(c);
            } else if (c == '&' && i + 1 < s.length() && s.charAt(i + 1) == '&') {
                if (cur.toString().trim().length() > 0) out.add(cur.toString());
                cur.setLength(0);
                cur.append("&&");
                i++;
            } else if (c == ';') {
                if (cur.toString().trim().length() > 0) out.add(cur.toString());
                cur.setLength(0);
                cur.append(';');
            } else cur.append(c);
        }
        if (cur.toString().replace("&&", "").replace(";", "").trim().length() > 0) out.add(cur.toString());
        return out;
    }
}
