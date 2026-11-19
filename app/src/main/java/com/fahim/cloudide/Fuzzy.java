package com.fahim.cloudide;

import java.util.Locale;

/** Subsequence matcher for Quick Open: "mainact" finds app/src/main/java/.../MainActivity.java. */
final class Fuzzy {
    private Fuzzy() { }

    /** Returns -1 when the query does not match, otherwise a score (higher is better). */
    static int score(String query, String path) {
        if (query.isEmpty()) return 0;
        String q = query.toLowerCase(Locale.ROOT);
        String t = path.toLowerCase(Locale.ROOT);
        int base = t.lastIndexOf('/') + 1;
        int best = -1;
        int inName = run(q, path, t, base);
        if (inName >= 0) best = inName + 60; // matches inside the file name beat matches in folders
        int whole = run(q, path, t, 0);
        if (whole > best) best = whole;
        return best;
    }

    private static int run(String q, String orig, String t, int from) {
        int score = 0, qi = 0, prev = -2;
        for (int i = from; i < t.length() && qi < q.length(); i++) {
            if (t.charAt(i) != q.charAt(qi)) continue;
            int s = 10;
            if (i == prev + 1) s += 16; // consecutive characters
            if (i == from) s += 14;
            else {
                char p = orig.charAt(i - 1), c = orig.charAt(i);
                if (!Character.isLetterOrDigit(p)) s += 12; // after / . _ -
                else if (Character.isLowerCase(p) && Character.isUpperCase(c)) s += 10; // camelCase hump
            }
            if (prev >= 0 && i - prev > 1) s -= Math.min(8, i - prev - 1);
            score += s;
            prev = i;
            qi++;
        }
        return qi == q.length() ? Math.max(1, score) : -1;
    }
}
