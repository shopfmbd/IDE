package com.fahim.cloudide;

import android.content.Context;
import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Creates a project from a template. Used by the home screen's "New project" and by the AI assistant, so both make exactly the same files.
 * {@code template} is the index into {@link AiActions#TEMPLATE_IDS}: java, kotlin, compose, cpp, rust, flutter, custom, local.
 */
final class ProjectFactory {
    private ProjectFactory() { }

    /**
     * Makes the project folder under the projects folder and writes the template, the project settings and the build workflow.
     * Throws IllegalArgumentException with a message a person can read when the name or package is not allowed or the project exists;
     * anything else that goes wrong removes the half-made folder and is thrown as it is.
     */
    static File create(Context c, int t, String name, String pkg) throws Exception { return create(c, t, name, pkg, null); }

    /** appName is the name shown under the app icon; null or empty uses the project name. */
    static File create(Context c, int t, String name, String pkg, String appName) throws Exception {
        if (appName == null || appName.trim().isEmpty()) appName = name;
        appName = appName.trim();
        if (appName.length() > 60 || !appName.matches("[^<>&\"'\\\\$\\r\\n]+"))
            throw new IllegalArgumentException("The app name must be under 60 characters and cannot contain < > & quotes, backslash or $");
        if (t < 0 || t >= AiActions.TEMPLATE_IDS.length) throw new IllegalArgumentException("Unknown template");
        if (!AiActions.validName(name) || !AiActions.validPackage(pkg))
            throw new IllegalArgumentException("Use letters and digits for the name, and a package like com.example.app (no Java keywords)");
        File dir = new File(Prefs.root(c), name);
        if (dir.exists()) throw new IllegalArgumentException("A project with that name already exists");
        try {
            Map<String, String> vars = new HashMap<>();
            vars.put("__PKG__", pkg);
            vars.put("__NAME__", name);
            vars.put("__APP__", appName);
            vars.put("__PKGJNI__", pkg.replace("_", "_1").replace('.', '_'));
            String snake = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
            if (snake.isEmpty() || Character.isDigit(snake.charAt(0))) snake = "app_" + snake;
            vars.put("__SNAKE__", snake);
            vars.put("__ORG__", pkg.substring(0, pkg.lastIndexOf('.')));
            String preset = "gradle";
            if (t <= 4 || t == 7) Io.template(c, "templates/common", "", dir, vars, pkg);
            if (t == 2) Io.template(c, "templates/compose", "", dir, vars, pkg);
            else if (t <= 4) {
                Io.template(c, "templates/views", "", dir, vars, pkg);
                String lang = t == 0 ? "java" : t == 1 ? "kotlin" : t == 3 ? "cpp" : "rust";
                Io.template(c, "templates/" + lang, "", dir, vars, pkg);
                if (t == 4) preset = "rust";
            } else if (t == 5) {
                Io.template(c, "templates/flutter", "", dir, vars, pkg);
                preset = "flutter";
            } else if (t == 7) {
                Io.template(c, "templates/local", "", dir, vars, pkg);
            } else {
                Io.template(c, "templates/custom", "", dir, vars, pkg);
                preset = "custom";
            }
            ProjectConfig pc = new ProjectConfig(dir);
            pc.preset = preset;
            pc.local = t == 7;
            Workflows.write(c, dir, pc);
            return dir;
        } catch (Exception e) {
            AppLog.e("Projects", "Creating project " + name + " failed", e);
            Io.delete(dir);
            throw e;
        }
    }
}
