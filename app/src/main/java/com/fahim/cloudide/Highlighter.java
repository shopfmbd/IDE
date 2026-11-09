package com.fahim.cloudide;

import android.text.Editable;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import java.util.regex.Matcher;

final class Highlighter {
    static final class Span extends ForegroundColorSpan {
        Span(int c) { super(c); }
    }

    static void apply(Editable e, Lang lang, EditorTheme th, int from, int to) {
        from = Math.max(0, from);
        to = Math.min(e.length(), to);
        for (Span s : e.getSpans(from, to, Span.class)) e.removeSpan(s);
        if (lang.pat == null || to <= from) return;
        Matcher m = lang.pat.matcher(e);
        m.region(from, to);
        m.useTransparentBounds(true);
        m.useAnchoringBounds(false);
        int n = lang.kinds.length;
        while (m.find()) {
            for (int g = 1; g <= n; g++) {
                if (m.start(g) >= 0) {
                    if (m.end(g) > m.start(g))
                        e.setSpan(new Span(th.tok[lang.kinds[g - 1]]), m.start(g), m.end(g), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                }
            }
        }
    }
}
