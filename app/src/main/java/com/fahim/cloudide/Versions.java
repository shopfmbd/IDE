package com.fahim.cloudide;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version rules for builds: which Java a Gradle release can run on, and which Gradle an Android Gradle plugin
 * needs. Tables come from the official Gradle and Android compatibility pages (checked October 2026).
 */
final class Versions {
    private Versions() { }

    /** {Gradle major, minor, patch, highest Java it can run on}, newest first. */
    private static final int[][] GRADLE_JAVA = {
            {9, 8, 0, 27}, {9, 4, 0, 26}, {9, 1, 0, 25}, {8, 14, 0, 24}, {8, 10, 0, 23},
            {8, 8, 0, 22}, {8, 5, 0, 21}, {8, 3, 0, 20}, {7, 6, 0, 19}, {7, 3, 0, 17}};

    /** {AGP major, minor, then the minimum Gradle major, minor, patch}, newest first. */
    private static final int[][] AGP_GRADLE = {
            {9, 5, 9, 6, 0}, {9, 4, 9, 6, 0}, {9, 3, 9, 5, 0}, {9, 2, 9, 4, 1}, {9, 1, 9, 3, 1}, {9, 0, 9, 1, 0},
            {8, 13, 8, 13, 0}, {8, 12, 8, 13, 0}, {8, 11, 8, 13, 0}, {8, 10, 8, 11, 1}, {8, 9, 8, 11, 1},
            {8, 8, 8, 10, 2}, {8, 7, 8, 9, 0}, {8, 6, 8, 7, 0}, {8, 5, 8, 7, 0}};

    private static final Pattern NUM = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
    private static final Pattern AGP_PLUGIN = Pattern.compile("com\\.android\\.(?:application|library)[\"']?\\)?\\s+version\\s+[\"']([0-9][0-9.]*)[\"']");
    private static final Pattern AGP_CLASSPATH = Pattern.compile("com\\.android\\.tools\\.build:gradle:([0-9][0-9.]*)");
    private static final Pattern WRAPPER = Pattern.compile("gradle-([0-9][0-9.]*)-(?:bin|all)\\.zip");

    /** {major, minor, patch} of "9.8.2" / "9.8" / "9.8.0-rc-3", or null for words such as "current". */
    static int[] parse(String v) {
        if (v == null) return null;
        Matcher m = NUM.matcher(v.trim());
        if (!m.find()) return null;
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3) == null ? 0 : Integer.parseInt(m.group(3))};
    }

    static int cmp(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return a[i] < b[i] ? -1 : 1;
        return 0;
    }

    /** Highest Java this Gradle version can run on, or 0 when unknown (e.g. "current"). */
    static int maxJava(String gradle) {
        int[] g = parse(gradle);
        if (g == null) return 0;
        for (int[] r : GRADLE_JAVA) if (cmp(g, new int[]{r[0], r[1], r[2]}) >= 0) return r[3];
        return 0;
    }

    /** Oldest Gradle that runs on this Java, like "9.8", or "" when beyond the table. */
    static String minGradleForJava(int java) {
        for (int i = GRADLE_JAVA.length - 1; i >= 0; i--)
            if (GRADLE_JAVA[i][3] >= java) return GRADLE_JAVA[i][0] + "." + GRADLE_JAVA[i][1];
        return "";
    }

    /** Oldest Gradle this Android Gradle plugin needs, like "9.6.0", or "" when unknown. */
    static String minGradleForAgp(String agp) {
        int[] a = parse(agp);
        if (a == null) return "";
        for (int[] r : AGP_GRADLE) if (r[0] == a[0] && r[1] == a[1]) return r[2] + "." + r[3] + "." + r[4];
        return "";
    }

    /** The Android Gradle plugin version a build file asks for, or "". */
    static String findAgp(String text) {
        if (text == null) return "";
        Matcher m = AGP_PLUGIN.matcher(text);
        if (m.find()) return m.group(1);
        m = AGP_CLASSPATH.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /** The Gradle version named in gradle-wrapper.properties, or "". */
    static String wrapperVersion(String props) {
        if (props == null) return "";
        Matcher m = WRAPPER.matcher(props);
        return m.find() ? m.group(1) : "";
    }

    private static int javaNumber(String jdk) {
        if (jdk == null || !jdk.trim().matches("\\d+")) return 0;
        return Integer.parseInt(jdk.trim());
    }

    /** Warnings to show before a build starts, or "" when the combination looks fine. */
    static String preflight(String jdk, String gradle, String agp, String wrapper) {
        boolean wrap = wrapper != null && !wrapper.isEmpty();
        String eff = wrap ? wrapper : gradle;
        String who = wrap ? "the project's Gradle wrapper (" + wrapper + ")" : "Gradle " + gradle;
        int j = javaNumber(jdk);
        StringBuilder t = new StringBuilder();
        int max = maxJava(eff);
        if (j > 0 && max > 0 && j > max) {
            String need = minGradleForJava(j);
            t.append("JDK ").append(j).append(" is newer than ").append(who).append(" supports (up to Java ").append(max).append(").\n")
                    .append(need.isEmpty() ? "Fix: lower the JDK.\n" : "Fix: use Gradle " + need + " or newer, or lower the JDK.\n");
        }
        String min = minGradleForAgp(agp);
        int[] e = parse(eff);
        if (!min.isEmpty() && e != null && cmp(e, parse(min)) < 0) {
            t.append("Android Gradle plugin ").append(agp).append(" needs Gradle ").append(min).append(" or newer, but this build uses ")
                    .append(who).append(".\n").append(wrap ? "Fix: update the wrapper, or lower the plugin version.\n"
                            : "Fix: set the Gradle version to " + min + " or newer.\n");
        }
        if (j > 0 && j < 17 && !agp.isEmpty()) {
            t.append("Android Gradle plugin ").append(agp).append(" needs JDK 17 or newer to run (the JDK is set to ").append(j).append(").\n");
        }
        return t.length() == 0 ? "" : "--- Version check ---\n" + t.toString().trim() + "\n";
    }
}
