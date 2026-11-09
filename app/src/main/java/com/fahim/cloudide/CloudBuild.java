package com.fahim.cloudide;

import android.content.Context;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Push the project, run the workflow, follow it, and fetch the APK or the error log. */
final class CloudBuild {
    interface Cb {
        void log(String s);
        void status(String s);
        void done(boolean ok, File apk, List<Diag> diags, String fullLog, String title);
    }

    final Context ctx;
    final File proj;
    final ProjectConfig cfg;
    final Cb cb;
    final boolean runBuild;
    volatile boolean running, cancelled;
    volatile long runId;
    volatile String runUrl = "";
    int runNo;
    long t0;
    Gh gh;
    String owner, repo;

    CloudBuild(Context c, File p, ProjectConfig cfg, boolean runBuild, Cb cb) {
        this.ctx = c.getApplicationContext();
        this.proj = p;
        this.cfg = cfg;
        this.runBuild = runBuild;
        this.cb = cb;
    }

    void start() {
        running = true;
        new Thread(this::run, "cloudbuild").start();
    }

    void cancel() {
        cancelled = true;
        final Gh g = gh;
        final long id = runId;
        if (g != null && id != 0) {
            new Thread(() -> {
                try { g.cancelRun(owner, repo, id); } catch (Exception ignored) { }
            }).start();
        }
    }

    /** Text printed by build.sh: everything after the "Run build.sh" step header, up to the next step. */
    private static String customOutput(String logs) {
        StringBuilder sb = new StringBuilder();
        boolean seen = false, on = false;
        for (String l : logs.split("\n")) {
            String x = l.replaceFirst(LogParser.TS, "").replace("\r", "");
            if (!on) {
                if (x.contains("bash build.sh")) seen = true;
                else if (seen && x.startsWith("##[endgroup]")) on = true;
            } else {
                if (x.startsWith("##[group]")) break;
                if (!x.startsWith("##[")) sb.append(x).append('\n');
            }
        }
        return sb.length() == 0 ? "(build.sh printed nothing)" : "--- build.sh output ---\n" + sb;
    }

    /** Checks the JDK / Gradle / Android Gradle plugin combination before anything is sent to GitHub. */
    private String preflight() {
        try {
            boolean gradle = false;
            String agp = "";
            for (String n : new String[]{"settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts"}) {
                File f = new File(proj, n);
                if (!f.exists()) continue;
                gradle = true;
                if (agp.isEmpty()) agp = Versions.findAgp(Io.read(f));
            }
            if (!gradle || !(cfg.preset.equals("gradle") || cfg.preset.equals("auto"))) return "";
            File wp = new File(proj, "gradle/wrapper/gradle-wrapper.properties");
            String wrap = wp.exists() ? Versions.wrapperVersion(Io.read(wp)) : "";
            return Versions.preflight(cfg.jdk, cfg.gradle, agp, wrap);
        } catch (Exception e) {
            return "";
        }
    }

    private void record(String result) {
        try {
            JSONObject h = new JSONObject();
            h.put("t", t0).put("ms", System.currentTimeMillis() - t0).put("result", result)
                    .put("task", runBuild ? cfg.task : "push").put("run", runNo).put("url", runUrl);
            cfg.addHistory(h);
            cfg.save();
        } catch (Exception ignored) {
        }
    }

    private void run() {
        t0 = System.currentTimeMillis();
        try {
            String token = Prefs.tokenFor(ctx, proj.getName());
            if (token.isEmpty()) throw new Exception("No GitHub token. Add one in Settings.");
            gh = new Gh(token, s -> cb.log(s));
            gh.email = Prefs.email(ctx);
            owner = cfg.owner.isEmpty() ? Prefs.owner(ctx) : cfg.owner;
            if (owner.isEmpty()) owner = gh.whoami().getString("login");
            repo = cfg.repo.isEmpty() ? cfg.slug() : cfg.repo;
            cfg.owner = owner;
            cfg.repo = repo;
            cfg.save();
            if (!new File(proj, "settings.gradle").exists() && !new File(proj, "settings.gradle.kts").exists())
                cb.log("Warning: no settings.gradle found. Is this a Gradle project?");
            Workflows.ensure(ctx, proj, cfg, s -> cb.log(s));
            String warn = preflight();
            if (!warn.isEmpty()) cb.log(warn);
            cb.status("Preparing repository");
            String branch = gh.ensureRepo(owner, repo, cfg.branch, cfg.priv);
            cb.status("Uploading");
            gh.push(proj, owner, repo, branch, cfg);
            cfg.save();
            if (!runBuild) {
                record("pushed");
                cb.done(true, null, new ArrayList<Diag>(), "", "Pushed to " + owner + "/" + repo);
                return;
            }
            cb.status("Starting workflow");
            long before = gh.latestRun(owner, repo, cfg.workflow);
            gh.dispatch(owner, repo, cfg, branch);
            JSONObject run = gh.awaitRun(owner, repo, cfg.workflow, before);
            runId = run.getLong("id");
            runNo = run.optInt("run_number");
            runUrl = run.optString("html_url");
            cb.log("Run #" + runNo + ": " + runUrl);
            if (cancelled) gh.cancelRun(owner, repo, runId);
            cb.status("Building");
            run = gh.monitor(owner, repo, runId, () -> cancelled);
            String concl = run.optString("conclusion");
            if (concl.equals("success")) {
                cb.status("Downloading APK");
                File outDir = new File(ctx.getExternalFilesDir(null), "builds/" + proj.getName());
                List<File> outs = gh.downloadOutputs(owner, repo, runId, outDir);
                File apk = null;
                for (File f : outs) {
                    cb.log("output: " + Io.rel(outDir, f) + " (" + (f.length() / 1024) + " KB)");
                    if (f.getName().endsWith(".apk") && (apk == null || f.getName().contains("debug"))) apk = f;
                }
                if (cfg.preset.equals("custom") || cfg.preset.equals("auto")) {
                    try {
                        String full = gh.failureLog(owner, repo, runId);
                        String sum = Detect.summary(full);
                        cb.log(cfg.preset.equals("auto") && !sum.isEmpty() ? sum : customOutput(full));
                    } catch (Exception ignored) {
                    }
                }
                cfg.lastStatus = "success";
                record("success");
                cb.log("Finished in " + Human.dur(System.currentTimeMillis() - t0));
                cb.done(true, apk, new ArrayList<Diag>(), "", apk != null ? "Build succeeded"
                        : "Build succeeded: " + outs.size() + " output file(s) saved");
            } else {
                cb.status("Reading log");
                String logs = gh.failureLog(owner, repo, runId);
                cfg.lastStatus = "failed";
                record(concl.equals("cancelled") ? "cancelled" : "failed");
                cb.log("Finished in " + Human.dur(System.currentTimeMillis() - t0));
                String sum = Detect.summary(logs);
                if (!sum.isEmpty()) cb.log(sum);
                String tip = Hints.forLog(logs, cfg.jdk, cfg.gradle);
                if (!tip.isEmpty()) cb.log(tip);
                cb.done(false, null, LogParser.parse(logs), logs, "Build " + concl);
            }
        } catch (Exception e) {
            cb.log("ERROR: " + e.getMessage());
            record(cancelled ? "cancelled" : "error");
            cb.done(false, null, new ArrayList<Diag>(), "", cancelled ? "Cancelled" : "Failed");
        } finally {
            running = false;
        }
    }
}
