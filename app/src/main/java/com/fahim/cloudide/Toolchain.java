package com.fahim.cloudide;

import android.content.Context;
import android.os.Build;
import dalvik.system.DexClassLoader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The compilers that run inside this app, with no Termux and no desktop JDK:
 *   ecj.dex.jar   Eclipse compiler (latest) plus the javax.lang.model / javax.tools / javax.annotation.processing
 *                 classes Android does not have. That missing package is why ECJ only worked in old versions before.
 *   d8.dex.jar    D8 / R8 dexer
 *   sign.dex.jar  apksig with a one-method wrapper
 *   android.jar   the platform stubs, used as the boot class path
 *   aapt2         native arm64 binary (or libaapt2.so shipped in jniLibs)
 * The dex jars are built by .github/workflows/toolchain.yml and loaded here with DexClassLoader.
 *
 * Bundled in the APK (no download needed):
 *   jniLibs/arm64-v8a/lib{aapt2,aapt,zipalign,aidl,dexdump,split_select}.so   the Android SDK build-tools
 *   assets/toolchain/*                                                         the dex jars, android.jar, VERSIONS.txt
 * seedBundled() copies the assets into files/toolchain the first time they are needed, so a fresh install builds APKs
 * on the phone straight away. Download / Import in Settings still work and replace the bundled copy.
 */
final class Toolchain {
    static final String[] CORE = {"android.jar", "ecj.dex.jar", "d8.dex.jar", "sign.dex.jar"};
    private static final Set<String> KNOWN = new HashSet<>(Arrays.asList(
            "android.jar", "ecj.dex.jar", "d8.dex.jar", "sign.dex.jar", "aapt2", "libaapt2.so", "VERSIONS.txt",
            "kotlinc.dex.jar", "kotlin-stdlib.jar", "compose-plugin.jar", "jdk-stubs.jar"));
    private static final Map<String, DexClassLoader> LOADERS = new HashMap<>();
    private static final Object IO = new Object();
    /** How aapt2 was started last time: "direct" or "linker". */
    static volatile String aaptMode = "";

    private Toolchain() { }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "toolchain");
        d.mkdirs();
        return d;
    }

    static File file(Context c, String n) { return new File(dir(c), n); }

    /** libaapt2.so from jniLibs is the only place Android 10+ lets an app run a binary from directly. */
    static File aapt2(Context c) {
        File n = new File(c.getApplicationInfo().nativeLibraryDir, "libaapt2.so");
        if (n.isFile()) return n;
        File f = file(c, "aapt2");
        return f.isFile() ? f : null;
    }

    /** SDK build-tool shipped in jniLibs as lib<name>.so ("split-select" is libsplit_select.so). Null when absent. */
    static File nativeTool(Context c, String name) {
        if (name.equals("aapt2")) return aapt2(c);
        String lib = "lib" + name.replace('-', '_') + ".so";
        File n = new File(c.getApplicationInfo().nativeLibraryDir, lib);
        if (n.isFile()) return n;
        File f = file(c, name);
        return f.isFile() ? f : null;
    }

    static final String[] NATIVE_TOOLS = {"aapt2", "aapt", "zipalign", "aidl", "dexdump", "split-select"};

    // ---------------------------------------------------------------- bundled in the APK

    private static boolean seeded;
    /** Written to VERSIONS.txt by bundle-jars.sh / toolchain.yml once ECJ, R8 and apksig carry the Runtime.version() patch. */
    static final String PATCH_MARK = "java9-shim 2";
    private static final Set<String> REPLACE_IF_STALE = new HashSet<>(Arrays.asList(
            "ecj.dex.jar", "d8.dex.jar", "sign.dex.jar", "android.jar", "jdk-stubs.jar", "VERSIONS.txt"));

    /**
     * Copies assets/toolchain/* (dex jars, android.jar, VERSIONS.txt) into files/toolchain when they are not there yet.
     * Files the person downloaded or imported are never overwritten. Safe to call often; it blocks while another
     * thread is copying, so nothing reads a half-written jar.
     */
    static void seedBundled(Context c) {
        synchronized (IO) {
            if (seeded) return;
            seeded = true;
            try {
                String[] names = c.getAssets().list("toolchain");
                if (names == null) return;
                boolean any = false;
                boolean stale = installedIsStale(c);
                for (String n : names) {
                    if (!KNOWN.contains(n) || n.equals("libaapt2.so")) continue;
                    File f = file(c, n);
                    if (f.isFile() && f.length() > 0 && !(stale && REPLACE_IF_STALE.contains(n))) continue;
                    try (InputStream in = c.getAssets().open("toolchain/" + n)) {
                        store(c, n, in);
                        any = true;
                    }
                }
                if (any) finish(c);
            } catch (IOException e) {
                AppLog.w("Toolchain", "Could not unpack the bundled toolchain, will retry", e);
                seeded = false; // try again next time
            }
        }
    }

    /**
     * True when the APK bundles the patched toolchain but the copy in files/toolchain is an older one. Those old jars
     * call Runtime.version() (Java 9), which Android lacks, so ECJ cannot start at all: replacing them is always right.
     */
    private static boolean installedIsStale(Context c) {
        try {
            String bundled = new String(Io.bytes(c.getAssets().open("toolchain/VERSIONS.txt")), "UTF-8");
            if (!bundled.contains(PATCH_MARK)) return false;
            if (!file(c, "ecj.dex.jar").isFile()) return false;
            String have = versions(c);
            if (!have.contains(PATCH_MARK)) return true;
            // a newer android.jar in the APK (for example API 37 over API 36) replaces the installed one
            return apiOf(bundled) > apiOf(have);
        } catch (IOException e) {
            return false;
        }
    }

    private static int apiOf(String versionsText) {
        Matcher m = Pattern.compile("android-(\\d+)").matcher(versionsText);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** What the APK itself carries, for the status text. */
    static String bundledInfo(Context c) {
        StringBuilder sb = new StringBuilder();
        for (String t : NATIVE_TOOLS) if (new File(c.getApplicationInfo().nativeLibraryDir, "lib" + t.replace('-', '_') + ".so").isFile()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(t);
        }
        return sb.length() == 0 ? "" : "Built into the app: " + sb;
    }

    static List<String> missing(Context c) {
        seedBundled(c);
        List<String> m = new ArrayList<>();
        for (String n : CORE) if (!file(c, n).isFile()) m.add(n);
        if (aapt2(c) == null) m.add("aapt2");
        return m;
    }

    static boolean ready(Context c) { return missing(c).isEmpty(); }

    /** API level of the bundled android.jar, read from the "android-NN" line of VERSIONS.txt; 0 when unknown. */
    static int platformApi(Context c) {
        Matcher m = Pattern.compile("android-(\\d+)").matcher(versions(c));
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Marketing version for an API level (36 = Android 16, 37 = Android 17). */
    static String androidName(int api) {
        return api >= 33 ? String.valueOf(api - 20) : String.valueOf(api); // 33 = Android 13 ... 37 = Android 17
    }

    static String versions(Context c) {
        File f = file(c, "VERSIONS.txt");
        try {
            return f.isFile() ? Io.read(f).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    static String shortStatus(Context c) {
        List<String> m = missing(c);
        if (m.isEmpty()) return "Installed" + (versions(c).isEmpty() ? "" : "\n" + versions(c))
                + (bundledInfo(c).isEmpty() ? "" : "\n" + bundledInfo(c));
        return "Not complete. Missing: " + join(m, ", ") + "\nTap to install";
    }

    static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- install

    /** Copies the known files out of a zip (any folder depth) into the toolchain folder. Returns how many. */
    static int installZip(Context c, File zip, Gh.Log log) throws IOException {
        int n = 0;
        try (ZipInputStream zi = new ZipInputStream(new java.io.FileInputStream(zip))) {
            ZipEntry e;
            while ((e = zi.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String base = new File(e.getName()).getName();
                if (!KNOWN.contains(base)) continue;
                store(c, base, zi);
                log.log("installed " + target(base));
                n++;
            }
        }
        finish(c);
        return n;
    }

    static int installFolder(Context c, File folder, Gh.Log log) throws IOException {
        int n = 0;
        File[] kids = folder.listFiles();
        if (kids == null) throw new IOException("Cannot read " + folder);
        for (File f : kids) {
            if (f.isDirectory() || !KNOWN.contains(f.getName())) continue;
            try (InputStream in = new java.io.FileInputStream(f)) {
                store(c, f.getName(), in);
            }
            log.log("installed " + target(f.getName()));
            n++;
        }
        finish(c);
        return n;
    }

    static void download(Context c, String url, Gh.Log log) throws IOException {
        File tmp = new File(c.getCacheDir(), "toolchain-download.zip");
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(20000);
        h.setReadTimeout(60000);
        h.setInstanceFollowRedirects(true);
        int code = h.getResponseCode();
        if (code / 100 != 2) throw new IOException("Download failed: HTTP " + code + " (a private repository needs the zip imported by hand)");
        long total = h.getContentLengthLong(), got = 0, lastLog = 0;
        try {
            try (InputStream in = h.getInputStream(); FileOutputStream o = new FileOutputStream(tmp)) {
                byte[] b = new byte[16384];
                int r;
                while ((r = in.read(b)) != -1) {
                    o.write(b, 0, r);
                    got += r;
                    if (got - lastLog > 4_000_000) {
                        lastLog = got;
                        log.log("downloaded " + (got >> 20) + (total > 0 ? " of " + (total >> 20) : "") + " MB");
                    }
                }
            }
            if (installZip(c, tmp, log) == 0) throw new IOException("That zip held none of the toolchain files");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete(); // also when the download breaks half way
        }
    }

    private static String target(String n) { return n.equals("libaapt2.so") ? "aapt2" : n; }

    private static void store(Context c, String name, InputStream in) throws IOException {
        File f = file(c, target(name));
        f.delete(); // a read-only dex file has to be deleted, not overwritten
        File tmp = new File(f.getPath() + ".part");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            byte[] b = new byte[16384];
            int r;
            while ((r = in.read(b)) > 0) o.write(b, 0, r);
        }
        if (!tmp.renameTo(f)) throw new IOException("Cannot write " + f);
    }

    private static void finish(Context c) {
        synchronized (LOADERS) { LOADERS.clear(); }
        for (String n : CORE) {
            File f = file(c, n);
            if (f.getName().endsWith(".jar")) f.setReadOnly(); // Android 14+ refuses to load writable dex files
        }
        File a = file(c, "aapt2");
        if (a.isFile()) {
            a.setReadable(true, false);
            a.setExecutable(true, false);
        }
        aaptMode = "";
    }

    static void remove(Context c) {
        synchronized (LOADERS) { LOADERS.clear(); }
        File[] k = dir(c).listFiles();
        if (k != null) for (File f : k) f.delete();
        synchronized (IO) { seeded = false; } // the copy bundled in the app comes back on next use
    }

    /** The debug signing key ships inside the app. */
    static File[] debugKey(Context c) throws IOException {
        File pk = file(c, "debug.pk8"), ce = file(c, "debug.x509");
        if (!pk.isFile() || !ce.isFile()) {
            try (FileOutputStream o = new FileOutputStream(pk)) { o.write(Io.bytes(c.getAssets().open("debug/debug.pk8"))); }
            try (FileOutputStream o = new FileOutputStream(ce)) { o.write(Io.bytes(c.getAssets().open("debug/debug.x509"))); }
        }
        return new File[]{pk, ce};
    }

    // ---------------------------------------------------------------- loading

    static ClassLoader loader(Context c, String jar) throws IOException {
        seedBundled(c);
        synchronized (LOADERS) {
            DexClassLoader l = LOADERS.get(jar);
            if (l == null) {
                File f = file(c, jar);
                if (!f.isFile()) throw new IOException("Toolchain file missing: " + jar);
                f.setReadOnly();
                // Parent is the boot loader only, so the app's own androidx classes cannot clash with the tools.
                ClassLoader parent = c.getClassLoader().getParent();
                if ("kotlinc.dex.jar".equals(jar)) {
                    // kotlinc finds its own jar with Class.getResource("/pkg/Name.class") (PathUtil). D8 leaves no .class entries
                    // in a dex jar, so the lookup fails ("Resource not found"). Answer such lookups with a jar: URL naming
                    // this file when the class itself loads; the compiler only takes the jar path out of the URL.
                    final File jf = f;
                    l = new DexClassLoader(f.getAbsolutePath(), c.getCodeCacheDir().getAbsolutePath(), null, parent) {
                        @Override protected URL findResource(String name) {
                            URL u = super.findResource(name);
                            if (u != null || !name.endsWith(".class")) return u;
                            try {
                                Class.forName(name.substring(0, name.length() - 6).replace('/', '.'), false, this);
                                return new URL("jar:" + jf.toURI() + "!/" + name);
                            } catch (Throwable t) {
                                return null;
                            }
                        }
                    };
                } else {
                    l = new DexClassLoader(f.getAbsolutePath(), c.getCodeCacheDir().getAbsolutePath(), null, parent);
                }
                LOADERS.put(jar, l);
            }
            return l;
        }
    }

    static Throwable root(Throwable t) {
        while ((t instanceof InvocationTargetException || t instanceof ExceptionInInitializerError) && t.getCause() != null)
            t = t.getCause();
        return t;
    }

    static String describe(Throwable t) {
        t = root(t);
        StringBuilder sb = new StringBuilder(t.getClass().getName() + ": " + t.getMessage());
        for (Throwable x = t.getCause(); x != null && sb.length() < 600; x = x.getCause())
            sb.append("\n  caused by ").append(x.getClass().getName()).append(": ").append(x.getMessage());
        if (t instanceof NoSuchMethodError && String.valueOf(t.getMessage()).contains("Runtime$Version"))
            sb.append("\n  These compiler jars were built without the Java 9 Runtime.version() patch (Android lacks it)."
                    + " App settings > Build tools > Toolchain > Remove, then use the jars bundled in this app, or rebuild them with toolchain/bundle-jars.sh.");
        String all = sb.toString();
        if (all.contains("java.lang.management") || all.contains("com.sun.management"))
            sb.append("\n  This Kotlin pack was built before the java.lang.management stand-ins existed (Android has no such package)."
                    + " Run the \"Build language packs\" workflow for kotlin, then remove and install the Kotlin pack again.");
        else if (all.contains("containers.Unsafe") || all.contains("getAndAddInt"))
            sb.append("\n  IntelliJ's Unsafe wrapper inside kotlinc cannot find sun.misc.Unsafe.getAndAddInt on this Android."
                    + " Run the \"Build language packs\" workflow for kotlin (it now redirects that class to cloudide.shim.Unsafe),"
                    + " then remove and install the Kotlin pack again.");
        else if (t instanceof NoClassDefFoundError || t instanceof ClassNotFoundException)
            sb.append("\n  A class the compiler needs is missing from the toolchain jar. Rebuild the toolchain (javax stubs must be inside ecj.dex.jar; for kotlinc, in kotlinc.dex.jar: rebuild the Kotlin pack).");
        return sb.toString();
    }

    // ---------------------------------------------------------------- ECJ

    /** Runs the Eclipse batch compiler in this process. Returns true when it reports success. */
    static boolean ecj(Context c, List<String> args, StringBuilder out) throws Exception {
        seedBundled(c);
        File stubs = file(c, "jdk-stubs.jar");
        args = applyJavaOverride(c, args);
        args = ecjArgs(args, stubs.isFile() ? stubs : null);
        ClassLoader cl = loader(c, "ecj.dex.jar");
        Class<?> batch = cl.loadClass("org.eclipse.jdt.core.compiler.batch.BatchCompiler");
        Class<?> prog = cl.loadClass("org.eclipse.jdt.core.compiler.CompilationProgress");
        Method m = batch.getMethod("compile", String[].class, PrintWriter.class, PrintWriter.class, prog);
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        try {
            Object r = m.invoke(null, args.toArray(new String[0]), pw, pw, null);
            return Boolean.TRUE.equals(r);
        } catch (InvocationTargetException e) {
            Throwable r = root(e);
            if (r instanceof LinkageError || r instanceof NoSuchMethodError) synchronized (LOADERS) { LOADERS.remove("ecj.dex.jar"); }
            throw new Exception(describe(e), r);
        } finally {
            pw.flush();
            out.append(sw);
        }
    }


    /**
     * ECJ rejects -bootclasspath at Java 9 and above ("option -bootclasspath not supported at compliance level 9 and
     * above"). There the platform jar goes on -classpath instead. Callers retry at Java 8 (where -bootclasspath is
     * allowed) when ECJ still cannot find java.lang.Object.
     */
    static List<String> ecjArgs(List<String> in) { return ecjArgs(in, null); }

    /**
     * Newest language level the local build offers. ECJ, D8 and the JDK class stubs cover Java 21; the library side
     * is whatever android.jar has (SequencedCollection and friends need a recent platform).
     */
    static final int MAX_JAVA = 21;
    private static final int[] LADDER = {21, 17, 11, 8};

    /** Clamps a level read from a build file into what the local build can do (8 .. MAX_JAVA). */
    static int clampJava(int level) { return Math.max(8, Math.min(MAX_JAVA, level)); }

    /** Choices shown in Settings. "Auto" (0) follows the project's build file. */
    static final String[] JAVA_CHOICES = {"Auto (from project)", "8", "11", "17", "21"};

    /** The Java level picked in Settings for local builds: 0 = auto, otherwise 8, 11, 17 or 21. */
    static int javaOverride(Context c) {
        int v = Prefs.sp(c).getInt("local_java", 0);
        return v == 0 ? 0 : clampJava(v);
    }

    static void setJavaOverride(Context c, int level) {
        Prefs.sp(c).edit().putInt("local_java", level).apply();
    }

    /** Rewrites -source / -target / --release to the level chosen in Settings. Does nothing on Auto. */
    static List<String> applyJavaOverride(Context c, List<String> in) {
        int lv = javaOverride(c);
        if (lv == 0) return in;
        List<String> a = new ArrayList<>(in);
        for (int i = 0; i + 1 < a.size(); i++)
            if (a.get(i).equals("-source") || a.get(i).equals("-target") || a.get(i).equals("--release"))
                a.set(i + 1, String.valueOf(lv));
        return a;
    }

    /** The next lower level to retry at after a failure at this one: 21 -> 17 -> 11 -> 8, then 0. */
    static int lowerJava(int level) {
        for (int l : LADDER) if (l < level) return l;
        return 0;
    }

    /**
     * True when ECJ failed because it could not see the platform classes at this language level (as opposed to a
     * normal error in the person's code). Only then is it worth trying a lower level.
     */
    static boolean platformProblem(String ecjOut) {
        return ecjOut.contains("java.lang.Object") || ecjOut.contains("bootclasspath") || ecjOut.contains("java.lang.Record")
                || ecjOut.contains("java.lang.Enum") || ecjOut.contains("java.lang.String cannot be resolved")
                || ecjOut.contains("MatchException") || ecjOut.contains("LambdaMetafactory") || ecjOut.contains("cannot be resolved. It is indirectly referenced");
    }

    static List<String> ecjArgs(List<String> in, File stubs) {
        int level = 8;
        for (int i = 0; i + 1 < in.size(); i++)
            if (in.get(i).equals("-source") || in.get(i).equals("-target") || in.get(i).equals("--release")) {
                try {
                    String v = in.get(i + 1);
                    level = Math.max(level, Integer.parseInt(v.startsWith("1.") ? v.substring(2) : v));
                } catch (NumberFormatException ignored) { }
            }
        int b = in.indexOf("-bootclasspath");
        if (b < 0 || b + 1 >= in.size()) return in;
        if (level < 9) {
            // Java 8 keeps -bootclasspath, but ECJ still needs LambdaMetafactory & co for lambdas: android.jar lacks them.
            if (stubs == null) return in;
            List<String> a8 = new ArrayList<>(in);
            a8.set(b + 1, in.get(b + 1) + File.pathSeparator + stubs.getPath());
            return a8;
        }
        List<String> a = new ArrayList<>(in);
        // android.jar plays java.base. The stubs add the Java 9-21 classes it lacks (Record, MatchException,
        // runtime.ObjectMethods, StringConcatFactory ...) behind it, so ECJ finds them for records, switch patterns and
        // string concatenation. They are only read at compile time; D8 desugars the uses.
        String boot = a.get(b + 1) + (stubs != null ? File.pathSeparator + stubs.getPath() : "");
        a.remove(b + 1);
        a.remove(b);
        int cp = a.indexOf("-classpath");
        if (cp < 0) cp = a.indexOf("-cp");
        if (cp >= 0 && cp + 1 < a.size()) a.set(cp + 1, boot + File.pathSeparator + a.get(cp + 1));
        else { a.add(0, boot); a.add(0, "-classpath"); }
        return a;
    }

    // ---------------------------------------------------------------- D8

    /** Runs D8 in this process. R8 writes its diagnostics to System.err, so that is captured while it runs. */
    static boolean d8(Context c, List<String> args, StringBuilder out) throws Exception {
        ClassLoader cl = loader(c, "d8.dex.jar");
        Class<?> d8 = cl.loadClass("com.android.tools.r8.D8");
        Class<?> cmdC = cl.loadClass("com.android.tools.r8.D8Command");
        Class<?> originC = cl.loadClass("com.android.tools.r8.origin.Origin");
        Object origin = originC.getMethod("root").invoke(null);
        synchronized (IO) {
            PrintStream oldErr = System.err, oldOut = System.out;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(buf, true, "UTF-8");
            System.setErr(ps);
            System.setOut(ps);
            try {
                Object builder = cmdC.getMethod("parse", String[].class, originC).invoke(null, args.toArray(new String[0]), origin);
                Object cmd = builder.getClass().getMethod("build").invoke(builder);
                d8.getMethod("run", cmdC).invoke(null, cmd);
                return true;
            } catch (InvocationTargetException e) {
                Throwable t = root(e);
                if (t.getClass().getSimpleName().equals("CompilationFailedException")) return false;
                throw new Exception(describe(e), t);
            } finally {
                System.setErr(oldErr);
                System.setOut(oldOut);
                ps.flush();
                out.append(buf.toString("UTF-8"));
            }
        }
    }

    static String d8Version(Context c) {
        try {
            Class<?> v = loader(c, "d8.dex.jar").loadClass("com.android.tools.r8.Version");
            return String.valueOf(v.getMethod("getVersionString").invoke(null));
        } catch (Throwable t) {
            return "unknown (" + root(t).getClass().getSimpleName() + ")";
        }
    }

    // ---------------------------------------------------------------- signing

    static void sign(Context c, File in, File out, int minSdk) throws Exception {
        File[] key = debugKey(c);
        ClassLoader cl = loader(c, "sign.dex.jar");
        Class<?> k = cl.loadClass("cloudide.sign.SignMain");
        Method m = k.getMethod("sign", String.class, String.class, String.class, String.class, int.class);
        try {
            m.invoke(null, in.getAbsolutePath(), out.getAbsolutePath(), key[0].getAbsolutePath(), key[1].getAbsolutePath(), minSdk);
        } catch (InvocationTargetException e) {
            throw new Exception(describe(e), root(e));
        }
    }

    // ---------------------------------------------------------------- aapt2

    /**
     * Runs aapt2. Order tried: straight exec (works for libaapt2.so in nativeLibraryDir and on targetSdk 28 and
     * older), then through /system/bin/linker64, which loads the file with mmap and so passes the Android 10+
     * "no exec from app data" rule. No Termux involved.
     */
    static int aapt2(Context c, List<String> args, File cwd, StringBuilder out) throws IOException, InterruptedException {
        File bin = aapt2(c);
        if (bin == null) throw new IOException("aapt2 is not installed");
        return exec(c, bin, args, cwd, out);
    }

    /** Runs any bundled SDK build-tool (aapt, zipalign, aidl, dexdump, split-select) the same way as aapt2. */
    static int runTool(Context c, String name, List<String> args, File cwd, StringBuilder out) throws IOException, InterruptedException {
        File bin = nativeTool(c, name);
        if (bin == null) throw new IOException(name + " is not installed (expected lib" + name.replace('-', '_') + ".so in the app's native libraries)");
        return exec(c, bin, args, cwd, out);
    }

    private static int exec(Context c, File bin, List<String> args, File cwd, StringBuilder out) throws IOException, InterruptedException {
        List<List<String>> tries = new ArrayList<>();
        List<String> direct = new ArrayList<>();
        direct.add(bin.getAbsolutePath());
        direct.addAll(args);
        List<String> viaLinker = new ArrayList<>();
        viaLinker.add(Build.SUPPORTED_64_BIT_ABIS.length > 0 ? "/system/bin/linker64" : "/system/bin/linker");
        viaLinker.add(bin.getAbsolutePath());
        viaLinker.addAll(args);
        if ("linker".equals(aaptMode)) { tries.add(viaLinker); tries.add(direct); }
        else { tries.add(direct); tries.add(viaLinker); }
        IOException last = null;
        for (List<String> cmd : tries) {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd);
            pb.redirectErrorStream(true);
            pb.environment().put("TMPDIR", c.getCacheDir().getAbsolutePath());
            Process p;
            try {
                p = pb.start();
            } catch (IOException e) {
                last = e;
                continue;
            }
            p.getOutputStream().close();
            try (InputStream in = p.getInputStream()) {
                byte[] b = new byte[4096];
                int r;
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                while ((r = in.read(b)) != -1) raw.write(b, 0, r);
                out.append(raw.toString("UTF-8")); // decoded once: a character split between two reads would come out broken
            }
            int code = p.waitFor();
            // 126/127 from the linker means it could not map or start the file; try the other way
            if ((code == 126 || code == 127) && tries.indexOf(cmd) == 0 && out.length() < 400) {
                last = new IOException("exit " + code + ": " + out);
                out.setLength(0);
                continue;
            }
            aaptMode = cmd == viaLinker ? "linker" : "direct";
            return code;
        }
        throw new IOException("Cannot start " + bin.getName() + ": " + (last == null ? "" : last.getMessage())
                + "\nIt must be an arm64 Android build (not the Linux or Termux one). Ship it as libaapt2.so in app/src/main/jniLibs/arm64-v8a.");
    }

    // ---------------------------------------------------------------- self test

    /** Runs every tool once and reports what happened, so a broken piece is named instead of guessed at. */
    static String selfTest(Context c) {
        StringBuilder r = new StringBuilder();
        r.append("Device: Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
                .append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?").append('\n');
        if (Build.VERSION.SDK_INT < 26) r.append("FAIL  Android 8.0 or newer is required.\n");
        List<String> miss = missing(c);
        if (!miss.isEmpty()) {
            r.append("FAIL  missing: ").append(join(miss, ", ")).append('\n');
        }
        if (!versions(c).isEmpty()) r.append(versions(c)).append('\n');

        if (aapt2(c) != null) {
            try {
                StringBuilder o = new StringBuilder();
                int code = aapt2(c, Arrays.asList("version"), c.getCacheDir(), o);
                r.append(code == 0 ? "OK    aapt2 " : "FAIL  aapt2 exit " + code + " ").append(o.toString().trim())
                        .append(" (").append(aaptMode).append(")\n");
            } catch (Throwable t) {
                r.append("FAIL  aapt2: ").append(t.getMessage()).append('\n');
            }
        }

        for (String tool : new String[]{"aapt", "zipalign", "aidl", "dexdump", "split-select"}) {
            if (nativeTool(c, tool) == null) continue;
            try {
                StringBuilder o = new StringBuilder();
                int code = runTool(c, tool, tool.equals("aapt") ? Arrays.asList("version") : Arrays.<String>asList(), c.getCacheDir(), o);
                // zipalign, aidl and dexdump print usage and exit non-zero without arguments: that still proves they start.
                // dexdump reports "No file specified" through Android's log, not stdout/stderr, so it exits 1 with no output at
                // all. A normal exit code (below 126) means the program ran; 126/127 (cannot load) and 128+ (signal) mean it did not.
                boolean started = code == 0 || o.length() > 0 || (code > 0 && code < 126);
                r.append(started ? "OK    " : "FAIL  ").append(tool).append(" starts");
                if (!started) r.append(" (exit ").append(code).append(')');
                if (tool.equals("aapt")) r.append(" (").append(o.toString().trim()).append(')');
                r.append('\n');
            } catch (Throwable e) {
                r.append("FAIL  ").append(tool).append(": ").append(e.getMessage()).append('\n');
            }
        }

        File t = new File(c.getCacheDir(), "selftest");
        Io.delete(t);
        File src = new File(t, "src/T.java"), out = new File(t, "out");
        try {
            out.mkdirs();
            for (String level : new String[]{"21", "17", "11", "8"}) {
                int lv = Integer.parseInt(level);
                String body = "import java.util.*;\npublic class T {\n"
                        + (lv >= 17 ? "  record P(int a) {}\n  sealed interface S permits A, B {}\n  record A() implements S {}\n  record B() implements S {}\n" : "")
                        + (lv >= 21 ? "  static int g(S s) { return switch (s) { case A a -> 1; case B b -> 2; }; }\n"
                                + "  static String h(Object o) { return o instanceof P p && p.a() > 0 ? \"p\" + p.a() : \"\"; }\n" : "")
                        + (lv >= 11 ? "  static int f(List<String> l) { var s = 0; for (var x : l) s += x.length(); Runnable r = () -> {}; return s; }\n"
                                : "  static int f(List<String> l) { int s = 0; for (String x : l) s += x.length(); Runnable r = () -> {}; return s; }\n")
                        + "}\n";
                Io.write(src, body);
                Io.delete(out);
                out.mkdirs();
                StringBuilder o = new StringBuilder();
                List<String> a = new ArrayList<>(Arrays.asList("-d", out.getPath(), "-source", level, "-target", level, "-proc:none", "-nowarn",
                        "-bootclasspath", file(c, "android.jar").getPath(), src.getPath()));
                boolean ok = false;
                try {
                    ok = ecj(c, a, o);
                } catch (Throwable e) {
                    o.append(describe(e));
                }
                r.append(ok ? "OK    ECJ compiles Java " + level + "\n" : "FAIL  ECJ Java " + level + ": " + clip(o.toString(), 2500) + "\n");
                if (ok) break;
            }
        } catch (Throwable e) {
            r.append("FAIL  ECJ test: ").append(e).append('\n');
        }

        if (file(c, "d8.dex.jar").isFile()) r.append("OK    D8 loaded, version ").append(d8Version(c)).append('\n');
        if (file(c, "sign.dex.jar").isFile()) {
            try {
                loader(c, "sign.dex.jar").loadClass("cloudide.sign.SignMain");
                r.append("OK    signer loaded\n");
            } catch (Throwable e) {
                r.append("FAIL  signer: ").append(describe(e)).append('\n');
            }
        }
        Io.delete(t);
        return r.toString();
    }

    static String clip(String s, int n) { return s.length() <= n ? s.trim() : s.substring(0, n).trim() + " ..."; }
}
