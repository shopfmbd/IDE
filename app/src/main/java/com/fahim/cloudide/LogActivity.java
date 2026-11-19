package com.fahim.cloudide;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;

/** App log and diagnostics: filter by level, copy, share as a file, clear. Opened from Settings and after a crash. */
public class LogActivity extends AppCompatActivity {
    private TextView view;
    private ScrollView scroll;
    private TextView info;
    private int minLevel = AppLog.DEBUG;

    @Override
    protected void onCreate(Bundle b) {
        Ui.edge(this);
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        MaterialToolbar tb = new MaterialToolbar(this);
        tb.setTitle("Logs and diagnostics");
        tb.setNavigationIcon(R.drawable.ic_back);
        tb.setNavigationOnClickListener(v -> finish());
        Menu m = tb.getMenu();
        m.add(0, 1, 0, "Copy").setIcon(R.drawable.ic_file).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        m.add(0, 2, 1, "Share").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        m.add(0, 3, 2, "Jump to end").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        m.add(0, 4, 3, "Clear log").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        tb.setOnMenuItemClickListener(this::onAction);
        root.addView(tb, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));

        info = new TextView(this);
        info.setTextSize(12);
        info.setTypeface(Typeface.MONOSPACE);
        info.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 4));
        info.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF888888));
        root.addView(info, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        ChipGroup g = new ChipGroup(this);
        g.setSingleSelection(true);
        g.setSelectionRequired(true);
        g.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        String[] names = {"Everything", "Warnings and errors", "Errors only"};
        int[] levels = {AppLog.DEBUG, AppLog.WARN, AppLog.ERROR};
        for (int i = 0; i < names.length; i++) {
            Chip c = new Chip(this);
            c.setText(names[i]);
            c.setCheckable(true);
            c.setId(100 + i);
            final int lv = levels[i];
            c.setOnClickListener(v -> {
                minLevel = lv;
                render(true);
            });
            g.addView(c);
        }
        g.check(100);
        hs.addView(g);
        root.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(this);
        view = new TextView(this);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(11.5f);
        view.setTextIsSelectable(true);
        view.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 24));
        HorizontalScrollView wide = new HorizontalScrollView(this);
        wide.addView(view);
        scroll.addView(wide);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        Ui.insets(root, true, true);
        AppLog.ackCrash();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render(true);
    }

    private boolean onAction(MenuItem it) {
        switch (it.getItemId()) {
            case 1:
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("CloudIDE log", AppLog.report(this)));
                Ui.toast(this, "Diagnostics and log copied");
                return true;
            case 2:
                try {
                    Ui.share(this, AppLog.reportFile(this));
                } catch (Exception e) {
                    AppLog.e("LogActivity", "Share failed", e);
                    Ui.toast(this, "Could not share: " + e.getMessage());
                }
                return true;
            case 3:
                scroll.post(() -> scroll.fullScroll(android.view.View.FOCUS_DOWN));
                return true;
            case 4:
                Ui.confirm(this, "Clear the log?", "Deletes the saved log. New events are recorded again straight away.", "Clear", () -> {
                    AppLog.clear();
                    render(false);
                });
                return true;
            default:
                return false;
        }
    }

    private static int levelOf(String line) {
        // "MM-dd HH:mm:ss.SSS L/Tag ..." : the level letter sits at index 19
        if (line.length() > 20 && line.charAt(20) == '/') {
            switch (line.charAt(19)) {
                case 'E': return AppLog.ERROR;
                case 'W': return AppLog.WARN;
                case 'I': return AppLog.INFO;
                default: return AppLog.DEBUG;
            }
        }
        return -1; // continuation line (stack trace)
    }

    private void render(boolean toEnd) {
        info.setText(AppLog.deviceInfo(this).trim());
        String all = AppLog.read();
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int cur = AppLog.DEBUG;
        int shown = 0;
        for (String line : all.split("\n")) {
            if (line.isEmpty()) continue;
            int lv = levelOf(line);
            if (lv >= 0) cur = lv;
            if (cur < minLevel) continue;
            int start = sb.length();
            sb.append(line).append('\n');
            if (cur == AppLog.ERROR) sb.setSpan(new ForegroundColorSpan(Ui.BAD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            else if (cur == AppLog.WARN) sb.setSpan(new ForegroundColorSpan(Ui.WARN), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (lv >= 0) shown++;
        }
        if (sb.length() == 0) sb.append(all.isEmpty() ? "Nothing logged yet. Errors, crashes and build problems show up here."
                : "No entries at this level.");
        view.setText(sb);
        if (toEnd && shown > 0) scroll.post(() -> scroll.fullScroll(android.view.View.FOCUS_DOWN));
    }
}
