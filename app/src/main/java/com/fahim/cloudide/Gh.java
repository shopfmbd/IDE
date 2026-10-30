package com.fahim.cloudide;

import android.util.Base64;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** GitHub REST client: repos, incremental push via the Git Data API, workflow dispatch, artifacts, logs. */
final class Gh {
    interface Log { void log(String s); }
    interface Cancel { boolean is(); }

    static final class HttpEx extends IOException {
        final int code;
        HttpEx(int c, String m) { super(m); code = c; }
    }

    final String token;
    final Log log;
    /** Optional commit author email (Settings > GitHub). Name is the repository owner. */
    String email = "";

    Gh(String token, Log log) {
        this.token = token;
        this.log = log;
    }

    // ------------------------------------------------------------ http
    HttpURLConnection conn(String url, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "CloudIDE");
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(false);
        return c;
    }

    private static String explain(int code, String body, String method, String path) {
        String m = body;
        try { m = new JSONObject(body).optString("message", body); } catch (Exception ignored) { }
        if (code == 401) return "GitHub rejected the token (401 " + m + "). Update it in Settings.";
        if (code == 403) return "Access denied (403): " + m + ". The token may lack the repo or workflow scope, or you hit a rate limit.";
        if (code == 404) return "Not found (404) for " + method + " " + path.replaceAll("\\?.*", "") + ". Check the repository name and that the token can access it.";
        return code + ": " + m;
    }

    private String rawOnce(String method, String path, JSONObject body) throws IOException {
        HttpURLConnection c = conn(path.startsWith("http") ? path : "https://api.github.com" + path, method);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.toString().getBytes("UTF-8"));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String s = in == null ? "" : new String(Io.bytes(in), "UTF-8");
        if (code >= 400) throw new HttpEx(code, explain(code, s, method, path));
        return s;
    }

    String raw(String method, String path, JSONObject body) throws IOException {
        for (int i = 0; ; i++) {
            try {
                return rawOnce(method, path, body);
            } catch (HttpEx e) {
                if (i >= 2 || (e.code < 500 && e.code != 429)) throw e;
            } catch (IOException e) {
                if (i >= 2) throw e;
            }
            sleep(1500L * (i + 1));
        }
    }

    JSONObject obj(String method, String path, JSONObject body) throws Exception {
        String s = raw(method, path, body);
        return s.trim().isEmpty() ? new JSONObject() : new JSONObject(s);
    }

    static void sleep(long ms) throws IOException {
        try { Thread.sleep(ms); } catch (InterruptedException e) { throw new IOException("Interrupted"); }
    }

    byte[] download(String url) throws Exception {
        HttpURLConnection c = conn(url, "GET");
        int code = c.getResponseCode();
        if (code >= 300 && code < 400) {
            String next = c.getHeaderField("Location");
            if (next == null) throw new HttpEx(code, "Download failed: redirect without a target");
            c = (HttpURLConnection) new URL(next).openConnection(); // a new connection on purpose: the token must not follow the redirect
            c.setReadTimeout(120000);
            code = c.getResponseCode();
        }
        if (code >= 400) throw new HttpEx(code, "Download failed (" + code + ")");
        return Io.bytes(c.getInputStream());
    }

    // ------------------------------------------------------------ account + repos
    JSONObject whoami() throws Exception { return obj("GET", "/user", null); }

    String scopes() throws IOException {
        HttpURLConnection c = conn("https://api.github.com/user", "GET");
        int code = c.getResponseCode();
        if (code >= 400) throw new HttpEx(code, explain(code, "", "GET", "/user"));
        String s = c.getHeaderField("X-OAuth-Scopes");
        Io.bytes(c.getInputStream());
        return s == null ? "" : s;
    }

    /** Up to 500 repositories the account can reach, most recently updated first (the API returns 100 per page). */
    JSONArray repos() throws Exception {
        JSONArray all = new JSONArray();
        for (int page = 1; page <= 5; page++) {
            JSONArray part = new JSONArray(raw("GET", "/user/repos?per_page=100&sort=updated&page=" + page, null));
            for (int i = 0; i < part.length(); i++) all.put(part.get(i));
            if (part.length() < 100) break;
        }
        return all;
    }

    byte[] zipball(String owner, String repo, String ref) throws Exception {
        return download("https://api.github.com/repos/" + owner + "/" + repo + "/zipball" + (ref.isEmpty() ? "" : "/" + ref));
    }

    /** Makes sure the repository exists (creating it if needed) and returns the branch to use. */
    String ensureRepo(String owner, String repo, String want, boolean priv) throws Exception {
        JSONObject r;
        try {
            r = obj("GET", "/repos/" + owner + "/" + repo, null);
        } catch (HttpEx e) {
            if (e.code != 404) throw e;
            log.log("Creating " + (priv ? "private" : "public") + " repository " + owner + "/" + repo);
            JSONObject b = new JSONObject().put("name", repo).put("private", priv).put("auto_init", true);
            String login = whoami().getString("login");
            obj("POST", owner.equalsIgnoreCase(login) ? "/user/repos" : "/orgs/" + owner + "/repos", b);
            sleep(2500);
            r = obj("GET", "/repos/" + owner + "/" + repo, null);
        }
        return want.isEmpty() ? r.getString("default_branch") : want;
    }

    private String ref(String rp, String branch) throws Exception {
        return obj("GET", rp + "/git/ref/heads/" + branch, null).getJSONObject("object").getString("sha");
    }

    /** Head commit of branch; creates the first commit for empty repos and the branch if missing. */
    private String head(String rp, String branch) throws Exception {
        try {
            return ref(rp, branch);
        } catch (HttpEx e) {
            if (e.code != 404 && e.code != 409) throw e;
        }
        String def = obj("GET", rp, null).getString("default_branch");
        String base;
        try {
            base = ref(rp, def);
        } catch (HttpEx e) {
            if (e.code != 404 && e.code != 409) throw e;
            log.log("Repository is empty, creating the first commit");
            obj("PUT", rp + "/contents/README.md", new JSONObject().put("message", "Initial commit")
                    .put("content", Base64.encodeToString("# Project\n".getBytes(), Base64.NO_WRAP)));
            base = ref(rp, def);
        }
        if (!branch.equals(def)) {
            obj("POST", rp + "/git/refs", new JSONObject().put("ref", "refs/heads/" + branch).put("sha", base));
            log.log("Created branch " + branch);
        }
        return base;
    }

    // ------------------------------------------------------------ push
    String push(File dir, String owner, String repo, String branch, ProjectConfig cfg) throws Exception {
        final String rp = "/repos/" + owner + "/" + repo;
        String parent = head(rp, branch);
        String baseTree = obj("GET", rp + "/git/commits/" + parent, null).getJSONObject("tree").getString("sha");
        Map<String, String> remote = new HashMap<>();
        JSONArray ta = obj("GET", rp + "/git/trees/" + baseTree + "?recursive=1", null).getJSONArray("tree");
        for (int i = 0; i < ta.length(); i++) {
            JSONObject t = ta.getJSONObject(i);
            if ("blob".equals(t.optString("type"))) remote.put(t.getString("path"), t.getString("sha"));
        }
        List<File> files = Io.projectFiles(dir);
        final String base = dir.getAbsolutePath() + "/";
        Map<String, String> local = new LinkedHashMap<>();
        List<String> need = new ArrayList<>();
        log.log("Scanning " + files.size() + " files");
        for (File f : files) {
            String rel = f.getAbsolutePath().substring(base.length());
            String sha = Io.sha(f);
            local.put(rel, sha);
            if (!sha.equals(remote.get(rel))) need.add(rel);
        }
        if (local.equals(remote)) {
            log.log("No changes to push, using the latest commit");
            cfg.pushed = toJson(local);
            return parent;
        }
        if (!need.isEmpty()) {
            final AtomicInteger done = new AtomicInteger();
            final int total = need.size();
            ExecutorService ex = Executors.newFixedThreadPool(6);
            List<Future<Object>> fut = new ArrayList<>();
            for (String rel : need) {
                final String r = rel;
                fut.add(ex.submit(() -> {
                    obj("POST", rp + "/git/blobs", new JSONObject().put("encoding", "base64")
                            .put("content", Base64.encodeToString(Io.bytes(new FileInputStream(new File(base + r))), Base64.NO_WRAP)));
                    log.log("upload " + r + " (" + done.incrementAndGet() + "/" + total + ")");
                    return null;
                }));
            }
            try {
                for (Future<Object> f : fut) f.get();
            } catch (ExecutionException e) {
                ex.shutdownNow();
                Throwable c = e.getCause();
                throw c instanceof Exception ? (Exception) c : e;
            } finally {
                ex.shutdown();
            }
        }
        JSONArray tree = new JSONArray();
        for (Map.Entry<String, String> en : local.entrySet()) {
            String name = en.getKey();
            boolean exec = name.equals("gradlew") || name.endsWith(".sh");
            tree.put(new JSONObject().put("path", name).put("mode", exec ? "100755" : "100644").put("type", "blob").put("sha", en.getValue()));
        }
        String t = obj("POST", rp + "/git/trees", new JSONObject().put("tree", tree)).getString("sha");
        JSONObject commit = new JSONObject().put("message", "Update from CloudIDE")
                .put("tree", t).put("parents", new JSONArray().put(parent));
        if (!email.isEmpty()) {
            JSONObject who = new JSONObject().put("name", owner).put("email", email);
            commit.put("author", who).put("committer", who);
        }
        String c = obj("POST", rp + "/git/commits", commit).getString("sha");
        obj("PATCH", rp + "/git/refs/heads/" + branch, new JSONObject().put("sha", c));
        log.log("Pushed " + c.substring(0, 7) + " (" + need.size() + " changed files)");
        cfg.pushed = toJson(local);
        return c;
    }

    private static JSONObject toJson(Map<String, String> m) throws Exception {
        JSONObject j = new JSONObject();
        for (Map.Entry<String, String> e : m.entrySet()) j.put(e.getKey(), e.getValue());
        return j;
    }

    // ------------------------------------------------------------ actions
    long latestRun(String owner, String repo, String wf) {
        try {
            JSONArray a = obj("GET", "/repos/" + owner + "/" + repo + "/actions/workflows/" + wf + "/runs?per_page=1", null)
                    .getJSONArray("workflow_runs");
            return a.length() == 0 ? 0 : a.getJSONObject(0).getLong("id");
        } catch (Exception e) {
            return 0;
        }
    }

    void dispatch(String owner, String repo, ProjectConfig c, String branch) throws Exception {
        String path = "/repos/" + owner + "/" + repo + "/actions/workflows/" + c.workflow + "/dispatches";
        JSONObject inputs = new JSONObject().put("task", c.task).put("gradle", c.gradle).put("jdk", c.jdk);
        String art = c.artifact.isEmpty() ? Tasks.artifactFor(c.task) : c.artifact;
        if (!art.isEmpty()) inputs.put("artifact", art);
        // only the auto workflow knows this input; sending it elsewhere would make GitHub reject the whole dispatch
        if (c.preset.equals("auto") && !c.compiler.equals("javac")) inputs.put("compiler", c.compiler);
        for (int i = 0; ; i++) {
            try {
                try {
                    raw("POST", path, new JSONObject().put("ref", branch).put("inputs", inputs));
                } catch (HttpEx e) {
                    if (e.code != 422) throw e;
                    raw("POST", path, new JSONObject().put("ref", branch));
                }
                log.log("Workflow started");
                return;
            } catch (HttpEx e) {
                if (e.code != 404 || i >= 8) throw e;
                sleep(4000);
            }
        }
    }

    JSONObject awaitRun(String owner, String repo, String wf, long before) throws Exception {
        String path = "/repos/" + owner + "/" + repo + "/actions/workflows/" + wf + "/runs?event=workflow_dispatch&per_page=5";
        for (int i = 0; i < 45; i++) {
            try {
                JSONArray runs = obj("GET", path, null).getJSONArray("workflow_runs");
                JSONObject best = null;
                for (int k = 0; k < runs.length(); k++) {
                    JSONObject r = runs.getJSONObject(k);
                    if (r.getLong("id") > before && (best == null || r.getLong("id") > best.getLong("id"))) best = r;
                }
                if (best != null) return best;
            } catch (HttpEx e) {
                if (e.code != 404) throw e;
            }
            sleep(2000);
        }
        throw new IOException("The workflow did not start. Check that " + wf + " exists on the branch and allows manual runs (workflow_dispatch).");
    }

    JSONObject monitor(String owner, String repo, long id, Cancel cancel) throws Exception {
        String rp = "/repos/" + owner + "/" + repo;
        Set<String> seen = new HashSet<>();
        while (true) {
            if (cancel.is()) throw new IOException("Cancelled");
            JSONObject run = obj("GET", rp + "/actions/runs/" + id, null);
            try {
                JSONArray jobs = obj("GET", rp + "/actions/runs/" + id + "/jobs", null).getJSONArray("jobs");
                for (int i = 0; i < jobs.length(); i++) {
                    JSONArray st = jobs.getJSONObject(i).optJSONArray("steps");
                    if (st == null) continue;
                    for (int j = 0; j < st.length(); j++) {
                        JSONObject s = st.getJSONObject(j);
                        String name = s.optString("name"), status = s.optString("status"), concl = s.optString("conclusion");
                        if (seen.add(name + "|" + status + "|" + concl)) {
                            if (status.equals("in_progress")) log.log("\u25B6 " + name);
                            else if (status.equals("completed"))
                                log.log((concl.equals("success") ? "\u2713 " : concl.equals("skipped") ? "- " : "\u2717 ") + name);
                        }
                    }
                }
            } catch (HttpEx ignored) {
            }
            if (run.optString("status").equals("completed")) return run;
            sleep(4000);
        }
    }

    void cancelRun(String owner, String repo, long id) throws Exception {
        raw("POST", "/repos/" + owner + "/" + repo + "/actions/runs/" + id + "/cancel", null);
    }

    List<File> downloadOutputs(String owner, String repo, long runId, File dir) throws Exception {
        String rp = "/repos/" + owner + "/" + repo;
        JSONArray a = new JSONArray();
        for (int i = 0; i < 6 && a.length() == 0; i++) {
            a = obj("GET", rp + "/actions/runs/" + runId + "/artifacts", null).getJSONArray("artifacts");
            if (a.length() == 0) sleep(2500);
        }
        if (a.length() == 0)
            throw new IOException("The build finished but produced no output files. Check the output pattern in the project settings.");
        byte[] z = download(a.getJSONObject(0).getString("archive_download_url"));
        Io.delete(dir);
        dir.mkdirs();
        String base = dir.getCanonicalPath() + File.separator;
        List<File> out = new ArrayList<>();
        ZipInputStream zi = new ZipInputStream(new ByteArrayInputStream(z));
        ZipEntry e;
        while ((e = zi.getNextEntry()) != null) {
            if (e.isDirectory()) continue;
            File f = new File(dir, e.getName());
            if (!f.getCanonicalPath().startsWith(base)) continue;
            f.getParentFile().mkdirs();
            try (FileOutputStream o = new FileOutputStream(f)) {
                byte[] b = new byte[8192];
                int r;
                while ((r = zi.read(b)) > 0) o.write(b, 0, r);
            }
            out.add(f);
        }
        zi.close();
        if (out.isEmpty()) throw new IOException("The output artifact was empty");
        return out;
    }

    String failureLog(String owner, String repo, long runId) throws Exception {
        String rp = "/repos/" + owner + "/" + repo;
        JSONArray jobs = obj("GET", rp + "/actions/runs/" + runId + "/jobs", null).getJSONArray("jobs");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < jobs.length(); i++) {
            long id = jobs.getJSONObject(i).getLong("id");
            sb.append(new String(download("https://api.github.com" + rp + "/actions/jobs/" + id + "/logs"), "UTF-8"));
        }
        return sb.toString();
    }
}
