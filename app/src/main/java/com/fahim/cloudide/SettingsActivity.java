package com.fahim.cloudide;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.ImageView;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

public class SettingsActivity extends AppCompatActivity {
    private final FolderPicker folderPicker = new FolderPicker(this);

    LinearLayout box;

    @Override
    protected void onCreate(Bundle b) {
        Ui.edge(this);
        super.onCreate(b);
        section = getIntent().getStringExtra(EXTRA_SECTION);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        MaterialToolbar tb = new MaterialToolbar(this);
        tb.setTitle(titleOf(section == null ? "" : section));
        tb.setNavigationIcon(R.drawable.ic_back);
        tb.setNavigationOnClickListener(v -> finish());
        root.addView(tb, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));
        ScrollView sv = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, Ui.dp(this, 32));
        sv.addView(box);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        Ui.insets(root, true, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildsBytes = libBytes = -1; // measured again in the background when a page needs them
        render();
    }

    // Folder sizes are measured off the UI thread: walking a big library cache froze the settings screen.
    private long buildsBytes = -1, libBytes = -1;
    private boolean measuring;

    private File buildsDir() { return new File(getExternalFilesDir(null), "builds"); }

    private void measure() {
        if (measuring) return;
        measuring = true;
        final File builds = buildsDir();
        new Thread(() -> {
            long b = Io.size(builds), l = LocalDeps.cacheBytes(this);
            runOnUiThread(() -> {
                measuring = false;
                buildsBytes = b;
                libBytes = l;
                if (Ui.alive(this)) render();
            });
        }, "measure-sizes").start();
    }

    private String libraryCacheText() {
        if (libBytes < 0) {
            measure();
            return "Measuring...";
        }
        return Human.size(libBytes) + " of downloaded libraries. Tap to clear.";
    }

    private void clearLibraryCache() {
        Ui.confirm(this, "Clear the library cache?", "Libraries are downloaded again the next time a build needs them.", "Clear", () -> {
            Io.delete(LocalDeps.cacheDir(this));
            libBytes = -1;
            render();
        });
    }

    int color(int attr, int def) { return MaterialColors.getColor(this, attr, def); }

    int dp(int v) { return Ui.dp(this, v); }

    /** The rounded card that holds the rows of the current group. */
    LinearLayout card;

    GradientDrawable shape(int fill, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** Starts a new group: a small label, then a rounded card that the following rows go into. */
    void header(String t) {
        TextView v = new TextView(this);
        v.setText(t.toUpperCase(java.util.Locale.ROOT));
        v.setTextSize(12);
        v.setLetterSpacing(0.08f);
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setTextColor(color(androidx.appcompat.R.attr.colorPrimary, 0xFF3B6FD4));
        v.setPadding(dp(28), dp(24), dp(20), dp(8));
        box.addView(v);
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(shape(color(com.google.android.material.R.attr.colorSurfaceContainer, 0x22888888), 16));
        card.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        box.addView(card, lp);
    }

    void add(View row) {
        if (card == null) {
            box.addView(row);
            return;
        }
        if (card.getChildCount() > 0) {
            View d = new View(this);
            d.setBackgroundColor(color(com.google.android.material.R.attr.colorOutlineVariant, 0x33888888));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
            lp.leftMargin = dp(20);
            card.addView(d, lp);
        }
        card.addView(row);
    }

    LinearLayout texts(String title, String sub) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        TextView a = new TextView(this);
        a.setText(title);
        a.setTextSize(16);
        a.setTextColor(color(com.google.android.material.R.attr.colorOnSurface, 0xFF000000));
        t.addView(a);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(13);
            s.setPadding(0, dp(2), 0, 0);
            s.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666));
            t.addView(s);
        }
        return t;
    }

    void ripple(View v) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        v.setBackgroundResource(tv.resourceId);
    }

    void row(String title, String sub, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), dp(14), dp(20), dp(14));
        r.addView(texts(title, sub), new LinearLayout.LayoutParams(0, -2, 1f));
        ripple(r);
        r.setOnClickListener(l);
        add(r);
    }

    void toggle(String title, String sub, String key, boolean def) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), dp(10), dp(20), dp(10));
        r.addView(texts(title, sub), new LinearLayout.LayoutParams(0, -2, 1f));
        MaterialSwitch s = new MaterialSwitch(this);
        s.setChecked(Prefs.bool(this, key, def));
        s.setOnCheckedChangeListener((bv, on) -> Prefs.put(this, key, on));
        r.addView(s);
        ripple(r);
        r.setOnClickListener(v -> s.toggle());
        add(r);
    }

    /** A line of explanatory text under or above a group. */
    void note(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(13);
        v.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666));
        v.setPadding(dp(28), dp(16), dp(28), dp(0));
        box.addView(v);
    }

    static int idxOf(String[] a, String v) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i;
        return -1;
    }

    // ------------------------------------------------------------ pages
    // The first page is only a list of topics. Each topic opens its own page. Intent extra EXTRA_SECTION
    // jumps straight to a topic ("github", "tools", ...).
    static final String EXTRA_SECTION = "section";

    /** key, title, icon */
    static final Object[][] TOPICS = {
            {"look", "Appearance", R.drawable.ic_t_look},
            {"editor", "Editor", R.drawable.ic_t_edit},
            {"tools", "Build tools", R.drawable.ic_t_build},
            {"libraries", "Libraries", R.drawable.ic_t_build},
            {"languages", "Languages", R.drawable.ic_t_code},
            {"github", "GitHub account", R.drawable.ic_t_account},
            {"storage", "Storage", R.drawable.ic_folder},
            {"help", "Diagnostics", R.drawable.ic_t_help},
            {"about", "About", R.drawable.ic_t_info},
    };

    String section;

    static String titleOf(String key) {
        for (Object[] t : TOPICS) if (t[0].equals(key)) return (String) t[1];
        return "App settings";
    }

    void render() {
        box.removeAllViews();
        card = null;
        if (section == null) hub();
        else if (section.equals("look")) pageLook();
        else if (section.equals("editor")) pageEditor();
        else if (section.equals("tools")) pageTools();
        else if (section.equals("libraries")) pageLibraries();
        else if (section.equals("languages")) pageLanguages();
        else if (section.equals("github")) pageGithub();
        else if (section.equals("storage")) pageStorage();
        else if (section.equals("help")) pageHelp();
        else pageAbout();
    }

    void topic(String key) {
        Object[] t = null;
        for (Object[] x : TOPICS) if (x[0].equals(key)) t = x;
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(12), dp(12), dp(12));

        ImageView icon = new ImageView(this);
        icon.setImageResource((Integer) t[2]);
        icon.setColorFilter(color(com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF003730));
        icon.setPadding(dp(9), dp(9), dp(9), dp(9));
        icon.setBackground(shape(color(com.google.android.material.R.attr.colorPrimaryContainer, 0x3300BFA5), 12));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(40), dp(40));
        ip.rightMargin = dp(16);
        r.addView(icon, ip);

        TextView name = new TextView(this);
        name.setText((String) t[1]);
        name.setTextSize(16);
        name.setTextColor(color(com.google.android.material.R.attr.colorOnSurface, 0xFF000000));
        r.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView go = new ImageView(this);
        go.setImageResource(R.drawable.ic_chevron);
        go.setColorFilter(color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666));
        r.addView(go, new LinearLayout.LayoutParams(dp(24), dp(24)));

        ripple(r);
        final String k = key;
        r.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class).putExtra(EXTRA_SECTION, k)));
        add(r);
    }

    void hub() {
        header("General");
        topic("look");
        topic("editor");
        header("Build");
        topic("tools");
        topic("libraries");
        topic("languages");
        topic("github");
        header("Data");
        topic("storage");
        header("Support");
        topic("help");
        topic("about");
        note("Options that apply to a single project, such as the build method and repository, are under Project settings in the editor menu.");
        View pad = new View(this);
        box.addView(pad, new LinearLayout.LayoutParams(-1, dp(8)));
    }

    void pageLook() {
        header("Theme");
        String[] nightN = {"Follow system", "Light", "Dark"};
        int night = Prefs.sp(this).getInt("night", 2);
        row("App theme", nightN[night], v -> Ui.choose(this, "App theme", nightN, night, i -> {
            Prefs.sp(this).edit().putInt("night", i).apply();
            App.applyTheme(this);
            render();
        }));
        String[] et = new String[EditorTheme.ALL.length + 1];
        et[0] = "Automatic (matches app theme)";
        for (int k = 0; k < EditorTheme.ALL.length; k++) et[k + 1] = EditorTheme.ALL[k].name;
        int ecur = Math.min(Prefs.sp(this).getInt("etheme", 0), et.length - 1);
        row("Code colors", et[ecur], v -> Ui.choose(this, "Code colors", et, ecur, i -> {
            Prefs.sp(this).edit().putInt("etheme", i).apply();
            render();
        }));
        header("Text");
        String[] fs = {"10", "11", "12", "13", "14", "15", "16", "18", "20", "22", "24"};
        row("Font size", (int) Prefs.font(this) + " sp. Pinch the editor to zoom.",
                v -> Ui.choose(this, "Font size", fs, idxOf(fs, String.valueOf((int) Prefs.font(this))), i -> {
                    Prefs.sp(this).edit().putFloat("font", Float.parseFloat(fs[i])).apply();
                    render();
                }));
    }

    void pageEditor() {
        header("Editing");
        toggle("Auto save", "Save when typing stops or when you switch files", "autosave", true);
        toggle("Code completion", "Suggest keywords, words from the file and snippets", "complete", true);
        toggle("Auto-close brackets and quotes", "Insert the closing character automatically", "pairs", true);
        header("Display");
        toggle("Word wrap", "Wrap long lines instead of scrolling sideways", "wrap", true);
        toggle("Line numbers", "Show the line number gutter", "lines", true);
        toggle("Highlight matching brackets", "", "bracket", true);
        header("Indentation");
        String[] tabs = {"2", "4", "8"};
        row("Tab width", Prefs.tab(this) + " spaces", v -> Ui.choose(this, "Tab width", tabs, idxOf(tabs, String.valueOf(Prefs.tab(this))), i -> {
            Prefs.sp(this).edit().putInt("tab", Integer.parseInt(tabs[i])).apply();
            render();
        }));
    }

    void pageTools() {
        header("Toolchain");
        row("Toolchain", Toolchain.shortStatus(this), v -> toolchainMenu());
        row("Run self-test", "Checks every build tool and shows the result", v -> checkToolchain());
        header("Java");
        int jo = Toolchain.javaOverride(this);
        row("Java version", jo == 0 ? "Automatic: follows the project" : "Java " + jo + " for all local builds",
                v -> Ui.choose(this, "Java version", Toolchain.JAVA_CHOICES, jo == 0 ? 0 : idxOf(Toolchain.JAVA_CHOICES, String.valueOf(jo)), i -> {
                    Toolchain.setJavaOverride(this, i == 0 ? 0 : Integer.parseInt(Toolchain.JAVA_CHOICES[i]));
                    render();
                }));
        header("Dependencies");
        row("Library cache", libraryCacheText(), v -> clearLibraryCache());
    }

    void pageLanguages() {
        header("Available languages");
        row("Java and Android", Toolchain.ready(this) ? "Installed" : "Not installed. Open Build tools", v -> startActivity(
                new Intent(this, SettingsActivity.class).putExtra(EXTRA_SECTION, "tools")));
        for (Packs.Pack p : Packs.all(this)) {
            String st;
            if (Packs.installed(this, p.id)) st = "Installed" + (Packs.version(this, p.id).isEmpty() ? "" : " (" + Packs.version(this, p.id) + ")");
            else if (p.meta) st = "Requires " + Toolchain.join(p.requires, ", ");
            else st = "Not installed, about " + p.mb + " MB";
            row(p.label, st, v -> packMenu(p));
        }
        header("Options");
        toggle("Prompt to install missing languages", "Ask when a project needs a language that is not installed", "auto_plugins", true);
        row("Refresh language list", "Fetch the latest list of available languages", v ->
                install("Languages", "Refreshing the language list...", false, log -> Packs.refreshCatalog(this, log)));
        row("Install from file", "Use a downloaded pack-<language>.zip, a GitHub Actions artifact, or a plugin zip", v ->
                Ui.input(this, "Language pack (.zip)", "File path", "/storage/emulated/0/Download/pack-kotlin.zip", path -> {
                    File f = new File(path.trim());
                    if (f.isFile()) install("Languages", "Installing...", false, log -> log.log("Installed " + Packs.importAny(this, f, log)));
                    else Ui.toast(this, "File not found, or storage access is missing");
                }));
        note("Tap a language to download it from your GitHub release. The release is created by the \"Build language packs\" workflow.");
    }

    void pageGithub() {
        String tok = Prefs.token(this), owner = Prefs.owner(this);
        header("Account");
        row("Username", owner.isEmpty() ? "Not set. Used as the owner of new repositories" : owner,
                v -> Ui.input(this, "GitHub username", "Username", Prefs.owner(this), s -> {
                    String clean = ProjectConfig.owner(s);
                    if (clean.isEmpty() && !s.isEmpty()) Ui.toast(this, "Not a valid GitHub username");
                    else Prefs.put(this, "owner", clean);
                    render();
                }));
        String mail = Prefs.email(this);
        row("Email (Gmail)", mail.isEmpty() ? "Not set. Used as the author of commits you push" : mail,
                v -> Ui.input(this, "Email", "name@gmail.com", mail, s2 -> {
                    String e = s2.trim();
                    if (!e.isEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) {
                        Ui.toast(this, "Not a valid email address");
                        return;
                    }
                    Prefs.put(this, "email", e);
                    render();
                }));
        row("Access token", tok.isEmpty() ? "Not set" : "Saved, encrypted (ends in " + tok.substring(Math.max(0, tok.length() - 4)) + ")",
                v -> Ui.form(this, "Access token", new String[]{"Personal access token"}, new String[]{""}, new boolean[]{true}, "Save", r -> {
                    if (!r[0].isEmpty()) Prefs.setToken(this, r[0]);
                    render();
                }));
        header("Token tools");
        row("Create token", "Opens GitHub with the repo and workflow scopes selected",
                v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/tokens/new?scopes=repo,workflow&description=CloudIDE"))));
        row("Verify token", "Checks that the token is valid and has the required scopes", v -> testConnection());
        if (!tok.isEmpty()) row("Remove token", "Delete the saved token from this device", v ->
                Ui.confirm(this, "Remove the token?", "GitHub Actions builds, upload and download stop working until a token is saved again.", "Remove", () -> {
                    Prefs.setToken(this, "");
                    render();
                }));
        note("The account is only required for GitHub Actions builds, upload and download. Local builds do not use it.");
    }

    /** Where libraries are downloaded from, and how the one-time download and cache behave. */
    void pageLibraries() {
        header("Download once, use offline");
        note("A library is downloaded the first time a build needs it and kept on this device. Every later build of any project reuses the saved file and does not use the internet.");
        toggle("Offline mode", "Use only libraries that are already downloaded. A missing library stops the build with a clear message instead of downloading", "lib_offline", false);

        header("Repositories");
        for (Repos.Repo r : Repos.BUILT_IN) toggle(r.name, r.hint + "\n" + r.url, "repo_on_" + r.id, r.def);
        note("Libraries are searched in this order, so a library found in the first repository is never looked up in the others. If a library is not found anywhere, the build says which repositories were tried.");

        header("Your own repositories");
        for (String u : Repos.custom(this)) {
            row(u, "Tap to remove", v -> Ui.confirm(this, "Remove this repository?", u, "Remove", () -> {
                Repos.removeCustom(this, u);
                render();
            }));
        }
        row("Add a repository", "The address of any Maven repository, for example https://maven.example.com/releases", v ->
                Ui.input(this, "Add a repository", "https://...", "https://", t -> {
                    String err = Repos.addCustom(this, t);
                    if (err != null) Ui.toast(this, err);
                    render();
                }));

        header("Project files");
        toggle("Use repositories from the project", "Also search the maven { url ... } addresses written in a project's settings.gradle and build.gradle. They are tried before the list above", "repo_project", true);
        toggle("Wait for JitPack builds", "JitPack builds a GitHub library the first time somebody asks for it. This can take a few minutes, so the app waits instead of giving up after one minute", "repo_jitpack_wait", true);

        header("Saved libraries");
        row("Library cache", libraryCacheText(), v -> clearLibraryCache());
        row("Look again for missing libraries", "Forgets \"not found\" results and saved version lists. Downloaded libraries are kept", v ->
                new Thread(() -> {
                    int n = LocalDeps.refreshLookups(this);
                    runOnUiThread(() -> Ui.toast(this, n == 0 ? "Nothing to refresh" : "Done. The next build asks the repositories again."));
                }, "refresh-lookups").start());
        note("Now: " + Repos.summary(this) + ".");
    }

    void pageStorage() {
        header("Projects");
        row("Projects folder", Prefs.root(this).getAbsolutePath() + "\nTap to change.",
                v -> Ui.menu(this, "Projects folder", new String[]{"Choose folder...", "Use default folder"}, i -> {
                    if (i == 1) {
                        Prefs.put(this, "root", "");
                        render();
                        return;
                    }
                    folderPicker.pick(d -> {
                        if (!d.canWrite()) {
                            AppLog.w("Settings", "Rejected projects folder " + d);
                            Ui.toast(this, "The folder cannot be written to. Check All files access.");
                            return;
                        }
                        Prefs.put(this, "root", d.getAbsolutePath());
                        render();
                    });
                }));
        String access = Build.VERSION.SDK_INT >= 30 ? (Environment.isExternalStorageManager() ? "Granted" : "Not granted. Tap to grant")
                : "Managed by the storage permission prompt";
        row("All files access", access, v -> {
            if (Build.VERSION.SDK_INT >= 30)
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
        });
        header("Cache and downloads");
        final File builds = buildsDir();
        String buildText = buildsBytes < 0 ? "Measuring..." : buildsBytes == 0 ? "Empty"
                : ApkInfo.size(buildsBytes) + " of APKs and build files. Tap to delete.";
        if (buildsBytes < 0) measure();
        row("Build outputs", buildText, v -> {
            if (buildsBytes == 0) return;
            Ui.confirm(this, "Delete build outputs?", "The APKs and files stored on this device are removed. Projects and GitHub runs are not affected.", "Delete", () -> {
                Io.delete(builds);
                buildsBytes = -1;
                render();
            });
        });
        row("Library cache", libraryCacheText(), v -> clearLibraryCache());
    }

    void pageHelp() {
        header("Logs");
        row("Open log", "Errors, crashes and build problems with timestamps", v ->
                startActivity(new Intent(this, LogActivity.class)));
        row("Copy diagnostics", "Device, app version and the full log, copied to the clipboard", v -> {
            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                    .setPrimaryClip(android.content.ClipData.newPlainText("CloudIDE diagnostics", AppLog.report(this)));
            Ui.toast(this, "Diagnostics copied");
        });
        toggle("Verbose logging", "Also record routine steps. Increases the log size", "verbose_log", false);
        header("Checks");
        row("Run toolchain self-test", "Checks every build tool and shows the result", v -> checkToolchain());
    }

    void pageAbout() {
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        header("CloudIDE");
        row("Version", ver.isEmpty() ? "Unknown" : ver, v -> { });
    }

    interface Job { void run(Gh.Log log) throws Exception; }

    void toolchainMenu() {
        Ui.menu(this, "Build tools", new String[]{"Download", "Import from zip or folder", "Remove"}, i -> {
            if (i == 0) {
                String owner = Prefs.owner(this);
                String def = Prefs.str(this, "tc_url");
                if (def.isEmpty() && !owner.isEmpty()) def = "https://github.com/" + owner + "/IDE/releases/download/toolchain/toolchain-arm64.zip";
                Ui.input(this, "Toolchain zip URL", "URL", def, url -> {
                    Prefs.put(this, "tc_url", url.trim());
                    install("Downloading toolchain...", log -> Toolchain.download(this, url.trim(), log));
                });
            } else if (i == 1) {
                Ui.menu(this, "Import build tools", new String[]{"Choose folder...", "Type a zip or folder path"}, k -> {
                    if (k == 0) {
                        folderPicker.pick(f -> install("Importing...", log -> Toolchain.installFolder(this, f, log)));
                        return;
                    }
                    Ui.input(this, "Toolchain zip or folder", "Path", "/storage/emulated/0/Download/toolchain-arm64.zip", p -> {
                        File f = new File(p.trim());
                        if (f.isDirectory()) install("Importing...", log -> Toolchain.installFolder(this, f, log));
                        else if (f.isFile()) install("Importing...", log -> Toolchain.installZip(this, f, log));
                        else Ui.toast(this, "Not a file or folder, or no storage access");
                    });
                });
            } else {
                Ui.confirm(this, "Remove toolchain?", "The unpacked compilers are deleted. The copy bundled inside the app is unpacked again the next time a build needs it.", "Remove", () -> {
                    Toolchain.remove(this);
                    render();
                });
            }
        });
    }

    void packMenu(Packs.Pack p) {
        boolean in = Packs.installed(this, p.id);
        String[] acts = in ? new String[]{"Download again", "Install from file", "Remove"} : new String[]{"Download", "Install from file"};
        Ui.menu(this, p.label + (p.what.isEmpty() ? "" : ": " + p.what), acts, i -> {
            if (i == 0) {
                runPack("Downloading " + p.label + "...", log -> Packs.installWithDeps(this, p.id, log), p);
            } else if (i == 1) {
                Ui.input(this, "Language pack (.zip)", "File path", "/storage/emulated/0/Download/pack-" + p.id + ".zip", path -> {
                    File f = new File(path.trim());
                    if (f.isFile()) runPack("Installing...", log -> Packs.importZip(this, p.id, f, log), p);
                    else Ui.toast(this, "File not found, or storage access is missing");
                });
            } else {
                Ui.confirm(this, "Remove " + p.label + "?", "The language is deleted from this device. You can install it again.", "Remove", () -> {
                    Packs.remove(this, p.id);
                    render();
                });
            }
        });
    }

    /** Result text appended after a successful job (empty for none). Runs on the worker thread. */
    interface Note { String text(); }

    void runPack(String msg, Job job, Packs.Pack p) {
        runJob(p.label, msg, job, () -> {
            String res = p.label + " pack installed.";
            if (!p.jvm && !Packs.execDirect(this))
                res += "\n\nThis build of CloudIDE targets SDK " + getApplicationInfo().targetSdkVersion
                        + ", so programs are started through the system linker. Run Check toolchain to see if they start on this phone. "
                        + "If not, rebuild with cloudide.targetSdk=28 in gradle.properties.";
            return res;
        });
    }

    void install(String msg, Job job) {
        runJob("Build tools", msg, job, () ->
                Toolchain.ready(this) ? "Toolchain is complete." : "Still missing: " + Toolchain.join(Toolchain.missing(this), ", "));
    }

    void install(String title, String msg, boolean toolchainNote, Job job) {
        if (toolchainNote) install(msg, job);
        else runJob(title, msg, job, () -> "");
    }

    /** Runs a long job off the UI thread behind a progress dialog, then shows its log and the closing note. */
    private void runJob(String title, String msg, Job job, Note note) {
        AlertDialog busy = Ui.busy(this, msg);
        new Thread(() -> {
            StringBuilder log = new StringBuilder();
            String res;
            try {
                job.run(s -> log.append(s).append('\n'));
                String n = note.text();
                res = (log.toString().trim() + (n.isEmpty() ? "" : "\n\n" + n)).trim();
                if (res.isEmpty()) res = "Done.";
            } catch (Throwable e) {
                AppLog.e("Settings", msg + " failed", e);
                res = (log + "\nFailed: " + e.getMessage()).trim();
            }
            final String m = res;
            runOnUiThread(() -> {
                Ui.dismiss(busy);
                if (!Ui.alive(this)) return;
                new MaterialAlertDialogBuilder(this).setTitle(title).setMessage(m).setPositiveButton("OK", null).show();
                render();
            });
        }).start();
    }

    void checkToolchain() {
        AlertDialog busy = Ui.busy(this, "Testing the toolchain...");
        new Thread(() -> {
            String r;
            try {
                r = Toolchain.selfTest(this) + "\n" + NativeExec.selfTest(this);
            } catch (Throwable e) {
                AppLog.e("Settings", "Toolchain self-test crashed", e);
                r = "Test crashed: " + Toolchain.describe(e);
            }
            final String m = r;
            runOnUiThread(() -> {
                Ui.dismiss(busy);
                if (!Ui.alive(this)) return;
                new MaterialAlertDialogBuilder(this).setTitle("Self-test").setMessage(m).setPositiveButton("OK", null)
                        .setNeutralButton("Copy log", (d, w) -> {
                            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                                    .setPrimaryClip(android.content.ClipData.newPlainText("toolchain check", m));
                            android.widget.Toast.makeText(this, "Log copied", android.widget.Toast.LENGTH_SHORT).show();
                        }).show();
            });
        }).start();
    }

    void testConnection() {
        String tok = Prefs.token(this);
        if (tok.isEmpty()) {
            Ui.toast(this, "Save a token first");
            return;
        }
        AlertDialog busy = Ui.busy(this, "Contacting GitHub...");
        new Thread(() -> {
            String msg;
            try {
                Gh g = new Gh(tok, s -> { });
                String login = g.whoami().getString("login");
                String sc = g.scopes();
                if (sc.isEmpty())
                    msg = "Signed in as " + login + ".\nFine-grained token: scopes cannot be listed. It needs Contents, Workflows, Actions (read and write) and Administration (to create repositories).";
                else
                    msg = "Signed in as " + login + ".\nScopes: " + sc + (sc.contains("repo") && sc.contains("workflow")
                            ? "\nAll required scopes are present." : "\nMissing scope: you need both repo and workflow.");
            } catch (Exception e) {
                AppLog.w("Settings", "GitHub connection test failed", e);
                msg = String.valueOf(e.getMessage());
            }
            final String m = msg;
            runOnUiThread(() -> {
                Ui.dismiss(busy);
                if (!Ui.alive(this)) return;
                new MaterialAlertDialogBuilder(this).setTitle("GitHub").setMessage(m).setPositiveButton("OK", null).show();
            });
        }).start();
    }
}
