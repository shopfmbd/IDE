package com.fahim.cloudide;

import java.util.Locale;

/** Gradle task presets for the build picker, and the output files each task produces. */
final class Tasks {
    private Tasks() { }

    static final String APK = "**/build/outputs/apk/**/*.apk";

    /** {label, gradle task}. The last entry is a placeholder for a typed task. */
    static final String[][] PRESETS = {
            {"Debug APK", "assembleDebug"},
            {"Release APK", "assembleRelease"},
            {"Release bundle (AAB)", "bundleRelease"},
            {"Clean, then debug APK", "clean assembleDebug"},
            {"Lint report", "lint"},
            {"Unit tests", "test"},
            {"Other task...", ""}
    };

    /**
     * Output pattern for tasks that do not produce just an APK. Empty means "use the workflow default",
     * so ordinary assemble tasks keep behaving exactly as before.
     */
    static String artifactFor(String task) {
        String t = task == null ? "" : task.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        if (t.contains("bundle")) sb.append("**/build/outputs/bundle/**/*.aab\n");
        if (t.contains("lint")) sb.append("**/build/reports/lint-results*\n");
        if (t.contains("test")) sb.append("**/build/reports/tests/**\n**/build/test-results/**/*.xml\n");
        if (sb.length() == 0) return "";
        return sb.append(APK).toString();
    }

    static String label(String task) {
        for (String[] p : PRESETS) if (p[1].equals(task)) return p[0];
        return task;
    }
}
