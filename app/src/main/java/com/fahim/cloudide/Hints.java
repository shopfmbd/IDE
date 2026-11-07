package com.fahim.cloudide;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plain-language advice for the version mix-ups that break builds (JDK too new/old for Gradle, AGP or compileSdk). */
final class Hints {
    private Hints() { }

    private static final Pattern CLASS_VER = Pattern.compile("Unsupported class file major version (\\d+)");
    private static final Pattern CLASS_ERR = Pattern.compile("UnsupportedClassVersionError.*?class file version (\\d+)");
    private static final Pattern CANT_READ = Pattern.compile("Could not determine java version from '([^']+)'");
    private static final Pattern MIN_GRADLE = Pattern.compile("Minimum supported Gradle version is ([\\d.]+)");
    private static final Pattern NEEDS_JAVA = Pattern.compile("(?:Android Gradle plugin requires Java|requires Java) (\\d+)");
    private static final Pattern COMPILE_SDK = Pattern.compile("compile against version (\\d+) or later");
    private static final Pattern RELEASE = Pattern.compile("release version (\\d+) not supported");

    /** Libraries whose official home moved: {old group, new group, a version known to exist there}. */
    private static final String[][] MOVED = {
            {"com.github.yausername.youtubedl-android", "io.github.junkfood02.youtubedl-android", "0.18.1"},
    };

    /** Advice when a "not found" library is one that moved to Maven Central, or "" when it is not a known one. */
    static String moved(String msg) {
        if (msg == null) return "";
        for (String[] m : MOVED) {
            if (msg.contains(m[0] + ":"))
                return "\nThis library moved. Its JitPack copy stops at old versions. In your build file change the group "
                        + m[0] + " to " + m[1] + " (for example " + m[1] + ":library:" + m[2]
                        + "; ffmpeg and aria2c use the same group and version). It is on Maven Central, which is already switched on.";
        }
        return "";
    }

    /** Java feature version for a class file major version (65 = Java 21). */
    static int javaOf(int major) { return major - 44; }

    /** Advice for this log, or "" when nothing known matches. jdk and gradle are the values the project used. */
    static String forLog(String log, String jdk, String gradle) {
        if (log == null || log.isEmpty()) return "";
        StringBuilder t = new StringBuilder();
        Matcher m = CLASS_VER.matcher(log);
        if (m.find()) {
            int j = javaOf(Integer.parseInt(m.group(1)));
            t.append("A tool in this build cannot read Java ").append(j).append(" class files. The JDK is set to ").append(jdk)
                    .append(" and Gradle to ").append(gradle).append(".\n")
                    .append("Fix: lower the JDK (17 or 21 work with most Android builds), or set Gradle to 'current' for the newest stable release.\n");
            String need = Versions.minGradleForJava(j);
            if (!need.isEmpty()) t.append("Gradle ").append(need).append(" or newer is needed to run on Java ").append(j).append(".\n");
        }
        m = CLASS_ERR.matcher(log);
        if (m.find() && t.length() == 0) {
            int j = javaOf(Integer.parseInt(m.group(1)));
            t.append("Something was compiled for Java ").append(j).append(" but the build runs an older JDK (").append(jdk).append(").\n")
                    .append("Fix: set the JDK to ").append(j).append(" or higher.\n");
        }
        m = CANT_READ.matcher(log);
        if (m.find() && t.length() == 0) {
            t.append("Gradle ").append(gradle).append(" does not recognise Java ").append(m.group(1)).append(" yet.\n")
                    .append("Fix: set Gradle to 'current' (newest stable), or lower the JDK.\n");
        }
        m = MIN_GRADLE.matcher(log);
        if (m.find()) {
            t.append("The Android Gradle plugin needs Gradle ").append(m.group(1)).append(" or newer (this build used ").append(gradle).append(").\n")
                    .append("Fix: set Gradle version to ").append(m.group(1)).append(" or 'current'.\n");
        }
        m = NEEDS_JAVA.matcher(log);
        if (m.find()) {
            t.append("This build needs Java ").append(m.group(1)).append(" or newer to run (the JDK was ").append(jdk).append(").\n")
                    .append("Fix: set the JDK to ").append(m.group(1)).append(" or higher.\n");
        }
        m = COMPILE_SDK.matcher(log);
        if (m.find()) {
            t.append("A library needs compileSdk ").append(m.group(1)).append(" or higher.\n")
                    .append("Fix: raise compileSdk in app/build.gradle, or use an older version of that library.\n");
        }
        m = RELEASE.matcher(log);
        if (m.find()) {
            t.append("The code asks for Java ").append(m.group(1)).append(" but the JDK in use (").append(jdk).append(") is older.\n")
                    .append("Fix: set the JDK to ").append(m.group(1)).append(" or higher, or lower the language level in the build file.\n");
        }
        return t.length() == 0 ? "" : "--- Version tip ---\n" + t.toString().trim() + "\n";
    }
}
