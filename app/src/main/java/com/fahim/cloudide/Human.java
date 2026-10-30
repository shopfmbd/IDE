package com.fahim.cloudide;

/** Small formatters for times and durations shown in the UI. */
final class Human {
    private Human() { }

    /** "just now", "5 min ago", "3 h ago", "2 d ago". */
    static String ago(long then, long now) {
        if (then <= 0) return "";
        long s = Math.max(0, (now - then) / 1000);
        if (s < 45) return "just now";
        if (s < 3600) return Math.max(1, Math.round(s / 60f)) + " min ago";
        if (s < 86400) return Math.round(s / 3600f) + " h ago";
        if (s < 86400L * 60) return Math.round(s / 86400f) + " d ago";
        return Math.round(s / (86400f * 30)) + " mo ago";
    }

    /** "42s", "1m 42s", "1h 05m". */
    static String dur(long ms) {
        long s = Math.max(0, Math.round(ms / 1000.0));
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m " + (s % 60) + "s";
        return (m / 60) + "h " + String.format(java.util.Locale.US, "%02d", m % 60) + "m";
    }

    /** "12 KB", "3.4 MB". */
    static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
    }
}
