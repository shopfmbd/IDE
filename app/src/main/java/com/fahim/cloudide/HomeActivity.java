package com.fahim.cloudide;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** The project list: create, import, clone, open, rename and delete projects. */
public class HomeActivity extends AppCompatActivity {
    private static final int BUILD_NONE = 0, BUILD_OK = 1, BUILD_FAILED = 2;

    /** What one list row shows. Read from disk on a background thread, so scrolling and typing in the search box stay smooth. */
    static final class Row {
        final File dir;
        final String name, subtitle, status;
        final int build;

        Row(File dir, String subtitle, String status, int build) {
            this.dir = dir;
            this.name = dir.getName();
            this.subtitle = subtitle;
            this.status = status;
            this.build = build;
        }
    }

    private RecyclerView list;
    private View empty, emptyActions;
    private TextView emptyTitle, emptyBody;
    private EditText search;
    private final List<Row> all = new ArrayList<>();   // every project, most recently opened first
    private final List<Row> items = new ArrayList<>(); // the rows that match the search box
    private ProjAdapter ad;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final FolderPicker folderPicker = new FolderPicker(this);
    private int loadGeneration;

    @Override
    protected void onCreate(Bundle b) {
        Ui.edge(this);
        super.onCreate(b);
        setContentView(R.layout.activity_home);
        Ui.insets(findViewById(R.id.root), true, true);
        setSupportActionBar(findViewById(R.id.toolbar));
        search = findViewById(R.id.search);
        emptyActions = findViewById(R.id.emptyActions);
        emptyTitle = findViewById(R.id.emptyTitle);
        emptyBody = findViewById(R.id.emptyBody);
        findViewById(R.id.emptyNew).setOnClickListener(v -> newProject());
        findViewById(R.id.emptyClone).setOnClickListener(v -> cloneRepo());
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence x, int a, int c, int d) { }

            @Override
            public void onTextChanged(CharSequence x, int a, int c, int d) { applyFilter(); }

            @Override
            public void afterTextChanged(Editable e) { }
        });
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        list.setLayoutManager(new LinearLayoutManager(this));
        ad = new ProjAdapter();
        list.setAdapter(ad);
        ExtendedFloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> addMenu());
        if (Build.VERSION.SDK_INT < 30 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        askStorage();
        refresh();
        offerCrashReport();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    /** After a crash, say so once and offer the log. Without this the person only sees the app "just closing". */
    private void offerCrashReport() {
        String why = AppLog.pendingCrash();
        if (why == null) return;
        AppLog.ackCrash();
        new MaterialAlertDialogBuilder(this).setTitle(R.string.crash_title)
                .setMessage(getString(R.string.crash_message, why))
                .setPositiveButton(R.string.open_log, (d, w) -> startActivity(new Intent(this, LogActivity.class)))
                .setNegativeButton(R.string.dismiss, null).show();
    }

    private void askStorage() {
        if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager() || Prefs.bool(this, "askedStorage", false)) return;
        Prefs.put(this, "askedStorage", true);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.storage_title)
                .setMessage(R.string.storage_message)
                .setPositiveButton(R.string.allow, (d, w) -> startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))))
                .setNegativeButton(R.string.not_now, null).show();
    }

    // ------------------------------------------------------------ loading and filtering

    /** Reloads the project list from disk without blocking the screen. */
    void refresh() {
        final int gen = ++loadGeneration;
        final android.content.Context app = getApplicationContext();
        io.execute(() -> {
            List<Row> rows = new ArrayList<>();
            final Map<String, Long> opened = new HashMap<>();
            File[] dirs = Prefs.root(app).listFiles();
            if (dirs != null) {
                long now = System.currentTimeMillis();
                for (File f : dirs) {
                    if (!f.isDirectory() || f.getName().startsWith(".")) continue;
                    opened.put(f.getName(), Prefs.opened(app, f.getName()));
                    rows.add(readRow(f, now));
                }
            }
            Collections.sort(rows, (a, c) -> {
                long x = opened.get(a.name), y = opened.get(c.name);
                return x != y ? Long.compare(y, x) : a.name.compareToIgnoreCase(c.name);
            });
            runOnUiThread(() -> {
                if (gen != loadGeneration || !Ui.alive(this)) return; // a newer load is already on its way
                all.clear();
                all.addAll(rows);
                applyFilter();
            });
        });
    }

    private static Row readRow(File f, long now) {
        ProjectConfig c = ProjectConfig.load(f);
        String sub = c.repo.isEmpty() ? Detect.label(f)
                : c.owner + "/" + c.repo + (c.branch.isEmpty() ? "" : "  (" + c.branch + ")");
        JSONObject last = c.history.length() > 0 ? c.history.optJSONObject(0) : null;
        String ago = Human.ago(last == null ? 0 : last.optLong("t"), now);
        String suffix = ago.isEmpty() ? "" : " " + ago;
        int build = BUILD_NONE;
        String st;
        if (c.lastStatus.equals("success")) {
            st = "Last build passed" + suffix;
            build = BUILD_OK;
        } else if (c.lastStatus.equals("failed")) {
            st = "Last build failed" + suffix;
            build = BUILD_FAILED;
        } else st = c.repo.isEmpty() ? "Not linked to GitHub yet" : "Not built yet";
        return new Row(f, sub, st, build);
    }

    /** Applies the search box to the rows already in memory. */
    private void applyFilter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        items.clear();
        for (Row r : all) if (q.isEmpty() || r.name.toLowerCase(Locale.ROOT).contains(q)) items.add(r);
        ad.notifyDataSetChanged();
        boolean filtering = !q.isEmpty() && !all.isEmpty();
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        emptyTitle.setText(filtering ? R.string.no_match_title : R.string.empty_title);
        emptyBody.setText(filtering ? R.string.no_match_body : R.string.empty_body);
        emptyActions.setVisibility(filtering ? View.GONE : View.VISIBLE);
        if (getSupportActionBar() != null)
            getSupportActionBar().setSubtitle(all.isEmpty() ? "" : getResources().getQuantityString(R.plurals.project_count, all.size(), all.size()));
    }

    // ------------------------------------------------------------ menu

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        getMenuInflater().inflate(R.menu.menu_home, m);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem it) {
        if (it.getItemId() == R.id.m_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (it.getItemId() == R.id.m_ai) {
            startActivity(new Intent(this, AgentActivity.class));
            return true;
        }
        if (it.getItemId() == R.id.m_logs) {
            startActivity(new Intent(this, LogActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(it);
    }

    private void openProject(File f) {
        Prefs.touch(this, f.getName());
        startActivity(new Intent(this, EditorActivity.class).putExtra("dir", f.getAbsolutePath()));
    }

    private void addMenu() {
        Ui.menu(this, "Add project", new String[]{"New project", "Clone from GitHub", "Open folder in place", "Import folder (copy)"}, i -> {
            if (i == 0) newProject();
            else if (i == 1) cloneRepo();
            else if (i == 2) openPath();
            else importPath();
        });
    }

    // ------------------------------------------------------------ create

    private void newProject() {
        Ui.menu(this, "Template", new String[]{"Java  -  Views", "Kotlin  -  Views", "Kotlin  -  Jetpack Compose", "C++  -  NDK + CMake (JNI)",
                        "Rust  -  JNI via cargo-ndk", "Flutter  -  Dart", "Custom  -  any language (C, C++, Rust, Go ...)",
                        "Java  -  On-device build (no libraries)"},
                t -> Ui.form(this, "New project", new String[]{"App name", "Package name"},
                        new String[]{"My App", "com.example.myapp"}, null, "Create", v -> create(t, v[0], v[1])));
    }

    /** "com.example.app": lower-case segments, none of them a Java keyword. */
    static boolean validPackage(String pkg) { return AiActions.validPackage(pkg); }

    private void create(int t, String name, String pkg) {
        try {
            openProject(ProjectFactory.create(this, t, name, pkg));
        } catch (IllegalArgumentException e) {
            Ui.toast(this, e.getMessage());
        } catch (Exception e) {
            AppLog.e("Home", "Creating project " + name + " failed", e);
            Ui.toast(this, "Could not create the project: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ import and clone

    private void openPath() {
        folderPicker.pick(d -> openProject(d));
    }

    private void importPath() {
        folderPicker.pick(s -> {
            File to = new File(Prefs.root(this), s.getName());
            if (to.exists()) {
                Ui.toast(this, "A project named " + s.getName() + " already exists");
                return;
            }
            if (Io.inside(s, to)) {
                Ui.toast(this, "Pick a folder outside the projects folder");
                return;
            }
            AlertDialog busy = Ui.busy(this, "Copying " + s.getName() + "...");
            io.execute(() -> {
                try {
                    Io.copy(s, to);
                    runOnUiThread(() -> {
                        Ui.dismiss(busy);
                        refresh();
                    });
                } catch (Exception e) {
                    AppLog.e("Home", "Import of " + s + " failed", e);
                    Io.delete(to); // do not leave a half-copied project behind
                    runOnUiThread(() -> {
                        Ui.dismiss(busy);
                        Ui.toast(this, "Import failed: " + e.getMessage());
                    });
                }
            });
        });
    }

    private void cloneRepo() {
        String tok = Prefs.token(this);
        if (tok.isEmpty()) {
            Ui.toast(this, "Add your GitHub token in Settings first");
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        AlertDialog busy = Ui.busy(this, "Loading your repositories...");
        io.execute(() -> {
            try {
                JSONArray a = new Gh(tok, s -> { }).repos();
                String[] names = new String[a.length()];
                for (int k = 0; k < names.length; k++) names[k] = a.getJSONObject(k).getString("full_name");
                runOnUiThread(() -> {
                    Ui.dismiss(busy);
                    if (names.length == 0) Ui.toast(this, "No repositories found");
                    else Ui.menu(this, "Clone repository", names, i -> doClone(names[i], tok));
                });
            } catch (Exception e) {
                AppLog.e("Home", "Loading repositories failed", e);
                runOnUiThread(() -> {
                    Ui.dismiss(busy);
                    Ui.toast(this, String.valueOf(e.getMessage()));
                });
            }
        });
    }

    private void doClone(String full, String tok) {
        String[] p = full.split("/");
        File dest = new File(Prefs.root(this), p[1]);
        if (dest.exists()) {
            Ui.toast(this, "A project named " + p[1] + " already exists");
            return;
        }
        AlertDialog busy = Ui.busy(this, "Downloading " + full + "...");
        io.execute(() -> {
            try {
                byte[] z = new Gh(tok, s -> { }).zipball(p[0], p[1], "");
                List<String> got = Io.unzipStrip(z, dest);
                ProjectConfig c = ProjectConfig.load(dest);
                c.owner = p[0];
                c.repo = p[1];
                // Remember what GitHub has, so changed and new files are marked in the file tree right away
                for (String rel : got) {
                    try {
                        c.pushed.put(rel, Io.sha(new File(dest, rel)));
                    } catch (Exception ignored) {
                        // an unreadable file simply shows as changed
                    }
                }
                c.save();
                runOnUiThread(() -> {
                    Ui.dismiss(busy);
                    refresh();
                    openProject(dest);
                });
            } catch (Exception e) {
                AppLog.e("Home", "Clone of " + full + " failed", e);
                Io.delete(dest);
                runOnUiThread(() -> {
                    Ui.dismiss(busy);
                    Ui.toast(this, String.valueOf(e.getMessage()));
                });
            }
        });
    }

    // ------------------------------------------------------------ rename and delete

    private void projectMenu(File f) {
        Ui.menu(this, f.getName(), new String[]{"Open", "Rename", "Delete", "Open on GitHub"}, i -> {
            if (i == 0) openProject(f);
            else if (i == 1) rename(f);
            else if (i == 2) delete(f);
            else openOnGitHub(f);
        });
    }

    private void rename(File f) {
        Ui.input(this, "Rename project", "Name", f.getName(), n -> {
            if (n.isEmpty() || n.equals(f.getName())) return;
            File to = new File(f.getParentFile(), n);
            if (!n.matches("[A-Za-z0-9][A-Za-z0-9 ._-]*")) Ui.toast(this, "Use letters, digits, space, dot, dash or underscore");
            else if (to.exists()) Ui.toast(this, "A project with that name already exists");
            else if (!f.renameTo(to)) {
                AppLog.w("Home", "Rename failed: " + f + " -> " + to);
                Ui.toast(this, "Rename failed. Check storage access in Settings.");
            } else {
                Prefs.renameProject(this, f.getName(), n); // keeps the project's own token and last opened file
                Prefs.touch(this, n);
            }
            refresh();
        });
    }

    private void delete(File f) {
        Ui.confirm(this, "Delete " + f.getName() + "?",
                "This deletes the local copy only. The GitHub repository is not touched.", "Delete", () -> {
                    AlertDialog busy = Ui.busy(this, "Deleting " + f.getName() + "...");
                    final android.content.Context app = getApplicationContext();
                    io.execute(() -> {
                        Io.delete(f); // a big project can take a while, so not on the UI thread
                        Prefs.forgetProject(app, f.getName());
                        runOnUiThread(() -> {
                            Ui.dismiss(busy);
                            refresh();
                        });
                    });
                });
    }

    private void openOnGitHub(File f) {
        ProjectConfig c = ProjectConfig.load(f);
        if (c.repo.isEmpty()) Ui.toast(this, "Not linked to GitHub yet");
        else startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/" + c.owner + "/" + c.repo)));
    }

    // ------------------------------------------------------------ list

    private class ProjAdapter extends RecyclerView.Adapter<ProjAdapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            final TextView name, sub, status, mono;
            final View dot;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.name);
                sub = v.findViewById(R.id.sub);
                status = v.findViewById(R.id.status);
                mono = v.findViewById(R.id.mono);
                dot = v.findViewById(R.id.dot);
            }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_project, p, false));
        }

        @Override
        public void onBindViewHolder(VH vh, int i) {
            final Row r = items.get(i);
            vh.name.setText(r.name);
            vh.mono.setText(r.name.substring(0, 1).toUpperCase(Locale.ROOT));
            vh.mono.setBackgroundTintList(ColorStateList.valueOf(Ui.tint(r.name)));
            vh.sub.setText(r.subtitle);
            vh.status.setText(r.status);
            int dot = r.build == BUILD_OK ? Ui.OK : r.build == BUILD_FAILED ? Ui.BAD
                    : MaterialColors.getColor(vh.itemView, com.google.android.material.R.attr.colorOutline, 0xFF888888);
            vh.dot.setBackgroundTintList(ColorStateList.valueOf(dot));
            vh.itemView.setOnClickListener(v -> openProject(r.dir));
            vh.itemView.setOnLongClickListener(v -> {
                projectMenu(r.dir);
                return true;
            });
        }

        @Override
        public int getItemCount() { return items.size(); }
    }
}
