package com.fahim.cloudide;

import android.content.Context;
import android.os.Build;
import dalvik.system.DexClassLoader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Builds every language it can on this phone, with no Termux:
 *   Java, Kotlin        in this process (ECJ, kotlinc, D8 from the toolchain and the Kotlin pack)
 *   C, C++, CMake, Make clang and friends from the C / C++ pack
 *   Rust, Go, Python, Node   their packs
 *   build.sh            sh, with every installed pack on PATH
 * Android app projects still go through LocalBuild. Gradle / Maven projects with dependencies, Flutter and .NET
 * cannot be built here; they are reported as such and stay on the GitHub build.
 */
final class LocalLang {
    static final class Fail extends Exception { Fail(String m) { super(m); } }

    static final class Cancel extends Exception { }

    private static final class Job {
        final String lang;
        final File dir;
        /** For plugin jobs: the project marker file that was found ("Gemfile"), or "" for the per-file steps. */
        String marker = "";

        Job(String lang, File dir) {
            this.lang = lang;
            this.dir = dir;
        }

        Job(String lang, File dir, String marker) {
            this(lang, dir);
            this.marker = marker;
        }
    }

    private static final Set<String> PRUNE = new HashSet<>(Arrays.asList("node_modules", "build", "target", ".git", ".gradle", "dist", "vendor",
            ".dart_tool", "cloudide-out", ".cloudide", ".venv", "venv", "Pods", ".idea", "__pycache__"));
    private static final Set<String> MARKERS = new HashSet<>(Arrays.asList("settings.gradle", "settings.gradle.kts", "build.gradle",
            "build.gradle.kts", "pubspec.yaml", "package.json", "Cargo.toml", "pom.xml", "go.mod", "pyproject.toml", "setup.py",
            "requirements.txt", "CMakeLists.txt", "Makefile", "makefile", "GNUmakefile"));
    private static final Pattern DIAG = Pattern.compile("^(.+?):(\\d+):(?:(\\d+):)?\\s*(?:fatal )?(error|warning)\\s*:\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GODIAG = Pattern.compile("^(\\.{0,2}/?[^\\s:]+\\.go):(\\d+):(\\d+): (.*)$");
    private static final Pattern RUSTLOC = Pattern.compile("^\\s*--> (.+?):(\\d+):(\\d+)");
    private static final Pattern PYLOC = Pattern.compile("^\\s*File \"(.+?)\", line (\\d+)");
    private static final Pattern JSLOC = Pattern.compile("^(.+\\.[cm]?js):(\\d+)$");
    private static final Pattern ERRMSG = Pattern.compile("^(?:[A-Za-z]*Error): (.*)$");

    final Context ctx;
    final File proj;
    final ProjectConfig cfg;
    final CloudBuild.Cb cb;
    volatile boolean running, cancelled;
    private final List<Diag> diags = new ArrayList<>();
    private final Map<String, String> results = new LinkedHashMap<>();
    private File outRoot;
    private long t0;
    private String pendMsg, pendFile;
    private int pendLine, pendCol;
    /** Severity (1 error, 2 warning) of the "error: ..." / "warning: ..." line that is waiting for its "--> file:line" line. */
    private int pendSev = 1;

    LocalLang(Context c, File p, ProjectConfig cfg, CloudBuild.Cb cb) {
        this.ctx = c.getApplicationContext();
        this.proj = p;
        this.cfg = cfg;
        this.cb = cb;
    }

    void start() {
        running = true;
        BuildService.start(ctx); // same speed boost as the Android build: Android does not freeze or kill it in the background
        new Thread(null, this::run, "locallang", 64L << 20).start();
    }

    void cancel() { cancelled = true; }

    static File outDir(Context c, File proj) { return new File(c.getExternalFilesDir(null), "builds/" + proj.getName()); }

    // ------------------------------------------------------------------ project scan

    private static final class Scan {
        final Map<String, List<File>> dirs = new HashMap<>();
        final Map<String, List<File>> files = new HashMap<>();
        /** Every file seen, for the plugin detect rules (exact names and *.suffix). */
        final List<File> all = new ArrayList<>();
        boolean buildSh;

        List<File> d(String n) {
            List<File> l = dirs.get(n);
            return l == null ? new ArrayList<File>() : l;
        }

        List<File> f(String ext) {
            List<File> l = files.get(ext);
            return l == null ? new ArrayList<File>() : l;
        }
    }

    private static void walk(File dir, int depth, Scan s) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        Arrays.sort(kids);
        for (File k : kids) {
            String n = k.getName();
            if (k.isDirectory()) {
                if (depth < 4 && !PRUNE.contains(n)) walk(k, depth + 1, s);
                continue;
            }
            s.all.add(k);
            if (MARKERS.contains(n) || n.endsWith(".sln") || n.endsWith(".csproj")) {
                String key = n.endsWith(".sln") ? "*.sln" : n.endsWith(".csproj") ? "*.csproj" : n;
                List<File> l = s.dirs.get(key);
                if (l == null) s.dirs.put(key, l = new ArrayList<>());
                if (!l.contains(dir)) l.add(dir);
            }
            int i = n.lastIndexOf('.');
            if (i > 0) {
                String ext = n.substring(i).toLowerCase(java.util.Locale.ROOT);
                List<File> l = s.files.get(ext);
                if (l == null) s.files.put(ext, l = new ArrayList<>());
                l.add(k);
            }
        }
    }

    private Scan scan() {
        Scan s = new Scan();
        s.buildSh = new File(proj, "build.sh").isFile();
        walk(proj, 0, s);
        return s;
    }

    /** True for an Android app project, which LocalBuild handles. */
    static boolean android(File p) {
        return new File(p, "app/src/main/AndroidManifest.xml").isFile() || new File(p, "app/AndroidManifest.xml").isFile()
                || new File(p, "src/main/AndroidManifest.xml").isFile() || new File(p, "AndroidManifest.xml").isFile();
    }

    private static List<File> top(List<File> dirs) {
        List<File> out = new ArrayList<>();
        for (File d : dirs) {
            boolean inner = false;
            for (File o : dirs) if (!o.equals(d) && (d.getPath() + "/").startsWith(o.getPath() + "/")) inner = true;
            if (!inner) out.add(d);
        }
        return out;
    }

    private static boolean under(File f, File dir) { return (f.getPath() + "/").startsWith(dir.getPath() + "/"); }

    private List<Job> plan(Scan s, List<String[]> skipped) {
        List<Job> j = new ArrayList<>();
        if (s.buildSh) {
            j.add(new Job("build.sh", proj));
            return j;
        }
        List<File> flutter = new ArrayList<>();
        for (File d : s.d("pubspec.yaml")) {
            try {
                if (Pattern.compile("^\\s*flutter:", Pattern.MULTILINE).matcher(Io.read(new File(d, "pubspec.yaml"))).find()) flutter.add(d);
            } catch (IOException ignored) {
            }
        }
        List<File> gradle = new ArrayList<>(s.d("settings.gradle"));
        for (File d : s.d("settings.gradle.kts")) if (!gradle.contains(d)) gradle.add(d);
        if (gradle.isEmpty()) for (String n : new String[]{"build.gradle", "build.gradle.kts"}) for (File d : s.d(n)) if (d.equals(proj) && !gradle.contains(d)) gradle.add(d);
        List<File> maven = top(s.d("pom.xml"));
        boolean jvmProject = !gradle.isEmpty() || !maven.isEmpty() || !flutter.isEmpty();

        for (File d : top(s.d("package.json"))) if (!underAny(d, flutter)) j.add(new Job("Node", d));
        for (File d : top(s.d("Cargo.toml"))) if (!underAny(d, flutter)) j.add(new Job("Rust", d));
        for (File d : top(s.d("go.mod"))) j.add(new Job("Go", d));
        List<File> py = new ArrayList<>(s.d("pyproject.toml"));
        for (String n : new String[]{"setup.py", "requirements.txt"}) for (File d : s.d(n)) if (!py.contains(d)) py.add(d);
        if (!py.isEmpty()) j.add(new Job("Python", top(py).get(0)));
        else if (!s.f(".py").isEmpty() && !jvmProject) j.add(new Job("Python", proj));
        List<File> cmake = top(s.d("CMakeLists.txt"));
        for (File d : cmake) j.add(new Job("CMake", d));
        List<File> mk = new ArrayList<>(s.d("Makefile"));
        for (String n : new String[]{"makefile", "GNUmakefile"}) for (File d : s.d(n)) if (!mk.contains(d)) mk.add(d);
        for (File d : top(mk)) {
            boolean other = false;
            for (String m : new String[]{"CMakeLists.txt", "Cargo.toml", "package.json", "go.mod", "pom.xml", "pyproject.toml", "setup.py"})
                if (new File(d, m).exists()) other = true;
            if (!other) j.add(new Job("Make", d));
        }

        // Plugin languages (Ruby, PHP, Zig, ...) described by the catalog
        for (Packs.Pack pk : Packs.all(ctx)) {
            if (pk.build == null || pk.manual || pk.builtin) continue;
            boolean owned = false;
            for (File f : s.all) if (pk.owns(f.getName())) { owned = true; break; }
            if (!owned) continue;
            boolean projectJob = false;
            JSONArray pj = pk.build.optJSONArray("projects");
            if (pj != null) for (int i = 0; i < pj.length(); i++) {
                String marker = pj.optJSONObject(i).optString("marker");
                List<File> dirs = new ArrayList<>();
                for (File f : s.all) if (f.getName().equals(marker) && !dirs.contains(f.getParentFile())) dirs.add(f.getParentFile());
                for (File d : top(dirs)) {
                    j.add(new Job("plugin:" + pk.id, d, marker));
                    projectJob = true;
                }
            }
            if (!projectJob && pk.build.optJSONArray("perFile") != null) j.add(new Job("plugin:" + pk.id, proj));
        }

        // Gradle and Maven projects without Android: libraries come from MavenResolver, sources from ECJ / kotlinc
        for (File d : gradle) addJvm(j, skipped, s, d, "Gradle");
        for (File d : maven) addJvm(j, skipped, s, d, "Maven");
        if (!flutter.isEmpty()) skipped.add(new String[]{"Flutter", "the Flutter and Dart SDKs have no Android build"});
        if (!s.d("*.sln").isEmpty() || !s.d("*.csproj").isEmpty()) skipped.add(new String[]{".NET", "the .NET SDK has no Android build"});

        if (!jvmProject) {
            if (!s.f(".kt").isEmpty()) j.add(new Job("Kotlin", proj));
            else if (!s.f(".java").isEmpty()) j.add(new Job("Java", proj));
            else if (j.isEmpty()) {
                boolean c = false;
                for (String e : new String[]{".c", ".cpp", ".cc", ".cxx"}) if (!s.f(e).isEmpty()) c = true;
                if (c) j.add(new Job("C/C++", proj));
                else if (!s.f(".js").isEmpty() || !s.f(".mjs").isEmpty()) j.add(new Job("Node", proj));
                else if (!s.f(".go").isEmpty()) j.add(new Job("Go", proj));
            }
        }
        return j;
    }

    private static boolean androidGradle(File dir) {
        for (String n : new String[]{"build.gradle", "build.gradle.kts"}) {
            File[] cand = {new File(dir, n), new File(dir, "app/" + n)};
            for (File f : cand) if (f.isFile() && has(f, Pattern.compile("com\\.android"))) return true;
        }
        return false;
    }

    private void addJvm(List<Job> j, List<String[]> skipped, Scan s, File d, String what) {
        if (androidGradle(d)) {
            skipped.add(new String[]{what + " (Android) in " + Io.rel(proj, d), "the Android app must be at the project root (app/src/main/AndroidManifest.xml); open that folder as its own project"});
            return;
        }
        boolean kt = false, java = false;
        for (File f : s.f(".kt")) if (under(f, d) && !isTest(f)) kt = true;
        for (File f : s.f(".java")) if (under(f, d) && !isTest(f)) java = true;
        if (kt) j.add(new Job("Kotlin", d));
        else if (java) j.add(new Job("Java", d));
        else skipped.add(new String[]{what, "no Java or Kotlin sources found under " + Io.rel(proj, d)});
    }

    private static boolean isTest(File f) {
        String p = f.getPath().replace('\\', '/');
        return p.contains("/src/test/") || p.contains("/src/androidTest/") || p.contains("/src/testFixtures/");
    }

    private static boolean underAny(File d, List<File> parents) {
        for (File p : parents) if (under(d, p)) return true;
        return false;
    }

    /** Languages a project would build locally, for the terminal's langs/lbuild hints. */
    static String describe(Context c, File proj) {
        LocalLang l = new LocalLang(c, proj, ProjectConfig.load(proj), null);
        if (android(proj)) return "Android app (Java" + (KotlinTool.ready(c) ? ", Kotlin" : "") + ")";
        List<String[]> skipped = new ArrayList<>();
        List<Job> jobs = l.plan(l.scan(), skipped);
        StringBuilder sb = new StringBuilder();
        for (Job j : jobs) sb.append(sb.length() == 0 ? "" : ", ").append(dispStatic(c, j));
        if (sb.length() == 0 && !skipped.isEmpty()) {
            for (String[] k : skipped) sb.append(sb.length() == 0 ? "" : ", ").append(k[0]);
            return "none (" + sb + ": use the GitHub build)";
        }
        return sb.length() == 0 ? "nothing recognised" : sb.toString();
    }

    // ------------------------------------------------------------------ driver

    private void step(String s) throws Cancel {
        if (cancelled) throw new Cancel();
        cb.status(s);
        cb.log("> " + s);
    }

    private void run() {
        t0 = System.currentTimeMillis();
        outRoot = outDir(ctx, proj);
        boolean ok = true;
        try {
            Scan s = scan();
            List<String[]> skipped = new ArrayList<>();
            List<Job> jobs = plan(s, skipped);
            if (jobs.isEmpty() && skipped.isEmpty()) throw new Fail("No buildable source found. Looked for Java, Kotlin, C, C++, CMake, Make, Rust, Go, Python, Node and build.sh.");
            int built = 0;
            for (Job j : jobs) {
                step("[" + disp(j) + "] " + Io.rel(proj, j.dir).replaceFirst("^$", "."));
                pendMsg = null;
                pendFile = null;
                pendSev = 1;
                pendBase = j.dir; // paths in this job's messages are relative to its folder (jvm() and the like set their own)
                // the same key for PASS and FAIL, and one per folder when a language builds more than once
                final String key = disp(j) + (jobs.size() > 1 && sameLang(jobs, j) ? " (" + Io.rel(proj, j.dir) + ")" : "");
                try {
                    one(j, s);
                    results.put(key, "PASS");
                    built++;
                } catch (Fail f) {
                    cb.log("ERROR: " + f.getMessage());
                    results.put(key, "FAIL - " + Toolchain.clip(String.valueOf(f.getMessage()).split("\n")[0], 120));
                    ok = false;
                } catch (Cancel c) {
                    throw c;
                } catch (Exception e) { // an I/O or JSON error in one job must not drop the other jobs and the summary
                    cb.log("ERROR: " + Toolchain.describe(e));
                    results.put(key, "FAIL - " + Toolchain.clip(String.valueOf(Toolchain.describe(e)).split("\n")[0], 120));
                    ok = false;
                }
            }
            for (String[] k : skipped) results.put(k[0], "NOT LOCAL - " + k[1] + ". Use the GitHub build.");
            if (built == 0 && ok) throw new Fail("Nothing in this project can be built locally. " + describeSkips(skipped));
            StringBuilder sum = new StringBuilder("--- Build summary ---");
            for (Map.Entry<String, String> e : results.entrySet()) sum.append('\n').append(e.getKey()).append(": ").append(e.getValue());
            cb.log(sum.toString());
            cb.log("Finished in " + Human.dur(System.currentTimeMillis() - t0));
            cb.done(ok, null, diags, "", ok ? "Build succeeded (on device)" : "Local build failed");
        } catch (Cancel c) {
            cb.done(false, null, diags, "", "Cancelled");
        } catch (Fail f) {
            cb.log("ERROR: " + f.getMessage());
            cb.done(false, null, diags, "", "Local build failed");
        } catch (Throwable t) {
            cb.log("ERROR: " + Toolchain.describe(t));
            cb.done(false, null, diags, "", "Local build failed");
        } finally {
            running = false;
            BuildService.stop(ctx);
        }
    }

    private String disp(Job j) { return dispStatic(ctx, j); }

    private static String dispStatic(Context c, Job j) {
        if (!j.lang.startsWith("plugin:")) return j.lang;
        Packs.Pack p = Packs.find(c, j.lang.substring(7));
        return p == null ? j.lang.substring(7) : p.label;
    }

    private static boolean sameLang(List<Job> all, Job j) {
        int n = 0;
        for (Job o : all) if (o.lang.equals(j.lang)) n++;
        return n > 1;
    }

    private static String describeSkips(List<String[]> sk) {
        StringBuilder sb = new StringBuilder();
        for (String[] k : sk) sb.append(k[0]).append(": ").append(k[1]).append(". ");
        return sb.toString().trim();
    }

    private void one(Job j, Scan s) throws Exception {
        switch (j.lang) {
            case "build.sh": buildSh(j); break;
            case "Java": jvm(j, s, false); break;
            case "Kotlin": jvm(j, s, true); break;
            case "C/C++": plainC(j, s); break;
            case "CMake": cmake(j); break;
            case "Make": make(j); break;
            case "Rust": rust(j); break;
            case "Go": golang(j); break;
            case "Python": python(j); break;
            case "Node": node(j, s); break;
            default:
                if (j.lang.startsWith("plugin:")) plugin(j, s);
                else throw new Fail("No local engine for " + j.lang);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void need(String pack, String tool) throws Fail {
        if (!Packs.installed(ctx, pack)) {
            Packs.Pack p = Packs.find(ctx, pack);
            throw new Fail("The " + (p == null ? pack : p.label) + " plugin is not installed.\nType: plugin install " + pack + "   (about " + (p == null ? "?" : String.valueOf(p.mb)) + " MB).");
        }
        if (!Packs.canExec(ctx)) throw new Fail(NativeExec.blockedText(ctx, new IOException("error=13, Permission denied")));
        if (tool != null && NativeExec.tool(ctx, tool) == null) throw new Fail("The pack is installed but " + tool + " is missing from it. Remove the pack and install it again.");
    }

    private void needCore() throws Fail {
        if (Build.VERSION.SDK_INT < 26) throw new Fail("Local Java builds need Android 8.0 or newer.");
        List<String> miss = Toolchain.missing(ctx);
        miss.remove("aapt2"); // plain Java does not need aapt2
        if (!miss.isEmpty()) throw new Fail("The core toolchain is not installed (missing " + Toolchain.join(miss, ", ") + ").\nOpen App settings > Build tools and install it once.");
    }

    private Map<String, String> allEnv(String lang) {
        Map<String, String> e = NativeExec.allEnv(ctx);
        e.putAll(NativeExec.env(ctx, lang));
        return e;
    }

    private int exec(List<String> cmd, File cwd, String lang) throws Exception {
        cb.log("$ " + Toolchain.clip(Toolchain.join(cmd, " ").replace(Packs.prefix(ctx).getPath() + "/bin/", ""), 300));
        Map<String, String> env = allEnv(lang);
        if ("rust".equals(lang) && NativeExec.tool(ctx, "clang") != null)
            env.put("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", NativeExec.tool(ctx, "clang").getPath());
        try {
            return NativeExec.run(ctx, cmd, cwd, env, line -> {
                feed(line);
                cb.log(line);
            }, () -> cancelled);
        } catch (IOException e) {
            throw new Fail(e.getMessage());
        } finally {
            if (cancelled) throw new Cancel();
        }
    }

    private List<String> cmd(String tool, String... args) {
        List<String> l = new ArrayList<>();
        File t = NativeExec.tool(ctx, tool);
        l.add(t == null ? tool : t.getPath());
        l.addAll(Arrays.asList(args));
        return l;
    }

    private static String clean(String n) { return n.replaceAll("[^A-Za-z0-9._-]+", "_"); }

    private File out(String lang) {
        File d = new File(outRoot, lang);
        d.mkdirs();
        return d;
    }

    private File work(String name) {
        File w = new File(ctx.getCacheDir(), "local/" + proj.getName() + "/" + name);
        w.mkdirs();
        return w;
    }

    // ------------------------------------------------------------------ diagnostics

    private void add(String file, int line, int col, String msg, int sev) {
        if (diags.size() >= 200) return;
        for (Diag d : diags) if (d.line == line && d.msg.equals(msg.trim()) && d.sev == sev && d.file.endsWith(new File(file).getName())) return;
        String rel;
        file = file.replaceFirst("^(\\./)+", "");
        File f = new File(file);
        if (!f.isAbsolute()) f = new File(pendBase == null ? proj : pendBase, file);
        String root = proj.getAbsolutePath();
        String abs = f.getAbsolutePath();
        // "/x/app2/Foo.java" is not inside "/x/app": compare whole path segments
        rel = abs.equals(root) || abs.startsWith(root.endsWith("/") ? root : root + "/") ? Io.rel(proj, f) : f.getName();
        diags.add(new Diag(rel, line, col, msg.trim(), sev));
    }

    private File pendBase;
    private Pattern plugDiag;
    private JSONObject plugDiagDef;

    private int diagInt(String k) { return plugDiagDef == null ? 0 : plugDiagDef.optInt(k, 0); }

    /**
     * One output line from any tool: turns compiler messages into Problems entries. This runs on the thread that reads the
     * program's output, where an exception would end the app, so a line that cannot be parsed (a number too large for an int,
     * a plugin pattern that names a group it does not have) is simply not turned into a problem.
     */
    private void feed(String raw) {
        try {
            feed0(raw);
        } catch (RuntimeException ignored) {
            // the line is still shown in the log by the caller
        }
    }

    /** Group n of a plugin's diagnostic pattern, or null when n is 0, beyond the pattern or the group did not take part. */
    private static String grp(Matcher m, int n) { return n > 0 && n <= m.groupCount() ? m.group(n) : null; }

    private void feed0(String raw) {
        String line = raw.trim();
        if (line.isEmpty()) return;
        if (plugDiag != null) {
            Matcher pm = plugDiag.matcher(line);
            if (pm.find()) {
                String sevText = grp(pm, diagInt("sev"));
                int sev = 1;
                if (sevText != null) {
                    String t = sevText.toLowerCase(java.util.Locale.ROOT);
                    if (t.contains("warn") || t.contains("note") || t.contains("info")) sev = 2;
                }
                String f = diagInt("file") > 0 ? grp(pm, diagInt("file")) : "";
                String ln = diagInt("line") > 0 ? grp(pm, diagInt("line")) : "1";
                String cl = grp(pm, diagInt("col"));
                String msg = diagInt("msg") > 0 ? grp(pm, diagInt("msg")) : line;
                try {
                    if (f != null && !f.isEmpty() && ln != null && msg != null)
                        add(f, Integer.parseInt(ln.trim()), cl == null ? 0 : Integer.parseInt(cl.trim()), msg, sev);
                } catch (NumberFormatException ignored) {
                    // the plugin's pattern matched something that is not a number: show the line only
                }
                return;
            }
        }
        Matcher m = DIAG.matcher(line);
        if (m.matches()) {
            add(m.group(1), Integer.parseInt(m.group(2)), m.group(3) == null ? 0 : Integer.parseInt(m.group(3)), m.group(5), m.group(4).equalsIgnoreCase("error") ? 1 : 2);
            return;
        }
        m = GODIAG.matcher(line);
        if (m.matches()) {
            add(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), m.group(4), 1);
            return;
        }
        if (line.startsWith("error") || line.startsWith("warning")) {
            pendMsg = line.replaceFirst("^(error|warning)(\\[[^\\]]*\\])?:\\s*", "");
            pendSev = line.startsWith("warning") ? 2 : 1; // a Rust warning is a warning in Problems, not an error
            return;
        }
        m = RUSTLOC.matcher(raw);
        if (m.find()) {
            if (pendMsg != null) add(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), pendMsg, pendSev);
            pendMsg = null;
            return;
        }
        m = PYLOC.matcher(raw);
        if (m.find()) {
            pendFile = m.group(1);
            pendLine = Integer.parseInt(m.group(2));
            return;
        }
        m = JSLOC.matcher(line);
        if (m.matches()) {
            pendFile = m.group(1);
            pendLine = Integer.parseInt(m.group(2));
            return;
        }
        m = ERRMSG.matcher(line);
        if (m.matches() && pendFile != null) {
            add(pendFile, pendLine, 0, line, 1);
            pendFile = null;
        }
    }

    private void feedAll(String text) {
        for (String l : text.split("\n")) feed(l);
    }

    // ------------------------------------------------------------------ Java and Kotlin, inside this process

    private static final Pattern PKG = Pattern.compile("^\\s*package\\s+([\\w.]+)", Pattern.MULTILINE);
    private static final Pattern JMAIN = Pattern.compile("static\\s+void\\s+main\\s*\\(\\s*(?:final\\s+)?String");
    private static final Pattern KMAIN = Pattern.compile("^\\s*(?:suspend\\s+)?fun\\s+main\\s*\\(", Pattern.MULTILINE);

    private void jvm(Job j, Scan s, boolean kotlin) throws Exception {
        needCore();
        if (kotlin && !KotlinTool.ready(ctx)) throw new Fail("The Kotlin pack is not installed.\nOpen App settings > Languages and install it once.");
        List<File> java = new ArrayList<>(), kt = new ArrayList<>();
        for (File f : s.f(".java")) if (under(f, j.dir) && !isTest(f)) java.add(f);
        for (File f : s.f(".kt")) if (under(f, j.dir) && !isTest(f)) kt.add(f);
        // local library modules (project(":lib")) contribute their sources too
        List<File> libModules = LocalDeps.hasBuildFile(j.dir) ? LocalDeps.localModules(proj, j.dir) : new ArrayList<File>();
        for (File m : libModules) {
            for (File f : s.f(".java")) if (under(f, m) && !isTest(f) && !java.contains(f)) java.add(f);
            for (File f : s.f(".kt")) if (under(f, m) && !isTest(f) && !kt.contains(f)) kt.add(f);
        }
        if (!kt.isEmpty() && !KotlinTool.ready(ctx)) throw new Fail("This project has Kotlin files but the Kotlin pack is not installed.\nOpen App settings > Languages.");
        if (java.isEmpty() && kt.isEmpty()) throw new Fail("No source files found");
        pendBase = proj;
        File android = Toolchain.file(ctx, "android.jar");
        File w = work("jvm");
        Io.delete(w);
        w.mkdirs();
        File classes = new File(w, "classes");
        classes.mkdirs();
        StringBuilder cp = new StringBuilder(android.getAbsolutePath());
        List<File> libs = new ArrayList<>();
        for (String n : new String[]{"libs", "lib"}) {
            File[] fs = new File(j.dir, n).listFiles();
            if (fs != null) for (File f : fs) if (f.getName().endsWith(".jar")) libs.add(f);
        }
        if (LocalDeps.hasBuildFile(j.dir)) {
            step("Resolving libraries");
            LocalDeps.Out dep = LocalDeps.resolve(ctx, proj, j.dir, libModules, !kt.isEmpty(), msg -> cb.log(msg));
            for (String n : dep.notes) cb.log("note: " + n);
            int aarNo = 0; // two libraries can ship an AAR with the same file name (core-1.0.aar from different groups)
            for (File a : dep.aars) {
                File ad = new File(w, "aar/" + (aarNo++) + "_" + a.getName().replaceAll("[^A-Za-z0-9._-]", "_"));
                LocalBuild.unzipTo(a, ad);
                File cj = new File(ad, "classes.jar");
                if (cj.isFile()) libs.add(cj);
            }
            libs.addAll(dep.jars);
            for (File f : dep.compileOnly) cp.append(File.pathSeparator).append(f.getAbsolutePath());
            for (File a : dep.compileOnlyAars) {
                File ad = new File(w, "aar-co/" + (aarNo++) + "_" + a.getName().replaceAll("[^A-Za-z0-9._-]", "_"));
                LocalBuild.unzipTo(a, ad);
                File cj = new File(ad, "classes.jar");
                if (cj.isFile()) cp.append(File.pathSeparator).append(cj.getAbsolutePath());
            }
            cb.log(dep.jars.size() + dep.aars.size() + " library file(s) from the Maven cache");
        }
        for (File f : libs) cp.append(File.pathSeparator).append(f.getAbsolutePath());
        int level = Toolchain.MAX_JAVA;
        if (!kt.isEmpty()) {
            step("Compiling Kotlin");
            List<String> a = new ArrayList<>();
            KotlinTool.base(ctx, a, cp.toString(), classes, level);
            KotlinTool.composePlugin(ctx, a, kt);
            for (File f : kt) a.add(f.getAbsolutePath());
            for (File f : java) a.add(f.getAbsolutePath()); // so Kotlin can see the Java classes
            StringBuilder o = new StringBuilder();
            boolean ok = KotlinTool.compile(ctx, a, o);
            feedAll(o.toString());
            if (!ok) {
                cb.log(Toolchain.clip(o.toString(), 3000));
                throw new Fail("Kotlin compile failed. See Problems.");
            }
        }
        if (!java.isEmpty()) {
            step("Compiling Java (ECJ)");
            String cpj = cp + File.pathSeparator + classes.getAbsolutePath() + (kt.isEmpty() ? "" : File.pathSeparator + KotlinTool.stdlib(ctx).getAbsolutePath());
            boolean ok = ecj(java, classes, cpj, android, level);
            for (int lower = Toolchain.lowerJava(level); !ok && lower > 0 && Toolchain.platformProblem(lastEcj); lower = Toolchain.lowerJava(lower)) {
                cb.log("ECJ could not compile at Java " + level + " (platform classes), retrying at Java " + lower);
                level = lower;
                ok = ecj(java, classes, cpj, android, level);
            }
            if (!ok) throw new Fail("Java compile failed. See Problems.");
        }
        List<File> cf = new ArrayList<>();
        LocalBuild.walk(classes, ".class", cf);
        if (cf.isEmpty()) throw new Fail("The compiler produced no class files");
        cb.log(cf.size() + " classes");

        String name = clean(proj.getName());
        File od = out(kotlin ? "kotlin" : "java");
        File jar = new File(od, name + ".jar");
        jar.delete();
        LocalBuild.zipDir(classes, jar);

        step("Dexing (D8)");
        File dexDir = new File(w, "dex");
        dexDir.mkdirs();
        List<String> d8 = new ArrayList<>(Arrays.asList("--debug", "--min-api", "26", "--lib", android.getAbsolutePath(), "--output", dexDir.getAbsolutePath(), jar.getAbsolutePath()));
        for (File f : libs) d8.add(f.getAbsolutePath());
        if (!kt.isEmpty()) d8.add(KotlinTool.stdlib(ctx).getAbsolutePath());
        StringBuilder dout = new StringBuilder();
        if (!Toolchain.d8(ctx, d8, dout)) {
            cb.log(Toolchain.clip(dout.toString(), 2000));
            throw new Fail("D8 failed");
        }
        File dexJar = new File(od, name + "-dex.jar");
        dexJar.delete();
        try (ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(dexJar))) {
            for (int i = 1; ; i++) {
                File d = new File(dexDir, i == 1 ? "classes.dex" : "classes" + i + ".dex");
                if (!d.isFile()) break;
                zo.putNextEntry(new ZipEntry(d.getName()));
                try (InputStream in = new FileInputStream(d)) { zo.write(Io.bytes(in)); }
                zo.closeEntry();
            }
        }
        String main = findMain(java, kt);
        Io.write(new File(od, "run.txt"), dexJar.getName() + "\n" + main + "\n");
        cb.log("output: " + jar.getName() + ", " + dexJar.getName() + (main.isEmpty() ? " (no main method found)" : "; run it with: lrun   (main class " + main + ")"));
        Io.delete(w);
    }

    private String lastEcj = "";

    private boolean ecj(List<File> src, File classes, String cp, File android, int level) throws Exception {
        List<String> a = new ArrayList<>(Arrays.asList("-d", classes.getAbsolutePath(), "-source", String.valueOf(level), "-target", String.valueOf(level),
                "-encoding", "UTF-8", "-proc:none", "-nowarn", "-g", "-Xemacs", "-bootclasspath", android.getAbsolutePath(), "-classpath", cp));
        for (File f : src) a.add(f.getAbsolutePath());
        StringBuilder o = new StringBuilder();
        boolean ok = Toolchain.ecj(ctx, a, o);
        lastEcj = o.toString();
        feedAll(lastEcj);
        if (!ok) cb.log(Toolchain.clip(lastEcj, 3000));
        return ok;
    }

    private static String findMain(List<File> java, List<File> kt) {
        for (File f : kt) {
            try {
                String t = Io.read(f);
                if (KMAIN.matcher(t).find()) {
                    Matcher p = PKG.matcher(t);
                    String b = f.getName().replaceFirst("\\.kt$", "");
                    if (b.isEmpty()) continue;
                    return (p.find() ? p.group(1) + "." : "") + Character.toUpperCase(b.charAt(0)) + b.substring(1) + "Kt";
                }
            } catch (IOException ignored) {
            }
        }
        for (File f : java) {
            try {
                String t = Io.read(f);
                if (JMAIN.matcher(t).find()) {
                    Matcher p = PKG.matcher(t);
                    return (p.find() ? p.group(1) + "." : "") + f.getName().replaceFirst("\\.java$", "");
                }
            } catch (IOException ignored) {
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ C, C++, CMake, Make

    private static boolean has(File f, Pattern p) {
        try {
            return p.matcher(Io.read(f)).find();
        } catch (IOException e) {
            return false;
        }
    }

    private static final Pattern CMAIN = Pattern.compile("\\bmain\\s*\\(");

    private void plainC(Job j, Scan s) throws Exception {
        need("clang", "clang");
        List<File> src = new ArrayList<>();
        boolean cpp = false;
        for (String e : new String[]{".c", ".cpp", ".cc", ".cxx"})
            for (File f : s.f(e)) if (under(f, j.dir)) {
                src.add(f);
                if (!e.equals(".c")) cpp = true;
            }
        if (src.isEmpty()) throw new Fail("No C or C++ files");
        String cc = cpp ? "clang++" : "clang";
        String name = clean(proj.getName());
        File od = out("native");
        List<String> a = cmd(cc, "-O2", "-Wall", cpp ? "-std=c++17" : "-std=gnu17", "-o", new File(od, name).getPath());
        for (File f : src) a.add(f.getPath());
        a.add("-lm");
        int code = exec(a, j.dir, "clang");
        if (code == 0) {
            cb.log("output: " + name);
            return;
        }
        List<File> mains = new ArrayList<>(), libs = new ArrayList<>();
        for (File f : src) (has(f, CMAIN) ? mains : libs).add(f);
        if (mains.size() < 2) throw new Fail(cc + " failed (exit " + code + "). See Problems.");
        cb.log("Several files have main(), building each one as its own program");
        for (File m : mains) {
            String n = m.getName().replaceFirst("\\.[^.]+$", "");
            List<String> b = cmd(cc, "-O2", "-Wall", cpp ? "-std=c++17" : "-std=gnu17", "-o", new File(od, n).getPath(), m.getPath());
            for (File l : libs) b.add(l.getPath());
            b.add("-lm");
            if (exec(b, j.dir, "clang") != 0) throw new Fail(cc + " failed on " + m.getName() + ". See Problems.");
        }
    }

    private void cmake(Job j) throws Exception {
        need("clang", "cmake");
        File b = work("cmake-" + clean(Io.rel(proj, j.dir)));
        Io.delete(b);
        b.mkdirs();
        boolean ninja = NativeExec.tool(ctx, "ninja") != null;
        int c = exec(cmd("cmake", "-S", j.dir.getPath(), "-B", b.getPath(), "-DCMAKE_BUILD_TYPE=Release", "-G", ninja ? "Ninja" : "Unix Makefiles"), j.dir, "clang");
        if (c != 0) throw new Fail("cmake configure failed (exit " + c + "). See Problems.");
        c = exec(cmd("cmake", "--build", b.getPath(), "-j", String.valueOf(Math.max(1, Runtime.getRuntime().availableProcessors()))), j.dir, "clang");
        if (c != 0) throw new Fail("cmake build failed (exit " + c + "). See Problems.");
        int n = collect(b, t0, out("native"), 0);
        cb.log(n + " program(s) or libraries collected");
        Io.delete(b);
    }

    private void make(Job j) throws Exception {
        need("clang", "make");
        List<String> a = cmd("make", "-j", String.valueOf(Math.max(1, Runtime.getRuntime().availableProcessors())));
        int c = exec(a, j.dir, "clang");
        if (c != 0) throw new Fail("make failed (exit " + c + "). See Problems.");
        int n = collect(j.dir, t0, out("native"), 0);
        cb.log(n + " program(s) or libraries collected");
    }

    /** Copies ELF files newer than since from dir (3 levels deep, no object files) into dest. */
    private int collect(File dir, long since, File dest, int depth) throws IOException {
        File[] k = dir.listFiles();
        if (k == null) return 0;
        int n = 0;
        for (File f : k) {
            String nm = f.getName();
            if (f.isDirectory()) {
                if (depth < 3 && !nm.equals("CMakeFiles") && !nm.equals(".git") && !nm.equals("deps") && !nm.equals("build") && !nm.equals("incremental"))
                    n += collect(f, since, dest, depth + 1);
            } else if (f.lastModified() >= since - 2000 && !nm.endsWith(".o") && !nm.endsWith(".a") && !nm.endsWith(".d") && f.length() > 0 && NativeExec.isElf(f)) {
                Io.copy(f, new File(dest, nm));
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ Rust, Go, Python, Node

    private void rust(Job j) throws Exception {
        need("rust", "cargo");
        File target = new File(ctx.getCacheDir(), "cargo-target/" + proj.getName() + "/" + clean(Io.rel(proj, j.dir)));
        target.mkdirs();
        int c = exec(cmd("cargo", "build", "--release", "--target-dir", target.getPath()), j.dir, "rust");
        if (c != 0) throw new Fail("cargo build failed (exit " + c + "). See Problems.");
        int n = collect(new File(target, "release"), t0, out("rust"), 2);
        cb.log(n + " program(s) or libraries collected");
    }

    private void golang(Job j) throws Exception {
        need("go", "go");
        File gm = new File(j.dir, "go.mod");
        if (gm.isFile() && !new File(j.dir, "go.sum").exists() && !new File(j.dir, "vendor").isDirectory() && has(gm, Pattern.compile("^\\s*require\\b", Pattern.MULTILINE))) {
            if (exec(cmd("go", "mod", "tidy"), j.dir, "go") != 0) throw new Fail("go mod tidy failed. See the log.");
        }
        File od = out("go");
        int c = exec(cmd("go", "build", "-o", od.getPath() + "/", "./..."), j.dir, "go");
        if (c != 0) throw new Fail("go build failed (exit " + c + "). See Problems.");
        File[] made = od.listFiles();
        cb.log((made == null ? 0 : made.length) + " program(s) built");
    }

    private static final String PYCHECK = "import ast,os,sys\nbad=0\nfor r,d,f in os.walk('.'):\n"
            + "    d[:]=[x for x in d if x not in ('.git','venv','.venv','node_modules','__pycache__','build','dist','site-packages')]\n"
            + "    for n in f:\n        if n.endswith('.py'):\n            p=os.path.join(r,n)\n            try:\n"
            + "                with open(p,encoding='utf-8') as fh: ast.parse(fh.read(),p)\n            except SyntaxError as e:\n"
            + "                bad+=1\n                print('%s:%d:%d: error: %s'%(p,e.lineno or 1,e.offset or 0,e.msg))\n"
            + "            except Exception as e:\n"
            + "                bad+=1\n                print('%s:1:0: error: %s'%(p,e))\n"
            + "sys.exit(1 if bad else 0)\n";

    private void python(Job j) throws Exception {
        need("python", "python");
        pendBase = j.dir;
        if (new File(j.dir, "requirements.txt").isFile()) {
            int c = exec(cmd("python", "-m", "pip", "install", "--user", "-r", "requirements.txt"), j.dir, "python");
            if (c != 0) throw new Fail("pip install failed (exit " + c + "). Packages with C code need the C / C++ pack and often do not build on a phone.");
        }
        int c = exec(cmd("python", "-c", PYCHECK), j.dir, "python");
        if (c != 0) throw new Fail("Python syntax errors. See Problems.");
        cb.log("syntax ok. Run a script with: lrun main.py");
    }

    private void node(Job j, Scan s) throws Exception {
        need("node", "node");
        pendBase = j.dir;
        if (new File(j.dir, "package.json").isFile()) {
            if (NativeExec.tool(ctx, "npm") == null) throw new Fail("npm is missing from the Node pack. Remove the pack and install it again.");
            int c = exec(cmd("npm", "install", "--no-audit", "--no-fund"), j.dir, "node");
            if (c != 0) throw new Fail("npm install failed (exit " + c + ")");
            c = exec(cmd("npm", "run", "build", "--if-present"), j.dir, "node");
            if (c != 0) throw new Fail("npm run build failed (exit " + c + "). See Problems.");
            for (String d : new String[]{"dist", "build", "out"}) {
                File f = new File(j.dir, d);
                if (f.isDirectory()) {
                    Io.copy(f, new File(out("node"), d));
                    cb.log("output: " + d + "/");
                    break;
                }
            }
            return;
        }
        int bad = 0;
        for (String e : new String[]{".js", ".mjs"})
            for (File f : s.f(e))
                if (under(f, j.dir) && exec(cmd("node", "--check", f.getPath()), j.dir, "node") != 0) bad++;
        if (bad > 0) throw new Fail(bad + " file(s) have syntax errors. See Problems.");
        cb.log("syntax ok. Run a script with: lrun main.js");
    }

    private void buildSh(Job j) throws Exception {
        if (!Packs.execDirect(ctx) && !Packs.installedIds(ctx).isEmpty()) cb.log("Note: targetSdk > 28, pack programs are started through the system linker. Programs they start themselves may be refused.");
        File od = out("script");
        List<String> a = new ArrayList<>(Arrays.asList("/system/bin/sh", "build.sh"));
        Map<String, String> env = allEnv("");
        env.put("CLOUDIDE_OUT", od.getPath());
        cb.log("$ sh build.sh   (output folder in $CLOUDIDE_OUT)");
        int c;
        try {
            c = NativeExec.run(ctx, a, j.dir, env, line -> {
                feed(line);
                cb.log(line);
            }, () -> cancelled);
        } catch (IOException e) {
            throw new Fail(e.getMessage());
        }
        if (cancelled) throw new Cancel();
        if (c != 0) throw new Fail("build.sh exited with " + c);
    }

    // ------------------------------------------------------------------ catalog languages (Ruby, PHP, Zig, ...)

    private void needPlugin(Packs.Pack pk) throws Fail {
        if (!Packs.installed(ctx, pk.id))
            throw new Fail("The " + pk.label + " plugin is not installed.\nType: plugin install " + pk.id + "   (or App settings > Languages, about " + pk.mb + " MB).");
        for (String r : pk.requires)
            if (!Packs.installed(ctx, r)) throw new Fail("The " + pk.label + " plugin needs the " + r + " plugin.\nType: plugin install " + r);
        if (!Packs.canExec(ctx)) throw new Fail(NativeExec.blockedText(ctx, new IOException("error=13, Permission denied")));
    }

    private static String fill(String t, File file, File proj, File dir, File out, File cache) {
        String name = file == null ? "" : file.getName().replaceFirst("\\.[^.]+$", "");
        return t.replace("{file}", file == null ? "" : file.getPath())
                .replace("{rel}", file == null ? "" : Io.rel(proj, file))
                .replace("{name}", name).replace("{out}", out.getPath()).replace("{dir}", dir.getPath())
                .replace("{cache}", cache.getPath()).replace("{proj}", proj.getPath());
    }

    private List<String> stepCmd(JSONArray step, File file, File dir, File out, File cache) {
        List<String> a = new ArrayList<>();
        for (int i = 0; i < step.length(); i++) {
            String t = fill(step.optString(i), file, proj, dir, out, cache);
            if (i == 0) {
                File tool = NativeExec.tool(ctx, t);
                a.add(tool == null ? t : tool.getPath());
            } else a.add(t);
        }
        return a;
    }

    private void plugin(Job j, Scan s) throws Exception {
        Packs.Pack pk = Packs.find(ctx, j.lang.substring(7));
        if (pk == null) throw new Fail("Unknown language plugin " + j.lang);
        needPlugin(pk);
        pendBase = j.dir;
        plugDiagDef = pk.diag;
        plugDiag = pk.diag == null ? null : Pattern.compile(pk.diag.optString("re"));
        try {
            runPlugin(pk, j, s);
        } finally {
            plugDiag = null;   // a failed step must not leave this language's error pattern active for the next job
            plugDiagDef = null;
        }
    }

    private void runPlugin(Packs.Pack pk, Job j, Scan s) throws Exception {
        File od = out(pk.id), cache = work("plugin-" + pk.id);
        JSONObject b = pk.build;
        int ran = 0, bad = 0;
        if (!j.marker.isEmpty()) {
            JSONArray pj = b.optJSONArray("projects");
            for (int i = 0; pj != null && i < pj.length(); i++) {
                JSONObject e = pj.getJSONObject(i);
                if (!e.optString("marker").equals(j.marker)) continue;
                JSONArray steps = e.getJSONArray("steps");
                for (int k = 0; k < steps.length(); k++) {
                    List<String> cmd = stepCmd(steps.getJSONArray(k), null, j.dir, od, cache);
                    int c = exec(cmd, j.dir, pk.id);
                    ran++;
                    if (c != 0) throw new Fail(pk.label + ": " + Toolchain.clip(Toolchain.join(cmd.subList(0, Math.min(2, cmd.size())), " ").replace(Packs.prefix(ctx).getPath() + "/bin/", ""), 60) + " failed (exit " + c + "). See Problems.");
                }
            }
        }
        JSONArray pf = b.optJSONArray("perFile");
        for (int i = 0; pf != null && i < pf.length(); i++) {
            JSONObject e = pf.getJSONObject(i);
            List<String> exts = new ArrayList<>();
            JSONArray ea = e.optJSONArray("ext");
            if (ea != null) for (int k = 0; k < ea.length(); k++) exts.add(ea.optString(k));
            else exts.addAll(pk.exts);
            Pattern match = e.has("match") ? Pattern.compile(e.getString("match")) : null;
            JSONArray steps = e.getJSONArray("steps");
            for (File f : s.all) {
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                boolean ok = false;
                for (String x : exts) if (n.endsWith(x)) ok = true;
                for (String x : pk.skipSuffix) if (n.endsWith(x)) ok = false;
                if (!ok || !under(f, j.dir)) continue;
                if (match != null && !has(f, match)) continue;
                ran++;
                for (int k = 0; k < steps.length(); k++) {
                    if (exec(stepCmd(steps.getJSONArray(k), f, j.dir, od, cache), j.dir, pk.id) != 0) {
                        bad++;
                        break;
                    }
                }
            }
        }
        if (bad > 0) throw new Fail(pk.label + ": " + bad + " file(s) failed. See Problems.");
        if (ran == 0) cb.log("nothing to build for " + pk.label + " here");
        File[] made = od.listFiles();
        if (made != null && made.length > 0) cb.log("output: " + made.length + " file(s) in " + pk.id);
        else if (!pk.run.isEmpty()) cb.log("checked. Run a file with: lrun <file>");
    }

    // ------------------------------------------------------------------ running what was built

    /**
     * Runs the last build. name picks a script (main.py, main.js), a program from the build folder, or nothing
     * (the Java / Kotlin main class, else the only program). Returns the exit code.
     */
    static int runLast(Context c, File proj, List<String> args, NativeExec.Out out, NativeExec.Cancel cancel) throws Exception {
        String first = args.isEmpty() ? "" : args.get(0);
        List<String> rest = args.size() > 1 ? args.subList(1, args.size()) : new ArrayList<String>();
        if (first.endsWith(".py") || first.endsWith(".js") || first.endsWith(".mjs") || first.endsWith(".go")) {
            String tool = first.endsWith(".py") ? "python" : first.endsWith(".go") ? "go" : "node";
            String pack = tool;
            if (!Packs.installed(c, pack)) {
                Packs.Pack missing = Packs.find(c, pack);
                throw new Fail("The " + (missing == null ? pack : missing.label) + " plugin is not installed (type: plugin install " + pack + ").");
            }
            if (!Packs.canExec(c)) throw new Fail(NativeExec.blockedText(c, new IOException("error=13")));
            File exe = NativeExec.tool(c, tool);
            if (exe == null) throw new Fail("The pack is installed but " + tool + " is missing from it. Remove the pack and install it again.");
            List<String> a = new ArrayList<>();
            a.add(exe.getPath());
            if (tool.equals("go")) a.add("run");
            a.add(first);
            a.addAll(rest);
            return NativeExec.run(c, a, proj, NativeExec.env(c, tool), out, cancel);
        }
        if (!first.isEmpty()) {
            for (Packs.Pack pk : Packs.all(c)) {
                if (pk.run.isEmpty() || pk.builtin || !pk.owns(first)) continue;
                if (!Packs.installed(c, pk.id)) throw new Fail("The " + pk.label + " plugin is not installed.\nType: plugin install " + pk.id);
                if (!Packs.canExec(c)) throw new Fail(NativeExec.blockedText(c, new IOException("error=13")));
                File target = new File(first);
                if (!target.isAbsolute()) target = new File(proj, first);
                List<String> a = new ArrayList<>();
                for (int i = 0; i < pk.run.size(); i++) {
                    String t = pk.run.get(i).replace("{file}", target.getPath());
                    if (i == 0) {
                        File tool = NativeExec.tool(c, t);
                        a.add(tool == null ? t : tool.getPath());
                    } else a.add(t);
                }
                a.addAll(rest);
                Map<String, String> env = NativeExec.allEnv(c);
                env.putAll(NativeExec.env(c, pk.id));
                return NativeExec.run(c, a, proj, env, out, cancel);
            }
        }
        File root = outDir(c, proj);
        File runTxt = null;
        File[] langs = root.listFiles();
        // java/ and kotlin/ can both hold a run.txt from different builds: run the newest one, not whichever listFiles() names last
        if (langs != null && first.isEmpty()) for (File d : langs) {
            File cand = new File(d, "run.txt");
            if (cand.isFile() && (runTxt == null || cand.lastModified() > runTxt.lastModified())) runTxt = cand;
        }
        if (runTxt != null) {
            String[] l = Io.read(runTxt).split("\n");
            if (l.length < 2 || l[1].isEmpty()) throw new Fail("The last build has no main method to run.");
            return runMain(c, new File(runTxt.getParentFile(), l[0]), l[1], args, out);
        }
        List<File> bins = new ArrayList<>();
        findBins(root, first, bins, 0);
        if (bins.isEmpty()) throw new Fail("Nothing to run. Build first (lbuild), or name a script: lrun main.py");
        if (bins.size() > 1 && first.isEmpty()) {
            StringBuilder sb = new StringBuilder("Several programs were built, pick one: lrun <name>\n");
            for (File f : bins) sb.append("  ").append(f.getName()).append('\n');
            throw new Fail(sb.toString().trim());
        }
        if (!Packs.canExec(c)) throw new Fail(NativeExec.blockedText(c, new IOException("error=13")));
        File run = new File(c.getFilesDir(), "run/" + bins.get(0).getName());
        Io.copy(bins.get(0), run);
        run.setReadable(true, false);
        run.setExecutable(true, false);
        List<String> a = new ArrayList<>();
        a.add(run.getPath());
        a.addAll(rest);
        return NativeExec.run(c, a, proj, NativeExec.env(c, ""), out, cancel);
    }

    private static void findBins(File d, String name, List<File> out, int depth) {
        File[] k = d.listFiles();
        if (k == null) return;
        for (File f : k) {
            if (f.isDirectory()) {
                if (depth < 3) findBins(f, name, out, depth + 1);
            } else if ((name.isEmpty() || f.getName().equals(name)) && NativeExec.isElf(f) && !f.getName().endsWith(".so")) {
                out.add(f);
            }
        }
    }

    private static final Object IO = new Object();

    /** Runs a main class from a dex jar inside this process, with its output captured. It cannot be force-stopped. */
    static int runMain(Context c, File dexJar, String mainClass, List<String> args, NativeExec.Out out) throws Exception {
        File run = new File(c.getFilesDir(), "run/" + dexJar.getName());
        run.delete();
        Io.copy(dexJar, run);
        run.setReadOnly(); // Android 14+ refuses writable dex files
        DexClassLoader cl = new DexClassLoader(run.getAbsolutePath(), c.getCodeCacheDir().getAbsolutePath(), null, c.getClassLoader().getParent());
        Class<?> k = cl.loadClass(mainClass);
        Method m = k.getMethod("main", String[].class);
        final int[] code = {0};
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread t = new Thread(null, () -> {
            synchronized (IO) {
                PrintStream oe = System.err, oo = System.out;
                java.io.InputStream oi = System.in;
                try {
                    PrintStream ps = new PrintStream(buf, true, "UTF-8");
                    System.setOut(ps);
                    System.setErr(ps);
                    System.setIn(new java.io.ByteArrayInputStream(new byte[0]));
                    m.invoke(null, (Object) args.toArray(new String[0]));
                } catch (InvocationTargetException e) {
                    new PrintStream(buf, true).println(Toolchain.root(e));
                    code[0] = 1;
                } catch (Throwable e) {
                    new PrintStream(buf, true).println(e);
                    code[0] = 1;
                } finally {
                    System.setOut(oo);
                    System.setErr(oe);
                    System.setIn(oi);
                }
            }
        }, "lrun", 16L << 20);
        t.start();
        t.join();
        for (String l : buf.toString("UTF-8").split("\n", -1)) if (!l.isEmpty()) out.line(l);
        return code[0];
    }
}
