package com.fahim.cloudide;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.TextView;

/**
 * The monospace font for code. JetBrains Mono (SIL Open Font License 1.1, licence text in assets/fonts/OFL.txt) ships with
 * the app and is the default; "System monospace" is the fallback, and is also used if the font file cannot be loaded.
 */
final class Fonts {
    private Fonts() { }

    static final String[] NAMES = {"JetBrains Mono", "System monospace"};

    private static Typeface jetbrains;

    static int choice(Context c) { return Prefs.sp(c).getInt("code_font", 0); }

    static Typeface mono(Context c) {
        if (choice(c) == 1) return Typeface.MONOSPACE;
        Typeface t = jetbrains;
        if (t == null) {
            try {
                t = Typeface.createFromAsset(c.getApplicationContext().getAssets(), "fonts/JetBrainsMono-Regular.ttf");
            } catch (RuntimeException e) {
                AppLog.w("Fonts", "JetBrains Mono could not be loaded, using the system monospace font", e);
                t = Typeface.MONOSPACE;
            }
            jetbrains = t;
        }
        return t;
    }

    /** Ligatures such as -> or != drawn as one symbol. Off by default: what is drawn should match what was typed. */
    static boolean ligatures(Context c) { return Prefs.bool(c, "ligatures", false); }

    static void apply(TextView v, Context c) {
        v.setTypeface(mono(c));
        v.setFontFeatureSettings(ligatures(c) ? "'liga' 1, 'calt' 1" : "'liga' 0, 'calt' 0");
    }
}
