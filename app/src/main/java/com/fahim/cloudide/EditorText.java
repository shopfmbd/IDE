package com.fahim.cloudide;

import java.util.Arrays;
import java.util.Locale;

/**
 * Text rules for the editor that need no Android classes, so they are unit tested on a plain JDK
 * (toolchain/test/com/fahim/cloudide/EditorTextTest.java): closing a tag when ">" is typed, finding other uses of a word,
 * and measuring indentation for the indent guides.
 */
final class EditorText {
    private EditorText() { }

    /** HTML elements that never have a closing tag. Only applied to lower-case names, so an Android layout's own tags are never skipped. */
    private static final String VOID = ",area,base,br,col,embed,hr,img,input,link,meta,param,source,track,wbr,";

    private static boolean nameChar(char c, boolean first) {
        if (Character.isLetter(c) || c == '_' || c == ':') return true;
        return !first && (Character.isDigit(c) || c == '.' || c == '-');
    }

    static boolean isWordChar(char c) { return Character.isLetterOrDigit(c) || c == '_'; }

    /**
     * The closing tag to add when the person types ">" at {@code caret} (the ">" is not in the text yet), such as "&lt;/div&gt;" after
     * "&lt;div class=\"a\"", or null when no closing tag belongs there: a closing tag, comment, declaration, self-closing tag, HTML void
     * element, or a ">" inside a quoted attribute value.
     */
    static String closingTag(CharSequence t, int caret) {
        if (caret <= 0 || caret > t.length()) return null;
        int lo = Math.max(0, caret - 2000), lt = -1;
        for (int i = caret - 1; i >= lo; i--) {
            char c = t.charAt(i);
            if (c == '>') return null; // the last tag was already closed, so this ">" is not ending a tag
            if (c == '<') {
                lt = i;
                break;
            }
        }
        if (lt < 0) return null;
        int a = lt + 1;
        if (a >= caret) return null; // "<>"
        char first = t.charAt(a);
        if (first == '/' || first == '!' || first == '?') return null;
        if (t.charAt(caret - 1) == '/') return null; // <br/ ...
        int e = a;
        while (e < caret && nameChar(t.charAt(e), e == a)) e++;
        if (e == a) return null;
        char quote = 0;
        for (int i = e; i < caret; i++) {
            char c = t.charAt(i);
            if (quote == 0) {
                if (c == '"' || c == '\'') quote = c;
            } else if (c == quote) {
                quote = 0;
            }
        }
        if (quote != 0) return null; // inside an attribute value
        String name = t.subSequence(a, e).toString();
        if (name.equals(name.toLowerCase(Locale.ROOT)) && VOID.contains("," + name + ",")) return null;
        return "</" + name + ">";
    }

    /**
     * Start offsets of {@code needle} in {@code text}, at most {@code max}, not overlapping. With {@code whole} a match must not touch
     * a letter, digit or underscore, so "id" does not match inside "width".
     */
    static int[] occurrences(String text, String needle, boolean whole, int max) {
        if (needle.isEmpty() || max <= 0) return new int[0];
        int[] out = new int[16];
        int n = 0, from = 0;
        while (n < max) {
            int i = text.indexOf(needle, from);
            if (i < 0) break;
            int end = i + needle.length();
            from = end;
            if (whole && ((i > 0 && isWordChar(text.charAt(i - 1))) || (end < text.length() && isWordChar(text.charAt(end))))) continue;
            if (n == out.length) out = Arrays.copyOf(out, n * 2);
            out[n++] = i;
        }
        return Arrays.copyOf(out, n);
    }

    /** True when the selection is worth highlighting elsewhere: one run of 2 to 80 characters with no line break or space. */
    static boolean highlightable(CharSequence s) {
        int n = s.length();
        if (n < 2 || n > 80) return false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') return false;
        }
        return true;
    }

    /** True when every character is part of an identifier. */
    static boolean isIdentifier(CharSequence s) {
        if (s.length() == 0) return false;
        for (int i = 0; i < s.length(); i++) if (!isWordChar(s.charAt(i))) return false;
        return true;
    }

    /**
     * Width in columns of the leading whitespace of the line that starts at {@code lineStart} (a tab advances to the next multiple of
     * {@code tab}), or -1 when the line is blank.
     */
    static int indentColumns(CharSequence t, int lineStart, int tab) {
        int col = 0, len = t.length();
        for (int i = lineStart; i < len; i++) {
            char c = t.charAt(i);
            if (c == ' ') col++;
            else if (c == '\t') col += tab - (col % tab);
            else return c == '\n' || c == '\r' ? -1 : col;
        }
        return -1;
    }
}
