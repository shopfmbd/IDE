package com.fahim.cloudide;

import android.app.Activity;
import android.graphics.Typeface;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.util.List;

/** Read-only APK viewer: size breakdown, signatures, file list, text preview, extract and share. */
final class ApkInspector {
    private ApkInspector() { }

    static void show(Activity a, File apk) {
        new Thread(() -> {
            try {
                ApkInfo info = ApkInfo.read(apk);
                a.runOnUiThread(() -> summary(a, info));
            } catch (Exception e) {
                a.runOnUiThread(() -> Ui.toast(a, "Cannot read this APK: " + e.getMessage()));
            }
        }).start();
    }

    private static void summary(Activity a, ApkInfo info) {
        new MaterialAlertDialogBuilder(a).setTitle(info.file.getName()).setMessage(info.summary())
                .setPositiveButton("Browse files", (d, w) -> browse(a, info))
                .setNegativeButton("Close", null).show();
    }

    private static void browse(Activity a, ApkInfo info) {
        List<ApkInfo.Entry> all = info.entries;
        int n = Math.min(all.size(), 200);
        String[] names = new String[n];
        for (int i = 0; i < n; i++) names[i] = all.get(i).name + "   " + ApkInfo.size(all.get(i).packed);
        Ui.menu(a, "Largest files (" + n + " of " + all.size() + ")", names, i -> entry(a, info, all.get(i).name));
    }

    private static void entry(Activity a, ApkInfo info, String name) {
        Ui.menu(a, name, new String[]{"Preview as text", "Extract and share"}, i -> {
            if (i == 0) preview(a, info, name);
            else extract(a, info, name);
        });
    }

    private static void preview(Activity a, ApkInfo info, String name) {
        new Thread(() -> {
            try {
                String t = info.text(name, 60000);
                a.runOnUiThread(() -> {
                    if (t == null) {
                        Ui.toast(a, "Binary file (compiled XML, dex or native code). Extract it to open it elsewhere.");
                        return;
                    }
                    TextView tv = new TextView(a);
                    tv.setText(t);
                    tv.setTypeface(Typeface.MONOSPACE);
                    tv.setTextSize(12);
                    tv.setTextIsSelectable(true);
                    tv.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), Ui.dp(a, 8));
                    ScrollView sv = new ScrollView(a);
                    sv.addView(tv);
                    new MaterialAlertDialogBuilder(a).setTitle(ApkInfo.baseName(name)).setView(sv)
                            .setPositiveButton("Close", null).show();
                });
            } catch (Exception e) {
                a.runOnUiThread(() -> Ui.toast(a, String.valueOf(e.getMessage())));
            }
        }).start();
    }

    private static void extract(Activity a, ApkInfo info, String name) {
        new Thread(() -> {
            try {
                File out = new File(a.getExternalFilesDir(null), "inspect/" + info.file.getName() + "/" + ApkInfo.baseName(name));
                info.extract(name, out);
                a.runOnUiThread(() -> Ui.share(a, out));
            } catch (Exception e) {
                a.runOnUiThread(() -> Ui.toast(a, "Extract failed: " + e.getMessage()));
            }
        }).start();
    }
}
