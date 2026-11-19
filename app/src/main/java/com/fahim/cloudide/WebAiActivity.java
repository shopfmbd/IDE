package com.fahim.cloudide;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * An AI website (Claude, DeepSeek, ChatGPT or any other) inside the app, working on the project's files.
 *
 * <p>The person signs in to the website here as in any browser. Three buttons connect it to the project, and each one acts only when
 * tapped: <b>Send project</b> fills the chat box with the project (the person presses the website's own Send), <b>Apply reply</b>
 * reads the last reply on the page and writes the files it marks with a File line, and <b>Undo</b> puts the last write back.
 * There is no script bridge from the page into the app, so the website cannot read or write anything by itself.
 *
 * <p>Writes follow the rules of the built-in assistant (no paths outside the project, nothing that looks cut off, no masked keys),
 * keep a backup, and honor the Manual / Direct setting of the AI panel: manual shows a checklist first, direct writes the safe files.
 */
public class WebAiActivity extends AppCompatActivity {
    static final String EXTRA_PROJECT = "project";
    /** Files changed on disk from this screen; the editor reloads them when it comes back to the front. */
    static final Set<String> CHANGED = Collections.synchronizedSet(new LinkedHashSet<String>());
    static volatile String changedProject = "";

    private WebView web;
    private ProgressBar bar;
    private MaterialToolbar tb;
    private File proj;
    private WebAiScripts.Site site;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        Ui.edge(this);
        super.onCreate(state);
        String p = getIntent().getStringExtra(EXTRA_PROJECT);
        if (p == null || p.isEmpty()) {
            finish();
            return;
        }
        proj = new File(p);
        site = loadSite();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        tb = new MaterialToolbar(this);
        tb.setTitle(site.name);
        tb.setSubtitle(proj.getName());
        tb.setNavigationIcon(R.drawable.ic_back);
        tb.setNavigationOnClickListener(v -> finish());
        buildMenu(tb.getMenu());
        tb.setOnMenuItemClickListener(this::onAction);
        root.addView(tb, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        root.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 3)));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // look like a normal mobile browser: some sign-in and bot checks refuse the default "wv" WebView agent
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replaceAll("Version/\\d+\\.\\d+ ", ""));
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String sch = r.getUrl().getScheme();
                return !("http".equals(sch) || "https".equals(sch)); // never hand the page an intent: or file: link
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap icon) { bar.setVisibility(View.VISIBLE); }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) Ui.toast(WebAiActivity.this, "Could not load the page: " + e.getDescription());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                bar.setProgress(p);
                bar.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        row.setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer, 0xFF202020));
        MaterialButton send = new MaterialButton(this);
        send.setText("Send project");
        send.setOnClickListener(v -> showSendDialog());
        MaterialButton apply = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        apply.setText("Apply reply");
        apply.setOnClickListener(v -> readReply());
        MaterialButton undo = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        undo.setText("Undo");
        undo.setOnClickListener(v -> confirmUndo());
        for (MaterialButton bt : new MaterialButton[]{send, apply, undo}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, bt == undo ? 0.7f : 1f);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            bt.setSingleLine(true);
            bt.setInsetTop(0);
            bt.setInsetBottom(0);
            row.addView(bt, lp);
        }
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        Ui.insets(root, true, true);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) web.goBack();
                else finish();
            }
        });

        web.loadUrl(site.url);
        if (!Prefs.bool(this, "web_ai_intro", false)) {
            Prefs.put(this, "web_ai_intro", true);
            showHelp();
        }
    }

    // ------------------------------------------------------------ sites
    private WebAiScripts.Site loadSite() {
        String id = Prefs.str(this, "web_ai_site");
        String custom = Prefs.str(this, "web_ai_custom");
        if (id.equals("custom") && custom.startsWith("https://")) return new WebAiScripts.Site("custom", "Other site", custom, WebAiScripts.GENERIC_SELECTORS);
        return WebAiScripts.byId(id.isEmpty() ? "claude" : id);
    }

    private void buildMenu(Menu m) {
        SubMenu sm = m.addSubMenu(0, 0, 0, "Website");
        for (int i = 0; i < WebAiScripts.SITES.length; i++) {
            MenuItem it = sm.add(1, 10 + i, i, WebAiScripts.SITES[i].name);
            it.setCheckable(true);
            it.setChecked(WebAiScripts.SITES[i].id.equals(site.id));
        }
        MenuItem other = sm.add(1, 19, 9, "Other website...");
        other.setCheckable(true);
        other.setChecked(site.id.equals("custom"));
        sm.setGroupCheckable(1, true, true);
        m.add(0, 2, 1, "Reload");
        m.add(0, 3, 2, "Start page of this website");
        m.add(0, 4, 3, "Apply reply from clipboard");
        m.add(0, 5, 4, "How this works");
    }

    private boolean onAction(MenuItem it) {
        int id = it.getItemId();
        if (id >= 10 && id < 10 + WebAiScripts.SITES.length) {
            it.setChecked(true);
            useSite(WebAiScripts.SITES[id - 10]);
            return true;
        }
        switch (id) {
            case 19:
                askCustomSite(it);
                return true;
            case 2:
                web.reload();
                return true;
            case 3:
                web.loadUrl(site.url);
                return true;
            case 4:
                applyClipboard();
                return true;
            case 5:
                showHelp();
                return true;
            default:
                return false;
        }
    }

    private void useSite(WebAiScripts.Site s) {
        site = s;
        Prefs.put(this, "web_ai_site", s.id);
        tb.setTitle(s.name);
        web.loadUrl(s.url);
    }

    private void askCustomSite(final MenuItem it) {
        final EditText url = new EditText(this);
        url.setHint("https://example.com/chat");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(Prefs.str(this, "web_ai_custom"));
        LinearLayout box = new LinearLayout(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(url, new LinearLayout.LayoutParams(-1, -2));
        new MaterialAlertDialogBuilder(this)
                .setTitle("Any AI chat website")
                .setMessage("The chat page to open. It must start with https://. The reply is read from the page with general rules; if that does not work, copy the reply with the site's own Copy button and use Apply reply from clipboard.")
                .setView(box)
                .setPositiveButton("Open", (d, w) -> {
                    String u = url.getText().toString().trim();
                    if (!u.startsWith("https://") || u.length() < 12) {
                        Ui.toast(this, "Use an address that starts with https://");
                        return;
                    }
                    Prefs.put(this, "web_ai_custom", u);
                    it.setChecked(true);
                    useSite(new WebAiScripts.Site("custom", "Other site", u, WebAiScripts.GENERIC_SELECTORS));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showHelp() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("AI websites with your project")
                .setMessage("1. Sign in to the website here, like in any browser. If \"Continue with Google\" is refused, use the email option.\n\n"
                        + "2. Send project: type what you want done. The project's file list and source go into the chat box. You press the website's own Send button.\n\n"
                        + "3. When the reply is finished, tap Apply reply. The AI can create or replace files (File:), change part of a file (Edit: with SEARCH/REPLACE), "
                        + "and rename or move files (Rename:). You confirm the list first; with direct editing on (AI panel), safe changes are applied at once.\n\n"
                        + "4. Undo puts the last write back.\n\n"
                        + "Nothing is sent or written unless you tap a button. Keys and credential files are never put in the chat box. "
                        + "The text you send goes to that website under its own terms and privacy rules, so use it the way the website allows.")
                .setPositiveButton("OK", null)
                .show();
    }

    // ------------------------------------------------------------ project to the chat box
    private void showSendDialog() {
        final EditText task = new EditText(this);
        task.setHint("What should the AI do? For example: add a dark mode switch");
        task.setMinLines(3);
        task.setGravity(Gravity.TOP | Gravity.START);
        task.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        final CheckBox all = new CheckBox(this);
        all.setText("Include the source code of the project (up to about " + AiProtocol.estimateTokens(AiProtocol.MAX_PROJECT_CHARS) / 1000 + "k tokens)");
        all.setChecked(true);
        TextView note = new TextView(this);
        note.setText("The file list is always included. Keys, passwords and credential files are left out.");
        note.setTextSize(12);
        note.setPadding(0, Ui.dp(this, 6), 0, 0);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(task, new LinearLayout.LayoutParams(-1, -2));
        box.addView(all, new LinearLayout.LayoutParams(-1, -2));
        box.addView(note, new LinearLayout.LayoutParams(-1, -2));
        new MaterialAlertDialogBuilder(this)
                .setTitle("Put the project in the chat box")
                .setView(box)
                .setPositiveButton("Put in chat box", (d, w) -> sendProject(task.getText().toString(), all.isChecked()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendProject(final String task, final boolean wholeCode) {
        Ui.toast(this, "Reading the project...");
        io.execute(() -> {
            try {
                final AiProtocol.Context c = WebAiProject.context(proj, task, wholeCode);
                final String text = WebAiProject.prompt(c);
                runOnUiThread(() -> insert(text, c));
            } catch (Exception e) {
                AppLog.e("WebAi", "building the request failed", e);
                runOnUiThread(() -> Ui.toast(this, "Could not read the project: " + e.getMessage()));
            }
        });
    }

    private void insert(final String text, final AiProtocol.Context c) {
        if (isFinishing()) return;
        try {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("CloudIDE project", text));
        } catch (Exception e) {
            AppLog.e("WebAi", "clipboard failed", e);
        }
        final String what = (c.code.isEmpty() ? c.tree.size() + " file names" : c.code.size() + " files")
                + ", about " + AiProtocol.estimateTokens(text.length()) / 1000 + "k tokens";
        web.evaluateJavascript(WebAiScripts.insertScript(text), r -> {
            boolean ok = r != null && r.contains("ok");
            Ui.toast(this, ok ? "Put " + what + " in the chat box. Check it, then press the website's Send."
                    : "Could not find the chat box. The text is on your clipboard: tap the box and paste.");
        });
    }

    // ------------------------------------------------------------ reply to files
    private void readReply() {
        web.evaluateJavascript(WebAiScripts.readScript(site.replySelectors), this::onReplyRead);
    }

    private void onReplyRead(String raw) {
        String md = null;
        try {
            if (raw != null && !raw.equals("null")) {
                Object v = new JSONTokener(raw).nextValue();
                if (v instanceof String) {
                    JSONObject o = new JSONObject((String) v);
                    if (o.optBoolean("ok")) md = o.optString("md");
                }
            }
        } catch (Exception e) {
            AppLog.e("WebAi", "reading the reply failed", e);
        }
        if (md == null || md.trim().isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Could not read the reply")
                    .setMessage("This page did not show a reply in a form I can read (websites change their layout). Use the website's own Copy button on the reply, then choose Use clipboard.")
                    .setPositiveButton("Use clipboard", (d, w) -> applyClipboard())
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        handleReply(md);
    }

    private void applyClipboard() {
        String t = "";
        try {
            ClipData d = ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).getPrimaryClip();
            if (d != null && d.getItemCount() > 0) t = String.valueOf(d.getItemAt(0).coerceToText(this));
        } catch (Exception e) {
            AppLog.e("WebAi", "clipboard read failed", e);
        }
        if (t.trim().isEmpty()) Ui.toast(this, "The clipboard is empty. Copy the AI's reply first.");
        else handleReply(t);
    }

    private void handleReply(final String md) {
        io.execute(() -> {
            final List<AiEdits.Change> plans = WebAiProject.plan(proj, md);
            runOnUiThread(() -> {
                if (!isFinishing()) present(plans);
            });
        });
    }

    private void present(List<AiEdits.Change> plans) {
        if (plans.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Nothing to apply")
                    .setMessage("The reply has no File, Edit or Rename steps I can use, or the project already matches them. "
                            + "Ask the AI: \"Use File: path for whole files, Edit: path with SEARCH/REPLACE blocks for small changes, "
                            + "and Rename: old -> new, exactly as the project paths are written.\"")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        List<AiEdits.Change> safe = new ArrayList<>();
        for (AiEdits.Change p : plans) if (p.problem == null) safe.add(p);
        if (!safe.isEmpty() && Prefs.bool(this, "ai_direct", false)) {
            write(plans, safe);
            return;
        }
        review(plans, safe);
    }

    /** Manual mode: a checklist of the files that can be written, and a way to see why others were left out. */
    private void review(final List<AiEdits.Change> all, final List<AiEdits.Change> safe) {
        final int skipped = all.size() - safe.size();
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this);
        if (safe.isEmpty()) {
            b.setTitle("Nothing safe to apply").setMessage(skippedText(all)).setPositiveButton("OK", null).show();
            return;
        }
        final String[] labels = new String[safe.size()];
        final boolean[] on = new boolean[safe.size()];
        for (int i = 0; i < safe.size(); i++) {
            AiEdits.Change p = safe.get(i);
            labels[i] = p.describe();
            on[i] = true;
        }
        b.setTitle("Apply " + safe.size() + (safe.size() == 1 ? " change" : " changes") + " to the project?")
                .setMultiChoiceItems(labels, on, (d, which, checked) -> on[which] = checked)
                .setPositiveButton("Apply selected", (d, w) -> {
                    List<AiEdits.Change> chosen = new ArrayList<>();
                    for (int i = 0; i < on.length; i++) if (on[i]) chosen.add(safe.get(i));
                    if (chosen.isEmpty()) Ui.toast(this, "Nothing selected.");
                    else write(all, chosen);
                })
                .setNegativeButton("Cancel", null);
        if (skipped > 0) b.setNeutralButton(skipped + " skipped: why?", (d, w) ->
                new MaterialAlertDialogBuilder(this).setTitle("Not applied").setMessage(skippedText(all)).setPositiveButton("OK", null).show());
        b.show();
    }

    private String skippedText(List<AiEdits.Change> all) {
        StringBuilder sb = new StringBuilder();
        for (AiEdits.Change p : all) if (p.problem != null) sb.append(p.label()).append(": ").append(p.problem).append(".\n\n");
        if (sb.length() == 0) return "Everything in the reply can be applied.";
        return sb.append("Check these by hand, or ask the AI to fix them (for an edit: copy the exact lines from the file).").toString();
    }

    private void write(final List<AiEdits.Change> all, final List<AiEdits.Change> chosen) {
        io.execute(() -> {
            try {
                final List<String> done = WebAiProject.apply(proj, chosen);
                changedProject = proj.getAbsolutePath();
                CHANGED.addAll(done);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    StringBuilder sb = new StringBuilder();
                    int applied = 0;
                    for (AiEdits.Change c : chosen) {
                        if (c.problem != null) continue;
                        sb.append(c.describe()).append('\n');
                        applied++;
                    }
                    int skipped = 0;
                    for (AiEdits.Change p : all) if (p.problem != null) skipped++;
                    if (skipped > 0) sb.append('\n').append(skipped).append(skipped == 1 ? " step was not applied (it looked unsafe)." : " steps were not applied (they looked unsafe).");
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Applied " + applied + (applied == 1 ? " change" : " changes"))
                            .setMessage(sb.toString().trim())
                            .setPositiveButton("OK", null)
                            .setNegativeButton("Undo", (d, w) -> undo())
                            .show();
                });
            } catch (Exception e) {
                AppLog.e("WebAi", "writing files failed", e);
                runOnUiThread(() -> Ui.toast(this, "Applying failed: " + e.getMessage() + ". Undo restores what was changed."));
            }
        });
    }

    // ------------------------------------------------------------ undo
    private void confirmUndo() {
        io.execute(() -> {
            final List<String> files = WebAiProject.lastBatch(proj);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (files == null) {
                    Ui.toast(this, "Nothing to undo.");
                    return;
                }
                Ui.confirm(this, "Undo the last change?", "Puts these back as they were:\n" + android.text.TextUtils.join("\n", files), "Undo", this::undo);
            });
        });
    }

    private void undo() {
        io.execute(() -> {
            try {
                final List<String> done = WebAiProject.undoLast(proj);
                if (done != null) {
                    changedProject = proj.getAbsolutePath();
                    CHANGED.addAll(done);
                }
                runOnUiThread(() -> Ui.toast(this, done == null ? "Nothing to undo." : "Restored " + done.size() + (done.size() == 1 ? " file." : " files.")));
            } catch (Exception e) {
                AppLog.e("WebAi", "undo failed", e);
                runOnUiThread(() -> Ui.toast(this, "Undo failed: " + e.getMessage()));
            }
        });
    }

    // ------------------------------------------------------------ lifecycle
    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
        CookieManager.getInstance().flush(); // keep the sign-in
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
    }
}
