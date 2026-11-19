package com.fahim.cloudide;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Starts programs from the native language packs (files/usr). No Termux: the prefix belongs to this app. */
final class NativeExec {
    interface Out { void line(String s); }

    interface Cancel { boolean is(); }

    private NativeExec() { }

    /** Environment for one language. PATH always has every installed pack. */
    static Map<String, String> env(Context c, String lang) {
        File usr = Packs.prefix(c);
        File home = Packs.home(c);
        File tmp = new File(c.getCacheDir(), "tmp");
        tmp.mkdirs();
        Map<String, String> e = new HashMap<>();
        e.put("PREFIX", usr.getPath());
        e.put("HOME", home.getPath());
        e.put("TMPDIR", tmp.getPath());
        e.put("PATH", usr.getPath() + "/bin:/system/bin");
        e.put("LD_LIBRARY_PATH", usr.getPath() + "/lib");
        e.put("LANG", "en_US.UTF-8");
        e.put("TERM", "dumb");
        e.put("SSL_CERT_FILE", usr.getPath() + "/etc/tls/cert.pem");
        e.put("CURL_CA_BUNDLE", usr.getPath() + "/etc/tls/cert.pem");
        if ("go".equals(lang)) {
            e.put("GOROOT", usr.getPath() + "/lib/go");
            e.put("GOPATH", home.getPath() + "/go");
            e.put("GOCACHE", c.getCacheDir().getPath() + "/go-build");
            e.put("GOTMPDIR", tmp.getPath());
            e.put("GOTOOLCHAIN", "local");
            e.put("CGO_ENABLED", new File(usr, "bin/clang").exists() ? "1" : "0");
        } else if ("rust".equals(lang)) {
            e.put("CARGO_HOME", home.getPath() + "/.cargo");
            e.put("RUSTUP_HOME", home.getPath() + "/.rustup");
        } else if ("python".equals(lang)) {
            e.put("PYTHONUSERBASE", home.getPath() + "/.local");
            e.put("PYTHONDONTWRITEBYTECODE", "1");
            e.put("PIP_CACHE_DIR", c.getCacheDir().getPath() + "/pip");
        } else if ("node".equals(lang)) {
            e.put("npm_config_cache", home.getPath() + "/.npm");
            e.put("npm_config_update_notifier", "false");
        }
        pluginEnv(c, e, lang);
        hookEnv(c, e, lang);
        return e;
    }

    // ------------------------------------------------------------------ exec hook (targetSdk >= 29)

    /** A lib*.so shipped in jniLibs (extracted to nativeLibraryDir because useLegacyPackaging = true), or null. */
    static File nativeLib(Context c, String name) {
        File f = new File(c.getApplicationInfo().nativeLibraryDir, name);
        return f.isFile() ? f : null;
    }

    static boolean needsHook(Context c) { return !Packs.execDirect(c); }

    /** Folders whose programs the hook starts through the system linker. Both spellings of the app's data dir. */
    static String roots(Context c) {
        java.util.LinkedHashSet<String> r = new java.util.LinkedHashSet<>();
        List<File> base = new ArrayList<>();
        base.add(c.getFilesDir());
        base.add(c.getCacheDir());
        File ext = c.getExternalFilesDir(null); // scripts here can run (only read); ELF files here cannot (noexec mount)
        if (ext != null) base.add(ext);
        for (File f : base) {
            r.add(f.getPath());
            String p = f.getPath();
            if (p.startsWith("/data/user/0/")) r.add("/data/data/" + p.substring("/data/user/0/".length()));
            else if (p.startsWith("/data/data/")) r.add("/data/user/0/" + p.substring("/data/data/".length()));
            try {
                r.add(f.getCanonicalPath());
            } catch (IOException ignored) {
            }
        }
        StringBuilder b = new StringBuilder();
        for (String x : r) b.append(b.length() == 0 ? "" : ":").append(x);
        return b.toString();
    }

    static boolean inRoots(Context c, String path) {
        for (String r : roots(c).split(":")) if (!r.isEmpty() && (path.equals(r) || path.startsWith(r + "/"))) return true;
        return false;
    }

    /**
     * Makes every program started below this process work at targetSdk 29+: LD_PRELOAD=libexec_hook.so rewrites exec()
     * and posix_spawn() of files under the roots to "linker64 file"; libcloudexec.so does the same for Go's raw
     * syscalls (-toolexec / -exec / CC) and for static programs. Without the libraries nothing is added.
     */
    static void hookEnv(Context c, Map<String, String> e, String lang) {
        if (!needsHook(c)) return;
        File hook = nativeLib(c, "libexec_hook.so");
        File launcher = nativeLib(c, "libcloudexec.so");
        e.put("CLOUDIDE_PREFIX", Packs.prefix(c).getPath());
        e.put("CLOUDIDE_EXEC_ROOTS", roots(c));
        if (hook != null) {
            e.put("CLOUDIDE_PRELOAD", hook.getPath());
            e.put("LD_PRELOAD", hook.getPath());
        }
        if (launcher != null) e.put("CLOUDIDE_LAUNCHER", launcher.getPath());
        if (launcher != null && "go".equals(lang)) {
            // Go starts compile, link and the program it built with raw syscalls: LD_PRELOAD never sees them.
            String l = launcher.getPath();
            e.put("GOFLAGS", "-toolexec=" + l + " -exec=" + l + " -buildvcs=false");
            File clang = new File(Packs.prefix(c), "bin/clang");
            if (clang.exists()) e.put("CC", l + " " + clang.getPath());
        }
    }

    /**
     * Variables the language plugins ask for in their catalog entry (GEM_HOME for Ruby, ...). ${PREFIX}, ${HOME}
     * and ${CACHE} are filled in. only = one plugin id, or null / "" for every installed plugin.
     */
    static void pluginEnv(Context c, Map<String, String> e, String only) {
        for (Packs.Pack p : Packs.all(c)) {
            if (p.env.isEmpty() || (only != null && !only.isEmpty() && !p.id.equals(only))) continue;
            if (only == null || only.isEmpty()) {
                if (!Packs.installed(c, p.id)) continue;
            }
            for (Map.Entry<String, String> x : p.env.entrySet())
                e.put(x.getKey(), x.getValue().replace("${PREFIX}", Packs.prefix(c).getPath()).replace("${HOME}", Packs.home(c).getPath())
                        .replace("${CACHE}", c.getCacheDir().getPath()));
        }
    }

    /** Environment with every language's variables, for the terminal and build.sh. */
    static Map<String, String> allEnv(Context c) {
        Map<String, String> e = new HashMap<>();
        for (String l : new String[]{"go", "rust", "python", "node"}) e.putAll(env(c, l));
        pluginEnv(c, e, null);
        e.put("PATH", Packs.prefix(c).getPath() + "/bin:/system/bin");
        e.remove("CGO_ENABLED");
        return e;
    }

    /** Runs a command, streams each output line to out, returns the exit code (130 when cancelled). */
    static int run(Context c, List<String> cmd, File cwd, Map<String, String> env, Out out, Cancel cancel) throws IOException, InterruptedException {
        IOException last = null;
        List<List<String>> tries = new ArrayList<>();
        boolean ours = !cmd.isEmpty() && inRoots(c, cmd.get(0));
        File launcher = nativeLib(c, "libcloudexec.so");
        if (needsHook(c) && ours && launcher != null) {
            // The launcher resolves #! scripts and PATH, starts ELF files through linker64 and static ones itself
            List<String> via = new ArrayList<>();
            via.add(launcher.getPath());
            via.add("--");
            via.addAll(cmd);
            tries.add(via);
        }
        if (!needsHook(c) || !ours) tries.add(cmd);
        // Android 10+: a file in app storage can still be mapped by the linker when plain exec is refused
        if (needsHook(c) && ours && !cmd.isEmpty() && new File(cmd.get(0)).isFile() && isElf(new File(cmd.get(0)))) {
            List<String> via = new ArrayList<>();
            via.add(Build.SUPPORTED_64_BIT_ABIS.length > 0 ? "/system/bin/linker64" : "/system/bin/linker");
            via.addAll(cmd);
            tries.add(via);
        }
        if (tries.isEmpty()) tries.add(cmd);
        for (List<String> t : tries) {
            final int[] lines = new int[1];
            ProcessBuilder pb = new ProcessBuilder(t);
            pb.directory(cwd);
            pb.redirectErrorStream(true);
            pb.environment().putAll(env);
            final Process p;
            try {
                p = pb.start();
            } catch (IOException e) {
                last = e;
                continue;
            }
            try {
                p.getOutputStream().close();
            } catch (IOException ignored) {
            }
            final InputStream in = p.getInputStream();
            Thread reader = new Thread(() -> {
                try (Reader r = new InputStreamReader(in, "UTF-8")) {
                    StringBuilder line = new StringBuilder();
                    char[] b = new char[2048];
                    int n;
                    while ((n = r.read(b)) > 0) {
                        for (int i = 0; i < n; i++) {
                            if (b[i] == '\n') {
                                lines[0]++;
                                out.line(line.toString().replace("\r", ""));
                                line.setLength(0);
                            } else {
                                line.append(b[i]);
                            }
                        }
                    }
                    if (line.length() > 0) { lines[0]++; out.line(line.toString().replace("\r", "")); }
                } catch (IOException ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();
            int code;
            try {
                while (true) {
                    if (p.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        code = p.exitValue();
                        break;
                    }
                    if (cancel != null && cancel.is()) {
                        p.destroy();
                        if (!p.waitFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly(); // SIGTERM was ignored
                        reader.join(500);
                        return 130;
                    }
                }
            } catch (InterruptedException ie) {
                p.destroyForcibly(); // the calling thread was interrupted: do not leave the program running with nobody reading it
                throw ie;
            }
            reader.join(1500);
            // 126 / 127 with almost no output = the loader failed, not the program: try the next way
            if ((code == 126 || code == 127) && lines[0] <= 3 && t != tries.get(tries.size() - 1)) continue;
            return code;
        }
        throw new IOException(blockedText(c, last));
    }

    static String blockedText(Context c, IOException e) {
        String m = e == null ? "" : String.valueOf(e.getMessage());
        if (m.contains("error=13") || m.toLowerCase(java.util.Locale.ROOT).contains("permission denied")) {
            boolean hook = nativeLib(c, "libexec_hook.so") != null && nativeLib(c, "libcloudexec.so") != null;
            return "Android refused to start the program (" + m + ").\n"
                    + "This app has targetSdk " + c.getApplicationInfo().targetSdkVersion + " and Android " + Build.VERSION.RELEASE
                    + " does not allow plain exec from app storage.\n"
                    + (hook ? "The exec helper (libexec_hook.so / libcloudexec.so) is installed, but starting the program through the system linker failed too. Run Check toolchain for details; see NATIVE_EXEC.md."
                    : "The exec helper libraries are missing from this build (libexec_hook.so, libcloudexec.so). Build with native/build.sh (the GitHub workflow does it), or set cloudide.targetSdk=28.");
        }
        return "Cannot start the program: " + m;
    }

    static boolean isElf(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] h = new byte[4];
            return in.read(h) == 4 && h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    /** Finds an executable by name in the pack prefix, or null. */
    static File tool(Context c, String name) {
        File f = new File(Packs.prefix(c), "bin/" + name);
        return f.exists() ? f : null;
    }

    /** A cancel check that turns true after the given time, so a hanging program ends a self-test instead of blocking it. */
    private static Cancel after(long ms) {
        final long end = System.currentTimeMillis() + ms;
        return () -> System.currentTimeMillis() > end;
    }

    /** Shown by Check toolchain: can this build run native packs, and do the installed ones start. */
    static String selfTest(Context c) {
        StringBuilder r = new StringBuilder();
        r.append("Native packs: targetSdk ").append(c.getApplicationInfo().targetSdkVersion).append(", exec from app storage ")
                .append(Packs.execDirect(c) ? "allowed\n" : "refused, so every program is started through the system linker (results below show if it works)\n");
        execHookTest(c, r);
        List<String[]> probes = new ArrayList<>();
        List<String> owner = new ArrayList<>();
        for (Packs.Pack pk : Packs.all(c)) {
            if (pk.jvm || pk.meta || !Packs.installed(c, pk.id)) continue;
            for (String[] pr : pk.probes) {
                probes.add(pr);
                owner.add(pk.id);
            }
        }
        boolean any = false;
        for (int pi = 0; pi < probes.size(); pi++) {
            String[] p = probes.get(pi);
            File t = tool(c, p[0]);
            if (t == null) {
                r.append("FAIL  ").append(p[0]).append(": not found in the ").append(owner.get(pi)).append(" pack\n");
                continue;
            }
            any = true;
            List<String> cmd = new ArrayList<>();
            cmd.add(t.getAbsolutePath());
            for (int k = 1; k < p.length; k++) cmd.add(p[k]);
            final StringBuilder o = new StringBuilder();
            try {
                int code = run(c, cmd, c.getCacheDir(), allEnv(c), s -> { if (o.length() < 200) o.append(s).append(' '); }, after(30_000));
                r.append(code == 0 ? "OK    " : "FAIL  ").append(p[0]).append(' ').append(o.toString().trim()).append('\n');
            } catch (Throwable e) {
                r.append("FAIL  ").append(p[0]).append(": ").append(e.getMessage()).append('\n');
            }
        }
        if (!any && probes.isEmpty()) r.append("No native pack installed.\n");
        if (KotlinTool.ready(c)) {
            try {
                File t = new File(c.getCacheDir(), "kt-test");
                Io.delete(t);
                File src = new File(t, "T.kt");
                Io.write(src, "fun main() { val x = listOf(1, 2, 3).map { it * 2 }; println(x) }\n");
                File out = new File(t, "out");
                out.mkdirs();
                List<String> a = new ArrayList<>();
                KotlinTool.base(c, a, Toolchain.file(c, "android.jar").getPath(), out, 17);
                a.add(src.getPath());
                StringBuilder o = new StringBuilder();
                boolean ok = KotlinTool.compile(c, a, o);
                r.append(ok ? "OK    kotlinc compiles Kotlin (" + KotlinTool.mode() + ")\n" : "FAIL  kotlinc: " + kotlinDigest(o.toString()) + "\n");
                r.append("      (").append(HiddenApi.status()).append(")\n");
                Io.delete(t);
                if (ok) r.append(kotlinMultiFileTest(c));
            } catch (Throwable e) {
                r.append("FAIL  kotlinc: ").append(Toolchain.describe(e)).append('\n');
                r.append("      (").append(HiddenApi.status()).append(")\n");
            }
        }
        return r.toString();
    }

    /** Two files that use each other: data class, extension, interface, sealed class, lambdas, string templates. */
    private static String kotlinMultiFileTest(Context c) {
        File t = new File(c.getCacheDir(), "kt-test2");
        try {
            Io.delete(t);
            File out = new File(t, "out");
            out.mkdirs();
            File a = new File(t, "A.kt"), b = new File(t, "B.kt");
            Io.write(a, "package demo\n\ndata class Person(val name: String, val age: Int)\n\n"
                    + "fun Person.greeting() = \"Hi $name ($age)\"\n\n"
                    + "interface Shape { fun area(): Double }\n"
                    + "class Circle(val r: Double) : Shape { override fun area() = Math.PI * r * r }\n");
            Io.write(b, "package demo\n\n"
                    + "sealed class Result { class Ok(val v: Int) : Result(); class Err(val m: String) : Result() }\n\n"
                    + "fun describe(r: Result): String = when (r) { is Result.Ok -> \"ok ${r.v}\"; is Result.Err -> \"err ${r.m}\" }\n\n"
                    + "fun main() {\n"
                    + "    val people = listOf(Person(\"Ann\", 31), Person(\"Bo\", 25))\n"
                    + "    val names = people.sortedBy { it.age }.joinToString { it.greeting() }\n"
                    + "    val shapes: List<Shape> = listOf(Circle(1.0))\n"
                    + "    println(names + shapes.sumOf { it.area() } + describe(Result.Ok(1)))\n"
                    + "}\n");
            List<String> args = new ArrayList<>();
            KotlinTool.base(c, args, Toolchain.file(c, "android.jar").getPath(), out, 17);
            args.add(a.getPath());
            args.add(b.getPath());
            StringBuilder o = new StringBuilder();
            boolean ok = KotlinTool.compile(c, args, o);
            String[] want = {"demo/Person.class", "demo/AKt.class", "demo/Circle.class", "demo/BKt.class", "demo/Result$Ok.class", "demo/Result$Err.class"};
            StringBuilder missing = new StringBuilder();
            for (String w : want) if (!new File(out, w).isFile()) missing.append(' ').append(w);
            if (ok && missing.length() == 0) return "OK    kotlinc compiles two Kotlin files (classes, extension, sealed, lambdas)\n";
            return "FAIL  kotlinc two-file build: " + (ok ? "missing" + missing : kotlinDigest(o.toString())) + "\n";
        } catch (Throwable e) {
            return "FAIL  kotlinc two-file build: " + Toolchain.describe(e) + "\n";
        } finally {
            Io.delete(t);
        }
    }

    /** Compiler output, shortened for the self-test: the start, then every "Caused by:" with its first frames. */
    static String kotlinDigest(String all) {
        String[] lines = all.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length && sb.length() < 1500; i++) sb.append(lines[i]).append('\n');
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith("Caused by")) continue;
            if (sb.length() > 6000) break;
            sb.append("...\n");
            for (int j = i; j < lines.length && j < i + 6; j++) sb.append(lines[j]).append('\n');
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ exec hook self-test

    private static String runCapture(Context c, List<String> cmd, Map<String, String> env, int limit) {
        final StringBuilder o = new StringBuilder();
        try {
            int code = run(c, cmd, c.getCacheDir(), env, s -> { if (o.length() < limit) o.append(s).append(' '); }, after(30_000));
            return code + "|" + o.toString().trim();
        } catch (Throwable e) {
            return "-1|" + e.getMessage();
        }
    }

    private static void line(StringBuilder r, boolean ok, String name, String detail) {
        r.append(ok ? "OK    " : "FAIL  ").append(name);
        if (detail != null && !detail.isEmpty()) r.append(": ").append(detail);
        r.append('\n');
    }

    /** Proves that programs started BY programs work: no pack needed for the first three checks. */
    static void execHookTest(Context c, StringBuilder r) {
        if (!needsHook(c)) {
            r.append("Exec helper: not needed (plain exec is allowed here)\n");
            return;
        }
        File hook = nativeLib(c, "libexec_hook.so"), launcher = nativeLib(c, "libcloudexec.so");
        line(r, hook != null && launcher != null, "exec helper libraries",
                hook != null && launcher != null ? "libexec_hook.so + libcloudexec.so in nativeLibraryDir"
                        : "missing from the APK (run native/build.sh or the GitHub build)");
        if (hook == null || launcher == null) return;
        Map<String, String> env = new HashMap<>();
        env.put("PATH", Packs.prefix(c).getPath() + "/bin:/system/bin");
        env.put("TMPDIR", c.getCacheDir().getPath());
        hookEnv(c, env, "");

        String o = runCapture(c, java.util.Arrays.asList(launcher.getPath(), "--check"), env, 100);
        line(r, o.startsWith("0|") && o.contains("cloudexec ok"), "launcher starts from nativeLibraryDir", o);

        File d = new File(c.getCacheDir(), "hooktest");
        Io.delete(d);
        d.mkdirs();
        try {
            // a child ELF in app storage, started by a shell (the shell is a /system/bin program with the preload)
            File tb = new File("/system/bin/toybox");
            if (tb.isFile()) {
                File copy = new File(d, "toybox");
                Io.copy(tb, copy);
                copy.setReadable(true, false);
                copy.setExecutable(true, false);
                o = runCapture(c, java.util.Arrays.asList("/system/bin/sh", "-c", "\"" + copy.getPath() + "\" echo child-elf-ok"), env, 200);
                line(r, o.contains("child-elf-ok"), "child ELF from app storage (sh -> program)", o);
            }
            File sc = new File(d, "s.sh");
            Io.write(sc, "#!/system/bin/sh\necho child-script-ok\n");
            sc.setReadable(true, false);
            sc.setExecutable(true, false);
            o = runCapture(c, java.util.Arrays.asList("/system/bin/sh", "-c", "\"" + sc.getPath() + "\""), env, 200);
            line(r, o.contains("child-script-ok"), "child #! script from app storage", o);
        } catch (Throwable e) {
            line(r, false, "exec helper test files", e.getMessage());
        } finally {
            Io.delete(d);
        }

        Map<String, String> all = allEnv(c);
        if (Packs.installed(c, "python") && tool(c, "python") != null) {
            List<String> cmd = new ArrayList<>();
            cmd.add(tool(c, "python").getPath());
            cmd.add("-c");
            cmd.add("import subprocess; subprocess.run([\"python\",\"--version\"])");
            o = runCapture(c, cmd, all, 200);
            line(r, o.startsWith("0|") && o.contains("Python"), "python starts python (subprocess)", o);
        }
        if (Packs.installed(c, "node") && tool(c, "node") != null) {
            List<String> cmd = new ArrayList<>();
            cmd.add(tool(c, "node").getPath());
            cmd.add("-e");
            cmd.add("console.log(require('child_process').execFileSync('node',['--version']).toString().trim())");
            o = runCapture(c, cmd, all, 200);
            line(r, o.startsWith("0|") && o.contains("v"), "node starts node (child_process)", o);
        }
        if (Packs.installed(c, "go") && tool(c, "go") != null) {
            Map<String, String> ge = allEnv(c);
            ge.putAll(env(c, "go"));
            File gd = new File(c.getCacheDir(), "go-selftest");
            Io.delete(gd);
            gd.mkdirs();
            try {
                Io.write(new File(gd, "go.mod"), "module selftest\n\ngo 1.21\n");
                Io.write(new File(gd, "main.go"), "package main\nimport \"fmt\"\nfunc main() { fmt.Println(\"go-run-ok\") }\n");
                final StringBuilder go = new StringBuilder();
                int code = run(c, java.util.Arrays.asList(tool(c, "go").getPath(), "run", "."), gd, ge, s -> { if (go.length() < 300) go.append(s).append(' '); }, after(120_000));
                line(r, code == 0 && go.indexOf("go-run-ok") >= 0, "go builds and runs a program (toolexec)", code + "|" + go.toString().trim());
            } catch (Throwable e) {
                line(r, false, "go builds and runs a program (toolexec)", e.getMessage());
            } finally {
                Io.delete(gd);
            }
        }
    }
}
