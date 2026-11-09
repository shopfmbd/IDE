package com.fahim.cloudide;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Does what the assistant's {@link AiActions} ask: projects, project and app settings, commands, files, language plugins and builds.
 * {@link #run} is called on a background thread, one action at a time; whatever has to happen on a screen goes through the {@link Link}
 * of the host (the editor, or the standalone assistant screen) on the UI thread.
 *
 * <p>Limits that hold whatever the model asks: file paths go through {@link AiProtocol#cleanPath} (inside the project, no build output,
 * no credential files, no binaries), a file is never written with placeholder text, the assistant's own provider settings, the tokens and
 * its access mode are not reachable, deleting a project moves it to a trash folder, and commands stop after a timeout.
 */
final class AgentTools {
    /** What the host lets the tools do with its own screens. Every method is called on the UI thread. */
    interface Link {
        /** The project the host is working in, or null. */
        File project();

        /** True when a live editor holds {@link #project}: file changes then go through it, so undo, tabs and the tree stay right. */
        boolean editor();

        /** The editor's own settings object for the open project, or null. Changing the file on disk would be overwritten by it. */
        ProjectConfig config();

        /** Called after project settings changed, so the screen can show them. */
        void configChanged();

        /** Called after app settings changed, so the editor can apply them to what is on screen. */
        void settingsChanged();

        /** Called after language plugins were installed or removed, so the terminal sees the new PATH. */
        void pluginsChanged();

        /** Current text of a project file (unsaved edits included). Null when it does not exist, {@link AiEdits#UNREADABLE} when it cannot be read. */
        String readFile(String rel);

        /** Each returns null on success, else why not. */
        String writeFile(String rel, String text);

        String removeFile(String rel);

        String renameFile(String from, String to);

        String showFile(String rel, int line);

        /** Starts a build. Null when it started, else why it could not. */
        String startBuild(boolean run);

        boolean building();

        /** Goes up every time a build ends. */
        int buildCount();

        boolean buildOk();

        /** A summary of the last build: its state, the problems and the end of the log. */
        String buildReport();
    }

    private final Context app;
    private final Link link;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private volatile Shell running;
    private volatile SdkCmd.Session runningSdk;

    private final SandboxTools sandbox;

    AgentTools(Context c, Link link) {
        this.app = c.getApplicationContext();
        this.link = link;
        this.sandbox = new SandboxTools(this.app, this);
    }

    /** The sandbox the assistant works in (for the chat's upload button). */
    SandboxTools sandbox() { return sandbox; }

    /** The named project, else the open one. */
    File currentProject(String name) {
        if (name != null && !name.trim().isEmpty()) return namedProject(name);
        File p = link.project();
        if (p == null) throw new IllegalArgumentException("no project is open: pass \"project\", or use open_project first");
        return p;
    }

    /** Call before a new batch of actions. */
    void reset() { cancelled = false; }

    /** Stops the command that is running and the actions that have not started. */
    void cancel() {
        cancelled = true;
        Shell s = running;
        if (s != null) s.kill();
        SdkCmd.Session k = runningSdk;
        if (k != null) k.cancelled = true;
    }

    boolean cancelled() { return cancelled; }

    // ------------------------------------------------------------ dispatch
    AiActions.Result run(AiActions.Action a) {
        if (cancelled) return AiActions.Result.fail(a, "not run: stopped by the person");
        try {
            switch (a.name) {
                case "get_state": return getState(a);
                case "list_projects": return listProjects(a);
                case "create_project": return createProject(a);
                case "open_project": return openProject(a);
                case "delete_project": return deleteProject(a);
                case "get_project_settings": return getProjectSettings(a);
                case "set_project_settings": return setProjectSettings(a);
                case "regenerate_workflow": return regenerateWorkflow(a);
                case "get_app_settings": return getAppSettings(a);
                case "set_app_settings": return setAppSettings(a);
                case "run_command": return runCommand(a);
                case "plugins": return plugins(a);
                case "list_files": return listFiles(a);
                case "read_file": return readFile(a);
                case "search_files": return searchFiles(a);
                case "read_folder": return readFolder(a);
                case "write_file": return writeFile(a);
                case "delete_file": return deleteFile(a);
                case "restore_file": return restoreFile(a);
                case "rename_file": return renameFile(a);
                case "open_file": return openFile(a);
                case "build": return build(a);
                default:
                    if (SandboxTools.handles(a.name)) return sandbox.run(a);
                    return AiActions.Result.fail(a, "unknown action " + a.name);
            }
        } catch (IllegalArgumentException e) {
            return AiActions.Result.fail(a, String.valueOf(e.getMessage()));
        } catch (Throwable e) {
            AppLog.e("Agent", a.name + " failed", e);
            return AiActions.Result.fail(a, "failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private static AiActions.Result ok(AiActions.Action a, String t) { return AiActions.Result.ok(a, t); }

    private static AiActions.Result fail(AiActions.Action a, String t) { return AiActions.Result.fail(a, t); }

    // ------------------------------------------------------------ helpers
    /** Runs on the UI thread and waits for the answer. */
    private <T> T onUi(final Callable<T> c) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return c.call();
        final AtomicReference<Object> res = new AtomicReference<>();
        final AtomicReference<Throwable> err = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    res.set(c.call());
                } catch (Throwable e) {
                    err.set(e);
                } finally {
                    done.countDown();
                }
            }
        });
        if (!done.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("the app did not answer in time");
        Throwable t = err.get();
        if (t != null) {
            if (t instanceof Exception) throw (Exception) t;
            throw new RuntimeException(t);
        }
        @SuppressWarnings("unchecked")
        T r = (T) res.get();
        return r;
    }

    private File root() { return Prefs.root(app); }

    private File namedProject(String n) {
        String name = n.trim();
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.startsWith("."))
            throw new IllegalArgumentException("\"" + n + "\" is not a project name (list_projects shows the names)");
        File d = new File(root(), name);
        if (d.isDirectory()) return d;
        File[] all = root().listFiles();
        if (all != null) for (File f : all) if (f.isDirectory() && !f.getName().startsWith(".") && f.getName().equalsIgnoreCase(name)) return f;
        throw new IllegalArgumentException("no project named " + name + " (list_projects shows the names)");
    }

    /** The project the action is about: the named one, else the one the host has open. */
    private File project(AiActions.Action a) {
        String n = a.str("project");
        if (n != null && !n.trim().isEmpty()) return namedProject(n);
        File p = link.project();
        if (p == null) throw new IllegalArgumentException("no project is open: pass \"project\", or use open_project or create_project first");
        return p;
    }

    private boolean isOpenProject(File dir) {
        File p = link.project();
        try {
            return p != null && p.getCanonicalFile().equals(dir.getCanonicalFile());
        } catch (IOException e) {
            return p != null && p.equals(dir);
        }
    }

    /** A file inside a project, only for a path the assistant may touch. */
    private static File safeFile(File proj, String rel) throws IOException {
        String p = AiProtocol.cleanPath(rel);
        if (p == null)
            throw new IllegalArgumentException("the path \"" + rel + "\" is not allowed: use a path inside the project, not absolute, without .., not build output, not a binary or credential file");
        File f = new File(proj, p);
        if (!f.getCanonicalPath().startsWith(proj.getCanonicalPath() + File.separator)) throw new IllegalArgumentException("the path \"" + rel + "\" leaves the project");
        return f;
    }

    private static String need(AiActions.Action a, String k) {
        String v = a.str(k);
        if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException("\"" + k + "\" is needed for " + a.name);
        return v;
    }

    private ProjectConfig config(File dir) {
        ProjectConfig live = link.config();
        return live != null && isOpenProject(dir) ? live : ProjectConfig.load(dir);
    }

    // ------------------------------------------------------------ state and projects
    private AiActions.Result getState(AiActions.Action a) throws Exception {
        StringBuilder b = new StringBuilder();
        File p = link.project();
        if (p == null) b.append("Open project: none (the assistant screen is open, not an editor)\n");
        else {
            ProjectConfig c = config(p);
            b.append("Open project: ").append(p.getName()).append("  (").append(Detect.label(p)).append(")\n")
                    .append("Build method: ").append(c.local ? "local, on the phone" : "GitHub Actions").append(", preset ").append(c.preset).append('\n');
        }
        File r = root();
        String[] names = r.list();
        int n = 0;
        if (names != null) for (String s : names) if (!s.startsWith(".") && new File(r, s).isDirectory()) n++;
        b.append("Projects folder: ").append(r.getAbsolutePath()).append("  (").append(n).append(n == 1 ? " project)\n" : " projects)\n");
        String ver = "";
        try {
            ver = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        b.append("App: CloudIDE ").append(ver).append(", editor ").append(link.editor() ? "open" : "not open").append('\n');
        b.append("Build tools: ").append(Toolchain.shortStatus(app).replace("\nTap to install", "")).append('\n');
        b.append("Language plugins:\n").append(Packs.status(app));
        return ok(a, b.toString());
    }

    private AiActions.Result listProjects(AiActions.Action a) {
        File[] dirs = root().listFiles();
        List<File> l = new ArrayList<>();
        if (dirs != null) for (File f : dirs) if (f.isDirectory() && !f.getName().startsWith(".")) l.add(f);
        Collections.sort(l, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
        if (l.isEmpty()) return ok(a, "No projects yet in " + root().getAbsolutePath());
        StringBuilder b = new StringBuilder();
        for (File f : l) {
            ProjectConfig c = ProjectConfig.load(f);
            b.append(f.getName()).append("  -  ").append(Detect.label(f)).append(", ").append(c.local ? "local build" : "GitHub build");
            if (!c.repo.isEmpty()) b.append(", ").append(c.owner).append('/').append(c.repo);
            if (!c.lastStatus.isEmpty()) b.append(", last build ").append(c.lastStatus);
            b.append('\n');
        }
        return ok(a, b.toString().trim());
    }

    private AiActions.Result createProject(AiActions.Action a) throws Exception {
        String name = need(a, "name").trim();
        String pkg = a.str("package");
        pkg = pkg == null || pkg.trim().isEmpty() ? AiActions.defaultPackage(name) : pkg.trim();
        int t = AiActions.template(a.str("template"));
        if (t < 0) return fail(a, "unknown template \"" + a.str("template") + "\"; use one of " + String.join(", ", AiActions.TEMPLATE_IDS));
        String appName = a.str("app_name");
        File dir = ProjectFactory.create(app, t, name, pkg, appName);
        Prefs.touch(app, dir.getName());
        StringBuilder b = new StringBuilder("Created project ").append(dir.getName())
                .append(" (app name \"").append(appName == null || appName.trim().isEmpty() ? name : appName.trim()).append("\")").append(" (template ").append(AiActions.TEMPLATE_IDS[t])
                .append(", package ").append(pkg).append(") in ").append(dir.getAbsolutePath()).append("\nFiles:\n");
        List<File> files = Io.projectFiles(dir);
        int shown = 0;
        for (File f : files) {
            if (shown++ >= 40) {
                b.append("... and ").append(files.size() - 40).append(" more\n");
                break;
            }
            b.append(Io.rel(dir, f)).append('\n');
        }
        if (!a.bool("open", false)) return ok(a, b.toString().trim()); // stays where it is unless the person asked to open it
        b.append("Opening it in the editor now. The results of this batch reach you there, in the new project.");
        return new AiActions.Result(a, true, b.toString().trim(), dir);
    }

    private AiActions.Result openProject(AiActions.Action a) {
        File dir = namedProject(need(a, "name"));
        if (isOpenProject(dir)) return ok(a, dir.getName() + " is already open.");
        Prefs.touch(app, dir.getName());
        return new AiActions.Result(a, true, "Opening " + dir.getName() + " in the editor now. The results of this batch reach you there, in that project.", dir);
    }

    private AiActions.Result deleteProject(AiActions.Action a) throws IOException {
        File dir = namedProject(need(a, "name"));
        if (isOpenProject(dir)) return fail(a, dir.getName() + " is open in the editor. Open a different project first, then delete this one.");
        File base = app.getExternalFilesDir(null);
        File trash = new File(base == null ? app.getFilesDir() : base, "trash");
        if (!trash.isDirectory() && !trash.mkdirs()) return fail(a, "the trash folder could not be created");
        File to = new File(trash, dir.getName() + "-" + System.currentTimeMillis());
        if (!dir.renameTo(to)) {
            Io.copy(dir, to);
            Io.delete(dir);
        }
        if (dir.exists()) return fail(a, "the project could not be removed completely; part of it may be in " + to.getAbsolutePath());
        Prefs.forgetProject(app, dir.getName());
        return ok(a, "Moved " + dir.getName() + " to the trash folder " + to.getAbsolutePath() + " (it can be restored by moving it back).");
    }

    // ------------------------------------------------------------ project settings
    private static final List<String> PROJECT_KEYS = Arrays.asList("local", "owner", "repo", "branch", "private", "workflow", "task", "gradle", "jdk", "compiler", "preset", "artifact");

    private static String describeConfig(ProjectConfig c, boolean tokenSaved) {
        return "build method: " + (c.local ? "local (on the phone)" : "GitHub Actions") + "\n"
                + "local: " + c.local + "\nowner: " + c.owner + "\nrepo: " + c.repo + "\nbranch: " + (c.branch.isEmpty() ? "(default)" : c.branch) + "\n"
                + "private: " + c.priv + "\npreset: " + c.preset + "\nworkflow: " + c.workflow + "\ntask: " + c.task + "\ngradle: " + c.gradle + "\njdk: " + c.jdk
                + "\ncompiler: " + c.compiler + "\nartifact: " + (c.artifact.isEmpty() ? "(automatic)" : c.artifact)
                + "\nproject access token: " + (tokenSaved ? "saved (not shown)" : "not set") + "\nlast build: " + (c.lastStatus.isEmpty() ? "none" : c.lastStatus);
    }

    private AiActions.Result getProjectSettings(AiActions.Action a) {
        File dir = project(a);
        ProjectConfig c = config(dir);
        return ok(a, "Project " + dir.getName() + "\n" + describeConfig(c, !Prefs.projectToken(app, dir.getName()).isEmpty()));
    }

    private AiActions.Result setProjectSettings(final AiActions.Action a) throws Exception {
        final File dir = project(a);
        final List<String> given = new ArrayList<>();
        for (String k : a.args.keySet()) if (!k.equals("action") && !k.equals("project")) given.add(k);
        if (given.isEmpty()) return fail(a, "nothing to change; the settings are " + String.join(", ", PROJECT_KEYS));
        for (String k : given) if (!PROJECT_KEYS.contains(k))
            return fail(a, "\"" + k + "\" is not a project setting here (the tokens cannot be changed by the assistant); the settings are " + String.join(", ", PROJECT_KEYS));
        // check everything first, so a bad value changes nothing
        for (String k : given) {
            String v = a.str(k);
            if (v == null) return fail(a, k + ": a value is needed");
            if (k.equals("local") || k.equals("private")) {
                if (!(a.args.get(k) instanceof Boolean) && !v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) return fail(a, k + " is true or false");
            } else if (k.equals("repo")) {
                if (!v.trim().isEmpty() && ProjectConfig.parseRepo(v) == null) return fail(a, "repo is owner/name or a github.com address");
            } else if (k.equals("owner")) {
                if (!v.trim().isEmpty() && ProjectConfig.owner(v).isEmpty()) return fail(a, "owner is a GitHub user name");
            } else {
                String why = AiActions.projectSettingProblem(k, v.trim());
                if (why != null) return fail(a, why);
            }
        }
        final boolean live = isOpenProject(dir) && link.config() != null;
        return onUi(new Callable<AiActions.Result>() {
            @Override
            public AiActions.Result call() {
                ProjectConfig c = live ? link.config() : ProjectConfig.load(dir);
                StringBuilder b = new StringBuilder("Changed in project ").append(dir.getName()).append(":\n");
                for (String k : given) {
                    String v = a.str(k).trim();
                    String before;
                    switch (k) {
                        case "local": before = String.valueOf(c.local); c.local = a.bool("local", c.local); b.append("local: ").append(before).append(" -> ").append(c.local).append('\n'); continue;
                        case "private": before = String.valueOf(c.priv); c.priv = a.bool("private", c.priv); b.append("private: ").append(before).append(" -> ").append(c.priv).append('\n'); continue;
                        case "owner": before = c.owner; c.owner = ProjectConfig.owner(v); b.append("owner: ").append(before).append(" -> ").append(c.owner).append('\n'); continue;
                        case "repo": {
                            String[] pr = ProjectConfig.parseRepo(v);
                            before = c.repo;
                            if (pr == null) c.repo = "";
                            else {
                                if (!pr[0].isEmpty()) c.owner = pr[0];
                                c.repo = pr[1];
                            }
                            b.append("repo: ").append(before).append(" -> ").append(c.repo).append('\n');
                            continue;
                        }
                        case "branch": before = c.branch; c.branch = v; break;
                        case "workflow": before = c.workflow; c.workflow = v; break;
                        case "task": before = c.task; c.task = v; break;
                        case "gradle": before = c.gradle; c.gradle = v; break;
                        case "jdk": before = c.jdk; c.jdk = v; break;
                        case "compiler": before = c.compiler; c.compiler = v; break;
                        case "preset": before = c.preset; c.preset = v; break;
                        default: before = c.artifact; c.artifact = v; break;
                    }
                    b.append(k).append(": ").append(before).append(" -> ").append(v).append('\n');
                }
                c.save();
                if (live) link.configChanged();
                b.append("Saved. For GitHub builds, regenerate_workflow rewrites the workflow file to match the preset.");
                return ok(a, b.toString());
            }
        });
    }

    private AiActions.Result regenerateWorkflow(final AiActions.Action a) throws Exception {
        final File dir = project(a);
        final boolean live = isOpenProject(dir) && link.config() != null;
        return onUi(new Callable<AiActions.Result>() {
            @Override
            public AiActions.Result call() throws Exception {
                ProjectConfig c = live ? link.config() : ProjectConfig.load(dir);
                Workflows.write(app, dir, c);
                return ok(a, "Wrote .github/workflows/" + c.workflow + " for preset " + c.preset + " in " + dir.getName() + ".");
            }
        });
    }

    // ------------------------------------------------------------ app settings
    private String settingValue(SharedPreferences sp, AiActions.Setting s) {
        switch (s.key) {
            case "font": return String.valueOf((int) sp.getFloat("font", 14f));
            case "tab": return String.valueOf(sp.getInt("tab", 4));
            case "night": return String.valueOf(sp.getInt("night", 2));
            case "ai_max": return String.valueOf(AiConfig.maxTokens(app));
            case "root": return Prefs.root(app).getAbsolutePath();
            default: break;
        }
        switch (s.type) {
            case "bool": return String.valueOf(sp.getBoolean(s.key, s.def));
            case "int": case "choice": return String.valueOf(sp.getInt(s.key, 0));
            default: return sp.getString(s.key, "");
        }
    }

    private AiActions.Result getAppSettings(AiActions.Action a) {
        SharedPreferences sp = Prefs.sp(app);
        StringBuilder b = new StringBuilder("App settings (set_app_settings changes them):\n");
        for (AiActions.Setting s : AiActions.SETTINGS) b.append(s.key).append(" = ").append(settingValue(sp, s)).append("   - ").append(s.help).append('\n');
        b.append("Not available to the assistant: its own provider, key and address, the GitHub tokens, the toolchain address and this access mode.");
        return ok(a, b.toString());
    }

    private AiActions.Result setAppSettings(final AiActions.Action a) throws Exception {
        Map<String, Object> m = a.map("settings");
        if (m == null) {
            // also accept {"action":"set_app_settings","key":"night","value":2}
            String k = a.str("key");
            if (k == null || !a.has("value")) return fail(a, "pass {\"settings\": {\"key\": value, ...}}; get_app_settings lists the keys");
            m = new java.util.LinkedHashMap<>();
            m.put(k, a.args.get("value"));
        }
        if (m.isEmpty()) return fail(a, "no settings given; get_app_settings lists the keys");
        final java.util.LinkedHashMap<String, Object> checked = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            AiActions.Setting s = AiActions.setting(e.getKey());
            if (s == null) return fail(a, "\"" + e.getKey() + "\" is not a setting the assistant may change; get_app_settings lists them");
            Object v;
            try {
                v = AiActions.coerce(s, e.getValue());
            } catch (IllegalArgumentException ex) {
                return fail(a, ex.getMessage());
            }
            if (s.key.equals("etheme") && (Long) v > EditorTheme.ALL.length) return fail(a, "etheme must be from 0 to " + EditorTheme.ALL.length);
            if (s.key.equals("code_font") && (Long) v >= Fonts.NAMES.length) return fail(a, "code_font must be from 0 to " + (Fonts.NAMES.length - 1));
            if (s.key.equals("root")) {
                String path = (String) v;
                if (!path.isEmpty()) {
                    File d = new File(path);
                    if (!d.isAbsolute()) return fail(a, "root must be an absolute folder path, or empty for the default");
                    if (!d.isDirectory() && !d.mkdirs()) return fail(a, "the folder " + path + " could not be created");
                    if (!d.canWrite()) return fail(a, "the folder " + path + " is not writable");
                }
            }
            checked.put(s.key, v);
        }
        return onUi(new Callable<AiActions.Result>() {
            @Override
            public AiActions.Result call() {
                SharedPreferences sp = Prefs.sp(app);
                SharedPreferences.Editor ed = sp.edit();
                StringBuilder b = new StringBuilder("Changed:\n");
                boolean theme = false;
                for (Map.Entry<String, Object> e : checked.entrySet()) {
                    AiActions.Setting s = AiActions.setting(e.getKey());
                    b.append(s.key).append(": ").append(settingValue(sp, s)).append(" -> ").append(e.getValue()).append('\n');
                    Object v = e.getValue();
                    if (s.key.equals("font")) ed.putFloat("font", ((Long) v).floatValue());
                    else if (v instanceof Boolean) ed.putBoolean(s.key, (Boolean) v);
                    else if (v instanceof Long) ed.putInt(s.key, (int) (long) (Long) v);
                    else ed.putString(s.key, (String) v);
                    if (s.key.equals("night")) theme = true;
                }
                ed.apply();
                if (theme) App.applyTheme(app);
                link.settingsChanged();
                if (checked.containsKey("root")) b.append("Projects already in the old folder are not moved.\n");
                return ok(a, b.toString().trim());
            }
        });
    }

    // ------------------------------------------------------------ commands
    private static final List<String> IDE_ONLY = Arrays.asList("build", "lbuild", "push", "apk", "outputs", "history", "problems", "open", "clear", "help");

    private AiActions.Result runCommand(AiActions.Action a) throws Exception {
        final String line = need(a, "command").trim();
        File proj = link.project();
        String pn = a.str("project");
        if (pn != null && !pn.trim().isEmpty()) proj = namedProject(pn);
        File base = proj != null ? proj : root();
        File cwd = base;
        String rel = a.str("cwd");
        if (rel != null && !rel.trim().isEmpty() && !rel.trim().equals(".")) {
            cwd = new File(base, rel.trim()).getCanonicalFile();
            String bc = base.getCanonicalPath();
            // "/proj" must not accept "/projX": compare whole path segments
            if (!cwd.getPath().equals(bc) && !cwd.getPath().startsWith(bc + File.separator)) return fail(a, "cwd must be inside " + base.getName());
            if (!cwd.isDirectory()) return fail(a, "cwd is not a folder: " + rel);
        }
        final int timeout = (int) Math.max(5, Math.min(900, a.num("timeout", 120)));
        List<String> w = Shell.words(line);
        String first = w.isEmpty() ? "" : w.get(0);

        if (IDE_ONLY.contains(first) && w.size() >= 1 && !line.contains("&&") && !line.contains(";") && !line.contains("|"))
            return fail(a, "\"" + first + "\" is a command of the editor's terminal screen and not available here. Use the build action to build, open_file to open a file, and list_files / read_file for files.");
        if (first.equals("toolchain") && w.size() == 1) return ok(a, Toolchain.shortStatus(app));
        if (first.equals("langs") && w.size() == 1 && proj != null) return ok(a, Detect.label(proj) + "\nLocal build: " + LocalLang.describe(app, proj));
        if (first.equals("packs") || first.equals("plugins") || first.equals("plugin")) {
            String op = w.size() > 1 ? w.get(1) : "list";
            return pluginOp(a, op, w.size() > 2 ? w.get(2) : null);
        }

        SandboxFiles.Exec ex = execLine(proj, base, cwd, line, w, first, timeout);
        String text = ex.out;
        if (ex.timedOut) text += "\n[stopped: the command ran longer than " + timeout + " seconds]";
        else if (cancelled) text += "\n[stopped by the person]";
        else if (ex.code != 0) text += "\n[exit " + ex.code + "]";
        if (text.trim().isEmpty()) text = "(no output, exit " + ex.code + ")";
        return new AiActions.Result(a, ex.code == 0 && !ex.timedOut && !cancelled, text, null);
    }

    /** Runs one command line in the app's shell (SDK tools, language plugins on PATH). Used for project commands and for the on-phone sandbox. */
    SandboxFiles.Exec execLine(File proj, File base, File cwd, String line, List<String> w, String first, final int timeout) throws Exception {
        final StringBuffer out = new StringBuffer();
        final Shell sh = new Shell(cwd);
        sh.env = NativeExec.allEnv(app);
        running = sh;
        final boolean[] timedOut = {false};
        final Thread dog = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(timeout * 1000L);
                    timedOut[0] = true;
                    sh.kill();
                    SdkCmd.Session k = runningSdk;
                    if (k != null) k.cancelled = true;
                } catch (InterruptedException ignored) {
                }
            }
        });
        dog.setDaemon(true);
        dog.start();
        int code;
        try {
            if (first.equals("lrun") && proj != null) {
                List<String> ra = new ArrayList<>(w.subList(1, w.size()));
                code = LocalLang.runLast(app, proj, ra, new NativeExec.Out() {
                    @Override
                    public void line(String s) { out.append(s).append('\n'); }
                }, new NativeExec.Cancel() {
                    @Override
                    public boolean is() { return cancelled || timedOut[0]; }
                });
            } else {
                SdkCmd.Session sdk = new SdkCmd.Session(app, proj != null ? proj : base, sh);
                runningSdk = sdk;
                if (sdk.wants(line)) {
                    sdk.refreshVars();
                    code = sdk.runLine(line, new NativeExec.Out() {
                        @Override
                        public void line(String s) { out.append(s).append('\n'); }
                    });
                } else {
                    code = sh.run(line, new Shell.Out() {
                        @Override
                        public void text(String s) { out.append(s); }
                    });
                }
            }
        } finally {
            dog.interrupt();
            running = null;
            runningSdk = null;
        }
        return new SandboxFiles.Exec(out.toString(), code, timedOut[0]);
    }

    /** For the sandbox: a command line in a folder, no project. */
    SandboxFiles.Exec execIn(File dir, String line, int timeout) throws Exception {
        List<String> w = Shell.words(line);
        return execLine(null, dir, dir, line, w, w.isEmpty() ? "" : w.get(0), timeout);
    }


    // ------------------------------------------------------------ language plugins
    private static final java.util.regex.Pattern PLUGIN_ID = java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,40}");

    private AiActions.Result plugins(AiActions.Action a) throws Exception {
        String op = a.str("op");
        return pluginOp(a, op == null || op.trim().isEmpty() ? "list" : op.trim().toLowerCase(Locale.ROOT), a.str("name"));
    }

    private AiActions.Result pluginOp(AiActions.Action a, String op, String name) throws Exception {
        final StringBuffer out = new StringBuffer();
        Gh.Log lg = new Gh.Log() {
            @Override
            public void log(String s) { out.append(s).append('\n'); }
        };
        switch (op) {
            case "list":
            case "status":
                return ok(a, Packs.status(app) + "\nInstall with plugins {op:\"install\", name:\"<id>\"}.");
            case "install":
            case "remove": {
                if (name == null || !PLUGIN_ID.matcher(name.trim()).matches()) return fail(a, "\"name\" is the plugin id, for example python or node (op list shows them)");
                String id = name.trim();
                if (Packs.find(app, id) == null) return fail(a, "no plugin named " + id + "; op list shows the ids");
                if (op.equals("install")) Packs.installWithDeps(app, id, lg);
                else Packs.remove(app, id);
                onUi(new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        link.pluginsChanged();
                        return true;
                    }
                });
                out.append(op.equals("install") ? id + " installed\n" : id + " removed\n");
                return ok(a, out.toString().trim());
            }
            case "update":
                Packs.refreshCatalog(app, lg);
                return ok(a, (out.toString() + "Language list refreshed.").trim());
            default:
                return fail(a, "op is list, install, remove or update");
        }
    }

    // ------------------------------------------------------------ files
    private AiActions.Result listFiles(AiActions.Action a) throws IOException {
        File proj = project(a);
        String sub = a.str("path");
        String prefix = "";
        if (sub != null && !sub.trim().isEmpty() && !sub.trim().equals(".") && !sub.trim().equals("/")) {
            String s = sub.trim().replace('\\', '/');
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            File d = safeFile(proj, s);
            if (!d.isDirectory()) return fail(a, s + " is not a folder in " + proj.getName());
            prefix = s + "/";
        }
        List<File> all = Io.projectFiles(proj);
        StringBuilder b = new StringBuilder();
        int n = 0, total = 0;
        for (File f : all) {
            String rel = Io.rel(proj, f);
            if (!rel.startsWith(prefix) || AiProtocol.isSecretFile(rel)) continue;
            total++;
            if (n >= 1000) continue;
            n++;
            b.append(rel).append("  (").append(f.length()).append(" bytes)\n");
        }
        if (total == 0) return ok(a, "No files" + (prefix.isEmpty() ? "" : " under " + prefix) + " in " + proj.getName());
        if (total > n) b.append("... ").append(total - n).append(" more files; pass \"path\" to look inside a folder\n");
        return ok(a, proj.getName() + ":\n" + b.toString().trim());
    }

    private AiActions.Result readFile(final AiActions.Action a) throws Exception {
        final File proj = project(a);
        final String rel = AiProtocol.cleanPath(need(a, "path"));
        if (rel == null) throw new IllegalArgumentException("the path \"" + a.str("path") + "\" is not allowed (inside the project, no build output, no binary or credential files)");
        File f = safeFile(proj, rel);
        String text;
        if (isOpenProject(proj) && link.editor()) {
            text = onUi(new Callable<String>() {
                @Override
                public String call() { return link.readFile(rel); }
            });
        } else if (!f.exists()) {
            text = null;
        } else if (!f.isFile() || f.length() > 2L * 1024 * 1024) {
            text = AiEdits.UNREADABLE;
        } else {
            text = Io.read(f);
        }
        if (text == null) return fail(a, rel + " does not exist in " + proj.getName());
        if (text.equals(AiEdits.UNREADABLE)) return fail(a, rel + " cannot be read (a folder or a very large file)");
        String[] lines = text.split("\n", -1);
        int total = text.endsWith("\n") ? lines.length - 1 : lines.length; // a final line break does not start another line
        int from = (int) Math.max(1, a.num("start", 1)), to = (int) Math.min(total, a.num("end", total));
        if (from > total) return fail(a, rel + " has only " + total + " lines");
        StringBuilder b = new StringBuilder();
        for (int i = from - 1; i < to; i++) b.append(lines[i]).append('\n');
        String body = b.toString();
        String head = rel + "  (" + total + " lines" + (from > 1 || to < total ? ", showing " + from + "-" + to : "") + ")\n";
        if (body.length() > READ_CHARS) {
            int cut = body.lastIndexOf('\n', READ_CHARS);
            body = body.substring(0, cut > 0 ? cut + 1 : READ_CHARS);
            int shown = body.split("\n", -1).length - 1;
            head += "[cut after line " + (from + shown - 1) + "; call read_file again with start=" + (from + shown) + " to read the rest]\n";
        }
        return ok(a, head + body);
    }

    /** One read of a file or folder stays under this, so it comes back to the model whole (see {@link AiActions#MAX_OUTPUT}). */
    private static final int READ_CHARS = 22000;

    /** The text files of a project the assistant may read, in folder order, each with its text (unsaved edits included for the open project). */
    private List<String[]> sourceFiles(final File proj, String prefix, String onlyExt) throws Exception {
        final List<String> rels = new ArrayList<>();
        for (File f : Io.projectFiles(proj)) {
            String rel = Io.rel(proj, f);
            if (!rel.startsWith(prefix) || AiProtocol.cleanPath(rel) == null || f.length() > 1024L * 1024) continue;
            rels.add(rel);
        }
        List<String[]> out = new ArrayList<>();
        if (isOpenProject(proj) && link.editor()) {
            final List<String> rs = rels;
            List<String[]> live = onUi(new Callable<List<String[]>>() { // one hop to the UI thread for every file
                @Override
                public List<String[]> call() {
                    List<String[]> l = new ArrayList<>();
                    for (String r : rs) {
                        String t = link.readFile(r);
                        if (t != null && !t.equals(AiEdits.UNREADABLE)) l.add(new String[]{r, t});
                    }
                    return l;
                }
            });
            out.addAll(live);
        } else {
            for (String r : rels) {
                File f = new File(proj, r);
                try {
                    out.add(new String[]{r, Io.read(f)});
                } catch (IOException e) { /* unreadable: skipped */ }
            }
        }
        return out;
    }

    private AiActions.Result searchFiles(AiActions.Action a) throws Exception {
        File proj = project(a);
        String q = need(a, "query");
        String sub = a.str("path");
        String prefix = "";
        if (sub != null && !sub.trim().isEmpty() && !sub.trim().equals(".")) {
            prefix = sub.trim().replace('\\', '/');
            while (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
            File d = new File(proj, prefix);
            if (!d.isDirectory() && !d.isFile()) return fail(a, prefix + " does not exist in " + proj.getName());
            if (d.isDirectory()) prefix += "/";
        }
        boolean ic = a.bool("ignore_case", true);
        java.util.regex.Pattern pat;
        try {
            pat = java.util.regex.Pattern.compile(a.bool("regex", false) ? q : java.util.regex.Pattern.quote(q), ic ? java.util.regex.Pattern.CASE_INSENSITIVE : 0);
        } catch (java.util.regex.PatternSyntaxException e) {
            return fail(a, "the regular expression is not valid: " + e.getDescription());
        }
        int max = (int) Math.max(1, Math.min(400, a.num("max", 100)));
        StringBuilder b = new StringBuilder();
        int hits = 0, files = 0, scanned = 0;
        for (String[] f : sourceFiles(proj, prefix, null)) {
            scanned++;
            String[] lines = f[1].split("\n", -1);
            boolean any = false;
            for (int i = 0; i < lines.length; i++) {
                if (!pat.matcher(lines[i]).find()) continue;
                if (!any) { any = true; files++; }
                if (++hits <= max) {
                    String l = lines[i].trim();
                    b.append(f[0]).append(':').append(i + 1).append(": ").append(l.length() > 200 ? l.substring(0, 200) + "..." : l).append('\n');
                }
            }
        }
        if (hits == 0) return ok(a, "No match for \"" + q + "\" in " + scanned + " files of " + proj.getName());
        if (hits > max) b.append("... ").append(hits - max).append(" more matches; narrow the search with path or a longer query, or raise max\n");
        return ok(a, hits + " matches in " + files + " files (searched " + scanned + "):\n" + b.toString().trim());
    }

    private AiActions.Result readFolder(AiActions.Action a) throws Exception {
        File proj = project(a);
        String sub = a.str("path");
        String prefix = "";
        if (sub != null && !sub.trim().isEmpty() && !sub.trim().equals(".")) {
            prefix = sub.trim().replace('\\', '/');
            while (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
            if (!new File(proj, prefix).isDirectory()) return fail(a, prefix + " is not a folder in " + proj.getName());
            prefix += "/";
        }
        List<String[]> files = sourceFiles(proj, prefix, null);
        if (files.isEmpty()) return ok(a, "No readable source files" + (prefix.isEmpty() ? "" : " under " + prefix) + " in " + proj.getName());
        int start = (int) Math.max(1, a.num("start", 1));
        if (start > files.size()) return fail(a, "the folder has only " + files.size() + " files");
        StringBuilder b = new StringBuilder();
        int i = start - 1;
        for (; i < files.size(); i++) {
            String body = files.get(i)[1];
            String block = "=== " + files.get(i)[0] + " ===\n" + body + (body.endsWith("\n") ? "" : "\n");
            if (b.length() > 0 && b.length() + block.length() > READ_CHARS) break;
            if (block.length() > READ_CHARS) { // one very big file: its start now, the rest with read_file
                int cut = block.lastIndexOf('\n', READ_CHARS);
                block = block.substring(0, cut > 0 ? cut + 1 : READ_CHARS) + "[file cut here; use read_file with start and end for the rest]\n";
            }
            b.append(block);
        }
        String head = files.size() + " files" + (prefix.isEmpty() ? "" : " under " + prefix) + "; showing files " + start + "-" + i + ".\n";
        if (i < files.size()) head += "[more follows: call read_folder again with start=" + (i + 1) + (prefix.isEmpty() ? "" : " and path=" + prefix.substring(0, prefix.length() - 1)) + "]\n";
        else head += "[that is the last file]\n";
        return ok(a, head + b);
    }

    private AiActions.Result writeFile(final AiActions.Action a) throws Exception {
        final File proj = project(a);
        final String rel = AiProtocol.cleanPath(need(a, "path"));
        if (rel == null) throw new IllegalArgumentException("the path \"" + a.str("path") + "\" is not allowed (inside the project, no build output, no binary or credential files)");
        final String text = a.str("content");
        if (text == null) return fail(a, "\"content\" is needed: the complete new text of the file");
        if (text.length() > AiProtocol.MAX_EDIT_CHARS) return fail(a, "the content is too large");
        File f = safeFile(proj, rel);
        if (f.isDirectory()) return fail(a, rel + " is a folder");
        final boolean live = isOpenProject(proj) && link.editor();
        String old = live ? onUi(new Callable<String>() {
            @Override
            public String call() { return link.readFile(rel); }
        }) : (f.isFile() && f.length() <= 2L * 1024 * 1024 ? Io.read(f) : null);
        if (AiEdits.UNREADABLE.equals(old)) return fail(a, rel + " exists but cannot be read, so it is not overwritten blindly");
        String why = AiProtocol.unsafeReason(old, text);
        if (why != null) return fail(a, rel + " was not written: " + why + ". Send the complete file.");
        String err;
        if (live) {
            err = onUi(new Callable<String>() {
                @Override
                public String call() { return link.writeFile(rel, text); }
            });
        } else {
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return fail(a, "the folder for " + rel + " could not be created");
            Io.write(f, text);
            err = null;
        }
        if (err != null) return fail(a, rel + " was not written: " + err);
        return ok(a, "Wrote " + rel + " in " + proj.getName() + " (" + AiProtocol.countLines(text) + " lines, " + (old == null ? "new file" : "replaced") + ").");
    }

    private AiActions.Result deleteFile(final AiActions.Action a) throws Exception {
        final File proj = project(a);
        final String rel = AiProtocol.cleanPath(need(a, "path"));
        if (rel == null) throw new IllegalArgumentException("the path \"" + a.str("path") + "\" is not allowed");
        File f = safeFile(proj, rel);
        if (!f.exists()) return fail(a, rel + " does not exist");
        if (!f.isFile()) return fail(a, rel + " is a folder; delete_file removes one file (a folder can be removed with run_command)");
        boolean saved = false;
        try { // keep a copy first, so restore_file can bring the file back
            File bk = new File(fileTrash(proj), rel);
            File bd = bk.getParentFile();
            if (bd != null && !bd.isDirectory()) bd.mkdirs();
            Io.copy(f, bk);
            saved = bk.isFile();
        } catch (Exception e) {
            AppLog.e("Agent", "could not keep a copy of " + rel, e);
        }
        String err;
        if (isOpenProject(proj) && link.editor()) {
            err = onUi(new Callable<String>() {
                @Override
                public String call() { return link.removeFile(rel); }
            });
        } else {
            Io.delete(f);
            err = f.exists() ? "the file could not be deleted" : null;
        }
        return err == null ? ok(a, "Deleted " + rel + (saved ? " (a copy is kept: restore_file {path:\"" + rel + "\"} brings it back)" : " (no copy could be kept)")) : fail(a, err);
    }

    /** Where deleted files of a project are kept so they can be restored. */
    private File fileTrash(File proj) {
        File base = app.getExternalFilesDir(null);
        return new File(new File(base == null ? app.getFilesDir() : base, "file-trash"), proj.getName());
    }

    private AiActions.Result restoreFile(final AiActions.Action a) throws Exception {
        final File proj = project(a);
        File root = fileTrash(proj);
        String given = a.str("path");
        if (given == null || given.trim().isEmpty()) { // no path: list what can be restored
            List<File> all = new ArrayList<>();
            collect(root, all);
            if (all.isEmpty()) return ok(a, "No deleted files are kept for " + proj.getName() + ".");
            StringBuilder b = new StringBuilder("Deleted files that can be restored in ").append(proj.getName()).append(":\n");
            for (File x : all) b.append(Io.rel(root, x)).append('\n');
            return ok(a, b.toString().trim());
        }
        final String rel = AiProtocol.cleanPath(given);
        if (rel == null) throw new IllegalArgumentException("the path \"" + given + "\" is not allowed");
        File bk = new File(root, rel);
        if (!bk.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator) || !bk.isFile())
            return fail(a, "no deleted copy of " + rel + " is kept (restore_file without a path lists them). If the file was never deleted with delete_file, recreate it with write_file.");
        File target = safeFile(proj, rel);
        if (target.exists()) return fail(a, rel + " already exists; read it, or delete it first");
        String text = bk.length() <= AiProtocol.MAX_EDIT_CHARS ? Io.read(bk) : null;
        if (text != null && !AiEdits.UNREADABLE.equals(text)) {
            java.util.Map<String, Object> args = new java.util.HashMap<>(a.args);
            args.put("action", "write_file");
            args.put("path", rel);
            args.put("content", text);
            AiActions.Result r = writeFile(new AiActions.Action("write_file", args));
            return r.ok ? ok(a, "Restored " + rel + " in " + proj.getName() + " from the copy kept when it was deleted.") : fail(a, r.text);
        }
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return fail(a, "the folder for " + rel + " could not be created");
        Io.copy(bk, target);
        return target.isFile() ? ok(a, "Restored " + rel + " (copied back; reopen it in the editor to see it).") : fail(a, rel + " could not be restored");
    }

    private static void collect(File d, List<File> out) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out);
            else out.add(f);
        }
    }

    private AiActions.Result renameFile(final AiActions.Action a) throws Exception {
        final File proj = project(a);
        final String from = AiProtocol.cleanPath(need(a, "from")), to = AiProtocol.cleanPath(need(a, "to"));
        if (from == null || to == null) throw new IllegalArgumentException("both paths must be inside the project (no .., no build output, no binary or credential files)");
        File src = safeFile(proj, from), dst = safeFile(proj, to);
        if (!src.isFile()) return fail(a, from + " does not exist");
        if (dst.exists()) return fail(a, to + " already exists");
        String err;
        if (isOpenProject(proj) && link.editor()) {
            err = onUi(new Callable<String>() {
                @Override
                public String call() { return link.renameFile(from, to); }
            });
        } else {
            File dir = dst.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return fail(a, "the folder for " + to + " could not be created");
            if (!src.renameTo(dst)) {
                Io.write(dst, Io.read(src));
                Io.delete(src);
            }
            err = dst.isFile() ? null : "the rename failed";
        }
        return err == null ? ok(a, "Renamed " + from + " to " + to) : fail(a, err);
    }

    private AiActions.Result openFile(final AiActions.Action a) throws Exception {
        if (!link.editor() || link.project() == null) return fail(a, "no editor is open; use open_project first");
        final String rel = AiProtocol.cleanPath(need(a, "path"));
        if (rel == null) throw new IllegalArgumentException("the path is not allowed");
        final int line = (int) Math.max(0, a.num("line", 0));
        String err = onUi(new Callable<String>() {
            @Override
            public String call() { return link.showFile(rel, line); }
        });
        return err == null ? ok(a, "Opened " + rel + (line > 0 ? " at line " + line : "")) : fail(a, err);
    }

    // ------------------------------------------------------------ build
    private AiActions.Result build(final AiActions.Action a) throws Exception {
        if (!link.editor() || link.project() == null) return fail(a, "a build needs the project open in the editor: use open_project first");
        String pn = a.str("project");
        if (pn != null && !pn.trim().isEmpty() && !isOpenProject(namedProject(pn))) return fail(a, pn + " is not the open project; open it first");
        final boolean run = a.bool("run", true);
        final long limit = Math.max(30, Math.min(1800, a.num("timeout", 900))) * 1000L;
        final int before = onUi(new Callable<Integer>() {
            @Override
            public Integer call() { return link.buildCount(); }
        });
        String err = onUi(new Callable<String>() {
            @Override
            public String call() { return link.startBuild(run); }
        });
        if (err != null) return fail(a, err);
        long t0 = System.currentTimeMillis();
        boolean seen = false;
        while (true) {
            if (cancelled) return fail(a, "stopped waiting; the build itself may still be running on the phone");
            Thread.sleep(1000);
            boolean r = onUi(new Callable<Boolean>() {
                @Override
                public Boolean call() { return link.building(); }
            });
            int now = onUi(new Callable<Integer>() {
                @Override
                public Integer call() { return link.buildCount(); }
            });
            if (r) seen = true;
            if (now != before || (seen && !r)) break;
            long el = System.currentTimeMillis() - t0;
            if (!seen && el > 30000)
                return fail(a, "The build did not start within 30 seconds. A dialog may be waiting on the screen (missing compilers, or a GitHub token that is not set). "
                        + "Ask the person to answer it, or fix the cause (plugins install, set_project_settings local=true) and build again.");
            if (el > limit) return fail(a, "The build was still running after " + (limit / 1000) + " seconds; it continues on the phone. The log so far:\n" + report());
        }
        final String rep = report();
        boolean good = onUi(new Callable<Boolean>() {
            @Override
            public Boolean call() { return link.buildOk(); }
        });
        return new AiActions.Result(a, good, rep, null);
    }

    private String report() throws Exception {
        return onUi(new Callable<String>() {
            @Override
            public String call() { return link.buildReport(); }
        });
    }
}
