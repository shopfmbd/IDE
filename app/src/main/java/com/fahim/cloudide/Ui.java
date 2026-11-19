package com.fahim.cloudide;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

final class Ui {
    static final int OK = 0xFF2EB67D, BAD = 0xFFE5484D, WARN = 0xFFF5A524;

    interface Str { void run(String s); }
    interface Strs { void run(String[] v); }
    interface Idx { void run(int i); }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static void toast(Context c, String s) { Toast.makeText(c, s, Toast.LENGTH_LONG).show(); }

    /** Dismisses a dialog without crashing when its activity is already gone (a background job finishing late). */
    static void dismiss(AlertDialog d) {
        if (d == null) return;
        try {
            d.dismiss();
        } catch (RuntimeException ignored) {
            // the window is no longer attached
        }
    }

    /** True while the activity can still show dialogs and update views. */
    static boolean alive(Activity a) { return a != null && !a.isFinishing() && !a.isDestroyed(); }

    static TextInputLayout field(Context c, String hint, String val, boolean secret) {
        TextInputLayout l = new TextInputLayout(c, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        l.setHint(hint);
        TextInputEditText e = new TextInputEditText(l.getContext());
        e.setSingleLine(true);
        e.setText(val);
        if (secret) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            l.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        }
        l.addView(e);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(c, 8);
        l.setLayoutParams(p);
        return l;
    }

    static String text(TextInputLayout l) {
        return l.getEditText() == null ? "" : l.getEditText().getText().toString().trim();
    }

    static void form(Context c, String title, String[] hints, String[] vals, boolean[] secret, String ok, Strs cb) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 20), dp(c, 8), dp(c, 20), 0);
        final TextInputLayout[] f = new TextInputLayout[hints.length];
        for (int i = 0; i < f.length; i++) {
            f[i] = field(c, hints[i], vals == null ? "" : vals[i], secret != null && secret[i]);
            box.addView(f[i]);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(c);
        sv.addView(box);
        new MaterialAlertDialogBuilder(c).setTitle(title).setView(sv)
                .setPositiveButton(ok, (d, w) -> {
                    String[] r = new String[f.length];
                    for (int k = 0; k < r.length; k++) r[k] = text(f[k]);
                    cb.run(r);
                }).setNegativeButton("Cancel", null).show();
    }

    static void input(Context c, String title, String hint, String val, Str cb) {
        form(c, title, new String[]{hint}, new String[]{val}, null, "OK", v -> cb.run(v[0]));
    }

    static void confirm(Context c, String title, String msg, String yes, Runnable r) {
        new MaterialAlertDialogBuilder(c).setTitle(title).setMessage(msg)
                .setPositiveButton(yes, (d, w) -> r.run()).setNegativeButton("Cancel", null).show();
    }

    static void choose(Context c, String title, String[] items, int checked, Idx cb) {
        new MaterialAlertDialogBuilder(c).setTitle(title).setSingleChoiceItems(items, checked, (d, w) -> {
            d.dismiss();
            cb.run(w);
        }).show();
    }

    static void menu(Context c, String title, String[] items, Idx cb) {
        new MaterialAlertDialogBuilder(c).setTitle(title).setItems(items, (d, w) -> cb.run(w)).show();
    }

    static AlertDialog busy(Context c, String msg) {
        LinearLayout l = new LinearLayout(c);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(c, 24), dp(c, 20), dp(c, 24), dp(c, 20));
        CircularProgressIndicator p = new CircularProgressIndicator(c);
        p.setIndeterminate(true);
        TextView t = new TextView(c);
        t.setText(msg);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        t.setPadding(dp(c, 16), 0, 0, 0);
        l.addView(p);
        l.addView(t);
        return new MaterialAlertDialogBuilder(c).setView(l).setCancelable(false).show();
    }

    /** Draw behind the system bars (required on Android 15+). Call before super.onCreate. */
    static void edge(androidx.activity.ComponentActivity a) { androidx.activity.EdgeToEdge.enable(a); }

    /** Pads v by the system bars, display cutout and keyboard so content never sits underneath them. */
    static void insets(View v, boolean top, boolean bottom) {
        final int l = v.getPaddingLeft(), t = v.getPaddingTop(), r = v.getPaddingRight(), b = v.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, w) -> {
            Insets i = w.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            view.setPadding(l + i.left, t + (top ? i.top : 0), r + i.right, b + (bottom ? i.bottom : 0));
            return w;
        });
        ViewCompat.requestApplyInsets(v);
    }

    /** A stable pastel per project name, used for its monogram tile. */
    static int tint(String name) {
        float hue = (name.hashCode() & 0x7fffffff) % 360;
        return Color.HSVToColor(new float[]{hue, 0.45f, 0.88f});
    }

    static void share(Activity a, File f) {
        Uri u = FileProvider.getUriForFile(a, a.getPackageName() + ".fp", f);
        Intent s = new Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, u)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startActivity(Intent.createChooser(s, f.getName()));
    }
}
