package com.fahim.cloudide;

import android.content.Context;
import java.io.File;
import java.io.IOException;

/** Build types: each one is a GitHub Actions workflow stored in assets/templates/workflows. */
final class Workflows {
    static final String[] PRESETS = {"auto", "gradle", "rust", "flutter", "node", "custom"};

    static boolean valid(String p) {
        for (String s : PRESETS) if (s.equals(p)) return true;
        return false;
    }

    static void write(Context c, File proj, ProjectConfig cfg) throws IOException {
        String id = valid(cfg.preset) ? cfg.preset : "auto";
        String text = new String(Io.bytes(c.getAssets().open("templates/workflows/" + id + ".yml")), "UTF-8");
        Io.write(new File(proj, ".github/workflows/" + cfg.workflow), text);
        cfg.applied = id;
        cfg.save();
    }

    static void ensure(Context c, File proj, ProjectConfig cfg, Gh.Log log) throws IOException {
        File w = new File(proj, ".github/workflows/" + cfg.workflow);
        String id = valid(cfg.preset) ? cfg.preset : "auto";
        if (!w.exists()) {
            log.log("Adding the " + id + " workflow (.github/workflows/" + cfg.workflow + ")");
            write(c, proj, cfg);
        } else if (cfg.applied.isEmpty()) {
            // First build of an imported project. Never overwrite a workflow CloudIDE did not write.
            String kind = Detect.markerKind(Io.read(w));
            if (kind.isEmpty() && !cfg.workflow.equals("cloudide.yml")) {
                log.log("This project has its own " + cfg.workflow + ". Leaving it alone and using .github/workflows/cloudide.yml for CloudIDE builds");
                cfg.workflow = "cloudide.yml";
                write(c, proj, cfg);
            } else if (kind.equals(id)) {
                cfg.applied = id;
                cfg.save();
            } else {
                log.log("Setting up the " + id + " workflow");
                write(c, proj, cfg);
            }
        } else if (!cfg.applied.equals(id)) {
            log.log("Switching the workflow to the " + id + " build type");
            write(c, proj, cfg);
        }
    }
}
