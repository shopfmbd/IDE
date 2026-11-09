package com.fahim.cloudide;

import android.content.Context;
import android.content.Intent;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import java.io.File;

/**
 * "Project settings", shown as a bottom sheet with a fixed Save bar.
 * The first choice is the build method (Local or GitHub Actions). Repository and workflow options only appear
 * for GitHub Actions; rarely needed ones sit behind "Advanced options". Save validates in place and only closes
 * when the settings are usable.
 */
final class ProjectSettingsDialog {
    // Same order as Workflows.PRESETS: auto, gradle, rust, flutter, node, custom.
    private static final String[] PRESET_LABELS = {"Auto-detect", "Android (Gradle)", "Rust", "Flutter", "Node.js", "Custom script"};

    private ProjectSettingsDialog() { }

    /** A ScrollView that wraps its content but never grows taller than max, so the Save bar stays on screen. */
    private static final class CappedScroll extends ScrollView {
        private final int max;

        CappedScroll(Context c, int max) {
            super(c);
            this.max = max;
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST));
        }
    }

    static void show(Context c, File proj, ProjectConfig cfg, Runnable saved) { show(c, proj, cfg, saved, false); }

    /** needRepo: the repository fields are shown even for a Local project (Upload and Download need them). */
    static void show(Context c, File proj, ProjectConfig cfg, Runnable saved, boolean needRepo) {
        String o = cfg.owner.isEmpty() ? Prefs.owner(c) : cfg.owner;
        String r = cfg.repo.isEmpty() ? cfg.slug() : cfg.repo;
        final java.util.List<View> repoViews = new java.util.ArrayList<>();   // GitHub Actions, or needRepo
        final java.util.List<View> cloudViews = new java.util.ArrayList<>();  // GitHub Actions only
        final java.util.List<View> localViews = new java.util.ArrayList<>();  // Local only

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(c, 24), Ui.dp(c, 4), Ui.dp(c, 24), Ui.dp(c, 16));

        // ---- Build method
        box.addView(section(c, "Build method", "How this project is compiled when you run Build", false));
        MaterialButtonToggleGroup mode = toggle(c, new String[]{"Local", "GitHub Actions"}, cfg.local ? 0 : 1);
        box.addView(mode);
        TextView modeHelp = new TextView(c);
        modeHelp.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        modeHelp.setTextColor(MaterialColors.getColor(c, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666));
        modeHelp.setPadding(0, Ui.dp(c, 8), 0, 0);
        box.addView(modeHelp);

        // Local: nothing to enter here, only a shortcut to the tools it uses.
        LinearLayout tools = new LinearLayout(c);
        tools.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = Ui.dp(c, 16);
        tools.setLayoutParams(tl);
        MaterialButton openTools = new MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        openTools.setText("Build tools and Java version");
        openTools.setAllCaps(false);
        openTools.setOnClickListener(v -> c.startActivity(new Intent(c, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_SECTION, "tools")));
        tools.addView(openTools, new LinearLayout.LayoutParams(-1, -2));
        localViews.add(tools);
        box.addView(tools);

        // ---- Repository
        View repoHead = section(c, "Repository", "Remote used for upload, download and GitHub Actions builds", true);
        repoViews.add(repoHead);
        box.addView(repoHead);
        TextInputLayout repoL = Ui.field(c, "Repository", (o.isEmpty() ? "" : o + "/") + r, false);
        repoL.setHelperText("owner/name or repository URL");
        repoViews.add(repoL);
        box.addView(repoL);
        TextInputLayout branchL = Ui.field(c, "Branch", cfg.branch, false);
        branchL.setHelperText("Defaults to the repository's default branch");
        repoViews.add(branchL);
        box.addView(branchL);
        View visLabel = label(c, "Visibility if the repository is created");
        repoViews.add(visLabel);
        box.addView(visLabel);
        MaterialButtonToggleGroup vis = toggle(c, new String[]{"Private", "Public"}, cfg.priv ? 0 : 1);
        repoViews.add(vis);
        box.addView(vis);

        // ---- Workflow
        View typeHead = section(c, "Workflow", "How GitHub Actions builds this project", true);
        cloudViews.add(typeHead);
        box.addView(typeHead);
        final int[] preset = {Math.max(0, indexOf(Workflows.PRESETS, cfg.preset))};
        TextInputLayout typeL = new TextInputLayout(c, null,
                com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle);
        typeL.setHint("Project type");
        MaterialAutoCompleteTextView type = new MaterialAutoCompleteTextView(typeL.getContext());
        type.setInputType(InputType.TYPE_NULL);
        type.setSimpleItems(PRESET_LABELS);
        type.setText(PRESET_LABELS[preset[0]], false);
        type.setOnItemClickListener((p, v, pos, id) -> preset[0] = pos);
        typeL.addView(type);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = Ui.dp(c, 8);
        typeL.setLayoutParams(tp);
        cloudViews.add(typeL);
        box.addView(typeL);

        // ---- Advanced
        View advRow = advancedRow(c);
        cloudViews.add(advRow);
        box.addView(advRow);
        LinearLayout adv = new LinearLayout(c);
        adv.setOrientation(LinearLayout.VERTICAL);
        adv.setVisibility(View.GONE);
        box.addView(adv);
        ((MaterialSwitch) advRow.findViewWithTag("adv")).setOnCheckedChangeListener((b, on) -> adv.setVisibility(on ? View.VISIBLE : View.GONE));

        adv.addView(label(c, "Java compiler (plain Java projects)"));
        MaterialButtonToggleGroup comp = toggle(c, new String[]{"javac", "ecj"}, "ecj".equals(cfg.compiler) ? 1 : 0);
        adv.addView(comp);
        TextInputLayout taskL = Ui.field(c, "Gradle task", cfg.task, false);
        taskL.setHelperText("Default: assembleDebug");
        adv.addView(taskL);
        TextInputLayout wfL = Ui.field(c, "Workflow file", cfg.workflow, false);
        wfL.setHelperText("File name under .github/workflows");
        adv.addView(wfL);
        TextInputLayout outL = Ui.field(c, "Output files", cfg.artifact, false);
        outL.setHelperText("Files to download after the build. Empty selects them automatically");
        adv.addView(outL);
        TextInputLayout gradleL = Ui.field(c, "Gradle version", cfg.gradle, false);
        gradleL.setHelperText("Default: 9.8.2");
        adv.addView(gradleL);
        TextInputLayout jdkL = Ui.field(c, "JDK version", cfg.jdk, false);
        jdkL.setHelperText("For example 17 or 21");
        adv.addView(jdkL);
        TextInputLayout tokL = Ui.field(c, "Project access token", "", true);
        boolean hasTok = !Prefs.projectToken(c, proj.getName()).isEmpty();
        tokL.setHelperText(hasTok ? "A token is saved for this project. Leave empty to keep it, enter - to remove it"
                : "Optional. Overrides the account token for this project");
        adv.addView(tokL);

        Runnable apply = () -> {
            boolean cloud = selected(mode) == 1;
            modeHelp.setText(cloud
                    ? "Compiles on GitHub-hosted runners. Requires a repository and an access token (App settings, GitHub account)."
                    : "Compiles on this device with the built-in build tools. Works offline and needs no GitHub account.");
            for (View v : repoViews) v.setVisibility(cloud || needRepo ? View.VISIBLE : View.GONE);
            for (View v : cloudViews) v.setVisibility(cloud ? View.VISIBLE : View.GONE);
            for (View v : localViews) v.setVisibility(cloud ? View.GONE : View.VISIBLE);
            adv.setVisibility(cloud && ((MaterialSwitch) advRow.findViewWithTag("adv")).isChecked() ? View.VISIBLE : View.GONE);
        };
        mode.addOnButtonCheckedListener((g, id, checked) -> apply.run());
        apply.run();

        // ---- Sheet: header, scrolling content, fixed footer
        final int maxScroll = c.getResources().getDisplayMetrics().heightPixels * 62 / 100;
        ScrollView sv = new CappedScroll(c, maxScroll);
        sv.addView(box);

        MaterialButton cancel = new MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        cancel.setText("Cancel");
        cancel.setAllCaps(false);
        MaterialButton save = new MaterialButton(c);
        save.setText("Save");
        save.setAllCaps(false);
        LinearLayout foot = new LinearLayout(c);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setPadding(Ui.dp(c, 24), Ui.dp(c, 12), Ui.dp(c, 24), Ui.dp(c, 16));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(0, -2, 1f);
        fl.rightMargin = Ui.dp(c, 6);
        LinearLayout.LayoutParams fr = new LinearLayout.LayoutParams(0, -2, 1f);
        fr.leftMargin = Ui.dp(c, 6);
        foot.addView(cancel, fl);
        foot.addView(save, fr);

        View rule = new View(c);
        rule.setBackgroundColor(MaterialColors.getColor(c, com.google.android.material.R.attr.colorOutlineVariant, 0x33888888));

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(title(c, "Project settings", proj.getName()));
        root.addView(sv, new LinearLayout.LayoutParams(-1, -2));
        root.addView(rule, new LinearLayout.LayoutParams(-1, Ui.dp(c, 1)));
        root.addView(foot);

        BottomSheetDialog dlg = new BottomSheetDialog(c);
        dlg.setContentView(root);
        if (dlg.getWindow() != null) dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dlg.setOnShowListener(d -> {
            View sheet = dlg.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                BottomSheetBehavior<View> bh = BottomSheetBehavior.from(sheet);
                bh.setState(BottomSheetBehavior.STATE_EXPANDED);
                bh.setSkipCollapsed(true);
            }
        });
        dlg.show();

        cancel.setOnClickListener(v -> dlg.dismiss());
        repoL.getEditText().addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { repoL.setError(null); }
            @Override public void afterTextChanged(Editable s) { }
        });

        save.setOnClickListener(v -> {
            boolean cloud = selected(mode) == 1;
            if (cloud || needRepo) {
                String repoText = Ui.text(repoL);
                String[] pr = ProjectConfig.parseRepo(repoText);
                if (!repoText.isEmpty() && pr == null) {
                    repoL.setError("Use owner/name or a URL such as github.com/owner/name");
                    repoL.requestFocus();
                    return;
                }
                if (pr != null) {
                    if (!pr[0].isEmpty()) cfg.owner = pr[0];
                    cfg.repo = pr[1];
                }
                if (cfg.repo.isEmpty()) cfg.repo = cfg.slug();
                cfg.branch = Ui.text(branchL);
                cfg.priv = selected(vis) == 0;
            }
            if (cloud) {
                cfg.workflow = orDefault(Ui.text(wfL), "build.yml");
                cfg.task = orDefault(Ui.text(taskL), "assembleDebug");
                cfg.gradle = orDefault(Ui.text(gradleL), "9.8.2");
                cfg.jdk = orDefault(Ui.text(jdkL), "21");
                cfg.compiler = selected(comp) == 1 ? "ecj" : "javac";
                cfg.preset = Workflows.PRESETS[preset[0]];
                cfg.artifact = Ui.text(outL);
                String tok = Ui.text(tokL);
                if (tok.equals("-")) Prefs.setProjectToken(c, proj.getName(), "");
                else if (!tok.isEmpty()) Prefs.setProjectToken(c, proj.getName(), tok);
            }
            cfg.local = !cloud;
            cfg.save();
            dlg.dismiss();
            if (saved != null) saved.run();
        });
    }

    /** A row with a switch that reveals the rarely needed options. */
    private static View advancedRow(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(c, 24);
        r.setLayoutParams(lp);
        TextView t = new TextView(c);
        t.setText("Advanced options");
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        t.setTextColor(MaterialColors.getColor(c, androidx.appcompat.R.attr.colorPrimary, 0xFF006B5E));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        MaterialSwitch sw = new MaterialSwitch(c);
        sw.setTag("adv");
        r.addView(sw);
        return r;
    }

    // ------------------------------------------------------------ small view helpers
    private static View title(Context c, String main, String sub) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(Ui.dp(c, 24), Ui.dp(c, 24), Ui.dp(c, 24), Ui.dp(c, 8));
        TextView t = new TextView(c);
        t.setText(main);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        t.setTextColor(MaterialColors.getColor(c, com.google.android.material.R.attr.colorOnSurface, 0xFF000000));
        TextView s = new TextView(c);
        s.setText(sub);
        s.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        s.setTextColor(MaterialColors.getColor(c, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666));
        l.addView(t);
        l.addView(s);
        return l;
    }

    private static View section(Context c, String name, String sub, boolean divider) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(c, divider ? 24 : 12);
        l.setLayoutParams(lp);
        if (divider) {
            View d = new View(c);
            d.setBackgroundColor(MaterialColors.getColor(c, com.google.android.material.R.attr.colorOutlineVariant, 0x33888888));
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 1));
            dp.bottomMargin = Ui.dp(c, 16);
            d.setLayoutParams(dp);
            l.addView(d);
        }
        TextView t = new TextView(c);
        t.setText(name);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        t.setTextColor(MaterialColors.getColor(c, androidx.appcompat.R.attr.colorPrimary, 0xFF006B5E));
        TextView s = new TextView(c);
        s.setText(sub);
        s.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        s.setTextColor(MaterialColors.getColor(c, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666));
        l.addView(t);
        l.addView(s);
        return l;
    }

    private static View label(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        t.setTextColor(MaterialColors.getColor(c, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(c, 16);
        lp.bottomMargin = Ui.dp(c, 6);
        t.setLayoutParams(lp);
        return t;
    }

    private static MaterialButtonToggleGroup toggle(Context c, String[] labels, int selected) {
        MaterialButtonToggleGroup g = new MaterialButtonToggleGroup(c);
        g.setSingleSelection(true);
        g.setSelectionRequired(true);
        g.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        for (String s : labels) {
            MaterialButton b = new MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setId(View.generateViewId());
            b.setText(s);
            b.setAllCaps(false);
            b.setSingleLine(true);
            b.setPaddingRelative(Ui.dp(c, 8), b.getPaddingTop(), Ui.dp(c, 8), b.getPaddingBottom());
            b.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            g.addView(b);
        }
        g.check(g.getChildAt(Math.max(0, Math.min(selected, labels.length - 1))).getId());
        return g;
    }

    private static int selected(MaterialButtonToggleGroup g) {
        for (int i = 0; i < g.getChildCount(); i++)
            if (((MaterialButton) g.getChildAt(i)).isChecked()) return i;
        return 0;
    }

    private static int indexOf(String[] a, String v) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i;
        return -1;
    }

    private static String orDefault(String v, String d) { return v.isEmpty() ? d : v; }
}
