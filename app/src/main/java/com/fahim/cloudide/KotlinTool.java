package com.fahim.cloudide;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Runs kotlinc (kotlinc.dex.jar from the Kotlin pack) inside this process, like ECJ. */
final class KotlinTool {
    private static final Object LOCK = new Object();

    private KotlinTool() { }

    static boolean ready(Context c) { return Packs.installed(c, "kotlin"); }

    static File stdlib(Context c) { return Toolchain.file(c, "kotlin-stdlib.jar"); }

    /** Remembered for this app run so a later compile does not repeat a try that already failed. */
    private static volatile boolean psiFrontEnd;     // LightTree front-end failed once: read sources through PSI
    private static volatile int jdkMode;             // 0 nothing, 1 -Xallow-pre-17-runtime-jdk, 2 report Java 17

    /**
     * Compiles. args are normal kotlinc arguments. Messages are appended to out. Returns true on success.
     * Two things kotlinc may reject on Android are handled by retrying once, not by fixed flags:
     *  - the Java 9 style runtime ("running Kotlin compiler using JDK 9", seen in Kotlin 2.5 betas): first with
     *    -Xallow-pre-17-runtime-jdk (works until Kotlin 2.5.20-Beta1), then with java.specification.version reported as 17
     *    while the compiler runs. Kotlin 2.4 does not need either, and warns about the flag, so nothing is added up front;
     *  - the LightTree front-end failing in TreeRawFirDeclarationBuilderProxy: retried with -Xuse-fir-lt=false (PSI).
     */
    static boolean compile(Context c, List<String> args, StringBuilder out) throws Exception {
        ClassLoader cl = Toolchain.loader(c, "kotlinc.dex.jar");
        Class<?> k = cl.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
        Object inst = k.getDeclaredConstructor().newInstance();
        Method exec = k.getMethod("exec", PrintStream.class, String[].class);
        synchronized (LOCK) {
            System.setProperty("idea.io.use.nio2", "true");
            System.setProperty("java.awt.headless", "true");
            System.setProperty("kotlin.colors.enabled", "false");
            System.setProperty("idea.use.native.fs.for.win", "false");
            boolean psi = psiFrontEnd;
            int jm = jdkMode;
            for (int attempt = 0; ; attempt++) {
                List<String> a = new ArrayList<>();
                boolean spoof = jm >= 2;
                if (jm == 1) a.add("-Xallow-pre-17-runtime-jdk");
                if (psi) a.add("-Xuse-fir-lt=false");
                a.addAll(args);
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                PrintStream ps = new PrintStream(buf, true, "UTF-8");
                String oldSpec = System.getProperty("java.specification.version");
                if (spoof) System.setProperty("java.specification.version", "17");
                String text;
                Throwable thrown = null;
                boolean ok = false;
                try {
                    ok = "OK".equals(String.valueOf(exec.invoke(inst, ps, a.toArray(new String[0]))));
                } catch (InvocationTargetException e) {
                    thrown = e;
                } finally {
                    if (spoof) { if (oldSpec != null) System.setProperty("java.specification.version", oldSpec); else System.clearProperty("java.specification.version"); }
                    ps.flush();
                }
                text = buf.toString("UTF-8");
                if (ok) { psiFrontEnd = psi; jdkMode = jm; out.append(text); return true; }
                boolean jdkRefused = text.contains("running Kotlin compiler using JDK");
                boolean lightTreeFailed = text.contains("TreeRawFirDeclarationBuilderProxy") || text.contains("lightTree.converter");
                // IntelliJ code inside kotlinc can reflect on sun.misc.Unsafe methods that Android hides from apps. Lift the
                // restriction only when a compile really fails that way, then retry once.
                String symptom = text + (thrown != null ? Toolchain.describe(thrown) : "");
                boolean hiddenApi = symptom.contains("getAndAddInt") || (symptom.contains("NoSuchMethod") && symptom.contains("Unsafe"));
                if (attempt < 4 && hiddenApi && !HiddenApi.tried() && HiddenApi.relax() != null && HiddenApi.active()) continue;
                if (attempt < 4 && jdkRefused && jm < 2) { jm++; continue; }
                if (attempt < 4 && lightTreeFailed && !psi) { psi = true; continue; }
                out.append(text);
                if (thrown != null) throw new Exception(Toolchain.describe(thrown), Toolchain.root(thrown));
                return false;
            }
        }
    }

    /** True when a Kotlin source uses Jetpack Compose and so needs the Compose compiler plugin. */
    static boolean usesCompose(List<File> kt) {
        for (File f : kt) {
            try {
                String s = Io.read(f);
                if (s.contains("androidx.compose") || s.contains("@Composable")) return true;
            } catch (IOException ignored) { }
        }
        return false;
    }

    /**
     * Adds the Compose compiler plugin for projects that use Compose. Without it the compiler calls remember(), Column() and
     * the rest as ordinary functions and fails in code generation. The plugin's classes are inside kotlinc.dex.jar;
     * compose-plugin.jar only carries the service files that name them (see .github/workflows/packs.yml).
     */
    static void composePlugin(Context c, List<String> a, List<File> kt) throws Exception {
        if (!usesCompose(kt)) return;
        File p = Toolchain.file(c, "compose-plugin.jar");
        if (!p.isFile())
            throw new IllegalStateException("This project uses Jetpack Compose, but the installed Kotlin pack has no Compose compiler plugin. "
                    + "Run the \"Build language packs\" workflow for kotlin again, then remove and install the Kotlin pack.");
        a.add("-Xplugin=" + p.getAbsolutePath());
    }

    /** Which retries the last successful compile needed, for the self-test report. */
    static String mode() {
        return (psiFrontEnd ? "PSI front-end" : "LightTree front-end") + (jdkMode == 2 ? ", JDK version spoofed" : jdkMode == 1 ? ", -Xallow-pre-17-runtime-jdk" : ", no JDK workaround needed");
    }

    /** Arguments every Kotlin compile here needs: no JDK on the phone, android.jar plays the JDK. */
    static void base(Context c, List<String> a, String classpath, java.io.File outDir, int jvmTarget) {
        a.add("-no-stdlib");
        a.add("-no-reflect");
        a.add("-no-jdk");
        a.add("-jvm-target");
        a.add(jvmTarget >= 21 ? "21" : jvmTarget >= 17 ? "17" : jvmTarget >= 11 ? "11" : "1.8");
        a.add("-Xno-call-assertions");
        a.add("-Xno-param-assertions");
        a.add("-Xno-receiver-assertions");
        a.add("-nowarn");
        a.add("-classpath");
        a.add(classpath + java.io.File.pathSeparator + stdlib(c).getAbsolutePath());
        a.add("-d");
        a.add(outDir.getAbsolutePath());
    }
}
