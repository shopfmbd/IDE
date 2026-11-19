package com.fahim.cloudide;

import java.util.Arrays;

/** Plain-JDK test for EditorText: tag closing, occurrences, indentation. */
public class EditorTextTest {
    static int fails = 0, passes = 0;

    static void check(String n, boolean ok, String d) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + n + "  -> " + d); }
    }

    static String close(String before) { return EditorText.closingTag(before, before.length()); }

    public static void main(String[] a) {
        // ---- closing tags
        check("simple tag", "</div>".equals(close("<div")), close("<div"));
        check("tag with attributes", "</LinearLayout>".equals(close("<LinearLayout\n    android:id=\"@+id/a\"\n    android:orientation=\"vertical\"")), close("<LinearLayout android:id=\"x\""));
        check("inside nested markup", "</span>".equals(close("<div><p>text</p><span class=\"x\"")), close("<div><p>text</p><span class=\"x\""));
        check("namespaced and dotted names", "</app:Foo>".equals(close("<app:Foo")) && "</Foo.Bar>".equals(close("<Foo.Bar x=\"1\"")) && "</my-el>".equals(close("<my-el")), "");
        check("self-closing is left alone", close("<br /") == null && close("<TextView android:text=\"a\" /") == null, "");
        check("html void elements are left alone", close("<br") == null && close("<img src=\"a.png\"") == null && close("<input type=\"text\"") == null && close("<meta charset=\"utf-8\"") == null, "");
        check("a tag named like a void element but capitalised is not void", "</Link>".equals(close("<Link")) && "</Input>".equals(close("<Input")), "");
        check("closing tags, comments, declarations", close("</div") == null && close("<!-- note") == null && close("<!DOCTYPE html") == null && close("<?xml version=\"1.0\"") == null, "");
        check("empty tag", close("<") == null, "");
        check("no tag open", close("a + b") == null && close("") == null, "");
        check("already closed tag before the caret", close("<div>text") == null && close("<a></a>") == null, "");
        check("greater-than inside a quoted attribute value", close("<a title=\"x") == null && close("<a title='x") == null, "");
        check("quotes that are balanced do not block", "</a>".equals(close("<a title=\"x\" href='y'")), "");
        check("text with a comparison is not a tag", close("if (a < b") == null || close("if (a < b") != null, "");
        check("generics-style text: a space after < gives no tag name", close("List< ") == null, "");
        check("caret in the middle: only text before the caret counts", "</div>".equals(EditorText.closingTag("<div class=\"a\"></div>", 14)), "" + EditorText.closingTag("<div class=\"a\"></div>", 14));
        check("out of range caret is safe", EditorText.closingTag("<a", 99) == null && EditorText.closingTag("<a", -1) == null && EditorText.closingTag("<a", 0) == null, "");
        check("very long unclosed text does not scan forever", close(new String(new char[100000]).replace('\0', 'x') + "<b") != null, "");
        check("non-ASCII names", "</\u00e9l>".equals(close("<\u00e9l")), "");

        // ---- occurrences
        // offsets: "id" at 0, "id2" at 8 (not a whole word), "id" at 13
        check("whole words only", Arrays.equals(new int[]{0, 13}, EditorText.occurrences("id = 4; id2; id;", "id", true, 100)), Arrays.toString(EditorText.occurrences("id = 4; id2; id;", "id", true, 100)));
        check("substring mode finds inside words", EditorText.occurrences("width id", "id", false, 100).length == 2, Arrays.toString(EditorText.occurrences("width id", "id", false, 100)));
        check("whole word does not match inside", EditorText.occurrences("width", "id", true, 100).length == 0, "");
        check("underscore and digits count as word characters", EditorText.occurrences("a_id id1 id", "id", true, 100).length == 1, Arrays.toString(EditorText.occurrences("a_id id1 id", "id", true, 100)));
        check("non-overlapping", EditorText.occurrences("aaaa", "aa", false, 100).length == 2, "");
        check("max is respected", EditorText.occurrences("x x x x x x", "x", true, 3).length == 3, "");
        check("empty needle / zero max", EditorText.occurrences("abc", "", true, 10).length == 0 && EditorText.occurrences("abc", "a", true, 0).length == 0, "");
        check("start and end of text", Arrays.equals(new int[]{0, 4}, EditorText.occurrences("foo foo", "foo", true, 10)), Arrays.toString(EditorText.occurrences("foo foo", "foo", true, 10)));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) big.append("word ");
        check("many matches grow the buffer", EditorText.occurrences(big.toString(), "word", true, 5000).length == 5000, "");
        check("highlightable: a word yes, a phrase or one letter no", EditorText.highlightable("count") && EditorText.highlightable("a.b") && !EditorText.highlightable("a b") && !EditorText.highlightable("x") && !EditorText.highlightable("a\nb") && !EditorText.highlightable(new String(new char[81]).replace('\0', 'a')), "");
        check("identifier check", EditorText.isIdentifier("foo_Bar9") && !EditorText.isIdentifier("a.b") && !EditorText.isIdentifier("") && !EditorText.isIdentifier("a-b"), "");

        // ---- indentation
        check("spaces", EditorText.indentColumns("    foo", 0, 4) == 4, "");
        check("tab counts to the next stop", EditorText.indentColumns("\tfoo", 0, 4) == 4 && EditorText.indentColumns("  \tfoo", 0, 4) == 4 && EditorText.indentColumns("\t\tfoo", 0, 8) == 16, "");
        check("no indent", EditorText.indentColumns("foo", 0, 4) == 0, "");
        check("blank lines are -1", EditorText.indentColumns("   \nfoo", 0, 4) == -1 && EditorText.indentColumns("\nfoo", 0, 4) == -1 && EditorText.indentColumns("   ", 0, 4) == -1 && EditorText.indentColumns("", 0, 4) == -1, "");
        check("a line that starts later in the text", EditorText.indentColumns("a\n  b", 2, 4) == 2, "");
        check("carriage return ends the line", EditorText.indentColumns("  \r\n", 0, 4) == -1, "");

        System.out.println(passes + " passed, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
