package com.fahim.cloudide;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Layout;
import android.text.Spanned;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.ListPopupWindow;
import android.widget.TextView;
import androidx.appcompat.widget.AppCompatEditText;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Code editor: line numbers, syntax colors, bracket match, auto-indent, auto-close, autocomplete, pinch zoom. */
public class EditorView extends AppCompatEditText {
    interface Host {
        void onUserEdit();
        void onCursor(int line, int col);
        void onZoom(float sp);
    }

    static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}");

    Host host;
    EditorTheme th = EditorTheme.ALL[0];
    Lang lang = Lang.PLAIN;
    boolean silent, ready, pairs = true, bracketMatch = true, showLines = true, autoComplete = true, wrap = true;
    int tab = 4, gutter, br1 = -1, br2 = -1;
    final Paint pNum = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint pFill = new Paint();
    final Map<Integer, Integer> marks = new HashMap<>();
    final Handler h = new Handler(Looper.getMainLooper());
    final Set<String> docWords = new HashSet<>();
    final List<String> label = new ArrayList<>();
    final List<String> insert = new ArrayList<>();
    ListPopupWindow pop;
    ArrayAdapter<String> popAd;
    ScaleGestureDetector zoom;
    final Runnable hlRun = () -> rehighlight();
    final Runnable wordRun = () -> collectWords();
    final Runnable compRun = () -> showCompletions();
    final Runnable cursorRun = () -> reportCursor();

    // Offsets where each logical line starts. Rebuilt lazily after an edit, so drawing and cursor updates never rescan the text.
    private int[] lineStarts = new int[]{0};
    private int lineTotal = 1;
    private boolean linesDirty = true;
    private final char[] scan = new char[8192];

    public EditorView(Context c) { this(c, null); }

    public EditorView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        setTypeface(Typeface.MONOSPACE);
        setBackground(null);
        setGravity(Gravity.TOP | Gravity.START);
        setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        setLineSpacing(0f, 1.12f);
        setHorizontallyScrolling(false);
        setFilters(new InputFilter[]{this::filter});
        pNum.setTypeface(Typeface.MONOSPACE);
        pNum.setTextAlign(Paint.Align.RIGHT);
        zoom = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float px = getTextSize() * d.getScaleFactor();
                px = Math.max(spPx(8), Math.min(spPx(40), px));
                setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
                updateGutter();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                if (host != null) host.onZoom(getTextSize() / spPx(1));
            }
        });
        ready = true;
        updateGutter();
    }

    private float spPx(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics());
    }

    static boolean isWord(char c) { return Character.isLetterOrDigit(c) || c == '_'; }

    // ------------------------------------------------------------ line index
    private void ensureLines() {
        if (!linesDirty) return;
        Editable t = getText();
        int n = t.length(), count = 1;
        for (int from = 0; from < n; from += scan.length) {
            int to = Math.min(n, from + scan.length);
            t.getChars(from, to, scan, 0);
            for (int i = 0; i < to - from; i++) if (scan[i] == '\n') count++;
        }
        if (lineStarts.length < count) lineStarts = new int[Math.max(count, lineStarts.length * 2)];
        lineStarts[0] = 0;
        int k = 1;
        for (int from = 0; from < n; from += scan.length) {
            int to = Math.min(n, from + scan.length);
            t.getChars(from, to, scan, 0);
            for (int i = 0; i < to - from; i++) if (scan[i] == '\n') lineStarts[k++] = from + i + 1;
        }
        lineTotal = count;
        linesDirty = false;
    }

    /** 1-based number of the logical line (split by newline characters, not by wrapping) that holds offset. */
    int lineOf(int off) {
        ensureLines();
        int lo = 0, hi = lineTotal - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= off) lo = mid;
            else hi = mid - 1;
        }
        return lo + 1;
    }

    /** Offset where the 1-based logical line starts, or -1 when the file has fewer lines. */
    int offsetOfLine(int line) {
        ensureLines();
        return line < 1 || line > lineTotal ? -1 : lineStarts[line - 1];
    }

    // ------------------------------------------------------------ public API
    void applyTheme(EditorTheme t) {
        th = t;
        setTextColor(t.fg);
        setBackgroundColor(t.bg);
        setHighlightColor(t.sel);
        invalidate();
        rehighlight();
    }

    void setWrap(boolean w) {
        wrap = w;
        setHorizontallyScrolling(!w);
        requestLayout();
    }

    void setMarks(Map<Integer, Integer> m) {
        marks.clear();
        marks.putAll(m);
        invalidate();
    }

    void load(String text, Lang l) {
        silent = true;
        lang = l;
        setText(text);
        silent = false;
        marks.clear();
        setSelection(0);
        dismissPop();
        rehighlight();
        collectWords();
        updateGutter();
    }

    void replaceAll(String text, int caret) {
        silent = true;
        setText(text);
        silent = false;
        setSelection(Math.max(0, Math.min(caret, text.length())));
        rehighlight();
        collectWords();
    }

    void replaceRange(int a, int b, String r) {
        edit(a, b, r);
        setSelection(a + r.length());
        afterCmd();
    }

    void goToLine(int line) {
        int off = offsetOfLine(Math.max(1, line));
        if (off < 0) off = getText().length(); // past the end: go to the last line
        requestFocus();
        setSelection(Math.min(off, getText().length()));
    }

    // ------------------------------------------------------------ highlighting
    void scheduleHighlight() {
        h.removeCallbacks(hlRun);
        h.postDelayed(hlRun, getText().length() > 60000 ? 350 : 120);
    }

    void rehighlight() {
        if (!ready || lang.pat == null) return;
        Editable e = getText();
        int n = e.length(), from = 0, to = n;
        Layout l = getLayout();
        if (n > 150000 && l != null) {
            int a = l.getLineForVertical(Math.max(0, getScrollY() - 3000));
            int b = l.getLineForVertical(getScrollY() + getHeight() + 3000);
            from = l.getLineStart(a);
            to = l.getLineEnd(Math.min(b, l.getLineCount() - 1));
        }
        Highlighter.apply(e, lang, th, from, to);
    }

    void collectWords() {
        docWords.clear();
        Matcher m = WORD.matcher(getText());
        int c = 0;
        while (m.find() && c++ < 8000) docWords.add(m.group());
    }

    @Override
    protected void onScrollChanged(int l, int t, int ol, int ot) {
        super.onScrollChanged(l, t, ol, ot);
        if (ready && getText().length() > 150000) scheduleHighlight();
    }

    // ------------------------------------------------------------ text events
    @Override
    protected void onTextChanged(CharSequence t, int st, int before, int after) {
        super.onTextChanged(t, st, before, after);
        linesDirty = true;
        if (!ready) return;
        updateGutter();
        if (silent) return;
        scheduleHighlight();
        h.removeCallbacks(wordRun);
        h.postDelayed(wordRun, 700);
        if (host != null) host.onUserEdit();
        if (autoComplete && after == 1 && before == 0 && st < t.length() && isWord(t.charAt(st))) {
            h.removeCallbacks(compRun);
            h.postDelayed(compRun, 90);
        } else dismissPop();
    }

    @Override
    protected void onSelectionChanged(int a, int b) {
        super.onSelectionChanged(a, b);
        if (!ready) return;
        br1 = br2 = -1;
        if (bracketMatch && a == b) findBracket(a);
        if (host != null) {
            h.removeCallbacks(cursorRun);
            h.post(cursorRun); // several selection changes in one frame report once
        }
        invalidate();
    }

    private void reportCursor() {
        if (host == null) return;
        int pos = Math.max(0, Math.min(getSelectionStart(), getText().length()));
        int line = lineOf(pos);
        host.onCursor(line, pos - lineStarts[line - 1] + 1);
    }

    @Override
    protected void onDetachedFromWindow() {
        h.removeCallbacksAndMessages(null);
        dismissPop();
        super.onDetachedFromWindow();
    }

    private void findBracket(int pos) {
        Editable t = getText();
        int n = t.length();
        int[] cand = {pos - 1, pos};
        for (int i : cand) {
            if (i < 0 || i >= n) continue;
            char c = t.charAt(i);
            int k = "([{)]}".indexOf(c);
            if (k < 0) continue;
            boolean fwd = k < 3;
            char m = "([{)]}".charAt(fwd ? k + 3 : k - 3);
            int depth = 0;
            for (int j = i, cnt = 0; j >= 0 && j < n && cnt < 30000; j += fwd ? 1 : -1, cnt++) {
                char d = t.charAt(j);
                if (d == c) depth++;
                else if (d == m) {
                    depth--;
                    if (depth == 0) { br1 = i; br2 = j; return; }
                }
            }
            return;
        }
    }

    // ------------------------------------------------------------ smart typing
    private CharSequence filter(CharSequence src, int s, int e, Spanned dest, int ds, int de) {
        if (!ready || silent || e - s != 1 || ds != de) return null;
        char ch = src.charAt(s);
        int len = dest.length();
        char next = ds < len ? dest.charAt(ds) : 0;
        char prev = ds > 0 ? dest.charAt(ds - 1) : 0;
        if (ch == '\n') {
            int ls = ds;
            while (ls > 0 && dest.charAt(ls - 1) != '\n') ls--;
            int i = ls;
            while (i < ds && (dest.charAt(i) == ' ' || dest.charAt(i) == '\t')) i++;
            String ind = dest.subSequence(ls, i).toString();
            char last = 0;
            for (int k = ds - 1; k >= ls; k--) {
                char x = dest.charAt(k);
                if (x != ' ' && x != '\t') { last = x; break; }
            }
            String unit = Fmt.spaces(tab);
            boolean open = (lang.brace && (last == '{' || last == '(' || last == '['))
                    || (lang.id.equals("python") && last == ':');
            if (open && (next == '}' || next == ')' || next == ']')) {
                final int pos = ds + 1 + ind.length() + unit.length();
                post(() -> setSelection(Math.min(pos, length())));
                return "\n" + ind + unit + "\n" + ind;
            }
            if (open) return "\n" + ind + unit;
            return ind.isEmpty() ? null : "\n" + ind;
        }
        if (!pairs || lang == Lang.PLAIN) return null;
        int oi = "([{".indexOf(ch);
        if (")]}".indexOf(ch) >= 0 && next == ch) {
            post(() -> setSelection(Math.min(ds + 1, length())));
            return "";
        }
        if (oi >= 0 && !isWord(next)) {
            post(() -> setSelection(Math.min(ds + 1, length())));
            return "" + ch + ")]}".charAt(oi);
        }
        if (ch == '"' || ch == '\'') {
            if (next == ch) {
                post(() -> setSelection(Math.min(ds + 1, length())));
                return "";
            }
            if (!isWord(prev) && !isWord(next) && prev != ch) {
                post(() -> setSelection(Math.min(ds + 1, length())));
                return "" + ch + ch;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ gutter + drawing
    void updateGutter() {
        if (!ready) return;
        Context c = getContext();
        pNum.setTextSize(getTextSize() * 0.82f);
        int g = 0;
        if (showLines) {
            int digits = Math.max(3, String.valueOf(Math.max(1, getLineCount())).length());
            g = (int) (pNum.measureText("0") * digits) + Ui.dp(c, 16);
        }
        gutter = g;
        int pl = g + Ui.dp(c, 8);
        // The bottom padding must stay small: a TextView clips its text at the bottom padding unless scrolled to the very end,
        // so the old 90dp hid the lower part of the screen (the line numbers still drew, the code did not).
        if (getPaddingLeft() != pl) setPadding(pl, Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 12));
    }

    @Override
    protected void onDraw(Canvas cv) {
        Layout l = getLayout();
        if (l != null) {
            int top = getScrollY(), bottom = top + getHeight(), sx = getScrollX(), w = getWidth();
            int pt = getExtendedPaddingTop(), pl = getCompoundPaddingLeft();
            int sel = getSelectionStart();
            if (sel >= 0 && sel == getSelectionEnd()) {
                int ln = l.getLineForOffset(sel);
                pFill.setColor(th.line);
                cv.drawRect(sx, l.getLineTop(ln) + pt, sx + w, l.getLineBottom(ln) + pt, pFill);
            }
            for (Map.Entry<Integer, Integer> mk : marks.entrySet()) {
                int off = offsetOfLine(mk.getKey());
                if (off < 0) continue;
                int vl = l.getLineForOffset(Math.min(off, getText().length()));
                if (l.getLineBottom(vl) + pt < top || l.getLineTop(vl) + pt > bottom) continue; // off screen
                pFill.setColor(mk.getValue() == 1 ? 0x33FF5252 : 0x33FFC107);
                cv.drawRect(sx, l.getLineTop(vl) + pt, sx + w, l.getLineBottom(vl) + pt, pFill);
            }
            if (br1 >= 0 && br2 >= 0) {
                pFill.setColor(th.bracket);
                for (int idx : new int[]{br1, br2}) {
                    if (idx >= getText().length()) continue;
                    int ln = l.getLineForOffset(idx);
                    float x1 = l.getPrimaryHorizontal(idx) + pl, x2 = l.getPrimaryHorizontal(idx + 1) + pl;
                    if (x2 <= x1) x2 = x1 + getTextSize() * 0.6f;
                    cv.drawRect(x1, l.getLineTop(ln) + pt, x2, l.getLineBottom(ln) + pt, pFill);
                }
            }
        }
        super.onDraw(cv);
        if (l != null && showLines) {
            int top = getScrollY(), bottom = top + getHeight(), sx = getScrollX(), pt = getExtendedPaddingTop();
            pFill.setColor(th.gutterBg);
            cv.drawRect(sx, top, sx + gutter, bottom, pFill);
            pNum.setTextSize(getTextSize() * 0.82f);
            CharSequence t = getText();
            int first = l.getLineForVertical(Math.max(0, top - pt));
            int last = l.getLineForVertical(Math.max(0, bottom - pt));
            int num = lineOf(l.getLineStart(first));
            int curLine = l.getLineForOffset(Math.max(0, getSelectionStart()));
            float x = sx + gutter - Ui.dp(getContext(), 6);
            for (int i = first; i <= last && i < l.getLineCount(); i++) {
                int s = l.getLineStart(i);
                boolean isFirst = s == 0 || t.charAt(s - 1) == '\n';
                if (i > first && isFirst) num++;
                if (!isFirst) continue;
                int y = l.getLineBaseline(i) + pt;
                pNum.setColor(i == curLine ? th.fg : th.gutterFg);
                cv.drawText(String.valueOf(num), x, y, pNum);
                Integer sev = marks.get(num);
                if (sev != null) {
                    pFill.setColor(sev == 1 ? 0xFFFF5252 : 0xFFFFC107);
                    cv.drawCircle(sx + Ui.dp(getContext(), 5), y - pNum.getTextSize() / 3, Ui.dp(getContext(), 3), pFill);
                }
            }
        }
    }

    // ------------------------------------------------------------ touch + keys
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (zoom != null && ev.getPointerCount() > 1) {
            zoom.onTouchEvent(ev);
            return true;
        }
        return super.onTouchEvent(ev);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent ev) {
        if (keyCode == KeyEvent.KEYCODE_TAB && !ev.isCtrlPressed()) {
            tabKey();
            return true;
        }
        return super.onKeyDown(keyCode, ev);
    }

    // ------------------------------------------------------------ editing commands
    private int lineStart(int off) {
        Editable e = getText();
        while (off > 0 && e.charAt(off - 1) != '\n') off--;
        return off;
    }

    private int lineEnd(int off) {
        Editable e = getText();
        while (off < e.length() && e.charAt(off) != '\n') off++;
        return off;
    }

    private void edit(int s, int e, String r) {
        silent = true;
        getText().replace(s, e, r);
        silent = false;
    }

    private void afterCmd() {
        scheduleHighlight();
        if (host != null) host.onUserEdit();
    }

    void insertText(String s) {
        int a = Math.max(0, Math.min(getSelectionStart(), getSelectionEnd()));
        int b = Math.max(0, Math.max(getSelectionStart(), getSelectionEnd()));
        edit(a, b, s);
        setSelection(a + s.length());
        afterCmd();
    }

    void wrapSel(String o, String c) {
        int a = Math.max(0, Math.min(getSelectionStart(), getSelectionEnd()));
        int b = Math.max(0, Math.max(getSelectionStart(), getSelectionEnd()));
        String sel = getText().subSequence(a, b).toString();
        edit(a, b, o + sel + c);
        if (a == b) setSelection(a + o.length());
        else setSelection(a + o.length(), a + o.length() + sel.length());
        afterCmd();
    }

    void tabKey() {
        int a = getSelectionStart(), b = getSelectionEnd();
        if (a != b && getText().subSequence(Math.min(a, b), Math.max(a, b)).toString().indexOf('\n') >= 0) indent(false);
        else insertText(Fmt.spaces(tab));
    }

    void indent(boolean out) {
        int a = getSelectionStart(), b = getSelectionEnd();
        int ls = lineStart(Math.min(a, b)), le = lineEnd(Math.max(a, b));
        String[] ln = getText().subSequence(ls, le).toString().split("\n", -1);
        StringBuilder sb = new StringBuilder();
        String unit = Fmt.spaces(tab);
        for (int i = 0; i < ln.length; i++) {
            String s = ln[i];
            if (out) {
                int k = 0;
                while (k < s.length() && k < tab && s.charAt(k) == ' ') k++;
                if (k == 0 && s.startsWith("\t")) k = 1;
                s = s.substring(k);
            } else if (!s.isEmpty()) s = unit + s;
            sb.append(s);
            if (i < ln.length - 1) sb.append('\n');
        }
        String r = sb.toString();
        edit(ls, le, r);
        setSelection(ls, ls + r.length());
        afterCmd();
    }

    void toggleComment() {
        int a = getSelectionStart(), b = getSelectionEnd();
        int ls = lineStart(Math.min(a, b)), le = lineEnd(Math.max(a, b));
        String lc = lang.lineComment;
        String blk = getText().subSequence(ls, le).toString();
        if (lc == null) {
            String o = lang.blockOpen, c = lang.blockClose;
            if (o == null) return;
            String t = blk.trim(), r;
            if (t.startsWith(o) && t.endsWith(c))
                r = blk.replace(o + " ", "").replace(o, "").replace(" " + c, "").replace(c, "");
            else r = o + " " + blk + " " + c;
            edit(ls, le, r);
            setSelection(ls, ls + r.length());
            afterCmd();
            return;
        }
        String[] lines = blk.split("\n", -1);
        boolean all = true;
        for (String s : lines) if (!s.trim().isEmpty() && !s.trim().startsWith(lc)) all = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i];
            if (s.trim().isEmpty()) sb.append(s);
            else if (all) {
                int k = s.indexOf(lc);
                sb.append(s, 0, k);
                int after = k + lc.length();
                if (after < s.length() && s.charAt(after) == ' ') after++;
                sb.append(s.substring(after));
            } else {
                int ind = 0;
                while (ind < s.length() && (s.charAt(ind) == ' ' || s.charAt(ind) == '\t')) ind++;
                sb.append(s, 0, ind).append(lc).append(' ').append(s.substring(ind));
            }
            if (i < lines.length - 1) sb.append('\n');
        }
        String r = sb.toString();
        edit(ls, le, r);
        setSelection(ls, ls + r.length());
        afterCmd();
    }

    void duplicateLine() {
        int a = getSelectionStart(), b = getSelectionEnd();
        int ls = lineStart(Math.min(a, b)), le = lineEnd(Math.max(a, b));
        String txt = getText().subSequence(ls, le).toString();
        edit(le, le, "\n" + txt);
        int d = txt.length() + 1;
        setSelection(Math.min(a, b) + d, Math.max(a, b) + d);
        afterCmd();
    }

    void deleteLine() {
        int ls = lineStart(Math.min(getSelectionStart(), getSelectionEnd()));
        int le = lineEnd(Math.max(getSelectionStart(), getSelectionEnd()));
        int end = Math.min(le + 1, getText().length());
        if (le >= getText().length() && ls > 0) ls--;
        edit(ls, end, "");
        setSelection(Math.min(ls, getText().length()));
        afterCmd();
    }

    void moveLine(int dir) {
        Editable e = getText();
        int a = getSelectionStart(), b = getSelectionEnd();
        int ls = lineStart(Math.min(a, b)), le = lineEnd(Math.max(a, b));
        if (dir < 0) {
            if (ls == 0) return;
            int ps = lineStart(ls - 1);
            String cur = e.subSequence(ls, le).toString(), prev = e.subSequence(ps, ls - 1).toString();
            edit(ps, le, cur + "\n" + prev);
            setSelection(a - (ls - ps), b - (ls - ps));
        } else {
            if (le >= e.length()) return;
            int ne = lineEnd(le + 1);
            String cur = e.subSequence(ls, le).toString(), next = e.subSequence(le + 1, ne).toString();
            edit(ls, ne, next + "\n" + cur);
            int d = next.length() + 1;
            setSelection(a + d, b + d);
        }
        afterCmd();
    }

    void moveCursor(int dx, int dy) {
        int pos = getSelectionStart();
        Layout l = getLayout();
        if (dy != 0 && l != null) {
            int line = l.getLineForOffset(pos) + dy;
            if (line < 0 || line >= l.getLineCount()) return;
            setSelection(l.getOffsetForHorizontal(line, l.getPrimaryHorizontal(pos)));
        } else setSelection(Math.max(0, Math.min(getText().length(), pos + dx)));
    }

    // ------------------------------------------------------------ autocomplete
    void dismissPop() {
        if (pop != null && pop.isShowing()) pop.dismiss();
    }

    private void showCompletions() {
        if (!ready || !autoComplete) return;
        int cur = getSelectionStart();
        Layout l = getLayout();
        if (cur != getSelectionEnd() || cur <= 0 || l == null) { dismissPop(); return; }
        Editable e = getText();
        int st = cur;
        while (st > 0 && isWord(e.charAt(st - 1))) st--;
        if (cur - st < 2) { dismissPop(); return; }
        String pre = e.subSequence(st, cur).toString(), low = pre.toLowerCase(Locale.ROOT);
        label.clear();
        insert.clear();
        for (String[] sn : Snippets.get(lang.id)) {
            if (sn[0].startsWith(pre) && !sn[0].equals(pre)) {
                label.add("\u26A1 " + sn[0]);
                insert.add(sn[1]);
            }
        }
        TreeSet<String> ws = new TreeSet<>();
        for (String w : lang.words) if (w.length() > pre.length() && w.toLowerCase(Locale.ROOT).startsWith(low)) ws.add(w);
        for (String w : docWords) if (w.length() > pre.length() && w.toLowerCase(Locale.ROOT).startsWith(low)) ws.add(w);
        for (String w : ws) {
            if (label.size() >= 8) break;
            label.add(w);
            insert.add(w);
        }
        if (label.isEmpty()) { dismissPop(); return; }
        if (pop == null) {
            pop = new ListPopupWindow(getContext());
            pop.setAnchorView(this);
            pop.setModal(false);
            pop.setInputMethodMode(ListPopupWindow.INPUT_METHOD_NEEDED);
            pop.setWidth(Ui.dp(getContext(), 240));
            pop.setHeight(ListPopupWindow.WRAP_CONTENT);
            popAd = new ArrayAdapter<String>(getContext(), 0, label) {
                @Override
                public View getView(int i, View v, ViewGroup p) {
                    TextView t = v instanceof TextView ? (TextView) v : new TextView(getContext());
                    t.setText(getItem(i));
                    t.setTypeface(Typeface.MONOSPACE);
                    t.setTextSize(14);
                    t.setTextColor(th.fg);
                    t.setBackgroundColor(th.gutterBg);
                    t.setPadding(Ui.dp(getContext(), 12), Ui.dp(getContext(), 9), Ui.dp(getContext(), 12), Ui.dp(getContext(), 9));
                    return t;
                }
            };
            pop.setAdapter(popAd);
            pop.setOnItemClickListener((p, v, i, id) -> accept(i));
        }
        popAd.notifyDataSetChanged();
        int line = l.getLineForOffset(cur);
        int x = (int) l.getPrimaryHorizontal(cur) + getCompoundPaddingLeft() - getScrollX();
        int y = l.getLineBottom(line) + getExtendedPaddingTop() - getScrollY();
        pop.setHorizontalOffset(Math.max(0, Math.min(x, getWidth() - Ui.dp(getContext(), 240))));
        pop.setVerticalOffset(y - getHeight());
        pop.show();
    }

    private void accept(int i) {
        if (i < 0 || i >= insert.size()) return;
        String ins = insert.get(i);
        Editable e = getText();
        int cur = getSelectionStart(), st = cur;
        while (st > 0 && isWord(e.charAt(st - 1))) st--;
        int caret;
        silent = true;
        if (ins.contains("$0")) {
            int ls = lineStart(st), k = ls;
            while (k < st && (e.charAt(k) == ' ' || e.charAt(k) == '\t')) k++;
            String lineInd = e.subSequence(ls, k).toString();
            String body = ins.replace("\n", "\n" + lineInd);
            int p = body.indexOf("$0");
            body = body.replace("$0", "");
            e.replace(st, cur, body);
            caret = st + p;
        } else {
            e.replace(st, cur, ins);
            caret = st + ins.length();
        }
        silent = false;
        setSelection(Math.min(caret, e.length()));
        dismissPop();
        afterCmd();
    }
}
