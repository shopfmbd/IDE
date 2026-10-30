package com.fahim.cloudide;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Persistent app log. Lines are appended to files/logs/cloudide.log (rotated at {@link #MAX} bytes into
 * cloudide.1.log), so what happened before a crash is still there after the app restarts.
 * Every method is safe to call from any thread and never throws.
 */
final class AppLog {
    private AppLog() { }

    static final int DEBUG = 0, INFO = 1, WARN = 2, ERROR = 3;
    private static final String[] NAMES = {"D", "I", "W", "E"};
    private static final long MAX = 256 * 1024;
    private static final Object LOCK = new Object();
    private static File dir;
    private static Context app;

    /** Call once from Application.onCreate. */
    static void init(Context c) {
        synchronized (LOCK) {
            app = c.getApplicationContext();
            dir = new File(app.getFilesDir(), "logs");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
    }

    static void d(String tag, String msg) { write(DEBUG, tag, msg, null); }

    static void i(String tag, String msg) { write(INFO, tag, msg, null); }

    static void w(String tag, String msg) { write(WARN, tag, msg, null); }

    static void w(String tag, String msg, Throwable t) { write(WARN, tag, msg, t); }

    static void e(String tag, String msg, Throwable t) { write(ERROR, tag, msg, t); }

    static String trace(Throwable t) {
        if (t == null) return "";
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static void write(int level, String tag, String msg, Throwable t) {
        try {
            if (level == DEBUG && (app == null || !Prefs.bool(app, "verbose_log", false))) return;
            String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + NAMES[level] + "/" + tag
                    + " [" + Thread.currentThread().getName() + "] " + msg + (t == null ? "" : "\n" + trace(t));
            android.util.Log.println(level == ERROR ? android.util.Log.ERROR : level == WARN ? android.util.Log.WARN
                    : level == INFO ? android.util.Log.INFO : android.util.Log.DEBUG, "CloudIDE/" + tag, msg + (t == null ? "" : "\n" + trace(t)));
            synchronized (LOCK) {
                if (dir == null) return;
                File f = new File(dir, "cloudide.log");
                if (f.length() > MAX) {
                    File old = new File(dir, "cloudide.1.log");
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                    //noinspection ResultOfMethodCallIgnored
                    f.renameTo(old);
                }
                try (FileOutputStream o = new FileOutputStream(f, true)) {
                    o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable ignored) {
            // logging must never be the thing that crashes the app
        }
    }

    /** The whole log, oldest first (previous file then current). Empty string when there is none. */
    static String read() {
        synchronized (LOCK) {
            if (dir == null) return "";
            return slurp(new File(dir, "cloudide.1.log")) + slurp(new File(dir, "cloudide.log"));
        }
    }

    private static String slurp(File f) {
        if (!f.isFile()) return "";
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    static void clear() {
        synchronized (LOCK) {
            if (dir == null) return;
            File[] k = dir.listFiles();
            if (k != null) for (File f : k) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    // ---- crash handling -------------------------------------------------------------------------------------

    /** Records the crash, and leaves a marker so the next launch can offer to show it. */
    static void crash(Thread th, Throwable t) {
        e("CRASH", "Uncaught exception on thread " + (th == null ? "?" : th.getName()), t);
        try {
            synchronized (LOCK) {
                if (dir == null) return;
                try (FileOutputStream o = new FileOutputStream(new File(dir, "crash.pending"))) {
                    o.write((t.getClass().getName() + ": " + t.getMessage()).getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Summary of an unseen crash from the previous run, or null. Call {@link #ackCrash()} once it was shown. */
    static String pendingCrash() {
        synchronized (LOCK) {
            if (dir == null) return null;
            File m = new File(dir, "crash.pending");
            if (!m.isFile()) return null;
            String s = slurp(m).trim();
            return s.isEmpty() ? "Unknown error" : s;
        }
    }

    static void ackCrash() {
        synchronized (LOCK) {
            if (dir != null) //noinspection ResultOfMethodCallIgnored
                new File(dir, "crash.pending").delete();
        }
    }

    // ---- reports --------------------------------------------------------------------------------------------

    static String deviceInfo(Context c) {
        String ver = "?";
        try {
            android.content.pm.PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            ver = p.versionName + " (" + (Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode) + ")";
        } catch (Exception ignored) {
        }
        Runtime r = Runtime.getRuntime();
        return "CloudIDE " + ver + "\n"
                + "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), target SDK " + c.getApplicationInfo().targetSdkVersion + "\n"
                + "Device " + Build.MANUFACTURER + " " + Build.MODEL + ", ABI " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?") + "\n"
                + "Memory used " + Human.size(r.totalMemory() - r.freeMemory()) + " of " + Human.size(r.maxMemory()) + "\n"
                + "Toolchain: " + Toolchain.shortStatus(c) + "\n";
    }

    /** Device info plus the log: what to paste into a bug report. */
    static String report(Context c) {
        return "=== CloudIDE diagnostics ===\n" + deviceInfo(c) + "\n=== Log ===\n" + read();
    }

    /** Writes the report to a cache file the FileProvider can share. */
    static File reportFile(Context c) throws IOException {
        File d = new File(c.getCacheDir(), "share");
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("Cannot create " + d);
        File f = new File(d, "cloudide-log.txt");
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(report(c).getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }
}
