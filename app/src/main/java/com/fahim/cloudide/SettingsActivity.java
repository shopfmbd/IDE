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
    private final FilePicker filePicker = new FilePicker(this);

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
        if (getIntent().getBooleanExtra("signin", false)) {
            getIntent().removeExtra("signin"); // once only
            startActivity(new Intent(this, GithubLoginActivity.class));
        }
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
            deleteInBackground(LocalDeps.cacheDir(this), () -> libBytes = -1);
        });
    }

    /** Deleting a big cache walks thousands of files: do it off the UI thread (the sizes were already measured that way). */
    private void deleteInBackground(final File dir, final Runnable after) {
        final AlertDialog busy = Ui.busy(this, "Deleting...");
        new Thread(() -> {
            try {
                Io.delete(dir);
            } catch (Throwable e) {
                AppLog.e("Settings", "Delete failed: " + dir, e);
            }
            runOnUiThread(() -> {
                Ui.dismiss(busy);
                after.run();
                if (Ui.alive(this)) render();
            });
        }, "settings-delete").start();
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
        v.setText(t);
        v.setTextSize(13);
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
            {"ai", "AI assistant", R.drawable.ic_t_ai},
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
        else if (section.equals("ai")) pageAi();
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
        header("Assistant");
        row("Chat with AI", "Talk to the assistant without opening a project", v -> startActivity(new Intent(this, AgentActivity.class)));
        topic("ai");
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
        final int night = Math.max(0, Math.min(nightN.length - 1, Prefs.sp(this).getInt("night", 2))); // a stored value outside 0..2 must not crash the page
        row("App theme", nightN[night], v -> Ui.choose(this, "App theme", nightN, night, i -> {
            Prefs.sp(this).edit().putInt("night", i).apply();
            App.applyTheme(this);
            render();
        }));
        String[] et = new String[EditorTheme.ALL.length + 1];
        et[0] = "Automatic (matches app theme)";
        for (int k = 0; k < EditorTheme.ALL.length; k++) et[k + 1] = EditorTheme.ALL[k].name;
        final int ecur = Math.max(0, Math.min(Prefs.sp(this).getInt("etheme", 0), et.length - 1));
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
        toggle("Indent guides", "Thin vertical lines that show each level of indentation", "guides", true);
        toggle("Highlight other uses", "Mark every other place the selected word appears", "occurrences", true);
        row("Code font", Fonts.NAMES[Fonts.choice(this)], v -> Ui.choose(this, "Code font", Fonts.NAMES, Fonts.choice(this), i -> {
            Prefs.sp(this).edit().putInt("code_font", i).apply();
            render();
        }));
        toggle("Font ligatures", "Draw -> and != as single symbols (JetBrains Mono only)", "ligatures", false);
        header("Indentation");
        String[] tabs = {"2", "4", "8"};
        row("Tab width", Prefs.tab(this) + " spaces", v -> Ui.choose(this, "Tab width", tabs, idxOf(tabs, String.valueOf(Prefs.tab(this))), i -> {
            Prefs.sp(this).edit().putInt("tab", Integer.parseInt(tabs[i])).apply();
            render();
        }));
    }

    // ---------------------------------------------------------------- AI assistant
    void pageAi() {
        final AiProtocol.Provider p = AiConfig.provider(this);
        header("Chat");
        row("Chat with AI", "Open the assistant outside any project", v -> startActivity(new Intent(this, AgentActivity.class)));
        header("Agent access");
        final String[] modeNames = {"Full access (never asks)", "Ask before running actions", "Off (answers only)"};
        final String[] modeKeys = {"full", "ask", "off"};
        String curMode = Prefs.str(this, "ai_agent");
        if (curMode.isEmpty()) curMode = "full";
        final int modeIdx = Math.max(0, idxOf(modeKeys, curMode));
        row("Agent mode", modeNames[modeIdx], v -> Ui.choose(this, "Agent mode", modeNames, modeIdx, i -> {
            Prefs.put(this, "ai_agent", modeKeys[i]);
            render();
        }));
        toggle("Edit files directly", "Apply file changes from replies without tapping Apply (Undo stays on each reply)", "ai_direct", false);
        note("Full access: the assistant can run shell commands, read, write and delete files, change settings and start builds on its own, "
                + "with this app's permissions. A wrong command can delete project files.");
        header("AI sandbox");
        final String[] sbNames = {"Off", "On this phone", "My own server"};
        final String[] sbKeys = {"off", "local", "remote"};
        final String sbMode = AiConfig.sandboxMode(this);
        final int sbIdx = Math.max(0, idxOf(sbKeys, sbMode));
        row("Sandbox", sbNames[sbIdx], v -> Ui.choose(this, "Sandbox", sbNames, sbIdx, i -> {
            AiConfig.setSandboxMode(this, sbKeys[i]);
            render();
        }));
        if (sbMode.equals("remote")) {
            String url = AiConfig.sandboxUrl(this);
            row("Server address", url.isEmpty() ? "Not set (https://...)" : url, v -> Ui.input(this, "Sandbox server address", "https://sandbox.example.com", AiConfig.sandboxUrl(this), s -> {
                AiConfig.setSandboxUrl(this, s);
                render();
            }));
            row("Server token", AiConfig.sandboxToken(this).isEmpty() ? "Not set" : "Saved on this device, encrypted",
                    v -> Ui.form(this, "Sandbox server token", new String[]{"Paste the token"}, new String[]{""}, new boolean[]{true}, "Save", r -> {
                        AiConfig.setSandboxToken(this, r[0]);
                        render();
                    }));
        }
        if (!sbMode.equals("off")) row("Test sandbox", sbMode.equals("remote") ? "Checks the server address and token" : "Shows where the sandbox folder is", v -> testSandbox());
        note(sbMode.equals("off")
                ? "The assistant can run commands and code, unpack zips and make files for you in a sandbox that is separate from your projects. Needs Agent access."
                : sbMode.equals("local")
                ? "The sandbox is a folder inside this app. Commands use the app's shell and the language plugins you installed. Attach a file or .zip with \"Sandbox file\" in the chat."
                : "Commands run on your own server (see sandbox-server/README.md in the project repository). Use an https address: the token and everything the assistant runs travel over it.");
        header("Service");
        String[] names = new String[AiProtocol.PROVIDERS.length];
        int cur = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = AiProtocol.PROVIDERS[i].label;
            if (AiProtocol.PROVIDERS[i] == p) cur = i;
        }
        final int curIdx = cur;
        row("Provider", p.label, v -> Ui.choose(this, "Provider", names, curIdx, i -> {
            AiConfig.setProvider(this, AiProtocol.PROVIDERS[i]);
            render();
        }));
        String keySub = !AiConfig.key(this, p).isEmpty() ? "Saved on this device, encrypted"
                : (p.keyRequired ? "Not set" : "Not needed for most servers on your own device");
        final String keyName = AiConfig.keyName(p);
        row(Character.toUpperCase(keyName.charAt(0)) + keyName.substring(1), keySub, v -> Ui.form(this, AiConfig.shortName(p) + " " + keyName,
                new String[]{p.keyHint.isEmpty() ? "Paste the key" : "Paste it (" + p.keyHint + ")"}, new String[]{""}, new boolean[]{true}, "Save", r -> {
                    if (!AiConfig.setKey(this, p, r[0])) Ui.toast(this, "Secure storage is not available on this device, so the key was saved without encryption.");
                    render();
                }));
        if ("puter".equals(p.id)) {
            note("Claude without an Anthropic key. Make a free account at puter.com, open puter.com/dashboard, tap Create token and paste it here. "
                    + "Usage is charged to your own Puter account and its allowance, not to this app, so it is not unlimited: when the allowance runs out you wait or add credit on Puter.");
            row("Puter.js chat", "Sign in on Puter and chat here, no token needed", v -> startActivity(new android.content.Intent(this, PuterWebActivity.class)));
        }
        String model = AiConfig.model(this, p);
        row("Model", model.isEmpty() ? "Not chosen" : model, v -> modelMenu(p));
        String base = AiConfig.base(this, p);
        row("Server address", base.isEmpty() ? "Not set" : base, v -> Ui.input(this, "Server address", p.baseUrl.isEmpty() ? "https://..." : p.baseUrl,
                AiConfig.baseTyped(this, p), s -> {
                    AiConfig.setBase(this, p, s);
                    render();
                }));
        row("Test connection", "Asks the provider which models this key can use", v -> testConnection(p));

        header("Replies");
        String[] lens = {"2000", "4000", "8000", "16000", "32000", "64000"};
        row("Max reply length", AiConfig.maxTokens(this) + " tokens", v -> Ui.choose(this, "Max reply length (tokens)", lens,
                idxOf(lens, String.valueOf(AiConfig.maxTokens(this))), i -> {
                    Prefs.sp(this).edit().putInt("ai_max", Integer.parseInt(lens[i])).apply();
                    render();
                }));
        final String[] projK = {"30", "60", "120", "250", "500", "1000"};
        row("Project code size", "\"Project code\" sends up to about " + AiConfig.projectK(this) + "k tokens of source. Larger sends more files but costs more and needs a model with a big context window",
                v -> Ui.choose(this, "Project code size (thousand tokens)", projK, idxOf(projK, String.valueOf(AiConfig.projectK(this))), i -> {
                    Prefs.sp(this).edit().putInt("ai_project_k", Integer.parseInt(projK[i])).apply();
                    AiConfig.applyLimits(this);
                    render();
                }));
        row("Instructions", AiConfig.customSystem(this) ? "Custom" : "Default: short answers, code you can apply with one tap", v -> editInstructions());

        header("Privacy");
        note("Your question and what you attach (the open file, the selection, problems, file names) are sent to the provider you choose, only when you ask. "
                + "Key files such as .env or keystores are never attached, and key-like text is masked. Keys are stored encrypted on this device. "
                + "The provider bills usage to your own account.");
        row("Ask permission again", "Forget that you allowed sending code to this provider", v -> {
            AiConfig.setConsented(this, p, false);
            Ui.toast(this, "You will be asked before the next question.");
        });
    }

    void modelMenu(final AiProtocol.Provider p) {
        final String cur = AiConfig.model(this, p);
        java.util.List<String> items = new java.util.ArrayList<>();
        items.add("Choose from the provider's list");
        items.add("Type a model name");
        if (!p.defaultModel.isEmpty()) items.add("Reset to " + p.defaultModel);
        Ui.menu(this, "Model", items.toArray(new String[0]), i -> {
            if (i == 0) fetchModels(p, false);
            else if (i == 1) Ui.input(this, "Model name", p.defaultModel.isEmpty() ? "model id" : p.defaultModel, cur, s -> {
                AiConfig.setModel(this, p, s);
                render();
            });
            else {
                AiConfig.setModel(this, p, "");
                render();
            }
        });
    }

    /** Asks the provider for its model list. With {@code testOnly} it just reports whether the connection works. */
    void fetchModels(final AiProtocol.Provider p, final boolean testOnly) {
        final String key = AiConfig.key(this, p);
        if (p.keyRequired && key.isEmpty()) {
            Ui.toast(this, "Add the " + AiConfig.keyName(p) + " first.");
            return;
        }
        final String typedBase = AiConfig.baseTyped(this, p);
        final androidx.appcompat.app.AlertDialog busy = Ui.busy(this, testOnly ? "Testing connection..." : "Fetching models...");
        new Thread(() -> {
            String error = null;
            java.util.List<String> models = null;
            try {
                models = AiProtocol.parseModels(p.kind, AiClient.fetch(AiProtocol.models(p, typedBase, key)));
            } catch (java.io.IOException e) {
                error = AiClient.describe(e);
            } catch (RuntimeException e) { // a malformed server address or an unexpected reply must not leave the dialog open (or crash the app)
                AppLog.w("Settings", "Model list failed", e);
                error = String.valueOf(e.getMessage());
            }
            final String err = error;
            final java.util.List<String> ms = models;
            runOnUiThread(() -> {
                Ui.dismiss(busy);
                if (isFinishing()) return;
                if (err != null) {
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Could not connect").setMessage(err)
                            .setPositiveButton("OK", null).show();
                } else if (testOnly) {
                    Ui.toast(this, "Connected. " + ms.size() + (ms.size() == 1 ? " model" : " models") + " available.");
                } else if (ms.isEmpty()) {
                    Ui.toast(this, "The provider returned no models. You can still type a model name.");
                } else {
                    final java.util.List<String> shown = ms.size() > 400 ? ms.subList(0, 400) : ms;
                    String[] arr = shown.toArray(new String[0]);
                    Ui.choose(this, "Models (" + ms.size() + ")", arr, idxOf(arr, AiConfig.model(this, p)), i -> {
                        AiConfig.setModel(this, p, arr[i]);
                        render();
                    });
                }
            });
        }, "ai-models").start();
    }

    void testConnection(AiProtocol.Provider p) { fetchModels(p, true); }

    void testSandbox() {
        final String miss = AiConfig.sandboxMissing(this);
        if (miss != null) {
            Ui.toast(this, miss);
            return;
        }
        if (!"remote".equals(AiConfig.sandboxMode(this))) {
            Ui.toast(this, "On this phone: " + new File(getFilesDir(), "sandbox").getAbsolutePath());
            return;
        }
        final AlertDialog busy = Ui.busy(this, "Contacting the sandbox server...");
        new Thread(() -> {
            String msg;
            try {
                msg = "Connected: " + new SandboxClient(AiConfig.sandboxUrl(this), AiConfig.sandboxToken(this), AiConfig.sandboxSession(this)).health();
            } catch (Throwable e) {
                msg = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                Ui.dismiss(busy);
                if (!Ui.alive(this)) return;
                Ui.toast(this, m);
            });
        }, "sandbox-test").start();
    }

    void editInstructions() {
        final android.widget.EditText e = new android.widget.EditText(this);
        e.setText(AiConfig.system(this));
        e.setTextSize(13);
        e.setMinLines(8);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(e);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Instructions").setView(wrap)
                .setPositiveButton("Save", (d, w) -> {
                    String t = e.getText().toString().trim();
                    Prefs.put(this, "ai_system", t.equals(AiProtocol.DEFAULT_SYSTEM_PROMPT.trim()) ? "" : t);
                    render();
                })
                .setNeutralButton("Reset to default", (d, w) -> {
                    Prefs.put(this, "ai_system", "");
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
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
                filePicker.pick(f -> install("Languages", "Installing...", false, log -> log.log("Installed " + Packs.importAny(this, f, log)))));
        note("Tap a language to download it from your GitHub release. The release is created by the \"Build language packs\" workflow.");
    }

    void pageGithub() {
        String tok = Prefs.token(this), owner = Prefs.owner(this);
        header("Login");
        row("Login with GitHub", tok.isEmpty() ? "Log in on GitHub and tap approve. No token to copy" : "Logged in. Tap to log in again or switch account",
                v -> startActivity(new Intent(this, GithubLoginActivity.class)));
        header("Account");
        row("Username", owner.isEmpty() ? "Not set. Used as the owner of new repositories" : owner,
                v -> Ui.input(this, "GitHub username", "Username", Prefs.owner(this), s -> {
                    String clean = ProjectConfig.owner(s);
                    if (clean.isEmpty() && !s.isEmpty()) Ui.toast(this, "Not a valid GitHub username");
                    else Prefs.put(this, "owner", clean);
                    render();
                }));
        row("IDE repository", "The repository on your account that holds this IDE project and its workflows (language packs, toolchain). Now: " + Prefs.ideRepo(this),
                v -> Ui.input(this, "IDE repository name", "Repository", Prefs.ideRepo(this), s -> {
                    String t = s.trim();
                    if (!t.matches("[A-Za-z0-9._-]+")) Ui.toast(this, "Not a valid repository name");
                    else Prefs.put(this, "ide_repo", t);
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
        row("Login with a code (advanced)", "Uses your own GitHub OAuth App. Most people do not need this", v -> startBrowserSignIn());
        if (GhAuth.DEFAULT_CLIENT_ID.isEmpty()) {
            String cid = Prefs.str(this, "gh_client_id").trim();
            row("OAuth client ID", cid.isEmpty() ? "Only for the code login above" : cid,
                    v -> Ui.input(this, "GitHub OAuth client ID", "Ov23...", cid, t -> {
                        Prefs.put(this, "gh_client_id", t.trim());
                        render();
                    }));
        }
        row("Access token", tok.isEmpty() ? "Not set" : "Saved, encrypted (ends in " + tok.substring(Math.max(0, tok.length() - 4)) + ")",
                v -> Ui.form(this, "Access token", new String[]{"Personal access token"}, new String[]{""}, new boolean[]{true}, "Save", r -> {
                    String t = r[0].trim(); // a pasted token often carries a trailing newline or space, which GitHub rejects
                    if (!t.isEmpty()) Prefs.setToken(this, t);
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

    private volatile boolean signInCancelled;

    /** GitHub sign-in through the browser (device flow). Needs a client ID of an OAuth App with Device Flow enabled. */
    void startBrowserSignIn() {
        final String cid = GhAuth.clientId(this);
        if (cid.isEmpty()) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("One-time setup")
                    .setMessage("Browser sign-in needs a free GitHub OAuth App:\n\n"
                            + "1. Tap Open GitHub below.\n"
                            + "2. New OAuth App. Any name. Homepage and callback URL can both be https://github.com\n"
                            + "3. After creating it, tick Enable Device Flow and save.\n"
                            + "4. Copy the Client ID (not the secret) and paste it into OAuth client ID here.\n\n"
                            + "You only do this once. The existing token option keeps working.")
                    .setPositiveButton("Open GitHub", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/applications/new"))))
                    .setNegativeButton("Close", null).show();
            return;
        }
        signInCancelled = false;
        final AlertDialog busy = Ui.busy(this, "Contacting GitHub...");
        new Thread(() -> {
            try {
                final GhAuth.Code code = GhAuth.start(cid);
                runOnUiThread(() -> {
                    Ui.dismiss(busy);
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("GitHub code", code.userCode));
                    final AlertDialog dlg = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle("Your code: " + code.userCode)
                            .setMessage("The code is copied. Tap Open GitHub, paste it, and approve. Come back here when GitHub says you are connected. This closes by itself.")
                            .setPositiveButton("Open GitHub", null)
                            .setNegativeButton("Cancel", (d, w) -> signInCancelled = true)
                            .setCancelable(false).create();
                    dlg.show();
                    // keep the dialog open after tapping Open GitHub, so the person can come back to it
                    dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b ->
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(code.verifyUrl))));
                    new Thread(() -> {
                        try {
                            final String tok = GhAuth.waitForToken(cid, code, () -> signInCancelled);
                            Prefs.setToken(getApplicationContext(), tok);
                            try { // fill the username so new repositories get the right owner
                                String login = new Gh(tok, x -> { }).whoami().optString("login", "");
                                if (Prefs.owner(getApplicationContext()).isEmpty() && !login.isEmpty())
                                    Prefs.put(getApplicationContext(), "owner", ProjectConfig.owner(login));
                            } catch (Exception ignored) { }
                            runOnUiThread(() -> {
                                Ui.dismiss(dlg);
                                Ui.toast(this, "Signed in to GitHub");
                                render();
                            });
                        } catch (Exception e) {
                            AppLog.w("Settings", "GitHub browser sign-in failed", e);
                            runOnUiThread(() -> {
                                Ui.dismiss(dlg);
                                if (!signInCancelled) Ui.toast(this, e.getMessage() == null ? "Sign-in failed" : e.getMessage());
                            });
                        }
                    }, "gh-device-poll").start();
                });
            } catch (Exception e) {
                AppLog.w("Settings", "GitHub sign-in could not start", e);
                runOnUiThread(() -> {
                    Ui.dismiss(busy);
                    Ui.toast(this, e.getMessage() == null ? "Could not reach GitHub" : e.getMessage());
                });
            }
        }, "gh-device-start").start();
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
                deleteInBackground(builds, () -> buildsBytes = -1);
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
                if (def.isEmpty() && !owner.isEmpty()) def = "https://github.com/" + owner + "/" + Prefs.ideRepo(this) + "/releases/download/toolchain/toolchain-arm64.zip";
                Ui.input(this, "Toolchain zip URL", "URL", def, url -> {
                    Prefs.put(this, "tc_url", url.trim());
                    install("Downloading toolchain...", log -> Toolchain.download(this, url.trim(), log));
                });
            } else if (i == 1) {
                Ui.menu(this, "Import build tools", new String[]{"Choose folder...", "Choose zip file..."}, k -> {
                    if (k == 0) folderPicker.pick(f -> install("Importing...", log -> Toolchain.installFolder(this, f, log)));
                    else filePicker.pick(f -> install("Importing...", log -> Toolchain.installZip(this, f, log)));
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
        boolean canBuild = p.id.equals("kotlin") || !p.termux.isEmpty();
        List<String> names = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        names.add(in ? "Download again" : "Download");
        acts.add(() -> runPack("Downloading " + p.label + "...", log -> Packs.installWithDeps(this, p.id, log), p));
        names.add("Install from file...");
        acts.add(() -> filePicker.pick(f -> runPack("Installing...", log -> Packs.importZip(this, p.id, f, log), p)));
        if (canBuild) {
            names.add("Run workflow: build " + p.label + " on GitHub");
            acts.add(() -> runPackWorkflow(p));
        }
        if (in) {
            names.add("Remove");
            acts.add(() -> Ui.confirm(this, "Remove " + p.label + "?", "The language is deleted from this device. You can install it again.", "Remove", () -> {
                Packs.remove(this, p.id);
                render();
            }));
        }
        Ui.menu(this, p.label + (p.what.isEmpty() ? "" : ": " + p.what), names.toArray(new String[0]), i2 -> acts.get(i2).run());
    }

    /** Starts the "Build language packs" workflow (packs.yml) in the user's IDE repository for one language. */
    void runPackWorkflow(Packs.Pack p) {
        final String tok = Prefs.token(this), owner = Prefs.owner(this), repo = Prefs.ideRepo(this);
        if (tok.isEmpty() || owner.isEmpty()) {
            Ui.toast(this, "Set your GitHub username and token first (Settings > GitHub account)");
            return;
        }
        String time = p.id.equals("kotlin") ? "a few minutes"
                : p.id.equals("rust") ? "several hours, and it may need a larger GitHub runner"
                : "about one hour or more";
        String needs = p.requires.isEmpty() ? "" : "\n\n" + p.label + " also needs " + Toolchain.join(p.requires, ", ")
                + ". Build that one too if it is not on your release yet.";
        Ui.confirm(this, "Run workflow", "This starts the \"Build language packs\" workflow in " + owner + "/" + repo
                + " on GitHub and builds the " + p.label + " pack. It takes " + time + ". When it has finished, come back here, open "
                + p.label + " and tap Download." + needs, "Run workflow", () -> {
            AlertDialog busy = Ui.busy(this, "Starting the workflow...");
            new Thread(() -> {
                String res;
                try {
                    new Gh(tok, s -> { }).runWorkflow(owner, repo, "packs.yml", new org.json.JSONObject().put("packs", p.id));
                    res = "Started. GitHub is now building " + p.label + ".\nProgress: github.com/" + owner + "/" + repo + "/actions";
                } catch (Throwable e) {
                    AppLog.e("Settings", "Starting packs.yml failed", e);
                    res = "Could not start the workflow: " + e.getMessage();
                }
                final String m = res;
                runOnUiThread(() -> {
                    Ui.dismiss(busy);
                    if (!Ui.alive(this)) return;
                    new MaterialAlertDialogBuilder(this).setTitle("Run workflow").setMessage(m).setPositiveButton("OK", null)
                            .setNeutralButton("Open on GitHub", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/" + owner + "/" + repo + "/actions/workflows/packs.yml")))).show();
                });
            }, "run-packs-workflow").start();
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

    /** True when the comma separated scope list holds exactly this scope ("public_repo" or "repo:status" is not "repo"). */
    static boolean hasScope(String list, String scope) {
        for (String s : list.split("[,\\s]+")) if (s.equals(scope)) return true;
        return false;
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
                    msg = "Signed in as " + login + ".\nScopes: " + sc + (hasScope(sc, "repo") && hasScope(sc, "workflow")
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
