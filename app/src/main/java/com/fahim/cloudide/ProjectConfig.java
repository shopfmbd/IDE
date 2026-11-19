package com.fahim.cloudide;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Per-project GitHub/build settings, stored in .cloudide/project.json (never pushed). */
final class ProjectConfig {
    final File dir;
    String owner = "", repo = "", branch = "", workflow = "build.yml", task = "assembleDebug";
    String gradle = "9.8.2", jdk = "21", compiler = "javac", lastStatus = "", preset = "auto", applied = "", artifact = "";
    boolean priv = true;
    /** true = Local build (ECJ, aapt2, D8), false = GitHub Actions. */
    boolean local = false;
    JSONObject pushed = new JSONObject();
    /** Newest first; each entry has t (start, ms), ms (duration), result, task, run (number) and url. */
    JSONArray history = new JSONArray();

    ProjectConfig(File d) { dir = d; }

    static ProjectConfig load(File d) {
        ProjectConfig c = new ProjectConfig(d);
        try {
            File f = new File(d, ".cloudide/project.json");
            if (f.exists()) {
                JSONObject j = new JSONObject(Io.read(f));
                c.owner = owner(j.optString("owner"));
                String[] rp = parseRepo(j.optString("repo"));
                c.repo = rp == null ? "" : rp[1];
                c.branch = j.optString("branch");
                c.workflow = j.optString("workflow", "build.yml");
                c.task = j.optString("task", "assembleDebug");
                c.gradle = j.optString("gradle", "9.8.2");
                c.jdk = j.optString("jdk", "21");
                c.compiler = j.optString("compiler", "javac");
                c.priv = j.optBoolean("private", true);
                c.local = j.optBoolean("local", false);
                c.lastStatus = j.optString("lastStatus");
                c.preset = j.optString("preset", "auto");
                c.applied = j.optString("applied");
                c.artifact = j.optString("artifact");
                JSONObject p = j.optJSONObject("pushed");
                if (p != null) c.pushed = p;
                JSONArray hs = j.optJSONArray("history");
                if (hs != null) c.history = hs;
            }
        } catch (Exception ignored) {
        }
        return c;
    }

    void save() {
        try {
            JSONObject j = new JSONObject();
            j.put("owner", owner).put("repo", repo).put("branch", branch).put("workflow", workflow);
            j.put("task", task).put("gradle", gradle).put("jdk", jdk).put("compiler", compiler).put("private", priv).put("local", local);
            j.put("lastStatus", lastStatus).put("pushed", pushed);
            j.put("preset", preset).put("applied", applied).put("artifact", artifact).put("history", history);
            Io.write(new File(dir, ".cloudide/project.json"), j.toString(1));
        } catch (Exception ignored) {
        }
    }

    /** Adds a build to the front of the history and keeps the latest 25. */
    void addHistory(JSONObject entry) {
        JSONArray n = new JSONArray();
        n.put(entry);
        for (int i = 0; i < history.length() && n.length() < 25; i++) n.put(history.opt(i));
        history = n;
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");

    /** Reduces "https://github.com/me", "@me" or "me" to the bare username, or "" if it is not one. */
    static String owner(String in) {
        if (in == null) return "";
        String s = stripHost(in.trim());
        int i = s.indexOf('/');
        if (i >= 0) s = s.substring(0, i);
        s = s.replaceFirst("^@", "").trim();
        return NAME.matcher(s).matches() ? s : "";
    }

    private static String stripHost(String in) {
        String s = in.replaceFirst("^git@[^:]+:", "");
        s = s.replaceFirst("(?i)^[a-z][a-z0-9+.-]*://", "");
        s = s.replaceFirst("^[^/@]*@", "");
        return s.replaceFirst("(?i)^(www\\.)?github\\.com[/:]?", "");
    }

    /**
     * Accepts "owner/name", "name", a github.com link (with .git, /tree/main, query...) or an SSH remote.
     * Returns {owner, repo} (owner may be ""), or null when it cannot be understood.
     */
    static String[] parseRepo(String in) {
        if (in == null || in.trim().isEmpty()) return null;
        String raw = in.trim();
        boolean hadHost = !stripHost(raw).equals(raw);
        String s = stripHost(raw);
        int cut = s.indexOf('?');
        if (cut >= 0) s = s.substring(0, cut);
        cut = s.indexOf('#');
        if (cut >= 0) s = s.substring(0, cut);
        List<String> parts = new ArrayList<>();
        for (String x : s.split("/")) if (!x.trim().isEmpty()) parts.add(x.trim());
        if (parts.isEmpty() || (hadHost && parts.size() < 2)) return null;
        String owner = parts.size() >= 2 ? parts.get(0).replaceFirst("^@", "") : "";
        String repo = parts.get(parts.size() >= 2 ? 1 : 0).replaceFirst("(?i)\\.git$", "");
        if (!repo.isEmpty() && NAME.matcher(repo).matches() && (owner.isEmpty() || NAME.matcher(owner).matches()))
            return new String[]{owner, repo};
        return null;
    }

    String slug() { return dir.getName().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-"); }
}
