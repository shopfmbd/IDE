package com.fahim.cloudide;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Builds the project on the phone:
 *   manifest fix-up -> aapt2 compile + link (R.java) -> ECJ -> D8 -> aligned zip -> apksig.
 * Handles plain Java Android projects with a Gradle-style or flat layout, jar/aar files in libs/,
 * jniLibs and assets. Kotlin needs the Kotlin pack. Maven downloads are not part of the local engine (use the GitHub build).
 */
final class LocalBuild {
    static final class Fail extends Exception { Fail(String m) { super(m); } }

    static final class Cancel extends Exception { }

    private static final class Meta {
        String pkg = "", appId = "", versionName = "1.0";
        int minSdk = 24, target = 37, compile = 0, versionCode = 1, java = 17;
        List<String> deps = new ArrayList<>();
    }

    private static final Pattern DIAG = Pattern.compile("^(.+?):(\\d+):(?:(\\d+):)?\\s*(error|warning)\\s*:\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    final Context ctx;
    final File proj;
    final ProjectConfig cfg;
    final CloudBuild.Cb cb;
    volatile boolean running, cancelled;
    private final List<Diag> diags = new ArrayList<>();
    private final Map<String, String> remap = new HashMap<>();
    private long t0;

    LocalBuild(Context c, File p, ProjectConfig cfg, CloudBuild.Cb cb) {
        this.ctx = c.getApplicationContext();
        this.proj = p;
        this.cfg = cfg;
        this.cb = cb;
    }

    void start() {
        running = true;
        BuildService.start(ctx);
        // ECJ recurses deeply on big files, so give the thread a large stack
        new Thread(null, this::run, "localbuild", 64L << 20).start();
    }

    void cancel() { cancelled = true; }

    private void step(String s) throws Cancel {
        if (cancelled) throw new Cancel();
        cb.status(s);
        cb.log("> " + s);
    }

    private void run() {
        t0 = System.currentTimeMillis();
        // Speed boost for slow phones: higher CPU priority for the build (it passes on to the threads the compilers start),
        // and a wake lock so the CPU keeps running when the screen turns off.
        PowerManager.WakeLock wl = null;
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY); }
        catch (Throwable e) { try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND); } catch (Throwable ignored) { } }
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cloudide:build");
                wl.acquire(30L * 60 * 1000);
            }
        } catch (Throwable ignored) { }
        // Tell the person what is really active, so it is clear whether the boost is applied
        try {
            for (int i = 0; i < 15 && !BuildService.isUp(); i++) Thread.sleep(100);   // the service starts a moment after the build
            int prio = android.os.Process.getThreadPriority(android.os.Process.myTid());
            boolean held = wl != null && wl.isHeld();
            boolean svc = BuildService.isUp();
            int on = (prio < 0 ? 1 : 0) + (held ? 1 : 0) + (svc ? 1 : 0);
            cb.log("Speed boost: " + (on == 3 ? "ON" : on == 0 ? "OFF" : "PARTIAL") + "  |  CPU priority " + prio + (prio < 0 ? " (raised)" : " (normal)")
                    + "  |  wake lock " + (held ? "held" : "not held") + "  |  foreground service " + (svc ? "running" : "not running"
                    + " (allow notifications and set Battery saver to No restrictions for CloudIDE)"));
        } catch (Throwable ignored) { }
        try {
            File apk = build();
            cb.log("Finished in " + Human.dur(System.currentTimeMillis() - t0));
            cb.done(true, apk, new ArrayList<Diag>(), "", "Build succeeded (on device)");
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
            try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignored) { }
            BuildService.stop(ctx);
        }
    }

    // ------------------------------------------------------------------ pipeline

    private File build() throws Exception {
        if (Build.VERSION.SDK_INT < 26) throw new Fail("Local builds need Android 8.0 or newer.");
        List<String> miss = Toolchain.missing(ctx);
        if (!miss.isEmpty())
            throw new Fail("The local toolchain is not installed (missing " + Toolchain.join(miss, ", ")
                    + ").\nOpen App settings > Build tools and install it once.");

        step("Reading project");
        File module = null, manifest = null;
        for (File m : new File[]{new File(proj, "app"), proj}) {
            File mf = new File(m, "src/main/AndroidManifest.xml");
            if (!mf.isFile()) mf = new File(m, "AndroidManifest.xml");
            if (mf.isFile()) { module = m; manifest = mf; break; }
        }
        if (module == null) throw new Fail("No AndroidManifest.xml found (looked in app/src/main and the project root).");
        boolean gradleLayout = new File(module, "src/main").isDirectory();
        List<File> srcDirs = new ArrayList<>();
        List<File> libModules = gradleLayout && LocalDeps.hasBuildFile(module) ? LocalDeps.localModules(proj, module) : new ArrayList<File>();
        if (gradleLayout) {
            addIfDir(srcDirs, new File(module, "src/main/java"));
            addIfDir(srcDirs, new File(module, "src/main/kotlin"));
            for (File m : libModules) {
                addIfDir(srcDirs, new File(m, "src/main/java"));
                addIfDir(srcDirs, new File(m, "src/main/kotlin"));
            }
        } else {
            addIfDir(srcDirs, new File(module, "src"));
            addIfDir(srcDirs, new File(module, "java"));
        }
        File resDir = gradleLayout ? new File(module, "src/main/res") : new File(module, "res");
        File assetsDir = gradleLayout ? new File(module, "src/main/assets") : new File(module, "assets");
        File jniDir = gradleLayout ? new File(module, "src/main/jniLibs") : new File(module, "jniLibs");
        File javaResDir = gradleLayout ? new File(module, "src/main/resources") : null;

        List<File> javaFiles = new ArrayList<>();
        for (File d : srcDirs) walk(d, ".java", javaFiles);
        List<File> kt = new ArrayList<>();
        for (File d : srcDirs) walk(d, ".kt", kt);
        if (!kt.isEmpty() && !KotlinTool.ready(ctx))
            throw new Fail("This project has Kotlin files (" + kt.get(0).getName() + ").\nInstall the Kotlin pack once: App settings > Languages > Kotlin.");
        if (javaFiles.isEmpty() && kt.isEmpty()) throw new Fail("No .java or .kt files found in " + srcDirs);

        Meta meta = parseGradle(module);
        String text = Io.read(manifest);
        Matcher pm = Pattern.compile("<manifest\\b[^>]*?\\bpackage\\s*=\\s*\"([^\"]+)\"").matcher(text);
        String pkg = pm.find() ? pm.group(1) : (!meta.pkg.isEmpty() ? meta.pkg : meta.appId);
        if (pkg.isEmpty()) throw new Fail("No package name. Add namespace = '...' to build.gradle or package=\"...\" to the manifest.");
        String appId = meta.appId.isEmpty() ? pkg : meta.appId;
        int minSdk = Math.max(21, meta.minSdk);
        cb.log("package " + pkg + (appId.equals(pkg) ? "" : " (applicationId " + appId + ")") + ", minSdk " + minSdk + ", targetSdk " + meta.target
                + ", Java " + meta.java);

        File work = new File(ctx.getCacheDir(), "local/" + proj.getName());
        Io.delete(work);
        work.mkdirs();
        // Unlike work, this folder is kept between builds: compiled resource files and the app's dex, reused while their inputs are unchanged.
        File incr = new File(ctx.getCacheDir(), "localincr/" + proj.getName().replaceAll("[^A-Za-z0-9._-]+", "_"));
        File androidJar = Toolchain.file(ctx, "android.jar");
        int jarApi = Toolchain.platformApi(ctx);
        int wantApi = meta.compile > 0 ? meta.compile : meta.target;
        if (jarApi > 0 && wantApi > jarApi)
            cb.log("Note: compileSdk " + wantApi + " (Android " + Toolchain.androidName(wantApi) + ") is newer than the bundled android.jar (API " + jarApi
                    + "). Builds still run; APIs added after API " + jarApi + " will not resolve. Update android.jar in App settings > Build tools.");
        else if (wantApi >= 37) cb.log("compileSdk " + wantApi + " (Android " + Toolchain.androidName(wantApi) + ")");

        // manifest: aapt2 needs package=, Gradle normally injects it from namespace
        File mdir = new File(work, "manifest");
        File patched = new File(mdir, "AndroidManifest.xml");
        if (!pm.reset().find()) text = text.replaceFirst("<manifest\\b", "<manifest package=\"" + pkg + "\"");
        // Crash logger: a provider that installs an uncaught-exception handler and an activity that shows the stack trace.
        // It lets you read why a locally built app crashed on the phone itself. An empty file .nocrashlog in the project turns it off.
        List<File> crashSrc = new ArrayList<>();
        int appEnd = text.lastIndexOf("</application>");
        if (!new File(proj, ".nocrashlog").exists() && !text.contains("cloudide.crashlog") && appEnd > 0) {
            try {
                for (String n : new String[]{"CrashLogProvider", "CrashActivity"}) {
                    File f = new File(work, "crashlog/cloudide/crashlog/" + n + ".java");
                    Io.write(f, new String(Io.bytes(ctx.getAssets().open("crashlog/" + n + ".java.txt")), "UTF-8"));
                    crashSrc.add(f);
                }
                text = text.substring(0, appEnd)
                        + "    <provider android:name=\"cloudide.crashlog.CrashLogProvider\" android:authorities=\"" + appId + ".cloudide.crashlog\""
                        + " android:exported=\"false\" android:initOrder=\"2147483647\"/>\n"
                        + "    <activity android:name=\"cloudide.crashlog.CrashActivity\" android:process=\":cloudide_crash\" android:exported=\"false\""
                        + " android:excludeFromRecents=\"true\" android:configChanges=\"orientation|screenSize|keyboardHidden|screenLayout|smallestScreenSize|uiMode|density\""
                        + " android:theme=\"@android:style/Theme.DeviceDefault.Light.NoActionBar\"/>\n"
                        + text.substring(appEnd);
                cb.log("crash logger added (a crash opens a screen with the error; add a .nocrashlog file to the project to turn it off)");
            } catch (Exception e) {
                crashSrc.clear();
                cb.log("note: crash logger not added (" + e.getMessage() + ")");
            }
        }
        // Gradle fills ${applicationId} in the app's own manifest too (provider authorities, FileProvider ...)
        final String manifestText = text.replace("${applicationId}", appId).replace("${packageName}", appId);
        remap.put(patched.getAbsolutePath(), Io.rel(proj, manifest));

        // libraries from libs/
        step("Collecting libraries");
        List<File> libJars = new ArrayList<>();
        List<File> libRes = new ArrayList<>();
        Set<String> extraPkgs = new java.util.LinkedHashSet<>();
        Map<String, File> jni = new LinkedHashMap<>();
        List<String[]> libManifests0 = new ArrayList<>();
        List<File> libAssets0 = new ArrayList<>();
        Set<String> seenLibs = new HashSet<>();
        for (File ld : new File[]{new File(module, "libs"), new File(proj, "libs")}) {
            File[] fs = ld.listFiles();
            if (fs == null) continue;
            Arrays.sort(fs);
            for (File f : fs) {
                if (!seenLibs.add(f.getCanonicalPath())) continue;
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (n.endsWith(".jar")) libJars.add(f);
                else if (n.endsWith(".aar")) openAar(f, work, pkg, libJars, libRes, extraPkgs, jni, libManifests0, libAssets0);
            }
        }
        if (jniDir.isDirectory()) {
            File[] abis = jniDir.listFiles();
            if (abis != null) for (File abi : abis) {
                File[] sos = abi.listFiles();
                if (sos != null) for (File so : sos) if (so.getName().endsWith(".so")) jni.put("lib/" + abi.getName() + "/" + so.getName(), so);
            }
        }
        cb.log(libJars.size() + " library jar(s), " + libRes.size() + " library resource folder(s)");
        List<String[]> libManifests = libManifests0;
        List<File> libAssets = libAssets0;
        List<File> compileOnly = new ArrayList<>();
        // local library modules: their resources, assets, jni, libs and manifests join the app
        for (File m : libModules) {
            File lres = new File(m, "src/main/res");
            if (lres.isDirectory() && hasFiles(lres)) libRes.add(lres);
            File las = new File(m, "src/main/assets");
            if (las.isDirectory() && hasFiles(las)) libAssets.add(las);
            File[] jabis = new File(m, "src/main/jniLibs").listFiles();
            if (jabis != null) for (File abi : jabis) {
                File[] sos = abi.listFiles();
                if (sos != null) for (File so : sos) if (so.getName().endsWith(".so")) jni.put("lib/" + abi.getName() + "/" + so.getName(), so);
            }
            File[] mlibs = new File(m, "libs").listFiles();
            if (mlibs != null) for (File f : mlibs) {
                if (f.getName().endsWith(".jar")) libJars.add(f);
                else if (f.getName().endsWith(".aar")) openAar(f, work, pkg, libJars, libRes, extraPkgs, jni, libManifests, libAssets);
            }
            File lm = new File(m, "src/main/AndroidManifest.xml");
            Meta lmeta = parseGradle(m);
            String lpkg = lmeta.pkg;
            if (lm.isFile()) {
                String lt = Io.read(lm);
                Matcher lpm = Pattern.compile("<manifest\\b[^>]*?\\bpackage\\s*=\\s*\"([^\"]+)\"").matcher(lt);
                if (lpm.find()) lpkg = lpm.group(1);
                libManifests.add(new String[]{lpkg, lt});
            }
            if (!lpkg.isEmpty() && !lpkg.equals(pkg)) extraPkgs.add(lpkg);
            cb.log("module " + m.getName() + (lpkg.isEmpty() ? "" : " (" + lpkg + ")"));
        }
        if (LocalDeps.hasBuildFile(module)) {
            step("Resolving libraries (Maven)");
            LocalDeps.Out dep = LocalDeps.resolve(ctx, proj, module, libModules, !kt.isEmpty(), msg -> cb.log(msg));
            for (String n : dep.notes) cb.log("note: " + n);
            for (File a : dep.aars) openAar(a, work, pkg, libJars, libRes, extraPkgs, jni, libManifests, libAssets);
            libJars.addAll(dep.jars);
            compileOnly.addAll(dep.compileOnly);
            for (File a : dep.compileOnlyAars) {
                File cj = new File(extractedAar(a), "classes.jar");
                if (cj.isFile()) compileOnly.add(cj);
            }
            cb.log(dep.aars.size() + " aar and " + dep.jars.size() + " jar library file(s) from the Maven cache");
        }

        // manifests of libraries (providers, services, permissions) are merged into the app's
        try {
            List<String> mn = new ArrayList<>();
            String merged = ManifestMerge.merge(manifestText, appId, libManifests, mn);
            for (String n : mn) cb.log(n);
            Io.write(patched, merged);
        } catch (Exception e) {
            cb.log("note: manifest merge skipped (" + e.getMessage() + ")");
            Io.write(patched, manifestText);
        }

        // resources
        step("Compiling resources (aapt2)");
        List<File> resSets = new ArrayList<>(libRes);
        if (resDir.isDirectory() && hasFiles(resDir)) resSets.add(resDir);
        List<File> compiled = new ArrayList<>();
        List<File> appRes = new ArrayList<>();
        boolean appFlat = false;
        int idx = 0;
        for (File rs : resSets) {
            File z = new File(work, "res" + (idx++) + ".zip");
            if (rs.equals(resDir)) {
                // the app's own resources: only files that changed since the last build are compiled again
                appRes = compileAppRes(rs, incr, work);
                appFlat = !(appRes.size() == 1 && appRes.get(0).getName().endsWith(".zip"));
                continue;
            }
            // Library (AAR) resources never change between builds, so their compiled form is kept next to the extracted AAR.
            File cz = cachedCompiled(rs);
            if (cz != null && cz.isFile() && cz.length() > 0) { compiled.add(cz); continue; }
            StringBuilder o = new StringBuilder();
            int code = Toolchain.aapt2(ctx, Arrays.asList("compile", "--dir", rs.getAbsolutePath(), "-o", z.getAbsolutePath()), work, o);
            if (code != 0) fail("aapt2 compile failed (exit " + code + ")", o);
            if (cz != null && z.renameTo(cz)) compiled.add(cz);
            else compiled.add(z);
        }
        step("Linking resources (aapt2)");
        File gen = new File(work, "gen");
        gen.mkdirs();
        File base = new File(work, "base.apk");
        List<String> link = new ArrayList<>(Arrays.asList("link", "-o", base.getAbsolutePath(), "-I", androidJar.getAbsolutePath(),
                "--manifest", patched.getAbsolutePath(), "--java", gen.getAbsolutePath(), "--auto-add-overlay",
                "--min-sdk-version", String.valueOf(minSdk), "--target-sdk-version", String.valueOf(meta.target),
                "--version-code", String.valueOf(meta.versionCode), "--version-name", meta.versionName));
        if (!appId.equals(pkg)) link.addAll(Arrays.asList("--rename-manifest-package", appId));
        if (!extraPkgs.isEmpty()) link.addAll(Arrays.asList("--extra-packages", Toolchain.join(new ArrayList<>(extraPkgs), ":")));
        for (File la : libAssets) link.addAll(Arrays.asList("-A", la.getAbsolutePath()));
        if (assetsDir.isDirectory() && hasFiles(assetsDir)) link.addAll(Arrays.asList("-A", assetsDir.getAbsolutePath()));
        List<String> linkBase = new ArrayList<>(link);
        for (File z : compiled) { link.add("-R"); link.add(z.getAbsolutePath()); }
        for (File z : appRes) { link.add("-R"); link.add(z.getAbsolutePath()); }
        StringBuilder lo = new StringBuilder();
        int lc = Toolchain.aapt2(ctx, link, work, lo);
        if (lc != 0 && appFlat) {
            // safety net: if the cached per-file resources are the problem, drop them and compile the whole res folder once
            cb.log("note: linking the cached resource files failed, compiling every resource again");
            Io.delete(new File(incr, "res"));
            appRes = fullCompileRes(resDir, work);
            link = new ArrayList<>(linkBase);
            for (File z : compiled) { link.add("-R"); link.add(z.getAbsolutePath()); }
            for (File z : appRes) { link.add("-R"); link.add(z.getAbsolutePath()); }
            lo = new StringBuilder();
            lc = Toolchain.aapt2(ctx, link, work, lo);
        }
        if (lc != 0) fail("aapt2 link failed (exit " + lc + ")", lo);
        else if (lo.toString().trim().length() > 0) show(lo.toString(), 12);

        // BuildConfig
        boolean hasBC = false;
        for (File f : javaFiles) if (f.getName().equals("BuildConfig.java")) hasBC = true;
        if (!hasBC) {
            Io.write(new File(gen, pkg.replace('.', '/') + "/BuildConfig.java"), "package " + pkg + ";\n\npublic final class BuildConfig {\n"
                    + "    public static final boolean DEBUG = true;\n    public static final String APPLICATION_ID = \"" + appId + "\";\n"
                    + "    public static final String BUILD_TYPE = \"debug\";\n    public static final int VERSION_CODE = " + meta.versionCode + ";\n"
                    + "    public static final String VERSION_NAME = \"" + meta.versionName.replace("\"", "\\\"") + "\";\n}\n");
        }

        // java (the "Compiling Java (ECJ)" step is announced once, right before ECJ runs; with Kotlin it comes after Kotlin)
        List<File> sources = new ArrayList<>(javaFiles);
        walk(gen, ".java", sources);
        sources.addAll(crashSrc);
        File classes = new File(work, "classes");
        StringBuilder cp = new StringBuilder(androidJar.getAbsolutePath());
        for (File j : libJars) cp.append(File.pathSeparator).append(j.getAbsolutePath());
        for (File j : compileOnly) cp.append(File.pathSeparator).append(j.getAbsolutePath());
        int level = Toolchain.clampJava(meta.java);
        String codeKey = codeKey(sources, kt, libJars, compileOnly, level, minSdk, androidJar);
        File codeCache = new File(incr, "code");
        List<File> dexes = cachedDex(codeCache, codeKey);
        if (dexes != null) {
            step("Code unchanged, reusing compiled classes");
            cb.log((sources.size() + kt.size()) + " source file(s) and the resource IDs match the last build: Kotlin, ECJ and D8 skipped");
            if (!kt.isEmpty()) libJars.add(KotlinTool.stdlib(ctx));
        } else {
            classes.mkdirs();
            if (!kt.isEmpty()) {
                // Kotlin first: it reads the Java sources (and R.java) to resolve names, ECJ then sees the Kotlin classes.
                step("Compiling Kotlin");
                List<String> ka = new ArrayList<>();
                KotlinTool.base(ctx, ka, cp.toString(), classes, level);
                KotlinTool.composePlugin(ctx, ka, kt);
                for (File f : kt) ka.add(f.getAbsolutePath());
                for (File f : sources) ka.add(f.getAbsolutePath());
                StringBuilder ko = new StringBuilder();
                boolean kok = KotlinTool.compile(ctx, ka, ko);
                collect(ko.toString());
                if (!kok) {
                    show(ko.toString(), 40);
                    throw new Fail("Kotlin compile failed. See Problems.");
                }
                if (ko.toString().trim().length() > 0) show(ko.toString(), 10);
                libJars.add(KotlinTool.stdlib(ctx));
                cp.append(File.pathSeparator).append(KotlinTool.stdlib(ctx).getAbsolutePath());
                cp.append(File.pathSeparator).append(classes.getAbsolutePath());
            }
            step("Compiling Java (ECJ)");
            boolean ok = runEcj(sources, classes, cp.toString(), androidJar, level);
            // Step down one level at a time (21, 17, 11, 8) and only when ECJ could not see the platform classes. A
            // normal error in the code stops at once instead of being hidden behind a retry at an older level.
            for (int lower = Toolchain.lowerJava(level); !ok && lower > 0 && Toolchain.platformProblem(lastEcjOut); lower = Toolchain.lowerJava(lower)) {
                cb.log("ECJ could not compile at Java " + level + " (platform classes), retrying at Java " + lower);
                level = lower;
                ok = runEcj(sources, classes, cp.toString(), androidJar, level);
            }
            if (ok && level < Toolchain.clampJava(meta.java))
                cb.log("note: built at Java " + level + ", the project asks for " + meta.java + ". Run App settings > Build tools > Run self-test to see why.");
            if (!ok) throw new Fail("Java compile failed. See Problems.");
            List<File> classFiles = new ArrayList<>();
            walk(classes, ".class", classFiles);
            if (classFiles.isEmpty()) throw new Fail("ECJ produced no class files.");
            cb.log(classFiles.size() + " classes");

            // dex
            step("Dexing (D8)");
            // Libraries (Compose, Material, the Kotlin stdlib ...) are dexed once and reused until the set of libraries changes.
            // Only the app's own classes are dexed on every build; the libraries are on --classpath so D8 can still desugar against them.
            File jar = new File(work, "classes.jar");
            zipDir(classes, jar);
            File dexDir = new File(work, "dex");
            dexDir.mkdirs();
            List<String> d8 = new ArrayList<>(Arrays.asList("--debug", "--min-api", String.valueOf(minSdk), "--lib", androidJar.getAbsolutePath(),
                    "--output", dexDir.getAbsolutePath()));
            for (File j : libJars) { d8.add("--classpath"); d8.add(j.getAbsolutePath()); }
            d8.add(jar.getAbsolutePath());
            StringBuilder dout = new StringBuilder();
            boolean dok = Toolchain.d8(ctx, d8, dout);
            if (!dok) fail("D8 failed", dout);
            else if (dout.toString().trim().length() > 0) show(dout.toString(), 15);
            dexes = new ArrayList<>();
            for (int i = 1; ; i++) {
                File d = new File(dexDir, i == 1 ? "classes.dex" : "classes" + i + ".dex");
                if (!d.isFile()) break;
                dexes.add(d);
            }
            if (dexes.isEmpty()) throw new Fail("D8 produced no classes.dex");
            storeDex(codeCache, codeKey, dexes);
        }
        // Libraries (Compose, Material, the Kotlin stdlib ...) are dexed once and reused until the set of libraries changes.
        List<File> libDexes = predexLibs(libJars, minSdk, androidJar);
        dexes = new ArrayList<>(dexes);
        dexes.addAll(libDexes);   // the app's dex stays first (classes.dex); minSdk 21+ loads every classesN.dex natively

        // package
        step("Packaging");
        File unsigned = new File(work, "unsigned.apk");
        try (ApkWriter w = new ApkWriter(unsigned); ZipFile zf = new ZipFile(base)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || e.getName().startsWith("META-INF/")) continue;
                byte[] data = Io.bytes(zf.getInputStream(e));
                boolean store = e.getName().equals("resources.arsc") || e.getMethod() == ZipEntry.STORED;
                w.add(e.getName(), data, store, store ? 4 : 0);
            }
            for (int i = 0; i < dexes.size(); i++)
                w.add(i == 0 ? "classes.dex" : "classes" + (i + 1) + ".dex", Io.bytes(new FileInputStream(dexes.get(i))), false, 0);
            // Java resources of the libraries (META-INF/services/..., properties): without them kotlinx.coroutines has no Main
            // dispatcher and Compose or other ServiceLoader users crash as soon as the app opens.
            Map<String, byte[]> javaRes = javaResources(libJars, javaResDir);
            cb.log(javaRes.size() + " Java resource file(s) packaged from libraries");
            for (Map.Entry<String, byte[]> e : javaRes.entrySet()) w.add(e.getKey(), e.getValue(), false, 0);
            for (Map.Entry<String, File> e : jni.entrySet()) w.add(e.getKey(), Io.bytes(new FileInputStream(e.getValue())), false, 0);
        }

        step("Signing");
        File outDir = new File(ctx.getExternalFilesDir(null), "builds/" + proj.getName());
        outDir.mkdirs();
        File apk = new File(outDir, proj.getName().replaceAll("[^A-Za-z0-9._-]+", "_") + "-debug.apk");
        apk.delete();
        Toolchain.sign(ctx, unsigned, apk, minSdk);
        if (!apk.isFile()) throw new Fail("Signing produced no APK.");
        cb.log("output: " + apk.getName() + " (" + (apk.length() / 1024) + " KB)");
        Io.delete(work);
        return apk;
    }


    // ------------------------------------------------------------------ incremental build helpers

    private static final String INCR_VERSION = "v2";

    /** aapt2-compiles only the res files that are new or changed; the compiled form of every file is kept in the build cache. */
    private List<File> compileAppRes(File res, File incr, File work) throws Exception {
        File base = new File(incr, "res");
        File flat = new File(base, "flat");
        File index = new File(base, "index.txt");
        File bin = Toolchain.aapt2(ctx);
        String tag = INCR_VERSION + "|" + (bin == null ? "x" : bin.length() + "_" + bin.lastModified());
        Map<String, String> now = new java.util.TreeMap<>();
        File[] types = res.listFiles();
        if (types != null) for (File t : types) {
            if (!t.isDirectory() || t.getName().startsWith(".")) continue;
            File[] fs = t.listFiles();
            if (fs != null) for (File f : fs) if (f.isFile() && !f.getName().startsWith(".")) now.put(t.getName() + "/" + f.getName(), Io.sha(f));
        }
        Map<String, String> old = new HashMap<>();
        boolean reuse = false;
        try {
            if (index.isFile() && flat.isDirectory()) {
                String[] lines = Io.read(index).split("\n");
                if (lines.length > 0 && lines[0].equals(tag)) {
                    for (int i = 1; i < lines.length; i++) {
                        int t = lines[i].indexOf('\t');
                        if (t > 0) old.put(lines[i].substring(0, t), lines[i].substring(t + 1));
                    }
                    reuse = now.keySet().containsAll(old.keySet());   // a deleted or renamed file means a clean start
                }
            }
        } catch (IOException e) {
            reuse = false;
        }
        if (!reuse) { Io.delete(base); old.clear(); }
        flat.mkdirs();
        index.delete();   // stays deleted unless this whole step succeeds, so a half-finished compile is never trusted
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : now.entrySet()) if (!e.getValue().equals(old.get(e.getKey()))) changed.add(e.getKey());
        cb.log("resources: " + changed.size() + " changed of " + now.size() + " file(s)" + (changed.size() == now.size() ? "" : ", the rest come from the cache"));
        for (int i = 0; i < changed.size(); i += 150) {
            List<String> a = new ArrayList<>();
            a.add("compile");
            for (String c : changed.subList(i, Math.min(changed.size(), i + 150))) a.add(new File(res, c).getAbsolutePath());
            a.add("-o");
            a.add(flat.getAbsolutePath());
            StringBuilder o = new StringBuilder();
            int code = Toolchain.aapt2(ctx, a, work, o);
            if (code != 0) {
                // either a real error in a res file (the full compile reports it with file and line) or an aapt2 quirk
                Io.delete(base);
                cb.log("note: per-file resource compile failed, compiling the whole res folder");
                return fullCompileRes(res, work);
            }
        }
        File[] flats = flat.listFiles();
        List<File> out = new ArrayList<>();
        if (flats != null) { Arrays.sort(flats); for (File f : flats) if (f.getName().endsWith(".flat")) out.add(f); }
        StringBuilder ix = new StringBuilder(tag).append('\n');
        for (Map.Entry<String, String> e : now.entrySet()) ix.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        Io.write(index, ix.toString());
        return out;
    }

    /** The plain way: one aapt2 compile of the whole res folder into a zip. Reports problems with file and line. */
    private List<File> fullCompileRes(File res, File work) throws Exception {
        File z = new File(work, "res_app.zip");
        StringBuilder o = new StringBuilder();
        int code = Toolchain.aapt2(ctx, Arrays.asList("compile", "--dir", res.getAbsolutePath(), "-o", z.getAbsolutePath()), work, o);
        if (code != 0) fail("aapt2 compile failed (exit " + code + ")", o);
        return new ArrayList<>(Collections.singletonList(z));
    }

    private static String fp(File f) {
        return f.getName() + ":" + f.length() + ":" + f.lastModified();
    }

    /**
     * Fingerprint of everything the compiled app code depends on: the content of every source file (including the generated
     * R.java and BuildConfig.java, so a changed resource ID triggers a recompile), the libraries, the Java level and the tools.
     */
    private String codeKey(List<File> sources, List<File> kt, List<File> libJars, List<File> compileOnly, int level, int minSdk, File androidJar) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
        StringBuilder head = new StringBuilder(INCR_VERSION + "|" + level + "|" + minSdk + "|" + Toolchain.d8Version(ctx) + "|" + fp(androidJar));
        for (String n : new String[]{"ecj.dex.jar", "d8.dex.jar", "kotlinc.dex.jar", "compose-plugin.jar", "jdk-stubs.jar"}) head.append('|').append(fp(Toolchain.file(ctx, n)));
        if (!kt.isEmpty()) head.append("|kt|").append(fp(KotlinTool.stdlib(ctx)));
        for (File j : libJars) head.append("|lib|").append(j.getCanonicalPath()).append(':').append(j.length()).append(':').append(j.lastModified());
        for (File j : compileOnly) head.append("|co|").append(j.getCanonicalPath()).append(':').append(j.length()).append(':').append(j.lastModified());
        md.update(head.toString().getBytes(StandardCharsets.UTF_8));
        List<File> all = new ArrayList<>(kt);
        all.addAll(sources);
        byte[] buf = new byte[16384];
        for (File f : all) {
            md.update(("|src|" + f.getAbsolutePath() + "|" + f.length() + "|").getBytes(StandardCharsets.UTF_8));
            try (InputStream in = new FileInputStream(f)) {
                int r;
                while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    /** The app's dex files from the last successful build, or null when anything they depend on changed. */
    private List<File> cachedDex(File dir, String key) {
        try {
            File k = new File(dir, "key");
            if (!new File(dir, ".done").isFile() || !k.isFile() || !Io.read(k).trim().equals(key)) return null;
        } catch (IOException e) {
            return null;
        }
        List<File> out = new ArrayList<>();
        for (int i = 1; ; i++) {
            File d = new File(dir, i == 1 ? "classes.dex" : "classes" + i + ".dex");
            if (!d.isFile()) break;
            out.add(d);
        }
        return out.isEmpty() ? null : out;
    }

    private void storeDex(File dir, String key, List<File> dexes) {
        try {
            File tmp = new File(dir.getParentFile(), dir.getName() + ".tmp");
            Io.delete(tmp);
            tmp.mkdirs();
            for (int i = 0; i < dexes.size(); i++) Io.copy(dexes.get(i), new File(tmp, i == 0 ? "classes.dex" : "classes" + (i + 1) + ".dex"));
            Io.write(new File(tmp, "key"), key);
            new File(tmp, ".done").createNewFile();
            Io.delete(dir);
            if (!tmp.renameTo(dir)) Io.delete(tmp);
        } catch (Exception e) {
            cb.log("note: could not keep the compiled code for the next build (" + e.getMessage() + ")");
        }
    }

    private static boolean skipJavaRes(String n) {
        String l = n.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith("/") || l.endsWith(".class") || l.endsWith(".java") || l.endsWith(".kt")) return true;
        if (n.equals("AndroidManifest.xml") || n.equals("resources.arsc") || n.startsWith("res/") || l.endsWith(".kotlin_builtins") || l.endsWith(".kotlin_metadata")) return true;
        if (!l.startsWith("meta-inf/")) return false;
        // ServiceLoader files are named after a class ("META-INF/services/x.LicenseChecker"); the license/notice rule must not drop them
        if (l.startsWith("meta-inf/services/")) return false;
        return l.equals("meta-inf/manifest.mf") || l.startsWith("meta-inf/maven/") || l.startsWith("meta-inf/proguard/") || l.startsWith("meta-inf/versions/")
                || l.startsWith("meta-inf/com.android.tools/") || l.startsWith("meta-inf/gradle/") || l.endsWith(".sf") || l.endsWith(".rsa") || l.endsWith(".dsa")
                || l.endsWith(".ec") || l.endsWith(".kotlin_module") || l.endsWith(".version") || l.contains("license") || l.contains("notice");
    }

    /** Non-class files from the library jars and src/main/resources, with META-INF/services files of different jars merged. */
    private Map<String, byte[]> javaResources(List<File> jars, File appResDir) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        Map<String, java.util.LinkedHashSet<String>> services = new LinkedHashMap<>();
        if (appResDir != null && appResDir.isDirectory()) {
            List<File> fs = new ArrayList<>();
            Io.collect(appResDir, fs);
            String root = appResDir.getAbsolutePath() + File.separator;
            for (File f : fs) {
                String n = f.getAbsolutePath().startsWith(root) ? f.getAbsolutePath().substring(root.length()).replace(File.separatorChar, '/') : f.getName();
                if (skipJavaRes(n)) continue;
                try { out.put(n, Io.bytes(new FileInputStream(f))); } catch (IOException e) { cb.log("note: could not read " + n); }
            }
        }
        for (File j : jars) {
            if (!j.isFile()) continue;
            try (ZipFile z = new ZipFile(j)) {
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (e.isDirectory() || skipJavaRes(n)) continue;
                    if (n.startsWith("META-INF/services/")) {
                        java.util.LinkedHashSet<String> set = services.get(n);
                        if (set == null) { set = new java.util.LinkedHashSet<>(); services.put(n, set); }
                        for (String line : new String(Io.bytes(z.getInputStream(e)), StandardCharsets.UTF_8).split("\\r?\\n")) {
                            String t = line.trim();
                            if (!t.isEmpty()) set.add(t);
                        }
                    } else if (!out.containsKey(n)) {
                        out.put(n, Io.bytes(z.getInputStream(e)));
                    }
                }
            } catch (IOException e) {
                cb.log("note: could not read resources of " + j.getName() + " (" + e.getMessage() + ")");
            }
        }
        for (Map.Entry<String, java.util.LinkedHashSet<String>> e : services.entrySet())
            out.put(e.getKey(), (Toolchain.join(new ArrayList<>(e.getValue()), "\n") + "\n").getBytes(StandardCharsets.UTF_8));
        return out;
    }

    // ------------------------------------------------------------------ steps

    private String lastEcjOut = "";

    private boolean runEcj(List<File> sources, File classes, String cp, File androidJar, int level) throws Exception {
        List<String> a = new ArrayList<>(Arrays.asList("-d", classes.getAbsolutePath(), "-source", String.valueOf(level), "-target", String.valueOf(level),
                "-encoding", "UTF-8", "-proc:none", "-nowarn", "-g", "-Xemacs",
                "-bootclasspath", androidJar.getAbsolutePath(), "-classpath", cp));
        for (File f : sources) a.add(f.getAbsolutePath());
        StringBuilder o = new StringBuilder();
        boolean ok = Toolchain.ecj(ctx, a, o);
        lastEcjOut = o.toString();
        int before = diags.size();
        collect(lastEcjOut);
        if (!ok) {
            if (diags.size() == before) show(lastEcjOut, 40);
            else cb.log(diags.size() + " problem(s)");
        } else if (lastEcjOut.trim().length() > 0) {
            show(lastEcjOut, 10);
        }
        return ok;
    }

    private void fail(String msg, StringBuilder out) throws Fail {
        collect(out.toString());
        show(out.toString(), 40);
        throw new Fail(msg);
    }

    private void show(String s, int maxLines) {
        String[] ls = s.trim().split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ls.length && i < maxLines; i++) sb.append(ls[i]).append('\n');
        if (ls.length > maxLines) sb.append("... ").append(ls.length - maxLines).append(" more lines\n");
        cb.log(sb.toString().trim());
    }

    /** Turns "path:line: error: text" lines from ECJ and aapt2 into Problems entries. */
    private void collect(String out) {
        String root = proj.getAbsolutePath();
        for (String line : out.split("\n")) {
            if (diags.size() >= 200) return;
            Matcher m = DIAG.matcher(line.trim());
            if (!m.matches()) continue;
            String path = m.group(1);
            String rel = remap.containsKey(path) ? remap.get(path) : path.startsWith(root) ? Io.rel(proj, new File(path)) : new File(path).getName();
            int ln = Integer.parseInt(m.group(2));
            int col = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            diags.add(new Diag(rel, ln, col, m.group(5).trim(), m.group(4).equalsIgnoreCase("error") ? 1 : 2));
        }
    }

    // ------------------------------------------------------------------ caches (survive between builds, live in the app cache dir)

    private File aarCacheRoot() { return new File(ctx.getCacheDir(), "aarx"); }

    /** Unzips an AAR once; the folder is keyed by file path, size and date, so a changed AAR is unpacked again. */
    private File extractedAar(File aar) throws IOException {
        String n = aar.getName().replaceAll("[^A-Za-z0-9._-]", "_") + "_" + Integer.toHexString(aar.getCanonicalPath().hashCode())
                + "_" + aar.length() + "_" + aar.lastModified();
        File root = aarCacheRoot();
        File d = new File(root, n);
        if (new File(d, ".done").isFile()) return d;
        Io.delete(d);
        File tmp = new File(root, n + ".tmp");
        Io.delete(tmp);
        unzipTo(aar, tmp);
        new File(tmp, ".done").createNewFile();
        if (!tmp.renameTo(d)) {
            Io.delete(tmp);
            throw new IOException("could not store " + aar.getName() + " in the build cache");
        }
        return d;
    }

    /** Where the compiled (aapt2) form of a library's res folder is kept, or null for the app's own resources. */
    private File cachedCompiled(File resDir) {
        String root = aarCacheRoot().getAbsolutePath() + File.separator;
        if (!resDir.getAbsolutePath().startsWith(root)) return null;
        File bin = Toolchain.aapt2(ctx);
        String tag = bin == null ? "x" : bin.length() + "_" + bin.lastModified();   // a different aapt2 build writes a different file
        return new File(resDir.getParentFile(), "res.compiled." + tag + ".zip");
    }

    /**
     * Dexes all library jars in one D8 run and keeps the result. The cache key covers the libraries (path, size, date),
     * minSdk, the D8 version and android.jar, so any change dexes them again. Returns the dex files in order.
     */
    private List<File> predexLibs(List<File> libs, int minSdk, File androidJar) throws Exception {
        List<File> out = new ArrayList<>();
        if (libs.isEmpty()) return out;
        List<String> parts = new ArrayList<>();
        for (File j : libs) parts.add(j.getCanonicalPath() + ":" + j.length() + ":" + j.lastModified());
        Collections.sort(parts);
        StringBuilder key = new StringBuilder("v1|" + minSdk + "|" + Toolchain.d8Version(ctx) + "|" + androidJar.length() + "|" + androidJar.lastModified());
        for (String p : parts) key.append('|').append(p);
        StringBuilder hex = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-1").digest(key.toString().getBytes(StandardCharsets.UTF_8)))
            hex.append(String.format("%02x", b));
        File root = new File(ctx.getCacheDir(), "libdex/" + proj.getName().replaceAll("[^A-Za-z0-9._-]+", "_"));
        File dir = new File(root, hex.toString());
        if (!new File(dir, ".done").isFile()) {
            Io.delete(root);   // older library sets of this project are stale now
            File tmp = new File(root, hex + ".tmp");
            tmp.mkdirs();
            cb.log("Dexing " + libs.size() + " libraries (kept for the next builds)");
            List<String> a = new ArrayList<>(Arrays.asList("--release", "--min-api", String.valueOf(minSdk), "--lib", androidJar.getAbsolutePath(),
                    "--output", tmp.getAbsolutePath()));
            for (File j : libs) a.add(j.getAbsolutePath());
            StringBuilder o = new StringBuilder();
            if (!Toolchain.d8(ctx, a, o)) { Io.delete(tmp); fail("D8 failed on the libraries", o); }
            else if (o.toString().trim().length() > 0) show(o.toString(), 15);
            new File(tmp, ".done").createNewFile();
            if (!tmp.renameTo(dir)) { Io.delete(tmp); throw new IOException("could not store the dexed libraries in the build cache"); }
        } else {
            cb.log("libraries: dex cache hit");
        }
        for (int i = 1; ; i++) {
            File d = new File(dir, i == 1 ? "classes.dex" : "classes" + i + ".dex");
            if (!d.isFile()) break;
            out.add(d);
        }
        return out;
    }

    private void openAar(File aar, File work, String pkg, List<File> jars, List<File> res, Set<String> extra, Map<String, File> jni,
                         List<String[]> manifests, List<File> assets) throws IOException {
        File d = extractedAar(aar);
        File cj = new File(d, "classes.jar");
        if (cj.isFile()) jars.add(cj);
        File[] inner = new File(d, "libs").listFiles();
        if (inner != null) for (File f : inner) if (f.getName().endsWith(".jar")) jars.add(f);
        File r = new File(d, "res");
        if (r.isDirectory() && hasFiles(r)) res.add(r);
        File mf = new File(d, "AndroidManifest.xml");
        if (mf.isFile()) {
            String mt = Io.read(mf);
            Matcher m = Pattern.compile("package\\s*=\\s*\"([^\"]+)\"").matcher(mt);
            if (m.find()) {
                if (!m.group(1).equals(pkg)) extra.add(m.group(1));
                manifests.add(new String[]{m.group(1), mt});
            }
        }
        File as = new File(d, "assets");
        if (as.isDirectory() && hasFiles(as)) assets.add(as);
        File[] abis = new File(d, "jni").listFiles();
        if (abis != null) for (File abi : abis) {
            File[] sos = abi.listFiles();
            if (sos != null) for (File so : sos) if (so.getName().endsWith(".so")) jni.put("lib/" + abi.getName() + "/" + so.getName(), so);
        }
        cb.log("aar " + aar.getName());
    }

    /** Variables the terminal build script uses ($PKG, $MINSDK, ...), read the same way the one-button build reads them. */
    static Map<String, String> vars(File proj) {
        Map<String, String> v = new LinkedHashMap<>();
        File module = null, manifest = null;
        for (File m : new File[]{new File(proj, "app"), proj}) {
            File mf = new File(m, "src/main/AndroidManifest.xml");
            if (!mf.isFile()) mf = new File(m, "AndroidManifest.xml");
            if (mf.isFile()) { module = m; manifest = mf; break; }
        }
        if (module == null) return v;
        Meta meta = parseGradle(module);
        String pkg = meta.pkg;
        try {
            Matcher pm = Pattern.compile("<manifest\\b[^>]*?\\bpackage\\s*=\\s*\"([^\"]+)\"").matcher(Io.read(manifest));
            if (pm.find()) pkg = pm.group(1);
        } catch (IOException ignored) {
        }
        if (pkg.isEmpty()) pkg = meta.appId;
        boolean gl = new File(module, "src/main").isDirectory();
        v.put("MODULE", module.getAbsolutePath());
        v.put("MANIFEST", manifest.getAbsolutePath());
        v.put("SRC", gl ? module.getAbsolutePath() + "/src/main/java" : module.getAbsolutePath() + "/src");
        v.put("RES", gl ? module.getAbsolutePath() + "/src/main/res" : module.getAbsolutePath() + "/res");
        v.put("ASSETS", gl ? module.getAbsolutePath() + "/src/main/assets" : module.getAbsolutePath() + "/assets");
        v.put("JNI", gl ? module.getAbsolutePath() + "/src/main/jniLibs" : module.getAbsolutePath() + "/jniLibs");
        v.put("PKG", pkg);
        v.put("APPID", meta.appId.isEmpty() ? pkg : meta.appId);
        v.put("MINSDK", String.valueOf(Math.max(21, meta.minSdk)));
        v.put("TARGETSDK", String.valueOf(meta.target));
        v.put("VERSIONCODE", String.valueOf(meta.versionCode));
        v.put("VERSIONNAME", meta.versionName);
        v.put("JAVA", String.valueOf(Toolchain.clampJava(meta.java)));
        return v;
    }

    private static Meta parseGradle(File module) {
        Meta t = new Meta();
        for (String n : new String[]{"build.gradle", "build.gradle.kts"}) {
            File f = new File(module, n);
            if (!f.isFile()) continue;
            try {
                String s = stripComments(Io.read(f));
                t.pkg = first(s, "namespace\\s*=?\\s*[\"']([^\"']+)[\"']", t.pkg);
                t.appId = first(s, "applicationId\\s*=?\\s*[\"']([^\"']+)[\"']", t.appId);
                t.versionName = first(s, "versionName\\s*=?\\s*[\"']([^\"']+)[\"']", t.versionName);
                t.minSdk = num(first(s, "minSdk(?:Version)?\\s*=?\\s*(\\d+)", ""), t.minSdk);
                t.target = num(first(s, "targetSdk(?:Version)?\\s*=?\\s*(\\d+)", ""), t.target);
                t.compile = num(first(s, "compileSdk(?:Version)?\\s*=?\\s*(?:android-)?(\\d+)", ""), t.compile);
                t.versionCode = num(first(s, "versionCode\\s*=?\\s*(\\d+)", ""), t.versionCode);
                String j = first(s, "(?:sourceCompatibility|targetCompatibility)\\s*=?\\s*JavaVersion\\.VERSION_(\\d+(?:_\\d+)?)", "");
                if (j.isEmpty()) j = first(s, "JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)", "");
                if (j.isEmpty()) j = first(s, "(?:jvmToolchain|release)(?:\\.set)?\\s*[(=]\\s*(\\d+)", "");
                if (!j.isEmpty()) t.java = j.startsWith("1_") ? num(j.substring(2), 8) : num(j, 17);
                Matcher m = Pattern.compile("(?:implementation|api|compileOnly|runtimeOnly)\\s*\\(?\\s*[\"']([^\"']+:[^\"']+)[\"']").matcher(s);
                while (m.find()) t.deps.add(m.group(1));
            } catch (IOException ignored) {
            }
        }
        return t;
    }

    /** Removes // and block comments, so "// minSdk 21" cannot win over the real setting (string contents are left alone). */
    static String stripComments(String s) { return GradleDeps.stripComments(s); }

    private static String first(String s, String re, String def) {
        Matcher m = Pattern.compile(re).matcher(s);
        return m.find() ? m.group(1) : def;
    }

    private static int num(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ files

    private static void addIfDir(List<File> l, File d) { if (d.isDirectory()) l.add(d); }

    private static boolean hasFiles(File d) {
        File[] k = d.listFiles();
        if (k == null) return false;
        for (File f : k) if (f.isFile() || hasFiles(f)) return true;
        return false;
    }

    static void walk(File d, String ext, List<File> out) { walk(d, ext, out, 0); }

    private static void walk(File d, String ext, List<File> out, int depth) {
        File[] k = d.listFiles();
        if (k == null || depth > 40) return; // the limit stops a symlink loop from recursing forever
        Arrays.sort(k);
        for (File f : k) {
            if (f.isDirectory()) walk(f, ext, out, depth + 1);
            else if (f.getName().endsWith(ext)) out.add(f);
        }
    }

    static void unzipTo(File zip, File dir) throws IOException {
        dir.mkdirs();
        String base = dir.getCanonicalPath() + File.separator;
        try (ZipInputStream zi = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = zi.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                File f = new File(dir, e.getName());
                if (!f.getCanonicalPath().startsWith(base)) continue;
                f.getParentFile().mkdirs();
                try (FileOutputStream o = new FileOutputStream(f)) {
                    byte[] b = new byte[16384];
                    int r;
                    while ((r = zi.read(b)) > 0) o.write(b, 0, r);
                }
            }
        }
    }

    static void zipDir(File dir, File jar) throws IOException {
        try (ZipOutputStream zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(jar)))) {
            zipInto(zo, dir, "");
        }
    }

    private static void zipInto(ZipOutputStream zo, File d, String prefix) throws IOException {
        File[] k = d.listFiles();
        if (k == null) return;
        Arrays.sort(k);
        for (File f : k) {
            if (f.isDirectory()) {
                zipInto(zo, f, prefix + f.getName() + "/");
            } else {
                zo.putNextEntry(new ZipEntry(prefix + f.getName()));
                try (InputStream in = new FileInputStream(f)) {
                    byte[] b = new byte[16384];
                    int r;
                    while ((r = in.read(b)) > 0) zo.write(b, 0, r);
                }
                zo.closeEntry();
            }
        }
    }

    /**
     * A zip writer where the data offset of every stored entry is known, so resources.arsc lands on a 4-byte
     * boundary (Android 11+ refuses the APK otherwise). Padding goes in a 0xD935 extra field like zipalign does.
     */
    static final class ApkWriter implements Closeable {
        private final OutputStream os;
        private long off;
        private final ByteArrayOutputStream cd = new ByteArrayOutputStream();
        private final Set<String> names = new HashSet<>();
        private int count;

        ApkWriter(File f) throws IOException { os = new BufferedOutputStream(new FileOutputStream(f), 1 << 16); }

        void add(String name, byte[] data, boolean store, int align) throws IOException {
            if (!names.add(name)) return;
            if (count >= 0xFFFF) throw new IOException("The app has more than 65535 files; this packager has no ZIP64 support. Remove some assets or resources.");
            byte[] nm = name.getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(data);
            byte[] body = data;
            int method = 0;
            if (!store) {
                ByteArrayOutputStream b = new ByteArrayOutputStream(Math.max(64, data.length / 2));
                Deflater df = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
                try (DeflaterOutputStream dos = new DeflaterOutputStream(b, df)) { dos.write(data); }
                df.end();
                if (b.size() < data.length) { body = b.toByteArray(); method = 8; }
            }
            int extraLen = 0;
            if (method == 0 && align > 1) {
                int pad = (int) ((align - (off + 30 + nm.length) % align) % align);
                if (pad != 0) extraLen = pad < 4 ? pad + align : pad;
            }
            final int time = 0, date = ((2010 - 1980) << 9) | (1 << 5) | 1;
            ByteBuffer h = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
            h.putInt(0x04034b50).putShort((short) 20).putShort((short) 0x0800).putShort((short) method).putShort((short) time).putShort((short) date)
                    .putInt((int) crc.getValue()).putInt(body.length).putInt(data.length).putShort((short) nm.length).putShort((short) extraLen);
            byte[] extra = new byte[extraLen];
            if (extraLen >= 4) {
                extra[0] = 0x35;
                extra[1] = (byte) 0xD9;
                extra[2] = (byte) ((extraLen - 4) & 0xFF);
                extra[3] = (byte) (((extraLen - 4) >> 8) & 0xFF);
            }
            long lho = off;
            os.write(h.array());
            os.write(nm);
            os.write(extra);
            os.write(body);
            off += 30 + nm.length + extraLen + body.length;

            ByteBuffer c = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
            c.putInt(0x02014b50).putShort((short) 20).putShort((short) 20).putShort((short) 0x0800).putShort((short) method)
                    .putShort((short) time).putShort((short) date).putInt((int) crc.getValue()).putInt(body.length).putInt(data.length)
                    .putShort((short) nm.length).putShort((short) extraLen).putShort((short) 0).putShort((short) 0).putShort((short) 0)
                    .putInt(0).putInt((int) lho);
            cd.write(c.array());
            cd.write(nm);
            cd.write(extra);
            count++;
        }

        @Override
        public void close() throws IOException {
            long cdOff = off;
            byte[] cdb = cd.toByteArray();
            os.write(cdb);
            ByteBuffer e = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
            e.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) count).putShort((short) count)
                    .putInt(cdb.length).putInt((int) cdOff).putShort((short) 0);
            os.write(e.array());
            os.close();
        }
    }
}
