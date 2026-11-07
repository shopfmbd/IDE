package com.fahim.cloudide;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.tabs.TabLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.json.JSONArray;
import org.json.JSONObject;

public class EditorActivity extends AppCompatActivity implements EditorView.Host {
    static final String[] SYMS = {"Tab", "\u2190", "\u2192", "\u2191", "\u2193", "{}", "()", "[]", ";", "=", "\"\"", "'", "<>", "/", "\\",
            ":", ",", ".", "+", "-", "*", "&", "|", "!", "_", "#", "$", "@"};

    File proj;
    ProjectConfig cfg;
    EditorView ed;
    TabLayout tabs, ptabs;
    // terminal, error bar, saving files
    Shell shell;
    final StringBuilder termBuf = new StringBuilder();
    final List<String> termHist = new ArrayList<>();
    TextView term, termPrompt, errMsg;
    EditText termIn;
    ScrollView termScroll;
    View termBox, errBar, termRun;
    boolean termBusy;
    File pendingSave;
    final ActivityResultLauncher<String> saveLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("*/*"), uri -> onSaveTarget(uri));
    DrawerLayout drawer;
    MaterialToolbar toolbar;
    LinearProgressIndicator progress;
    View welcome, panel, findBar, pCancel, pInstall;
    RecyclerView tree, results;
    ScrollView logScroll;
    TextView log, findCount, projName;
    EditText findQ, replQ;
    CheckBox cbCase, cbRegex;

    final LinkedHashMap<File, Doc> docs = new LinkedHashMap<>();
    final List<File> rows = new ArrayList<>();
    final List<Integer> depths = new ArrayList<>();
    final Set<String> expanded = new HashSet<>();
    final Map<String, String> status = new HashMap<>();
    final Map<String, String> shaCache = new HashMap<>();
    final Map<String, List<Diag>> local = new HashMap<>();
    final List<Diag> shown = new ArrayList<>();
    final StringBuilder logBuf = new StringBuilder();
    List<Diag> buildDiags = new ArrayList<>();
    /** Goes up every time a build ends, and whether the last one worked: the assistant's build action waits on these. */
    volatile int buildSeq;
    volatile boolean lastBuildOk;
    List<Diag> found = new ArrayList<>();
    Doc cur;
    AiPanel ai;
    /** Project file names for the assistant, refreshed off the main thread. */
    volatile List<String> aiTree = new ArrayList<>();
    boolean ignoreTab, ignorePTab;
    int ptab, cursorLine = 1, cursorCol = 1;
    String buildMsg = "";
    CloudBuild job;
    LocalBuild ljob;
    LocalLang llang;
    SdkCmd.Session sdk;
    TreeAdapter treeAd;
    ResultAdapter resAd;
    final Handler h = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newSingleThreadExecutor();
    Runnable commitRun, saveRun, statusRun;

    // ------------------------------------------------------------ lifecycle
    @Override
    protected void onCreate(Bundle b) {
        Ui.edge(this);
        super.onCreate(b);
        String dir = getIntent().getStringExtra("dir");
        if (dir == null || !new File(dir).isDirectory()) {
            Ui.toast(this, "This project folder is not available any more");
            finish();
            return;
        }
        setContentView(R.layout.activity_editor);
        Ui.insets(findViewById(R.id.content), true, true);
        Ui.insets(findViewById(R.id.drawerPanel), true, true);
        proj = new File(dir);
        cfg = ProjectConfig.load(proj);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationIcon(R.drawable.ic_menu);
        drawer = findViewById(R.id.drawer);
        toolbar.setNavigationOnClickListener(v -> drawer.openDrawer(GravityCompat.START));
        ed = findViewById(R.id.editor);
        ed.host = this;
        tabs = findViewById(R.id.tabs);
        ptabs = findViewById(R.id.ptabs);
        progress = findViewById(R.id.progress);
        welcome = findViewById(R.id.welcome);
        panel = findViewById(R.id.panel);
        findBar = findViewById(R.id.findBar);
        pCancel = findViewById(R.id.pCancel);
        pInstall = findViewById(R.id.pInstall);
        tree = findViewById(R.id.tree);
        results = findViewById(R.id.results);
        logScroll = findViewById(R.id.logScroll);
        termBox = findViewById(R.id.termBox);
        termScroll = findViewById(R.id.termScroll);
        term = findViewById(R.id.term);
        termPrompt = findViewById(R.id.termPrompt);
        termIn = findViewById(R.id.termIn);
        termRun = findViewById(R.id.termRun);
        errBar = findViewById(R.id.errBar);
        errMsg = findViewById(R.id.errMsg);
        findViewById(R.id.errPrev).setOnClickListener(v -> jumpProblem(-1));
        findViewById(R.id.errNext).setOnClickListener(v -> jumpProblem(1));
        errMsg.setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("error", errMsg.getText()));
            Ui.toast(this, "Error copied");
        });
        termInit();
        log = findViewById(R.id.log);
        findCount = findViewById(R.id.findCount);
        projName = findViewById(R.id.projName);
        findQ = findViewById(R.id.findQ);
        replQ = findViewById(R.id.replQ);
        cbCase = findViewById(R.id.cbCase);
        cbRegex = findViewById(R.id.cbRegex);

        commitRun = () -> {
            if (cur != null) cur.commit(ed.getText().toString());
        };
        saveRun = () -> {
            if (cur != null && cur.dirty && Prefs.bool(this, "autosave", true)) saveDoc(cur);
        };
        statusRun = () -> {
            if (io.isShutdown()) return;
            io.execute(() -> {
            Map<String, String> m = new HashMap<>();
            Map<String, String> fresh = new HashMap<>(); // only files that still exist, so the cache cannot grow without bound
            JSONObject pushed = cfg.pushed;
            if (pushed.length() > 0) {
                for (File f : Io.projectFiles(proj)) {
                    String rel = Io.rel(proj, f);
                    String key = rel + "|" + f.length() + "|" + f.lastModified();
                    String sha = shaCache.get(key);
                    if (sha == null) {
                        try {
                            sha = Io.sha(f);
                        } catch (Exception e) {
                            continue;
                        }
                    }
                    fresh.put(key, sha);
                    String old = pushed.optString(rel, "");
                    if (old.isEmpty()) m.put(rel, "A");
                    else if (!old.equals(sha)) m.put(rel, "M");
                }
            }
            shaCache.clear();
            shaCache.putAll(fresh);
            runOnUiThread(() -> {
                status.clear();
                status.putAll(m);
                treeAd.notifyDataSetChanged();
            });
            });
        };

        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab t) {
                if (!ignoreTab && t.getTag() instanceof Doc) show((Doc) t.getTag(), 0);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab t) { }

            @Override
            public void onTabReselected(TabLayout.Tab t) { }
        });
        ptabs.addTab(ptabs.newTab().setText("Problems"));
        ptabs.addTab(ptabs.newTab().setText("Search"));
        ptabs.addTab(ptabs.newTab().setText("Build log"));
        ptabs.addTab(ptabs.newTab().setText("Terminal"));
        ptabs.addTab(ptabs.newTab().setText("AI").setIcon(R.drawable.ic_t_ai));
        ai = new AiPanel(this, new AiHost());
        ai.setVisibility(View.GONE);
        ((ViewGroup) termBox.getParent()).addView(ai, new android.widget.FrameLayout.LayoutParams(-1, -1));
        if (getIntent().getBooleanExtra("ai_resume", false)) {
            // the assistant opened or created this project: show its panel and let it carry on once the screen is up
            ptabs.post(() -> {
                showPanel(4);
                ai.resumeAgent();
            });
        }
        ed.setCustomSelectionActionModeCallback(new android.view.ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(android.view.ActionMode m, Menu menu) {
                menu.add(0, 7001, 100, "Ask AI");
                menu.add(0, 7002, 101, "Explain");
                return true;
            }

            @Override
            public boolean onPrepareActionMode(android.view.ActionMode m, Menu menu) { return false; }

            @Override
            public boolean onActionItemClicked(android.view.ActionMode m, MenuItem item) {
                if (item.getItemId() == 7001) {
                    m.finish();
                    showPanel(4);
                    ai.focusInput();
                    return true;
                }
                if (item.getItemId() == 7002) {
                    m.finish();
                    aiQuick(0);
                    return true;
                }
                return false;
            }

            @Override
            public void onDestroyActionMode(android.view.ActionMode m) { }
        });
        ptabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab t) {
                if (!ignorePTab) showPanel(t.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab t) { }

            @Override
            public void onTabReselected(TabLayout.Tab t) { }
        });

        treeAd = new TreeAdapter();
        tree.setLayoutManager(new LinearLayoutManager(this));
        tree.setAdapter(treeAd);
        resAd = new ResultAdapter();
        results.setLayoutManager(new LinearLayoutManager(this));
        results.setAdapter(resAd);

        projName.setText(proj.getName());
        findViewById(R.id.btnNewFile).setOnClickListener(v -> ask("New file (paths allowed)", "", s -> create(proj, s, false)));
        findViewById(R.id.btnNewFolder).setOnClickListener(v -> ask("New folder", "", s -> create(proj, s, true)));
        findViewById(R.id.btnRefresh).setOnClickListener(v -> {
            rebuildTree();
            scheduleStatus();
        });

        findViewById(R.id.findNext).setOnClickListener(v -> findNext(true));
        findViewById(R.id.findPrev).setOnClickListener(v -> findNext(false));
        findViewById(R.id.findClose).setOnClickListener(v -> findBar.setVisibility(View.GONE));
        findViewById(R.id.repOne).setOnClickListener(v -> replaceOne());
        findViewById(R.id.repAll).setOnClickListener(v -> replaceAll());
        findQ.setOnEditorActionListener((tv, a, ev) -> {
            findNext(true);
            return true;
        });

        findViewById(R.id.pClose).setOnClickListener(v -> panel.setVisibility(View.GONE));
        findViewById(R.id.pMore).setOnClickListener(v -> logMenu());
        findViewById(R.id.wFiles).setOnClickListener(v -> drawer.openDrawer(GravityCompat.START));
        findViewById(R.id.wQuick).setOnClickListener(v -> quickOpen());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawers();
                else if (findBar.getVisibility() == View.VISIBLE) findBar.setVisibility(View.GONE);
                else if (panel.getVisibility() == View.VISIBLE) panel.setVisibility(View.GONE);
                else leaveProject();
            }
        });
        pInstall.setOnClickListener(v -> {
            getFiles();
        });
        pCancel.setOnClickListener(v -> {
            if (job != null) job.cancel();
            if (ljob != null) ljob.cancel();
            if (llang != null) llang.cancel();
            Ui.toast(this, "Cancelling...");
        });

        buildSymbols();
        applyPrefs();

        String last = Prefs.str(this, "last_" + proj.getName());
        File lf = last.isEmpty() ? null : new File(last);
        if (lf == null || !lf.isFile()) lf = findMain(proj);
        for (File q = lf == null ? null : lf.getParentFile(); q != null && !q.equals(proj); q = q.getParentFile())
            expanded.add(q.getAbsolutePath());
        rebuildTree();
        scheduleStatus();
        if (lf != null) openFile(lf, 0);
        else drawer.post(() -> drawer.openDrawer(GravityCompat.START));
        drawer.post(this::checkPluginsOnOpen);
        updateSubtitle();
    }

    boolean hasUnsaved() {
        for (Doc d : docs.values()) if (d.dirty) return true;
        return false;
    }

    /** Leaves the project. With auto save off, unsaved files are asked about instead of being written or dropped silently. */
    void leaveProject() {
        if (!Prefs.bool(this, "autosave", true) && hasUnsaved()) {
            new MaterialAlertDialogBuilder(this).setTitle("Save changes?")
                    .setMessage("Some files have changes that are not saved yet.")
                    .setPositiveButton("Save all", (d, w) -> {
                        saveAll();
                        finish();
                    })
                    .setNegativeButton("Discard", (d, w) -> finish())
                    .setNeutralButton("Cancel", null).show();
            return;
        }
        saveAll();
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (Prefs.bool(this, "autosave", true)) saveAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncWebAiChanges();
    }

    /** Opens the AI website screen. Everything is saved first, because that screen reads and writes the files on disk. */
    void openWebAiScreen() {
        if (proj == null) return;
        saveAll();
        startActivity(new Intent(this, WebAiActivity.class).putExtra(WebAiActivity.EXTRA_PROJECT, proj.getAbsolutePath()));
    }

    /** After the AI website screen wrote or restored files: open tabs get the new text, closed files just show up in the tree. */
    private void syncWebAiChanges() {
        if (proj == null || WebAiActivity.CHANGED.isEmpty() || !proj.getAbsolutePath().equals(WebAiActivity.changedProject)) return;
        final List<String> rels;
        synchronized (WebAiActivity.CHANGED) {
            rels = new ArrayList<>(WebAiActivity.CHANGED);
            WebAiActivity.CHANGED.clear();
        }
        for (String rel : rels) {
            File f = new File(proj, rel);
            Doc d = docs.get(f);
            if (f.isFile()) {
                if (d == null) continue;
                try {
                    String text = Io.read(f);
                    if (d == cur) {
                        aiEdit(0, 0, text, true); // the editor's own undo history keeps the text from before
                        saveDoc(d);
                    } else {
                        d.commit(text);
                        d.text = text;
                        d.dirty = false;
                        updateTab(d);
                        runChecks(d);
                    }
                } catch (Exception e) {
                    AppLog.e("Editor", "reload after the AI website failed: " + rel, e);
                }
            } else if (d != null) {
                d.dirty = false;
                reallyClose(d); // the file was removed by Undo
            }
        }
        rebuildTree();
        refreshAiTree();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        if (ai != null) ai.shutdown();
        io.shutdown();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyPrefs();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.isCtrlPressed()) {
            int k = ev.getKeyCode();
            if (k == KeyEvent.KEYCODE_S) { saveAll(); return true; }
            if (k == KeyEvent.KEYCODE_Z) { undo(); return true; }
            if (k == KeyEvent.KEYCODE_Y) { redo(); return true; }
            if (k == KeyEvent.KEYCODE_F) { toggleFind(); return true; }
            if (k == KeyEvent.KEYCODE_P) {
                if (ev.isShiftPressed()) showPalette();
                else quickOpen();
                return true;
            }
            if (k == KeyEvent.KEYCODE_I) { showPanel(4); ai.focusInput(); return true; }
            if (k == KeyEvent.KEYCODE_B) { build(true); return true; }
            if (k == KeyEvent.KEYCODE_SLASH) { ed.toggleComment(); return true; }
        }
        return super.dispatchKeyEvent(ev);
    }

    void applyPrefs() {
        ed.applyTheme(EditorTheme.get(this));
        ed.tab = Prefs.tab(this);
        ed.pairs = Prefs.bool(this, "pairs", true);
        ed.bracketMatch = Prefs.bool(this, "bracket", true);
        ed.showLines = Prefs.bool(this, "lines", true);
        ed.autoComplete = Prefs.bool(this, "complete", true);
        ed.setTextSize(TypedValue.COMPLEX_UNIT_SP, Prefs.font(this));
        ed.setWrap(Prefs.bool(this, "wrap", true));
        ed.guides = Prefs.bool(this, "guides", true);
        ed.occurrences = Prefs.bool(this, "occurrences", true);
        Fonts.apply(ed, this);
        if (log != null) Fonts.apply(log, this);
        if (term != null) Fonts.apply(term, this);
        if (termPrompt != null) Fonts.apply(termPrompt, this);
        if (termIn != null) Fonts.apply(termIn, this);
        ed.updateGutter();
        ed.invalidate();
    }

    void buildSymbols() {
        LinearLayout bar = findViewById(R.id.symbols);
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        int col = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFF000000);
        for (String s : SYMS) {
            TextView t = new TextView(this);
            t.setText(s);
            t.setTextSize(16);
            t.setTypeface(Typeface.MONOSPACE);
            t.setGravity(Gravity.CENTER);
            t.setMinWidth(Ui.dp(this, 42));
            t.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
            t.setTextColor(col);
            t.setBackgroundResource(tv.resourceId);
            t.setOnClickListener(v -> sym(s));
            bar.addView(t, new LinearLayout.LayoutParams(-2, -1));
        }
    }

    void sym(String s) {
        if (s.equals("Tab")) ed.tabKey();
        else if (s.equals("\u2190")) ed.moveCursor(-1, 0);
        else if (s.equals("\u2192")) ed.moveCursor(1, 0);
        else if (s.equals("\u2191")) ed.moveCursor(0, -1);
        else if (s.equals("\u2193")) ed.moveCursor(0, 1);
        else if (s.length() == 2 && "{}()[]\"\"<>".contains(s)) ed.wrapSel(s.substring(0, 1), s.substring(1));
        else ed.insertText(s);
        ed.requestFocus();
    }

    // ------------------------------------------------------------ editor host callbacks
    @Override
    public void onUserEdit() {
        if (cur == null) return;
        if (!cur.dirty) {
            cur.dirty = true;
            updateTab(cur);
        }
        h.removeCallbacks(commitRun);
        h.postDelayed(commitRun, 600);
        h.removeCallbacks(saveRun);
        h.postDelayed(saveRun, 2500);
    }

    @Override
    public void onCursor(int line, int col) {
        cursorLine = line;
        cursorCol = col;
        updateSubtitle();
    }

    @Override
    public void onZoom(float sp) {
        Prefs.sp(this).edit().putFloat("font", (float) Math.round(sp)).apply();
    }

    void updateSubtitle() {
        updateErrBar();
        if (getSupportActionBar() == null) return;
        getSupportActionBar().setTitle(proj.getName());
        String s;
        if (!buildMsg.isEmpty()) s = buildMsg + "...";
        else if (cur != null) s = cur.file.getName() + "    Ln " + cursorLine + ", Col " + cursorCol;
        else s = cfg.repo.isEmpty() ? "" : cfg.owner + "/" + cfg.repo;
        getSupportActionBar().setSubtitle(s);
    }

    // ------------------------------------------------------------ documents and tabs
    File findMain(File d) {
        File[] k = d.listFiles();
        if (k == null) return null;
        Arrays.sort(k);
        for (File f : k) {
            if (f.isDirectory()) {
                if (Io.skipDir(f.getName())) continue;
                File r = findMain(f);
                if (r != null) return r;
            } else if (f.getName().startsWith("MainActivity.")) return f;
        }
        return null;
    }

    void openFile(File f, int line) {
        if (Io.isBinary(f.getName())) {
            Ui.toast(this, "Binary files can't be edited here");
            return;
        }
        if (f.length() > 3000000) {
            Ui.toast(this, "File is too large to edit");
            return;
        }
        Doc d = docs.get(f);
        if (d == null) {
            try {
                d = new Doc(f);
                d.text = Io.read(f);
                d.snap = d.text;
            } catch (Exception e) {
                AppLog.e("Editor", "Opening a file failed", e);
                Ui.toast(this, "Cannot open: " + e.getMessage());
                return;
            }
            docs.put(f, d);
            addTab(d);
        }
        show(d, line);
    }

    void show(Doc d, int line) {
        if (cur == d) {
            if (line > 0) ed.post(() -> ed.goToLine(line));
            drawer.closeDrawers();
            return;
        }
        if (cur != null) stash(cur);
        cur = d;
        ed.load(d.text, d.lang);
        ed.setSelection(Math.min(d.sel, ed.length()));
        ed.setMarks(marksFor(d));
        welcome.setVisibility(View.GONE);
        selectTab(d);
        if (line > 0) ed.post(() -> ed.goToLine(line));
        else if (d.scroll > 0) ed.post(() -> ed.scrollTo(0, d.scroll));
        drawer.closeDrawers();
        Prefs.put(this, "last_" + proj.getName(), d.file.getAbsolutePath());
        updateSubtitle();
        treeAd.notifyDataSetChanged();
    }

    void stash(Doc d) {
        d.text = ed.getText().toString();
        d.sel = ed.getSelectionStart();
        d.scroll = ed.getScrollY();
        d.commit(d.text);
        if (d.dirty && Prefs.bool(this, "autosave", true)) saveDoc(d);
    }

    boolean saveDoc(Doc d) {
        try {
            String t = d == cur ? ed.getText().toString() : d.text;
            Io.write(d.file, t);
            d.text = t;
            d.dirty = false;
            updateTab(d);
            runChecks(d);
            scheduleStatus();
            return true;
        } catch (Exception e) {
            AppLog.e("Editor", "Saving a file failed", e);
            Ui.toast(this, "Save failed: " + e.getMessage());
            return false;
        }
    }

    void saveAll() {
        for (Doc d : new ArrayList<>(docs.values())) if (d.dirty) saveDoc(d);
    }

    void runChecks(Doc d) {
        String rel = Io.rel(proj, d.file);
        local.put(rel, Check.run(rel, d.text));
        if (d == cur) ed.setMarks(marksFor(d));
        refreshResults();
        updatePanelTitles();
    }

    Map<Integer, Integer> marksFor(Doc d) {
        Map<Integer, Integer> m = new HashMap<>();
        String rel = Io.rel(proj, d.file);
        List<Diag> all = new ArrayList<>();
        List<Diag> l = local.get(rel);
        if (l != null) all.addAll(l);
        all.addAll(buildDiags);
        for (Diag x : all) {
            if (x.line > 0 && x.file.equals(rel) && x.sev < 3) {
                Integer old = m.get(x.line);
                if (old == null || x.sev < old) m.put(x.line, x.sev);
            }
        }
        return m;
    }

    void addTab(Doc d) {
        TabLayout.Tab t = tabs.newTab();
        View v = LayoutInflater.from(this).inflate(R.layout.item_tab, null);
        ((ImageView) v.findViewById(R.id.close)).setOnClickListener(x -> closeDoc(d));
        t.setCustomView(v);
        t.setTag(d);
        ignoreTab = true;
        tabs.addTab(t, false);
        ignoreTab = false;
        updateTab(d);
    }

    int indexOfTab(Doc d) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            TabLayout.Tab t = tabs.getTabAt(i);
            if (t != null && t.getTag() == d) return i;
        }
        return -1;
    }

    void updateTab(Doc d) {
        int i = indexOfTab(d);
        if (i < 0) return;
        TabLayout.Tab t = tabs.getTabAt(i);
        if (t == null || t.getCustomView() == null) return;
        ((TextView) t.getCustomView().findViewById(R.id.title)).setText((d.dirty ? "\u2022 " : "") + d.file.getName());
    }

    void selectTab(Doc d) {
        int i = indexOfTab(d);
        if (i < 0) return;
        TabLayout.Tab t = tabs.getTabAt(i);
        if (t == null) return;
        ignoreTab = true;
        t.select();
        ignoreTab = false;
    }

    void closeDoc(Doc d) {
        if (d.dirty && !Prefs.bool(this, "autosave", true)) {
            new MaterialAlertDialogBuilder(this).setTitle("Save " + d.file.getName() + "?")
                    .setPositiveButton("Save", (x, w) -> {
                        saveDoc(d);
                        reallyClose(d);
                    })
                    .setNegativeButton("Discard", (x, w) -> reallyClose(d))
                    .setNeutralButton("Cancel", null).show();
            return;
        }
        if (d.dirty) saveDoc(d);
        reallyClose(d);
    }

    void reallyClose(Doc d) {
        int idx = indexOfTab(d);
        docs.remove(d.file);
        boolean wasCur = d == cur;
        if (idx >= 0) {
            ignoreTab = true;
            tabs.removeTabAt(idx);
            ignoreTab = false;
        }
        if (wasCur) {
            cur = null;
            if (!docs.isEmpty()) {
                List<Doc> l = new ArrayList<>(docs.values());
                show(l.get(l.size() - 1), 0);
            } else {
                ed.load("", Lang.PLAIN);
                welcome.setVisibility(View.VISIBLE);
                updateSubtitle();
            }
        }
        treeAd.notifyDataSetChanged();
    }

    void closeUnder(File f) {
        String p = f.getAbsolutePath();
        for (Doc d : new ArrayList<>(docs.values())) {
            String dp = d.file.getAbsolutePath();
            if (dp.equals(p) || dp.startsWith(p + "/")) {
                d.dirty = false;
                reallyClose(d);
            }
        }
    }

    // ------------------------------------------------------------ file tree
    void rebuildTree() {
        rows.clear();
        depths.clear();
        walk(proj, 0);
        treeAd.notifyDataSetChanged();
    }

    void walk(File d, int dep) {
        File[] k = d.listFiles();
        if (k == null) return;
        Arrays.sort(k, (a, c) -> a.isDirectory() == c.isDirectory()
                ? a.getName().compareToIgnoreCase(c.getName()) : (a.isDirectory() ? -1 : 1));
        for (File f : k) {
            String n = f.getName();
            if (n.equals(".git") || n.equals(".gradle") || n.equals(".idea") || n.equals(".cloudide") || (dep <= 1 && n.equals("build")))
                continue;
            rows.add(f);
            depths.add(dep);
            if (f.isDirectory() && expanded.contains(f.getAbsolutePath())) walk(f, dep + 1);
        }
    }

    void scheduleStatus() {
        h.removeCallbacks(statusRun);
        h.postDelayed(statusRun, 400);
    }

    static int colorFor(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        if (l.endsWith(".java")) return 0xFFEA9A3E;
        if (l.endsWith(".kt") || l.endsWith(".kts")) return 0xFFA97BFF;
        if (l.endsWith(".xml")) return 0xFF6BBF59;
        if (l.endsWith(".gradle")) return 0xFF02A88E;
        if (l.endsWith(".json")) return 0xFFCBCB41;
        if (l.endsWith(".md")) return 0xFF519ABA;
        if (l.endsWith(".yml") || l.endsWith(".yaml")) return 0xFFE06C75;
        return 0xFF9AA0A6;
    }

    void ask(String title, String init, Ui.Str cb) { Ui.input(this, title, "Name", init, cb); }

    void fileMenu(File f) {
        File dir = f.isDirectory() ? f : f.getParentFile();
        Ui.menu(this, f.getName(), new String[]{"New file", "New folder", "Rename", "Duplicate", "Delete", "Copy path"}, i -> {
            if (i == 0) ask("New file (paths allowed)", "", s -> create(dir, s, false));
            else if (i == 1) ask("New folder", "", s -> create(dir, s, true));
            else if (i == 2) ask("Rename", f.getName(), s -> rename(f, s));
            else if (i == 3) duplicate(f);
            else if (i == 4) Ui.confirm(this, "Delete " + f.getName() + "?",
                    f.isDirectory() ? "The folder and everything in it will be deleted." : "This cannot be undone.", "Delete", () -> {
                        closeUnder(f);
                        Io.delete(f);
                        rebuildTree();
                        scheduleStatus();
                    });
            else {
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("path", f.getAbsolutePath()));
                Ui.toast(this, "Path copied");
            }
        });
    }

    String skeleton(File f) {
        String n = f.getName();
        String base = n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n;
        String p = f.getParentFile().getAbsolutePath();
        String key = "/src/main/java";
        String pkg = "";
        int i = p.indexOf(key);
        if (i >= 0) {
            pkg = p.substring(i + key.length()).replace('/', '.');
            if (pkg.startsWith(".")) pkg = pkg.substring(1);
        }
        if (n.endsWith(".java")) return (pkg.isEmpty() ? "" : "package " + pkg + ";\n\n") + "public class " + base + " {\n}\n";
        if (n.endsWith(".kt")) return (pkg.isEmpty() ? "" : "package " + pkg + "\n\n") + "class " + base + " {\n}\n";
        if (n.endsWith(".xml") && p.contains("/res/")) return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";
        return "";
    }

    void create(File dir, String name, boolean isDir) {
        if (name.isEmpty()) return;
        File f = new File(dir, name);
        if (!Io.inside(proj, f)) {
            Ui.toast(this, "The name must stay inside the project folder");
            return;
        }
        try {
            if (f.exists()) {
                if (f.isFile()) openFile(f, 0);
                return;
            }
            if (isDir) f.mkdirs();
            else Io.write(f, skeleton(f));
            expanded.add(dir.getAbsolutePath());
            rebuildTree();
            scheduleStatus();
            if (!isDir) openFile(f, 0);
        } catch (Exception e) {
            AppLog.e("Editor", "Creating a file failed", e);
            Ui.toast(this, "Create failed: " + e.getMessage());
        }
    }

    void rename(File f, String n) {
        if (n.isEmpty() || n.equals(f.getName())) return;
        File t = new File(f.getParentFile(), n);
        if (!Io.inside(proj, t) || (f.isDirectory() && Io.inside(f, t))) {
            Ui.toast(this, "That name is not allowed here");
            return;
        }
        if (t.exists()) {
            Ui.toast(this, "\"" + n + "\" already exists");
            return;
        }
        saveAll();
        closeUnder(f);
        if (t.getParentFile() != null) t.getParentFile().mkdirs();
        if (!f.renameTo(t)) Ui.toast(this, "Rename failed");
        rebuildTree();
        scheduleStatus();
        if (t.isFile()) openFile(t, 0);
    }

    void duplicate(File f) {
        try {
            Io.copy(f, Io.freeCopy(f.getParentFile(), f.getName()));
            rebuildTree();
            scheduleStatus();
        } catch (Exception e) {
            AppLog.e("Editor", "Duplicating a file failed", e);
            Ui.toast(this, "Duplicate failed: " + e.getMessage());
        }
    }

    class TreeAdapter extends RecyclerView.Adapter<TreeAdapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            ImageView arrow, icon;
            TextView name, badge;

            VH(View v) {
                super(v);
                arrow = v.findViewById(R.id.arrow);
                icon = v.findViewById(R.id.icon);
                name = v.findViewById(R.id.name);
                badge = v.findViewById(R.id.badge);
            }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_tree, p, false));
        }

        @Override
        public void onBindViewHolder(VH vh, int i) {
            File f = rows.get(i);
            boolean dir = f.isDirectory();
            String path = f.getAbsolutePath();
            vh.itemView.setPadding(Ui.dp(EditorActivity.this, 6 + 14 * depths.get(i)), 0, Ui.dp(EditorActivity.this, 10), 0);
            vh.arrow.setImageResource(expanded.contains(path) ? R.drawable.ic_expand : R.drawable.ic_chevron);
            vh.arrow.setVisibility(dir ? View.VISIBLE : View.INVISIBLE);
            vh.icon.setImageResource(dir ? R.drawable.ic_folder : R.drawable.ic_file);
            vh.icon.setColorFilter(dir ? 0xFFE2B857 : colorFor(f.getName()));
            vh.name.setText(f.getName());
            vh.name.setTypeface(null, cur != null && f.equals(cur.file) ? Typeface.BOLD : Typeface.NORMAL);
            String rel = Io.rel(proj, f);
            String st = status.get(rel);
            if (dir && st == null) {
                for (String k : status.keySet()) if (k.startsWith(rel + "/")) { st = "\u2022"; break; }
            }
            vh.badge.setText(st == null ? "" : st);
            vh.badge.setTextColor("A".equals(st) ? 0xFF73C991 : 0xFFE2C08D);
            vh.itemView.setOnClickListener(v -> {
                if (dir) {
                    if (!expanded.remove(path)) expanded.add(path);
                    rebuildTree();
                } else openFile(f, 0);
            });
            vh.itemView.setOnLongClickListener(v -> {
                fileMenu(f);
                return true;
            });
        }

        @Override
        public int getItemCount() { return rows.size(); }
    }

    // ------------------------------------------------------------ undo / redo / commands
    void undo() {
        if (cur == null) return;
        String t = cur.undo(ed.getText().toString());
        if (t != null) {
            ed.replaceAll(t, ed.getSelectionStart());
            onUserEdit();
        }
    }

    void redo() {
        if (cur == null) return;
        String t = cur.redo();
        if (t != null) {
            ed.replaceAll(t, ed.getSelectionStart());
            onUserEdit();
        }
    }

    void format() {
        if (cur == null) return;
        try {
            String before = ed.getText().toString();
            String out = Fmt.format(cur.lang, before, ed.tab);
            if (!out.equals(before)) {
                cur.commit(before);
                ed.replaceAll(out, ed.getSelectionStart());
                cur.commit(out);
                onUserEdit();
            }
            Ui.toast(this, "Formatted");
        } catch (Exception e) {
            AppLog.e("Editor", "Editor action failed", e);
            Ui.toast(this, String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------------ find and replace
    void toggleFind() {
        boolean show = findBar.getVisibility() != View.VISIBLE;
        findBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            if (ed.getSelectionStart() != ed.getSelectionEnd() && ed.getSelectionEnd() - ed.getSelectionStart() < 80)
                findQ.setText(ed.getText().subSequence(ed.getSelectionStart(), ed.getSelectionEnd()));
            findQ.requestFocus();
        }
    }

    Pattern findPat() {
        String q = findQ.getText().toString();
        if (q.isEmpty()) return null;
        int fl = Pattern.MULTILINE | (cbCase.isChecked() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        try {
            return Pattern.compile(cbRegex.isChecked() ? q : Pattern.quote(q), fl);
        } catch (PatternSyntaxException e) {
            findCount.setText("bad regex");
            return null;
        }
    }

    void findNext(boolean fwd) {
        Pattern p = findPat();
        if (p == null) return;
        String t = ed.getText().toString();
        Matcher m = p.matcher(t);
        int pos = fwd ? ed.getSelectionEnd() : ed.getSelectionStart();
        int s = -1, e = -1;
        if (fwd) {
            if (m.find(Math.min(Math.max(pos, 0), t.length())) || (m.reset() != null && m.find())) {
                s = m.start();
                e = m.end();
            }
        } else {
            while (m.find()) {
                if (m.start() < pos) { s = m.start(); e = m.end(); } else break;
            }
            if (s < 0) {
                m.reset();
                while (m.find()) { s = m.start(); e = m.end(); }
            }
        }
        if (s < 0 || e == s) {
            findCount.setText("0/0");
            return;
        }
        ed.requestFocus();
        ed.setSelection(s, e);
        int idx = 0, total = 0;
        Matcher c = p.matcher(t);
        while (c.find() && total < 5000) {
            total++;
            if (c.start() == s) idx = total;
        }
        findCount.setText(idx + "/" + total);
    }

    void replaceOne() {
        Pattern p = findPat();
        if (p == null) return;
        int a = Math.min(ed.getSelectionStart(), ed.getSelectionEnd()), b = Math.max(ed.getSelectionStart(), ed.getSelectionEnd());
        String repl = replQ.getText().toString();
        if (a >= 0 && b > a) {
            String sel = ed.getText().subSequence(a, b).toString();
            Matcher m = p.matcher(sel);
            if (m.matches()) {
                try {
                    ed.replaceRange(a, b, cbRegex.isChecked() ? m.replaceFirst(repl) : repl);
                } catch (Exception ex) {
                    Ui.toast(this, "Replacement error: " + ex.getMessage());
                    return;
                }
            }
        }
        findNext(true);
    }

    void replaceAll() {
        Pattern p = findPat();
        if (p == null || cur == null) return;
        String repl = replQ.getText().toString();
        String t = ed.getText().toString();
        try {
            String out = p.matcher(t).replaceAll(cbRegex.isChecked() ? repl : Matcher.quoteReplacement(repl));
            if (!out.equals(t)) {
                cur.commit(t);
                ed.replaceAll(out, ed.getSelectionStart());
                cur.commit(out);
                onUserEdit();
            }
        } catch (Exception ex) {
            AppLog.e("Editor", "Replace failed", ex);
            Ui.toast(this, "Replacement error: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ problems / search / log panel
    void showPanel(int tab) {
        panel.setVisibility(View.VISIBLE);
        ptab = tab;
        TabLayout.Tab t = ptabs.getTabAt(tab);
        if (t != null && !t.isSelected()) {
            ignorePTab = true;
            t.select();
            ignorePTab = false;
        }
        results.setVisibility(tab >= 2 ? View.GONE : View.VISIBLE);
        logScroll.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        termBox.setVisibility(tab == 3 ? View.VISIBLE : View.GONE);
        if (ai != null) ai.setVisibility(tab == 4 ? View.VISIBLE : View.GONE);
        // never more than half of a small or landscape screen; the assistant gets a little more room for the conversation
        int cap = (int) (getResources().getDisplayMetrics().heightPixels * (tab == 4 ? 0.62f : 0.5f));
        panel.getLayoutParams().height = Math.min(Ui.dp(this, tab == 4 ? 420 : tab == 3 ? 340 : 260), cap);
        panel.requestLayout();
        if (tab == 3) termIn.requestFocus();
        else if (tab == 4) {
            if (ai != null) ai.onShown();
            refreshAiTree();
        } else if (tab == 0 || tab == 2)
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(ed.getWindowToken(), 0);
        refreshResults();
    }

    void refreshResults() {
        shown.clear();
        if (ptab == 0) {
            List<Diag> all = new ArrayList<>();
            for (List<Diag> l : local.values()) all.addAll(l);
            all.addAll(buildDiags);
            Collections.sort(all, (a, c) -> a.sev != c.sev ? a.sev - c.sev : a.file.compareTo(c.file));
            shown.addAll(all);
        } else if (ptab == 1) shown.addAll(found);
        resAd.notifyDataSetChanged();
        updateErrBar();
    }

    // ------------------------------------------------------------ AI assistant
    void aiQuick(int i) {
        showPanel(4);
        ai.quick(i);
    }

    void openAiSettings() {
        startActivity(new Intent(this, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_SECTION, "ai"));
    }

    void refreshAiTree() {
        if (proj == null) return;
        io.execute(() -> {
            List<String> l = new ArrayList<>();
            for (File f : Io.projectFiles(proj)) {
                if (Io.isBinary(f.getName())) continue;
                l.add(Io.rel(proj, f));
                if (l.size() >= 600) break;
            }
            aiTree = l;
        });
    }

    /** Applies assistant code to the editor the same way Format does, so Undo and Redo work and the file is marked changed. */
    void aiEdit(int a, int b, String code, boolean whole) {
        if (cur == null) return;
        String before = ed.getText().toString();
        cur.commit(before);
        if (whole) ed.replaceAll(code, ed.getSelectionStart());
        else ed.replaceRange(a, b, code);
        cur.commit(ed.getText().toString());
        onUserEdit();
    }

    /** What the assistant may see, and how its code is applied. */
    final class AiHost implements AiPanel.Host, AgentTools.Link {
        private AgentTools agent;

        @Override
        public AgentTools tools() {
            if (agent == null) agent = new AgentTools(EditorActivity.this, this);
            return agent;
        }

        /** The assistant opened or created another project: save, start an editor for it that carries the conversation on, and leave this one. */
        @Override
        public void openProject(File dir) {
            saveAll();
            Prefs.touch(EditorActivity.this, dir.getName());
            startActivity(new Intent(EditorActivity.this, EditorActivity.class).putExtra("dir", dir.getAbsolutePath()).putExtra("ai_resume", true));
            finish();
        }

        // ---- AgentTools.Link: what the assistant's actions may do with this editor
        @Override
        public File project() { return proj; }

        @Override
        public boolean editor() { return true; }

        @Override
        public ProjectConfig config() { return cfg; }

        @Override
        public void configChanged() { updateSubtitle(); }

        @Override
        public void settingsChanged() { applyPrefs(); }

        @Override
        public void pluginsChanged() { shell.env = NativeExec.allEnv(EditorActivity.this); }

        @Override
        public String showFile(String rel, int line) {
            File f = projFile(rel);
            if (f == null || !f.isFile()) return rel + " does not exist";
            EditorActivity.this.openFile(f, line);
            return null;
        }

        @Override
        public String startBuild(boolean run) {
            if (EditorActivity.this.buildRunning()) return "a build is already running";
            EditorActivity.this.build(run);
            return null;
        }

        @Override
        public boolean building() { return EditorActivity.this.buildRunning(); }

        @Override
        public int buildCount() { return buildSeq; }

        @Override
        public boolean buildOk() { return lastBuildOk; }

        @Override
        public String buildReport() {
            StringBuilder b = new StringBuilder(lastBuildOk ? "Build succeeded" : "Build failed or was cancelled");
            b.append(cfg.local ? " (on the phone)" : " (GitHub Actions)").append('\n');
            File apk = lastBuildOk ? lastApk() : null;
            if (apk != null) b.append("APK: ").append(apk.getName()).append(" (").append(apk.length() / 1024).append(" KB)\n");
            int n = 0;
            for (Diag d : buildDiags) {
                if (d.sev > 2) continue;
                if (n++ >= 30) {
                    b.append("... more problems in the Problems tab\n");
                    break;
                }
                b.append(d.sev == 1 ? "error " : "warning ").append(d.file).append(':').append(d.line).append(": ").append(d.msg).append('\n');
            }
            String[] ls = logBuf.toString().split("\n");
            b.append("--- end of the build log ---\n");
            for (int i = Math.max(0, ls.length - 60); i < ls.length; i++) b.append(ls[i].replaceFirst(LogParser.TS, "")).append('\n');
            return b.toString();
        }

        @Override
        public AiProtocol.Context context(boolean file, boolean selection, boolean problems, boolean tree, boolean project) {
            AiProtocol.Context c = new AiProtocol.Context();
            if (proj != null) c.projectNote = "Project \"" + proj.getName() + "\"";
            if (cur != null) {
                String rel = Io.rel(proj, cur.file);
                c.filePath = rel;
                c.fileLang = cur.lang.id;
                c.cursorLine = cursorLine;
                if (file) {
                    if (AiProtocol.isSecretFile(rel)) c.skipped = "open file not attached (looks like a credentials file)";
                    else c.fileText = ed.getText().toString();
                }
                if (selection) {
                    int a = Math.min(ed.getSelectionStart(), ed.getSelectionEnd()), b = Math.max(ed.getSelectionStart(), ed.getSelectionEnd());
                    if (b > a) {
                        c.selection = ed.getText().subSequence(a, b).toString();
                        c.selStartLine = ed.lineOf(a);
                        c.selEndLine = ed.lineOf(Math.max(a, b - 1));
                    }
                }
            }
            if (problems) {
                List<Diag> all = new ArrayList<>();
                if (cur != null) {
                    List<Diag> l = local.get(Io.rel(proj, cur.file));
                    if (l != null) all.addAll(l);
                }
                all.addAll(buildDiags);
                for (Diag d : all) if (d.sev <= 2) c.problems.add(d.file + ":" + d.line + ":" + d.col + " " + (d.sev == 1 ? "error" : "warning") + ": " + d.msg);
            }
            if (tree) c.tree.addAll(aiTree);
            if (project && proj != null) addProjectCode(c);
            return c;
        }

        /**
         * Adds the project's source, most important files first, until the size budget is used. Unsaved edits are what gets sent
         * (the open file from the editor, other changed tabs from memory), and the open file is skipped when it is already attached whole.
         */
        private void addProjectCode(AiProtocol.Context c) {
            List<String> paths = new ArrayList<>();
            for (String p : aiTree) if (!AiProtocol.skipForCode(p) && !AiProtocol.isSecretFile(p)) paths.add(p);
            c.codeTotal = paths.size();
            Collections.sort(paths, (a, b) -> AiProtocol.codeRank(a) - AiProtocol.codeRank(b)); // stable: same rank keeps folder order
            String open = cur == null ? null : Io.rel(proj, cur.file);
            for (String rel : paths) {
                if (rel.equals(open) && c.fileText != null) continue; // already attached in full
                File f = new File(proj, rel);
                String text = null;
                if (rel.equals(open)) text = ed.getText().toString();
                else {
                    Doc d = docs.get(f);
                    if (d != null && d.dirty) text = d.text;
                    else if (f.isFile() && f.length() <= 200 * 1024) {
                        try {
                            text = Io.read(f);
                        } catch (Exception ex) {
                            text = null;
                        }
                    }
                }
                if (text == null) continue;
                if (!AiProtocol.addCode(c, rel, text)) break;
            }
            if (c.code.isEmpty()) c.codeTotal = 0;
        }

        @Override
        public boolean hasSelection() { return cur != null && ed.getSelectionStart() != ed.getSelectionEnd(); }

        @Override
        public boolean hasFile() { return cur != null; }

        @Override
        public void insertAtCursor(String code) {
            int at = Math.max(ed.getSelectionStart(), ed.getSelectionEnd());
            aiEdit(at, at, code, false);
        }

        @Override
        public void replaceSelection(String code) {
            aiEdit(Math.min(ed.getSelectionStart(), ed.getSelectionEnd()), Math.max(ed.getSelectionStart(), ed.getSelectionEnd()), code, false);
        }

        @Override
        public void replaceFile(String code) { aiEdit(0, 0, code, true); }

        @Override
        public void openSettings() { openAiSettings(); }

        @Override
        public void openWebAi() { openWebAiScreen(); }

        /** The file for a project-relative path, only when the path is allowed and really stays inside the project (symlinks included). */
        private File projFile(String rel) {
            String p = AiProtocol.cleanPath(rel);
            if (p == null || proj == null) return null;
            try {
                File f = new File(proj, p);
                String root = proj.getCanonicalPath() + File.separator;
                return f.getCanonicalPath().startsWith(root) ? f : null;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public String readFile(String rel) {
            File f = projFile(rel);
            if (f == null) return null;
            if (cur != null && f.equals(cur.file)) return ed.getText().toString();
            Doc d = docs.get(f);
            if (d != null) return d.text;
            if (!f.exists()) return null;
            if (!f.isFile() || f.length() > 2L * 1024 * 1024) return AiEdits.UNREADABLE; // a folder or a huge file: never replace it blindly
            try {
                return Io.read(f);
            } catch (Exception e) {
                return AiEdits.UNREADABLE;
            }
        }

        @Override
        public String renameFile(String from, String to) {
            File a = projFile(from), b = projFile(to);
            if (a == null || b == null) return "that path is not allowed in the project";
            if (!a.isFile()) return from + " does not exist";
            if (b.exists()) return to + " already exists";
            try {
                Doc d = docs.get(a);
                boolean wasCur = d != null && d == cur;
                if (d != null && !saveDoc(d)) return "saving " + from + " failed";
                File dir = b.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return "the folder for " + to + " could not be created";
                if (!a.renameTo(b)) {
                    Io.write(b, Io.read(a)); // a move between storage areas: copy, then remove
                    Io.delete(a);
                }
                if (!b.isFile()) return "the rename failed";
                if (d != null) {
                    d.dirty = false;
                    reallyClose(d);
                }
                rebuildTree();
                refreshAiTree();
                if (wasCur) openFile(b, 0); // keep the person looking at the same file, under its new name
                return null;
            } catch (Exception e) {
                AppLog.e("Editor", "AI rename failed", e);
                return e.getMessage() == null ? "the rename failed" : e.getMessage();
            }
        }

        @Override
        public String writeFile(String rel, String text) {
            File f = projFile(rel);
            if (f == null) return "that path is not allowed in the project";
            if (f.isDirectory()) return "that path is a folder";
            if (text.length() > AiProtocol.MAX_EDIT_CHARS) return "the text is too large";
            try {
                boolean isNew = !f.exists();
                Doc d = docs.get(f);
                if (d != null && d == cur) {
                    aiEdit(0, 0, text, true); // the editor's own undo history keeps the old text too
                    if (!saveDoc(d)) return "saving failed";
                } else if (d != null) {
                    d.commit(text);
                    d.text = text;
                    Io.write(f, text);
                    d.dirty = false;
                    updateTab(d);
                    runChecks(d);
                } else {
                    Io.write(f, text);
                }
                if (isNew) {
                    rebuildTree();
                    refreshAiTree();
                }
                return null;
            } catch (Exception e) {
                AppLog.e("Editor", "AI file write failed", e);
                return e.getMessage() == null ? "writing failed" : e.getMessage();
            }
        }

        @Override
        public String removeFile(String rel) {
            File f = projFile(rel);
            if (f == null) return "that path is not allowed in the project";
            Doc d = docs.get(f);
            if (d != null) {
                d.dirty = false;
                reallyClose(d);
            }
            Io.delete(f);
            rebuildTree();
            refreshAiTree();
            return f.exists() ? "the file could not be deleted" : null;
        }
    }

    // ------------------------------------------------------------ command palette
    void flattenMenu(Menu m, String prefix, List<MenuItem> items, List<String> names) {
        for (int i = 0; i < m.size(); i++) {
            MenuItem it = m.getItem(i);
            CharSequence t = it.getTitle();
            if (!it.isVisible() || t == null || t.length() == 0) continue;
            if (it.hasSubMenu()) flattenMenu(it.getSubMenu(), prefix + t + " > ", items, names);
            else if (it.isEnabled() && it.getItemId() != R.id.m_palette) {
                items.add(it);
                names.add(prefix + t);
            }
        }
    }

    /** Every command in the menu, searchable. Built from the real menu, so new commands appear here by themselves. */
    void showPalette() {
        final List<MenuItem> items = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        Menu m = toolbar.getMenu();
        if (m == null || m.size() == 0) {
            android.widget.PopupMenu pm = new android.widget.PopupMenu(this, toolbar);
            getMenuInflater().inflate(R.menu.menu_editor, pm.getMenu());
            m = pm.getMenu();
        }
        flattenMenu(m, "", items, names);
        final List<Integer> shownIdx = new ArrayList<>();
        final ArrayAdapter<CharSequence> ad = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<CharSequence>());
        ad.setNotifyOnChange(false);
        final int dim = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF888888);

        EditText q = new EditText(this);
        q.setHint("Type a command");
        q.setSingleLine(true);
        q.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        ListView lv = new ListView(this);
        lv.setAdapter(ad);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(q, new LinearLayout.LayoutParams(-1, -2));
        box.addView(lv, new LinearLayout.LayoutParams(-1, Ui.dp(this, 320)));

        Runnable refilter = () -> {
            String qs = q.getText().toString().trim();
            shownIdx.clear();
            if (qs.isEmpty()) {
                for (int i = 0; i < names.size(); i++) shownIdx.add(i);
            } else {
                final Map<Integer, Integer> sc = new HashMap<>();
                for (int i = 0; i < names.size(); i++) {
                    int v = Fuzzy.score(qs, names.get(i));
                    if (v >= 0) {
                        sc.put(i, v);
                        shownIdx.add(i);
                    }
                }
                Collections.sort(shownIdx, (a, c) -> sc.get(c) - sc.get(a));
            }
            ad.clear();
            for (int i : shownIdx) {
                String full = names.get(i);
                int sep = full.lastIndexOf(" > ");
                String name = sep < 0 ? full : full.substring(sep + 3), group = sep < 0 ? "" : "   " + full.substring(0, sep);
                SpannableString t = new SpannableString(name + group);
                if (!group.isEmpty()) {
                    t.setSpan(new ForegroundColorSpan(dim), name.length(), t.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    t.setSpan(new RelativeSizeSpan(0.8f), name.length(), t.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                ad.add(t);
            }
            ad.notifyDataSetChanged();
        };
        q.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence x, int a, int b, int c) { }

            @Override
            public void onTextChanged(CharSequence x, int a, int b, int c) { refilter.run(); }

            @Override
            public void afterTextChanged(android.text.Editable e) { }
        });
        refilter.run();
        AlertDialog dlg = new MaterialAlertDialogBuilder(this).setTitle("Command palette").setView(box)
                .setNegativeButton("Cancel", null).create();
        lv.setOnItemClickListener((p, v, i, id) -> {
            dlg.dismiss();
            if (i < shownIdx.size()) onOptionsItemSelected(items.get(shownIdx.get(i)));
        });
        q.setOnEditorActionListener((tv, a, ev) -> {
            dlg.dismiss();
            if (!shownIdx.isEmpty()) onOptionsItemSelected(items.get(shownIdx.get(0)));
            return true;
        });
        dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
        q.requestFocus();
    }

    void updatePanelTitles() {
        int n = buildDiags.size();
        for (List<Diag> l : local.values()) n += l.size();
        TabLayout.Tab a = ptabs.getTabAt(0), b = ptabs.getTabAt(1);
        if (a != null) a.setText("Problems (" + n + ")");
        if (b != null) b.setText("Search (" + found.size() + ")");
    }

    private final StringBuilder pendingLog = new StringBuilder();
    private boolean logFlushQueued;

    /**
     * Build output arrives line by line from worker threads. Redrawing the whole (up to 200 KB) log for every line froze
     * the UI during long builds, so lines are collected and drawn at most about 8 times a second.
     */
    void appendLog(String s) {
        AppLog.d("Build", s);
        synchronized (pendingLog) {
            pendingLog.append(s).append('\n');
            if (logFlushQueued) return;
            logFlushQueued = true;
        }
        log.postDelayed(this::flushLog, 120);
    }

    void flushLog() {
        String chunk;
        synchronized (pendingLog) {
            chunk = pendingLog.toString();
            pendingLog.setLength(0);
            logFlushQueued = false;
        }
        logBuf.append(chunk);
        if (logBuf.length() > 200000) {
            logBuf.delete(0, logBuf.length() - 150000);
            log.setText(logBuf);
        } else log.append(chunk);
        // scrollTo, not fullScroll: fullScroll moves focus and would close the keyboard while the terminal is in use
        logScroll.post(() -> logScroll.scrollTo(0, log.getBottom()));
    }

    class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            TextView title, msg;

            VH(View v) {
                super(v);
                title = v.findViewById(R.id.rtitle);
                msg = v.findViewById(R.id.rmsg);
            }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_result, p, false));
        }

        @Override
        public void onBindViewHolder(VH vh, int i) {
            Diag d = shown.get(i);
            String where = d.file.isEmpty() ? "Build" : d.file + (d.line > 0 ? ":" + d.line : "");
            vh.title.setText((d.sev == 1 ? "ERROR   " : d.sev == 2 ? "WARNING   " : "") + where);
            vh.title.setTextColor(d.sev == 1 ? 0xFFF14C4C : d.sev == 2 ? 0xFFCCA700
                    : MaterialColors.getColor(EditorActivity.this, androidx.appcompat.R.attr.colorPrimary, 0xFF3B6FD4));
            vh.msg.setText(d.msg);
            vh.itemView.setOnClickListener(v -> {
                if (d.file.isEmpty()) return;
                File f = new File(proj, d.file);
                if (f.exists()) openFile(f, d.line);
                else Ui.toast(EditorActivity.this, "Not found: " + d.file);
            });
        }

        @Override
        public int getItemCount() { return shown.size(); }
    }

    void searchProject() {
        Ui.input(this, "Search in project", "Text", "", q -> {
            if (q.isEmpty()) return;
            saveAll();
            found = new ArrayList<>();
            showPanel(1);
            io.execute(() -> {
                List<Diag> out = new ArrayList<>();
                String ql = q.toLowerCase(Locale.ROOT);
                for (File f : Io.projectFiles(proj)) {
                    if (Io.isBinary(f.getName()) || f.length() > 1500000) continue;
                    try {
                        String[] ls = Io.read(f).split("\n", -1);
                        for (int i = 0; i < ls.length && out.size() < 400; i++)
                            if (ls[i].toLowerCase(Locale.ROOT).contains(ql)) out.add(new Diag(Io.rel(proj, f), i + 1, 0, ls[i].trim(), 3));
                    } catch (Exception ignored) {
                    }
                    if (out.size() >= 400) break;
                }
                runOnUiThread(() -> {
                    found = out;
                    updatePanelTitles();
                    if (ptab == 1) refreshResults();
                });
            });
        });
    }

    // ------------------------------------------------------------ GitHub: settings, build, pull
    void projectSettings(Runnable after) {
        ProjectSettingsDialog.show(this, proj, cfg, () -> {
            updateSubtitle();
            if (after != null) after.run();
        });
    }

    /** Opens Project settings with the repository fields visible even for a Local project. */
    void projectSettingsRepo(Runnable after) {
        ProjectSettingsDialog.show(this, proj, cfg, () -> {
            updateSubtitle();
            if (after != null) after.run();
        }, true);
    }

    void build(boolean run) {
        if (buildRunning()) {
            Ui.toast(this, "A build is already running");
            return;
        }
        if (run && cfg.local) {
            localBuild();
            return;
        }
        if (Prefs.tokenFor(this, proj.getName()).isEmpty()) {
            new MaterialAlertDialogBuilder(this).setTitle("GitHub access token required")
                    .setMessage("Upload and GitHub Actions builds need an access token. Add one under App settings, GitHub account.\n\nTo build without GitHub, set the build method to Local in Project settings.")
                    .setPositiveButton("Open settings", (d, w) -> startActivity(new Intent(this, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_SECTION, "github")))
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        if (cfg.repo.isEmpty()) {
            // Save always fills in the repository, so this runs once and cannot loop.
            projectSettingsRepo(() -> {
                if (!cfg.repo.isEmpty()) build(run);
            });
            return;
        }
        saveAll();
        buildDiags = new ArrayList<>();
        logBuf.setLength(0);
        log.setText("");
        showPanel(2);
        progress.setVisibility(View.VISIBLE);
        pCancel.setVisibility(View.VISIBLE);
        pInstall.setVisibility(View.GONE);
        ljob = null;
        llang = null;
        job = new CloudBuild(this, proj, cfg, run, buildCallback());
        job.start();
    }

    /** Receives progress from any kind of build (GitHub Actions, local Android, other languages) and shows it. */
    private CloudBuild.Cb buildCallback() {
        return new CloudBuild.Cb() {
            @Override
            public void log(String s) { appendLog(s); }

            @Override
            public void status(String s) {
                runOnUiThread(() -> {
                    buildMsg = s;
                    updateSubtitle();
                });
            }

            @Override
            public void done(boolean ok, File apk, List<Diag> diags, String full, String title) {
                runOnUiThread(() -> finishBuild(ok, apk, diags, full, title));
            }
        };
    }

    boolean buildRunning() {
        return (job != null && job.running) || (ljob != null && ljob.running) || (llang != null && llang.running);
    }

    /** Local build with the in-app toolchain (no GitHub, no Termux). Offers missing compilers first. */
    void localBuild() {
        saveAll();
        checkPlugins(this::localBuildNow);
    }

    // ------------------------------------------------------------ language plugins

    /** Right after a project opens: if it uses a language whose compiler is not installed, offer the download. */
    void checkPluginsOnOpen() {
        if (!Prefs.bool(this, "auto_plugins", true)) return;
        io.execute(() -> {
            final Needs.Result r = Needs.scan(this, proj);
            if (!r.any() || Needs.allSkipped(this, proj, r)) return;
            runOnUiThread(() -> offerPlugins(r, null));
        });
    }

    /** Before a local build: runs then right away when nothing is missing, else offers the download first. */
    void checkPlugins(Runnable then) {
        io.execute(() -> {
            final Needs.Result r = Needs.scan(this, proj);
            runOnUiThread(() -> {
                if (!r.any()) then.run();
                else offerPlugins(r, then);
            });
        });
    }

    void offerPlugins(Needs.Result r, Runnable then) {
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this).setTitle("Download compilers?").setMessage(Needs.message(r))
                .setPositiveButton("Download", (d, w) -> installNeeds(r, then))
                .setNegativeButton(then == null ? "Not now" : "Build anyway", (d, w) -> {
                    if (then != null) then.run();
                });
        b.setNeutralButton("Don't ask again", (d, w) -> {
            Needs.skip(this, proj, r);
            if (then != null) then.run();
        });
        b.show();
    }

    void installNeeds(Needs.Result r, Runnable then) {
        final AlertDialog busy = Ui.busy(this, "Downloading " + r.mb() + " MB of compilers. Progress is in the Terminal tab.");
        showPanel(3);
        termPrint("Installing: " + (r.coreMissing ? "core toolchain " : "") + Needs.names(r.missing) + "\n");
        new Thread(() -> {
            String err = null;
            try {
                Needs.installAll(this, r, s -> runOnUiThread(() -> termPrint(s + "\n")));
            } catch (Throwable e) {
                AppLog.e("Editor", "Build step failed", e);
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String fe = err;
            runOnUiThread(() -> {
                Ui.dismiss(busy);
                if (fe != null) {
                    termPrint("Install failed: " + fe + "\n");
                    new MaterialAlertDialogBuilder(this).setTitle("Download failed").setMessage(fe).setPositiveButton("OK", null).show();
                    return;
                }
                termPrint("Done.\n");
                shell.env = NativeExec.allEnv(this);
                if (then != null) then.run();
                else Ui.toast(this, "Compilers installed");
            });
        }).start();
    }

    /** plugins | plugin install|remove|import|update|list [name] */
    void termPlugin(List<String> w) {
        String sub = w.size() > 1 ? w.get(1) : "list";
        if (w.get(0).equals("packs") || sub.equals("list")) {
            termPrint(Packs.status(this) + "\n");
            Needs.Result r = Needs.scan(this, proj);
            if (!r.used.isEmpty()) termPrint("This project uses: " + Needs.names(r.used) + (r.any() ? ".  Missing: type plugin install <name>" : ".  All installed.") + "\n");
            termPrint("plugin install <name> | remove <name> | import <zip> | update (refresh the language list)\n");
            return;
        }
        final String arg = w.size() > 2 ? w.get(2) : "";
        if (sub.equals("install") && arg.equals("needed")) {
            Needs.Result r = Needs.scan(this, proj);
            if (!r.any()) termPrint("Nothing missing.\n");
            else installNeeds(r, null);
            return;
        }
        if (!sub.equals("update") && arg.isEmpty()) {
            termPrint("usage: plugin install <name>|needed, plugin remove <name>, plugin import <zip>, plugin update\n");
            return;
        }
        if (sub.equals("remove")) {
            Packs.remove(this, arg);
            shell.env = NativeExec.allEnv(this);
            termPrint(arg + " removed\n");
            return;
        }
        termBusy = true;
        ((TextView) termRun).setText("Stop");
        new Thread(() -> {
            try {
                Gh.Log lg = s -> runOnUiThread(() -> termPrint(s + "\n"));
                if (sub.equals("install")) Packs.installWithDeps(this, arg, lg);
                else if (sub.equals("import")) lg.log("imported " + Packs.importPlugin(this, shellFile(arg), lg));
                else if (sub.equals("update")) Packs.refreshCatalog(this, lg);
                else runOnUiThread(() -> termPrint("unknown plugin command " + sub + "\n"));
                runOnUiThread(() -> {
                    shell.env = NativeExec.allEnv(this);
                    termPrint("done\n");
                });
            } catch (Throwable e) {
                AppLog.e("Editor", "Terminal command failed", e);
                final String m = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> termPrint("failed: " + m + "\n"));
            } finally {
                runOnUiThread(() -> {
                    termBusy = false;
                    ((TextView) termRun).setText("Run");
                });
            }
        }).start();
    }

    File shellFile(String p) {
        File f = new File(p);
        return f.isAbsolute() ? f : new File(shell.cwd, p);
    }

    void localBuildNow() {
        buildDiags = new ArrayList<>();
        logBuf.setLength(0);
        log.setText("");
        showPanel(2);
        progress.setVisibility(View.VISIBLE);
        pCancel.setVisibility(View.VISIBLE);
        pInstall.setVisibility(View.GONE);
        job = null;
        ljob = null;
        llang = null;
        if (!LocalLang.android(proj)) {
            llang = new LocalLang(this, proj, cfg, buildCallback());
            llang.start();
            return;
        }
        ljob = new LocalBuild(this, proj, cfg, buildCallback());
        ljob.start();
    }

    void finishBuild(boolean ok, File apk, List<Diag> diags, String full, String title) {
        lastBuildOk = ok;
        buildSeq++;
        progress.setVisibility(View.GONE);
        pCancel.setVisibility(View.GONE);
        buildMsg = "";
        updateSubtitle();
        appendLog(title);
        if (ok && apk != null) {
            pInstall.setVisibility(View.VISIBLE);
            ((TextView) pInstall).setText("Get APK");
            offerApk(apk);
        } else if (ok) {
            if ((job != null && job.runBuild) || llang != null) {
                pInstall.setVisibility(View.VISIBLE);
                ((TextView) pInstall).setText("Build files");
                offerFiles();
            }
        } else if (!ok) {
            if (!full.isEmpty()) {
                String[] ls = full.split("\n");
                StringBuilder sb = new StringBuilder(builtOnPhone() ? "--- last lines of the build log ---\n" : "--- last lines of the GitHub log ---\n");
                for (int i = Math.max(0, ls.length - 60); i < ls.length; i++) sb.append(ls[i].replaceFirst(LogParser.TS, "")).append('\n');
                appendLog(sb.toString());
            }
            buildDiags = diags;
            if (cur != null) ed.setMarks(marksFor(cur));
            updatePanelTitles();
            showPanel(diags.isEmpty() ? 2 : 0);
        }
        scheduleStatus();
    }

    File lastApk() { return findApk(new File(getExternalFilesDir(null), "builds/" + proj.getName())); }

    /** The most recently written APK under d (an older build must not win just because it is listed first). */
    File findApk(File d) {
        File[] k = d.listFiles();
        if (k == null) return null;
        File best = null;
        for (File f : k) {
            File c = f.isDirectory() ? findApk(f) : f.getName().endsWith(".apk") ? f : null;
            if (c != null && (best == null || c.lastModified() > best.lastModified())) best = c;
        }
        return best;
    }

    void collectFiles(File d, List<File> out) {
        File[] k = d.listFiles();
        if (k == null) return;
        Arrays.sort(k);
        for (File f : k) {
            if (f.isDirectory()) collectFiles(f, out);
            else out.add(f);
        }
    }

    File buildsDir() { return new File(getExternalFilesDir(null), "builds/" + proj.getName()); }

    /** The APK menu if the last build made one, otherwise the list of build files. */
    void getFiles() {
        File a = lastApk();
        if (a != null) outputMenu(a);
        else showOutputs();
    }

    void showOutputs() {
        final List<File> files = new ArrayList<>();
        collectFiles(buildsDir(), files);
        if (files.isEmpty()) {
            Ui.toast(this, "No build outputs yet. Run a build first.");
            return;
        }
        final boolean zip = files.size() > 1;
        String[] names = new String[files.size() + (zip ? 1 : 0)];
        if (zip) names[0] = "Save all " + files.size() + " files as .zip...";
        for (int k = 0; k < files.size(); k++) names[k + (zip ? 1 : 0)] = files.get(k).getName() + "  (" + ApkInfo.size(files.get(k).length()) + ")";
        Ui.menu(this, "Build outputs", names, i -> {
            if (zip && i == 0) saveAllZip(files);
            else outputMenu(files.get(i - (zip ? 1 : 0)));
        });
    }

    /** Shown after a successful build that produced files but no APK (C, Rust, Go, Python... builds). */
    void offerFiles() {
        final List<File> files = new ArrayList<>();
        collectFiles(buildsDir(), files);
        if (files.isEmpty()) {
            Ui.toast(this, "The build finished but produced no files. Check the Build log tab.");
            return;
        }
        StringBuilder sb = new StringBuilder(builtOnPhone() ? "This build did not produce an APK. Output files (built locally):\n"
                : "This build did not produce an APK. Output files (GitHub Actions):\n");
        for (int i = 0; i < Math.min(6, files.size()); i++)
            sb.append('\n').append(files.get(i).getName()).append("  (").append(ApkInfo.size(files.get(i).length())).append(')');
        if (files.size() > 6) sb.append("\n... and ").append(files.size() - 6).append(" more");
        sb.append("\n\nTo run a native executable on Android, save it, copy it into Termux and mark it executable with chmod +x.");
        new MaterialAlertDialogBuilder(this).setTitle("Build succeeded").setMessage(sb.toString())
                .setPositiveButton("Save to folder", (d, w) -> {
                    if (files.size() == 1) saveToFolder(files.get(0));
                    else saveAllZip(files);
                })
                .setNeutralButton("Files...", (d, w) -> showOutputs())
                .setNegativeButton("Close", null).show();
    }

    void saveAllZip(final List<File> files) {
        final File base = buildsDir();
        io.execute(() -> {
            try {
                File z = new File(getCacheDir(), proj.getName() + "-build.zip");
                try (java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(z))) {
                    byte[] b = new byte[16384];
                    for (File f : files) {
                        String name = f.getPath().startsWith(base.getPath() + "/") ? f.getPath().substring(base.getPath().length() + 1) : f.getName();
                        zo.putNextEntry(new java.util.zip.ZipEntry(name));
                        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                            int n;
                            while ((n = in.read(b)) > 0) zo.write(b, 0, n);
                        }
                        zo.closeEntry();
                    }
                }
                runOnUiThread(() -> saveToFolder(z));
            } catch (Exception e) {
                AppLog.e("Editor", "Zip export failed", e);
                runOnUiThread(() -> Ui.toast(this, "Could not make the zip: " + e.getMessage()));
            }
        });
    }

    void share(File f) { Ui.share(this, f); }

    void install(File apk) {
        if (apk == null || !apk.exists()) {
            List<File> have = new ArrayList<>();
            collectFiles(buildsDir(), have);
            if (have.isEmpty()) {
                Ui.toast(this, "No built APK yet. Run a build first.");
            } else {
                Ui.toast(this, "The last build made no APK, only " + have.size() + " other file(s). Opening them.");
                showOutputs();
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Ui.toast(this, "Allow installs from CloudIDE, then tap Install APK again");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fp", apk);
        startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }

    void pull() {
        String tok = Prefs.tokenFor(this, proj.getName());
        if (cfg.repo.isEmpty() || tok.isEmpty()) {
            Ui.toast(this, "Set the repository and a token first");
            return;
        }
        Ui.confirm(this, "Pull from GitHub", "Download " + cfg.owner + "/" + cfg.repo
                + " and overwrite local files with the same names? Files that exist only here are kept.", "Pull", () -> {
            saveAll();
            AlertDialog busy = Ui.busy(this, "Downloading...");
            io.execute(() -> {
                try {
                    byte[] z = new Gh(tok, s -> { }).zipball(cfg.owner, cfg.repo, cfg.branch);
                    List<String> got = Io.unzipStrip(z, proj);
                    for (String rel : got) {
                        try {
                            cfg.pushed.put(rel, Io.sha(new File(proj, rel)));
                        } catch (Exception ignored) {
                            // a file that cannot be read simply shows as changed
                        }
                    }
                    cfg.save();
                    runOnUiThread(() -> {
                        Ui.dismiss(busy);
                        for (Doc d : new ArrayList<>(docs.values())) {
                            d.dirty = false;
                            reallyClose(d);
                        }
                        rebuildTree();
                        scheduleStatus();
                        Ui.toast(this, "Pulled the latest from GitHub");
                    });
                } catch (Exception e) {
                    AppLog.e("Editor", "Operation failed", e);
                    runOnUiThread(() -> {
                        Ui.dismiss(busy);
                        Ui.toast(this, String.valueOf(e.getMessage()));
                    });
                }
            });
        });
    }


    // ------------------------------------------------------------ quick open
    void quickOpen() {
        io.execute(() -> {
            List<String> rels = new ArrayList<>();
            for (File f : Io.projectFiles(proj)) if (!Io.isBinary(f.getName())) rels.add(Io.rel(proj, f));
            runOnUiThread(() -> showQuickOpen(rels));
        });
    }

    void showQuickOpen(List<String> all) {
        final Set<String> open = new HashSet<>();
        for (File f : docs.keySet()) open.add(Io.rel(proj, f));
        final List<String> shownRels = new ArrayList<>();
        final ArrayAdapter<CharSequence> ad = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<CharSequence>());
        ad.setNotifyOnChange(false);
        final int dim = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF888888);

        EditText q = new EditText(this);
        q.setHint("Type part of a file name");
        q.setSingleLine(true);
        q.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        ListView lv = new ListView(this);
        lv.setAdapter(ad);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(q, new LinearLayout.LayoutParams(-1, -2));
        box.addView(lv, new LinearLayout.LayoutParams(-1, Ui.dp(this, 320)));

        Runnable refilter = () -> {
            String qs = q.getText().toString().trim();
            shownRels.clear();
            if (qs.isEmpty()) {
                shownRels.addAll(open);
                for (String r : all) if (shownRels.size() < 60 && !open.contains(r)) shownRels.add(r);
            } else {
                final Map<String, Integer> sc = new HashMap<>();
                List<String> hits = new ArrayList<>();
                for (String r : all) {
                    int v = Fuzzy.score(qs, r);
                    if (v >= 0) {
                        sc.put(r, v + (open.contains(r) ? 8 : 0));
                        hits.add(r);
                    }
                }
                Collections.sort(hits, (a, c) -> {
                    int d = sc.get(c) - sc.get(a);
                    return d != 0 ? d : a.length() - c.length();
                });
                shownRels.addAll(hits.subList(0, Math.min(hits.size(), 60)));
            }
            ad.clear();
            for (String r : shownRels) {
                int sl = r.lastIndexOf('/');
                String name = r.substring(sl + 1), dir = sl > 0 ? "   " + r.substring(0, sl) : "";
                SpannableString t = new SpannableString(name + dir);
                if (!dir.isEmpty()) {
                    t.setSpan(new ForegroundColorSpan(dim), name.length(), t.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    t.setSpan(new RelativeSizeSpan(0.8f), name.length(), t.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                ad.add(t);
            }
            ad.notifyDataSetChanged();
        };
        q.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence x, int a, int b, int c) { }

            @Override
            public void onTextChanged(CharSequence x, int a, int b, int c) { refilter.run(); }

            @Override
            public void afterTextChanged(android.text.Editable e) { }
        });
        refilter.run();
        AlertDialog dlg = new MaterialAlertDialogBuilder(this).setTitle("Quick open").setView(box)
                .setNegativeButton("Cancel", null).create();
        lv.setOnItemClickListener((p, v, i, id) -> {
            if (i < shownRels.size()) openFile(new File(proj, shownRels.get(i)), 0);
            dlg.dismiss();
        });
        q.setOnEditorActionListener((tv, a, ev) -> {
            if (!shownRels.isEmpty()) openFile(new File(proj, shownRels.get(0)), 0);
            dlg.dismiss();
            return true;
        });
        dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
        q.requestFocus();
    }

    // ------------------------------------------------------------ build task, history, log actions
    void pickTask() {
        if (!cfg.preset.equals("auto") && !cfg.preset.equals("gradle") && !cfg.preset.equals("rust")) {
            Ui.toast(this, "Build tasks apply to Gradle and Rust projects. This project uses the " + cfg.preset + " build type.");
            return;
        }
        String[] items = new String[Tasks.PRESETS.length];
        int checked = -1;
        for (int i = 0; i < items.length; i++) {
            String[] p = Tasks.PRESETS[i];
            items[i] = p[1].isEmpty() ? p[0] : p[0] + "   (" + p[1] + ")";
            if (p[1].equals(cfg.task)) checked = i;
        }
        Ui.choose(this, "Build task", items, checked, i -> {
            String task = Tasks.PRESETS[i][1];
            if (task.isEmpty()) {
                Ui.input(this, "Gradle task", "Task names, e.g. assembleDebug test", cfg.task, s -> {
                    if (s.isEmpty()) return;
                    cfg.task = s;
                    cfg.save();
                    build(true);
                });
            } else {
                cfg.task = task;
                cfg.save();
                build(true);
            }
        });
    }

    void showHistory() {
        JSONArray h = cfg.history;
        if (h.length() == 0) {
            Ui.toast(this, "No builds yet");
            return;
        }
        final String[] urls = new String[h.length()];
        String[] items = new String[h.length()];
        long now = System.currentTimeMillis();
        for (int i = 0; i < items.length; i++) {
            JSONObject o = h.optJSONObject(i);
            if (o == null) o = new JSONObject();
            String r = o.optString("result");
            String mark = r.equals("success") ? "\u2713 Passed" : r.equals("failed") ? "\u2717 Failed" : r.equals("cancelled") ? "- Cancelled"
                    : r.equals("pushed") ? "\u2191 Pushed" : "\u2717 Error";
            urls[i] = o.optString("url");
            items[i] = mark + "   " + Human.ago(o.optLong("t"), now) + "   " + Human.dur(o.optLong("ms"))
                    + (o.optInt("run") > 0 ? "   #" + o.optInt("run") : "") + "\n" + o.optString("task");
        }
        Ui.menu(this, "Build history (tap to open the run)", items, i -> {
            if (urls[i].isEmpty()) Ui.toast(this, "No run page for this entry");
            else startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(urls[i])));
        });
    }

    void clearPanel() {
        logBuf.setLength(0);
        log.setText("");
        buildDiags = new ArrayList<>();
        found = new ArrayList<>();
        local.clear();
        if (cur != null) ed.setMarks(marksFor(cur));
        refreshResults();
        updatePanelTitles();
    }

    void logMenu() {
        Ui.menu(this, "Build log", new String[]{"Copy log", "Share log", "Open run on GitHub", "Build history", "Clear"}, i -> {
            String text = logBuf.length() > 100000 ? logBuf.substring(logBuf.length() - 100000) : logBuf.toString();
            if (i == 0) {
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("build log", text));
                Ui.toast(this, "Log copied");
            } else if (i == 1) {
                Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(s, "Share build log"));
            } else if (i == 2) {
                String url = job != null ? job.runUrl : "";
                JSONObject last = cfg.history.optJSONObject(0);
                if (url.isEmpty() && last != null) url = last.optString("url");
                if (url.isEmpty()) Ui.toast(this, "No run yet");
                else startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } else if (i == 3) showHistory();
            else clearPanel();
        });
    }

    void outputMenu(File f) {
        final boolean apk = f.getName().endsWith(".apk");
        final List<String> a = new ArrayList<>();
        if (apk) a.add("Install");
        a.add("Save to a folder...");
        a.add("Share");
        if (apk) a.add("Inspect (size, signatures, files)");
        Ui.menu(this, f.getName(), a.toArray(new String[0]), i -> {
            String x = a.get(i);
            if (x.equals("Install")) install(f);
            else if (x.startsWith("Save")) saveToFolder(f);
            else if (x.equals("Share")) share(f);
            else ApkInspector.show(this, f);
        });
    }

    /** True while the finished build was a local build (the cloud build clears ljob and llang). */
    boolean builtOnPhone() { return ljob != null || llang != null; }

    void offerApk(File apk) {
        String where = builtOnPhone() ? "Built locally." : "Built with GitHub Actions.";
        new MaterialAlertDialogBuilder(this).setTitle("Build succeeded")
                .setMessage(apk.getName() + "  (" + ApkInfo.size(apk.length()) + ")\n" + where)
                .setPositiveButton("Install", (d, w) -> install(apk))
                .setNeutralButton("Save to folder", (d, w) -> saveToFolder(apk))
                .setNegativeButton("Close", null).show();
    }

    void saveToFolder(File f) {
        pendingSave = f;
        saveLauncher.launch(f.getName());
    }

    void onSaveTarget(Uri uri) {
        final File src = pendingSave;
        if (uri == null || src == null) return;
        io.execute(() -> {
            try (java.io.InputStream in = new java.io.FileInputStream(src);
                 java.io.OutputStream o = getContentResolver().openOutputStream(uri)) {
                if (o == null) throw new java.io.IOException("cannot open the chosen location");
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
                runOnUiThread(() -> Ui.toast(this, "Saved " + src.getName()));
            } catch (Exception e) {
                AppLog.e("Editor", "Background save failed", e);
                runOnUiThread(() -> Ui.toast(this, "Save failed: " + e.getMessage()));
            }
        });
    }

    // ------------------------------------------------------------ error bar under the editor
    List<Diag> diagsOf(Doc d) {
        String rel = Io.rel(proj, d.file);
        List<Diag> all = new ArrayList<>(), out = new ArrayList<>();
        List<Diag> l = local.get(rel);
        if (l != null) all.addAll(l);
        all.addAll(buildDiags);
        for (Diag x : all) if (x.line > 0 && x.file.equals(rel) && x.sev < 3) out.add(x);
        Collections.sort(out, (a, c) -> a.line != c.line ? a.line - c.line : a.sev - c.sev);
        return out;
    }

    void updateErrBar() {
        if (errBar == null) return;
        List<Diag> mine = cur == null ? new ArrayList<Diag>() : diagsOf(cur);
        if (mine.isEmpty()) {
            errBar.setVisibility(View.GONE);
            return;
        }
        Diag at = null;
        for (Diag d : mine) if (d.line == cursorLine && (at == null || d.sev < at.sev)) at = d;
        errBar.setVisibility(View.VISIBLE);
        if (at != null) {
            errMsg.setText((at.sev == 1 ? "Error" : "Warning") + " on line " + at.line + ": " + at.msg);
            errMsg.setTextColor(at.sev == 1 ? Ui.BAD : Ui.WARN);
        } else {
            errMsg.setText(mine.size() + (mine.size() == 1 ? " problem" : " problems") + " in this file. Tap Next to see them.");
            errMsg.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF888888));
        }
    }

    void jumpProblem(int dir) {
        if (cur == null) return;
        List<Diag> m = diagsOf(cur);
        if (m.isEmpty()) return;
        Diag pick = dir > 0 ? m.get(0) : m.get(m.size() - 1); // wrap around by default
        if (dir > 0) {
            for (Diag d : m) if (d.line > cursorLine) { pick = d; break; }
        } else {
            for (int i = m.size() - 1; i >= 0; i--) if (m.get(i).line < cursorLine) { pick = m.get(i); break; }
        }
        ed.goToLine(pick.line);
    }

    // ------------------------------------------------------------ terminal
    void termInit() {
        shell = new Shell(proj);
        shell.env = NativeExec.allEnv(this);
        sdk = new SdkCmd.Session(this, proj, shell);
        termPrompt.setOnClickListener(v -> termHistoryMenu());
        termRun.setOnClickListener(v -> {
            if (termBusy) {
                shell.kill();
                sdk.cancelled = true;
            } else termSubmit();
        });
        termIn.setOnEditorActionListener((tv, a, ev) -> {
            termSubmit();
            return true;
        });
        termPrint("CloudIDE terminal. Android shell plus the SDK tools (aapt2, ecj, d8, ...). Type help.\n");
        termUpdatePrompt();
    }

    void termUpdatePrompt() { termPrompt.setText(shell.where() + " $"); }

    private final StringBuilder termPending = new StringBuilder();
    private boolean termFlushQueued;

    /** Output is collected and drawn at most about 12 times a second, so a chatty command cannot freeze the screen. */
    void termPrint(String s) {
        termBuf.append(s);
        termPending.append(s);
        if (termFlushQueued) return;
        termFlushQueued = true;
        term.postDelayed(this::flushTerm, 80);
    }

    void flushTerm() {
        termFlushQueued = false;
        String chunk = termPending.toString();
        termPending.setLength(0);
        if (termBuf.length() > 100000) {
            termBuf.delete(0, termBuf.length() - 80000);
            term.setText(termBuf);
        } else term.append(chunk);
        termScroll.post(() -> termScroll.scrollTo(0, term.getBottom()));
    }

    void termHistoryMenu() {
        if (termHist.isEmpty()) {
            Ui.toast(this, "No commands yet");
            return;
        }
        final String[] items = termHist.toArray(new String[0]);
        Ui.menu(this, "Recent commands", items, i -> {
            termIn.setText(items[i]);
            termIn.setSelection(termIn.length());
            termIn.requestFocus();
        });
    }

    void termSubmit() {
        if (termBusy) return;
        final String line = termIn.getText().toString().trim();
        if (line.isEmpty()) return;
        termIn.setText("");
        termHist.remove(line);
        termHist.add(0, line);
        while (termHist.size() > 30) termHist.remove(termHist.size() - 1);
        termPrint(shell.where() + " $ " + line + "\n");
        if (termBuiltin(line)) {
            termUpdatePrompt();
            return;
        }
        termBusy = true;
        ((TextView) termRun).setText("Stop");
        sdk.cancelled = false;
        final boolean viaSdk = sdk.wants(line);
        if (viaSdk) sdk.refreshVars();
        new Thread(() -> {
            int code = viaSdk ? sdk.runLine(line, s -> runOnUiThread(() -> termPrint(s + "\n")))
                    : shell.run(line, s -> runOnUiThread(() -> termPrint(s)));
            runOnUiThread(() -> {
                if (code != 0) termPrint("[exit " + code + "]\n");
                termBusy = false;
                ((TextView) termRun).setText("Run");
                termUpdatePrompt();
            });
        }).start();
    }

    /** Commands the IDE itself understands. Returns false for anything that should go to the Android shell. */
    boolean termBuiltin(String line) {
        List<String> w = Shell.words(line);
        switch (w.get(0)) {
            case "help":
                termPrint("Handled by CloudIDE:\n"
                        + "  build [task]  build on GitHub (keeps the last task if none given)\n"
                        + "  lbuild        build locally (Java, Kotlin, C, C++, Rust, Go, Python, Node, Android)\n"
                        + "  lrun [x]      run the last local build, or a script: lrun main.py, lrun main.js\n"
                        + "  plugins       language plugins: list, and: plugin install|remove|import|update <name>\n"
                        + "  sdk, apkbuild SDK tools in the terminal: aapt2 aapt aidl dexdump split-select ecj kotlinc d8 apksigner zipalign mkjar apkpack deps\n"
                        + "                (sdk init writes build-apk.cmd, apkbuild runs it: an APK without Gradle)\n"
                        + "  toolchain     show the local toolchain status\n"
                        + "  push          push without building\n"
                        + "  apk           install, save or inspect the last built APK\n"
                        + "  outputs       list downloaded build files\n"
                        + "  history       build history\n"
                        + "  problems      show errors\n"
                        + "  langs         languages found in this project\n"
                        + "  open <file>   open a file in the editor\n"
                        + "  clear         clear this screen\n"
                        + "Everything else runs in the Android shell: ls, cat, grep, find, cp, mv, rm, mkdir, sed, wc, ps.\n"
                        + "Installed language plugins (plugin install <name>) are on PATH: clang, make, cmake, rustc, cargo, go, python, pip, node, npm, ruby, php, ...\n"
                        + "There is no git here, and programs cannot read keyboard input.\n");
                return true;
            case "clear":
                termBuf.setLength(0);
                termPending.setLength(0);
                term.setText("");
                return true;
            case "build":
                if (w.size() > 1) {
                    cfg.task = line.substring(5).trim();
                    cfg.save();
                }
                build(true);
                return true;
            case "lbuild":
                if (buildRunning()) termPrint("A build is already running\n");
                else localBuild();
                return true;
            case "toolchain":
                termPrint(Toolchain.shortStatus(this) + "\n");
                return true;
            case "packs":
            case "plugins":
            case "plugin":
                termPlugin(w);
                return true;
            case "lrun": {
                final List<String> ra = new ArrayList<>(w.subList(1, w.size()));
                new Thread(() -> {
                    try {
                        final int code = LocalLang.runLast(this, proj, ra, s -> runOnUiThread(() -> termPrint(s + "\n")), () -> false);
                        if (code != 0) runOnUiThread(() -> termPrint("[exit " + code + "]\n"));
                    } catch (Throwable e) {
                        AppLog.e("Editor", "Terminal tool failed", e);
                        final String m = e.getMessage() == null ? e.toString() : e.getMessage();
                        runOnUiThread(() -> termPrint(m + "\n"));
                    }
                }).start();
                return true;
            }
            case "push":
                build(false);
                return true;
            case "apk": {
                File a = lastApk();
                if (a == null) termPrint("No APK yet. Type build.\n");
                else outputMenu(a);
                return true;
            }
            case "outputs":
                showOutputs();
                return true;
            case "history":
                showHistory();
                return true;
            case "problems":
                showPanel(0);
                return true;
            case "langs":
                termPrint(Detect.label(proj) + "\nLocal build: " + LocalLang.describe(this, proj) + "\n");
                return true;
            case "open":
                if (w.size() < 2) {
                    termPrint("usage: open <file>\n");
                } else {
                    try {
                        File f = new File(w.get(1));
                        if (!f.isAbsolute()) f = new File(shell.cwd, w.get(1));
                        f = f.getCanonicalFile();
                        if (!f.isFile()) termPrint("open: no such file\n");
                        else if (!f.getPath().startsWith(proj.getCanonicalPath() + "/")) termPrint("open: only files inside the project can be opened\n");
                        else openFile(f, 0);
                    } catch (java.io.IOException e) {
                        termPrint("open: " + e.getMessage() + "\n");
                    }
                }
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------ toolbar menu
    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        getMenuInflater().inflate(R.menu.menu_editor, m);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem it) {
        int id = it.getItemId();
        if (id == R.id.m_palette) showPalette();
        else if (id == R.id.m_ai_open) {
            showPanel(4);
            ai.focusInput();
        } else if (id == R.id.m_ai_web) {
            openWebAiScreen();
        } else if (id == R.id.m_ai_mode) {
            showPanel(4);
            ai.toggleMode();
        } else if (id == R.id.m_ai_project) {
            showPanel(4);
            ai.attachProject();
            ai.focusInput();
        } else if (id == R.id.m_ai_review) aiQuick(8);
        else if (id == R.id.m_ai_explain) aiQuick(0);
        else if (id == R.id.m_ai_fix) aiQuick(1);
        else if (id == R.id.m_ai_refactor) aiQuick(2);
        else if (id == R.id.m_ai_tests) aiQuick(3);
        else if (id == R.id.m_ai_docs) aiQuick(4);
        else if (id == R.id.m_ai_settings) openAiSettings();
        else if (id == R.id.m_save) {
            if (cur != null) saveDoc(cur);
            saveAll();
            Ui.toast(this, "Saved");
        } else if (id == R.id.m_undo) undo();
        else if (id == R.id.m_redo) redo();
        else if (id == R.id.m_find) toggleFind();
        else if (id == R.id.m_build) build(true);
        else if (id == R.id.m_goto) Ui.input(this, "Go to line", "Line number", "", s -> {
            try {
                ed.goToLine(Integer.parseInt(s.trim()));
            } catch (NumberFormatException e) {
                Ui.toast(this, "Enter a number");
            }
        });
        else if (id == R.id.m_comment) ed.toggleComment();
        else if (id == R.id.m_dup) ed.duplicateLine();
        else if (id == R.id.m_del) ed.deleteLine();
        else if (id == R.id.m_up) ed.moveLine(-1);
        else if (id == R.id.m_down) ed.moveLine(1);
        else if (id == R.id.m_indent) ed.indent(false);
        else if (id == R.id.m_outdent) ed.indent(true);
        else if (id == R.id.m_format) format();
        else if (id == R.id.m_saveall) {
            saveAll();
            Ui.toast(this, "All files saved");
        } else if (id == R.id.m_search) searchProject();
        else if (id == R.id.m_quick) quickOpen();
        else if (id == R.id.m_term) showPanel(3);
        else if (id == R.id.m_getfiles) getFiles();
        else if (id == R.id.m_settings) startActivity(new Intent(this, SettingsActivity.class));
        else if (id == R.id.m_task) pickTask();
        else if (id == R.id.m_history) showHistory();
        else if (id == R.id.m_wf) {
            Ui.confirm(this, "Regenerate the build workflow?", "Rewrites .github/workflows/" + cfg.workflow + " for the "
                    + cfg.preset + " build type. Changes you made to that file are lost.", "Regenerate", () -> {
                try {
                    Workflows.write(this, proj, cfg);
                    Ui.toast(this, "Workflow written. Push to apply it.");
                } catch (Exception e) {
                    AppLog.e("Editor", "Regenerating the workflow failed", e);
                    Ui.toast(this, String.valueOf(e.getMessage()));
                }
            });
        }
        else if (id == R.id.m_push) build(false);
        else if (id == R.id.m_pull) pull();
        else if (id == R.id.m_install) install(lastApk());
        else if (id == R.id.m_outputs) showOutputs();
        else if (id == R.id.m_repo) {
            if (cfg.repo.isEmpty()) Ui.toast(this, "Not linked to GitHub yet");
            else startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/" + cfg.owner + "/" + cfg.repo)));
        } else if (id == R.id.m_psettings) projectSettings(null);
        else if (id == R.id.m_wrap) {
            boolean w = !Prefs.bool(this, "wrap", true);
            Prefs.put(this, "wrap", w);
            ed.setWrap(w);
        } else if (id == R.id.m_log) showPanel(ptab);
        else if (id == R.id.m_applog) startActivity(new Intent(this, LogActivity.class));
        else if (id == R.id.m_close) leaveProject();
        else return super.onOptionsItemSelected(it);
        return true;
    }
}
