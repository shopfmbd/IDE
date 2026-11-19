package com.fahim.cloudide;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Code formatting: re-indentation for brace languages, XML, and JSON pretty-print. */
final class Fmt {
    private Fmt() { }

    static String spaces(int n) {
        if (n <= 0) return "";
        char[] c = new char[n];
        java.util.Arrays.fill(c, ' ');
        return new String(c);
    }

    static String format(Lang l, String t, int tab) throws Exception {
        if (l.id.equals("json")) {
            Object v = new JSONTokener(t).nextValue();
            // Android's org.json escapes every "/" as "\\/", which would turn URLs into "https:\\/\\/...". Every "/" in its
            // output is escaped that way, so removing the backslash in front of it restores the original text.
            if (v instanceof JSONObject) return ((JSONObject) v).toString(tab).replace("\\/", "/") + "\n";
            if (v instanceof JSONArray) return ((JSONArray) v).toString(tab).replace("\\/", "/") + "\n";
            return t;
        }
        if (l.id.equals("xml")) return xml(t, tab);
        if (l.brace) return braces(t, tab);
        throw new Exception("Format works for Java, Kotlin, Gradle, JS, C, CSS, JSON and XML");
    }

    static String braces(String t, int tab) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean inBlock = false;
        String[] lines = t.split("\n", -1);
        for (int li = 0; li < lines.length; li++) {
            String s = lines[li].trim();
            if (s.isEmpty()) {
                if (li < lines.length - 1) out.append('\n');
                continue;
            }
            int net = 0, lead = 0;
            boolean leading = true, inStr = false, startsInBlock = inBlock;
            char q = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (inBlock) {
                    if (c == '*' && i + 1 < s.length() && s.charAt(i + 1) == '/') { inBlock = false; i++; }
                    continue;
                }
                if (inStr) {
                    if (c == '\\') i++;
                    else if (c == q) inStr = false;
                    continue;
                }
                if (c == '/' && i + 1 < s.length()) {
                    char n = s.charAt(i + 1);
                    if (n == '/') break;
                    if (n == '*') { inBlock = true; i++; leading = false; continue; }
                }
                if (c == '"' || c == '\'') { inStr = true; q = c; leading = false; continue; }
                if (c == '{' || c == '(' || c == '[') { net++; leading = false; }
                else if (c == '}' || c == ')' || c == ']') { net--; if (leading) lead++; }
                else if (!Character.isWhitespace(c)) leading = false;
            }
            int ind = Math.max(0, depth - lead);
            out.append(spaces(ind * tab)).append(startsInBlock && s.startsWith("*") ? " " : "").append(s);
            if (li < lines.length - 1) out.append('\n');
            depth = Math.max(0, depth + net);
        }
        return out.toString();
    }

    static String xml(String t, int tab) {
        StringBuilder sb = new StringBuilder();
        int depth = 0, kind = 0;
        boolean inTag = false, inCom = false;
        String[] lines = t.split("\n", -1);
        for (int li = 0; li < lines.length; li++) {
            String s = lines[li].trim();
            if (s.isEmpty()) {
                if (li < lines.length - 1) sb.append('\n');
                continue;
            }
            int ind;
            if (inCom) ind = depth;
            else if (inTag) ind = depth + 1;
            else if (s.startsWith("</")) ind = Math.max(0, depth - 1);
            else ind = depth;
            sb.append(spaces(ind * tab)).append(s);
            if (li < lines.length - 1) sb.append('\n');
            for (int i = 0; i < s.length(); i++) {
                if (inCom) {
                    int e = s.indexOf("-->", i);
                    if (e < 0) break;
                    i = e + 2;
                    inCom = false;
                    continue;
                }
                char c = s.charAt(i);
                if (!inTag) {
                    if (c != '<') continue;
                    if (s.startsWith("<!--", i)) { inCom = true; i += 3; continue; }
                    inTag = true;
                    kind = s.startsWith("</", i) ? 1 : (s.startsWith("<?", i) || s.startsWith("<!", i)) ? 2 : 0;
                } else if (c == '"' || c == '\'') {
                    int e = s.indexOf(c, i + 1);
                    if (e < 0) break;
                    i = e;
                } else if (c == '>') {
                    boolean self = i > 0 && s.charAt(i - 1) == '/';
                    if (kind == 1) depth = Math.max(0, depth - 1);
                    else if (kind == 0 && !self) depth++;
                    inTag = false;
                }
            }
        }
        return sb.toString();
    }
}
