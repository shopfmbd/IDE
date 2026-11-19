package com.fahim.cloudide;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import java.io.File;

final class Prefs {
    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("cloudide", Context.MODE_PRIVATE);
    }

    static boolean bool(Context c, String k, boolean d) { return sp(c).getBoolean(k, d); }

    static void put(Context c, String k, boolean v) { sp(c).edit().putBoolean(k, v).apply(); }

    static String str(Context c, String k) { return sp(c).getString(k, ""); }

    static void put(Context c, String k, String v) { sp(c).edit().putString(k, v).apply(); }

    static String token(Context c) { return Secure.dec(str(c, "token")); }

    static void setToken(Context c, String t) { put(c, "token", t == null || t.isEmpty() ? "" : Secure.enc(t)); }

    static String projectToken(Context c, String p) { return Secure.dec(str(c, "pt_" + p)); }

    static void setProjectToken(Context c, String p, String t) { put(c, "pt_" + p, t == null || t.isEmpty() ? "" : Secure.enc(t)); }

    static String tokenFor(Context c, String project) {
        String t = projectToken(c, project);
        return t.isEmpty() ? token(c) : t;
    }

    /** Name of the repository that holds the IDE project and its workflows. Set in Settings > GitHub account. */
    static String ideRepo(Context c) {
        String r = str(c, "ide_repo").trim();
        return r.isEmpty() ? "IDE" : r;
    }

    static String owner(Context c) { return ProjectConfig.owner(str(c, "owner")); }

    /** Email used as the author of commits pushed from the app. Empty = let GitHub use the account default. */
    static String email(Context c) { return str(c, "email").trim(); }

    static void touch(Context c, String project) { sp(c).edit().putLong("opened_" + project, System.currentTimeMillis()).apply(); }

    static long opened(Context c, String project) { return sp(c).getLong("opened_" + project, 0L); }

    /** Per-project keys, all named after the project folder. */
    private static final String[] PROJECT_KEYS = {"pt_", "opened_", "last_", "plug_skip_"};

    /** Moves a project's own token, last-opened time and last file to its new name, so a rename loses nothing. */
    static void renameProject(Context c, String from, String to) {
        SharedPreferences p = sp(c);
        SharedPreferences.Editor e = p.edit();
        for (String k : PROJECT_KEYS) {
            if (!p.contains(k + from)) continue;
            Object v = p.getAll().get(k + from);
            if (v instanceof String) e.putString(k + to, (String) v);
            else if (v instanceof Long) e.putLong(k + to, (Long) v);
            e.remove(k + from);
        }
        e.apply();
    }

    /** Forgets everything stored for a deleted project. Without this a new project with the same name would inherit its token. */
    static void forgetProject(Context c, String name) {
        SharedPreferences.Editor e = sp(c).edit();
        for (String k : PROJECT_KEYS) e.remove(k + name);
        e.apply();
    }

    static float font(Context c) { return sp(c).getFloat("font", 14f); }

    static int tab(Context c) { return sp(c).getInt("tab", 4); }

    static File root(Context c) {
        String p = str(c, "root");
        File d;
        if (!p.isEmpty()) d = new File(p);
        else if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())
            d = new File(Environment.getExternalStorageDirectory(), "AppProjects");
        else d = new File(c.getExternalFilesDir(null), "projects");
        d.mkdirs();
        return d;
    }
}
