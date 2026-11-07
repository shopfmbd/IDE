package com.fahim.cloudide;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/**
 * Where libraries may be downloaded from, and the options around it (App settings > Libraries).
 * A library is downloaded once into the cache (LocalDeps.cacheDir) and every later build of every project reuses
 * that file without touching the network.
 */
final class Repos {
    /** One built-in repository: id (used in the preference key), title, address, on by default, short hint. */
    static final class Repo {
        final String id, name, url, hint;
        final boolean def;

        Repo(String id, String name, String url, boolean def, String hint) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.def = def;
            this.hint = hint;
        }
    }

    /** The order here is the search order. JitPack is last: it is slow and only needed for com.github.* style libraries. */
    static final Repo[] BUILT_IN = {
            new Repo("google", "Google Maven", MavenResolver.GOOGLE, true, "AndroidX, Material, Firebase, Play services"),
            new Repo("central", "Maven Central", MavenResolver.CENTRAL, true, "Most Java, Kotlin and Android libraries"),
            new Repo("jitpack", "JitPack", "https://jitpack.io/", true, "Libraries published straight from GitHub, GitLab or Bitbucket (com.github.*)"),
            new Repo("gradleportal", "Gradle Plugin Portal", "https://plugins.gradle.org/m2/", false, "Gradle plugins and their libraries"),
            new Repo("centralmirror", "Maven Central mirror (Google Cloud)", "https://maven-central.storage-download.googleapis.com/maven2/", false, "Use when repo1.maven.org is slow or blocked"),
            new Repo("aliyun", "Aliyun public mirror", "https://maven.aliyun.com/repository/public/", false, "Mirror of Central, useful in China"),
            new Repo("clojars", "Clojars", "https://repo.clojars.org/", false, "JVM libraries published by the Clojure community"),
            new Repo("jboss", "JBoss", "https://repository.jboss.org/nexus/content/groups/public/", false, "Red Hat and JBoss libraries"),
            new Repo("huawei", "Huawei", "https://developer.huawei.com/repo/", false, "HMS Core and other Huawei libraries"),
    };

    private Repos() { }

    // ------------------------------------------------------------------ options

    static boolean enabled(Context c, Repo r) { return Prefs.bool(c, "repo_on_" + r.id, r.def); }

    /** Never touch the network: only libraries already in the cache are used. */
    static boolean offline(Context c) { return Prefs.bool(c, "lib_offline", false); }

    /** Also search maven { url "..." } repositories written in the project's own build files. */
    static boolean useProjectRepos(Context c) { return Prefs.bool(c, "repo_project", true); }

    /** JitPack builds a library on the first request, which can take minutes: wait for it instead of failing at 60 seconds. */
    static boolean waitForJitpack(Context c) { return Prefs.bool(c, "repo_jitpack_wait", true); }

    // ------------------------------------------------------------------ custom repositories

    static List<String> custom(Context c) {
        List<String> out = new ArrayList<>();
        for (String s : Prefs.str(c, "repo_custom").split("\n")) {
            String u = normalize(s);
            if (u != null && !out.contains(u)) out.add(u);
        }
        return out;
    }

    private static void saveCustom(Context c, List<String> l) { Prefs.put(c, "repo_custom", join(l)); }

    /** Adds a repository address. Returns null when it was added, otherwise the reason it was refused. */
    static String addCustom(Context c, String in) {
        String u = normalize(in);
        if (u == null) return "Enter a full address that starts with https:// (or http://).";
        for (Repo r : BUILT_IN) if (r.url.equals(u)) return r.name + " is already in the list above. Turn it on there.";
        List<String> l = custom(c);
        if (l.contains(u)) return "That repository is already added.";
        l.add(u);
        saveCustom(c, l);
        return null;
    }

    static void removeCustom(Context c, String url) {
        List<String> l = custom(c);
        l.remove(url);
        saveCustom(c, l);
    }

    /** "https://host/path" with exactly one trailing slash, or null when it is not a web address. */
    static String normalize(String s) {
        if (s == null) return null;
        s = s.trim();
        if (!(s.startsWith("https://") || s.startsWith("http://"))) return null;
        if (s.length() < 10 || s.contains(" ") || s.contains("\n") || s.indexOf('"') >= 0) return null;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s + "/";
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() == 0 ? "" : "\n").append(s);
        return sb.toString();
    }

    // ------------------------------------------------------------------ what the resolver gets

    /**
     * The full search order: repositories written in the project's build files first (when allowed), then the
     * built-in ones that are turned on, then the user's own. No repository appears twice.
     */
    static List<String> searchList(Context c, List<String> fromProject) {
        List<String> out = new ArrayList<>();
        if (useProjectRepos(c)) for (String p : fromProject) add(out, p);
        for (Repo r : BUILT_IN) if (enabled(c, r)) add(out, r.url);
        for (String u : custom(c)) add(out, u);
        return out;
    }

    private static void add(List<String> l, String url) {
        String u = normalize(url);
        if (u != null && !l.contains(u)) l.add(u);
    }

    /** Short description for the settings page: how many repositories are in use. */
    static String summary(Context c) {
        int n = 0;
        for (Repo r : BUILT_IN) if (enabled(c, r)) n++;
        n += custom(c).size();
        return n + (n == 1 ? " repository" : " repositories") + (offline(c) ? ", offline mode on" : "");
    }
}
