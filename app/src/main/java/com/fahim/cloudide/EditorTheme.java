package com.fahim.cloudide;

import android.content.Context;
import android.content.res.Configuration;

final class EditorTheme {
    final String name;
    final int bg, fg, gutterBg, gutterFg, line, bracket, sel;
    final int[] tok; // comment, string, keyword, type, number, meta, function, tag, attr

    EditorTheme(String name, int bg, int fg, int gutterBg, int gutterFg, int line, int bracket, int sel, int[] tok) {
        this.name = name;
        this.bg = bg;
        this.fg = fg;
        this.gutterBg = gutterBg;
        this.gutterFg = gutterFg;
        this.line = line;
        this.bracket = bracket;
        this.sel = sel;
        this.tok = tok;
    }

    static final EditorTheme[] ALL = {
            new EditorTheme("Dark+", 0xFF1E1E1E, 0xFFD4D4D4, 0xFF252526, 0xFF858585, 0xFF2A2D2E, 0xFF3A3D41, 0x553B6FD4,
                    new int[]{0xFF6A9955, 0xFFCE9178, 0xFF569CD6, 0xFF4EC9B0, 0xFFB5CEA8, 0xFFC586C0, 0xFFDCDCAA, 0xFF569CD6, 0xFF9CDCFE}),
            new EditorTheme("Monokai", 0xFF272822, 0xFFF8F8F2, 0xFF2D2E27, 0xFF8F908A, 0xFF3E3D32, 0xFF55564A, 0x55FFFFFF,
                    new int[]{0xFF75715E, 0xFFE6DB74, 0xFFF92672, 0xFF66D9EF, 0xFFAE81FF, 0xFFFD971F, 0xFFA6E22E, 0xFFF92672, 0xFFA6E22E}),
            new EditorTheme("Dracula", 0xFF282A36, 0xFFF8F8F2, 0xFF21222C, 0xFF6272A4, 0xFF44475A, 0xFF5A5D75, 0x55BD93F9,
                    new int[]{0xFF6272A4, 0xFFF1FA8C, 0xFFFF79C6, 0xFF8BE9FD, 0xFFBD93F9, 0xFFFFB86C, 0xFF50FA7B, 0xFFFF79C6, 0xFF50FA7B}),
            new EditorTheme("GitHub Light", 0xFFFFFFFF, 0xFF24292E, 0xFFF6F8FA, 0xFF959DA5, 0xFFF1F8FF, 0xFFD0E3FF, 0x553B6FD4,
                    new int[]{0xFF6A737D, 0xFF032F62, 0xFFD73A49, 0xFF6F42C1, 0xFF005CC5, 0xFFE36209, 0xFF6F42C1, 0xFF22863A, 0xFF6F42C1}),
            new EditorTheme("Solarized Dark", 0xFF002B36, 0xFF93A1A1, 0xFF073642, 0xFF586E75, 0xFF073642, 0xFF0F4B5C, 0x55268BD2,
                    new int[]{0xFF586E75, 0xFF2AA198, 0xFF859900, 0xFFB58900, 0xFFD33682, 0xFFCB4B16, 0xFF268BD2, 0xFF268BD2, 0xFF93A1A1}),
            new EditorTheme("Lagoon Dark", 0xFF0E1417, 0xFFD6E2E4, 0xFF121A1D, 0xFF5F7A80, 0xFF162226, 0xFF1F3A3F, 0x555ED9C5,
                    new int[]{0xFF6F8A91, 0xFFF2C879, 0xFF7DB8FF, 0xFF5ED9C5, 0xFFF79A8E, 0xFFC3A6FF, 0xFFA8E6A1, 0xFF7DB8FF, 0xFF8FE3D6}),
            new EditorTheme("Lagoon Light", 0xFFFAFCFC, 0xFF1B2A2E, 0xFFEEF4F5, 0xFF7E9297, 0xFFE9F2F3, 0xFFCFE6E3, 0x55006B5E,
                    new int[]{0xFF66797E, 0xFF8A5200, 0xFF0B5FA8, 0xFF006B5E, 0xFFB3261E, 0xFF6B4EB8, 0xFF2F6F18, 0xFF0B5FA8, 0xFF0F766E})
    };

    static EditorTheme get(Context c) {
        int i = Prefs.sp(c).getInt("etheme", 0);
        if (i > 0 && i <= ALL.length) return ALL[i - 1];
        boolean night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return night ? ALL[5] : ALL[6]; // Lagoon Dark / Lagoon Light
    }
}
