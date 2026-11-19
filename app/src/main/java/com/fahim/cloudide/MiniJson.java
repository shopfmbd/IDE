package com.fahim.cloudide;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer with no Android or library dependency, so the AI protocol code that uses it can be
 * compiled and tested on a plain JDK (see toolchain/test). Objects become {@code LinkedHashMap<String,Object>}, arrays
 * {@code ArrayList<Object>}, integers {@code Long}, other numbers {@code Double}; JSON null is Java null.
 *
 * The reader is deliberately forgiving about things that cannot change the meaning (raw control characters inside a
 * string, leading zeros) and strict about things that can (trailing data, unterminated strings, runaway nesting).
 */
final class MiniJson {
    static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        JsonException(String message) { super(message); }
    }

    private static final int MAX_DEPTH = 64;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final String s;
    private int i;

    private MiniJson(String s) { this.s = s; }

    // ------------------------------------------------------------ reading
    /** Parses one JSON value. Throws {@link JsonException} when the text is not exactly one valid value. */
    static Object parse(String text) {
        if (text == null) throw new JsonException("No text to parse");
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) throw p.err("Unexpected data after the value");
        return v;
    }

    /** Like {@link #parse} but returns null for anything that is not valid JSON. */
    static Object tryParse(String text) {
        try {
            return parse(text);
        } catch (JsonException e) {
            return null;
        }
    }

    private JsonException err(String m) { return new JsonException(m + " (at character " + i + ")"); }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw err("Nesting is too deep");
        if (i >= s.length()) throw err("Unexpected end of text");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object(depth);
            case '[': return array(depth);
            case '"': return string();
            case 't': literal("true"); return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw err("Unexpected character '" + c + "'");
        }
    }

    private void literal(String word) {
        if (!s.startsWith(word, i)) throw err("Expected " + word);
        i += word.length();
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw err("Expected a quoted name");
            String k = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw err("Expected ':'");
            i++;
            ws();
            m.put(k, value(depth + 1));
            ws();
            if (i >= s.length()) throw err("Unterminated object");
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw err("Expected ',' or '}'");
        }
    }

    private List<Object> array(int depth) {
        List<Object> a = new ArrayList<>();
        i++; // [
        ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
        while (true) {
            ws();
            a.add(value(depth + 1));
            ws();
            if (i >= s.length()) throw err("Unterminated array");
            char c = s.charAt(i++);
            if (c == ']') return a;
            if (c != ',') throw err("Expected ',' or ']'");
        }
    }

    private String string() {
        i++; // opening quote
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw err("Unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw err("Unterminated string");
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u': {
                    if (i + 4 > s.length()) throw err("Bad \\u escape");
                    int cp = 0;
                    for (int k = 0; k < 4; k++) {
                        int d = Character.digit(s.charAt(i + k), 16);
                        if (d < 0) throw err("Bad \\u escape");
                        cp = cp * 16 + d;
                    }
                    i += 4;
                    b.append((char) cp); // surrogate pairs arrive as two escapes and join naturally
                    break;
                }
                default: throw err("Bad escape \\" + e);
            }
        }
    }

    private Number number() {
        int start = i;
        if (s.charAt(i) == '-') i++;
        int digits = 0;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') { i++; digits++; }
        if (digits == 0) throw err("Bad number");
        boolean real = false;
        if (i < s.length() && s.charAt(i) == '.') {
            real = true;
            i++;
            int f = 0;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') { i++; f++; }
            if (f == 0) throw err("Bad number");
        }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            real = true;
            i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            int x = 0;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') { i++; x++; }
            if (x == 0) throw err("Bad number");
        }
        String t = s.substring(start, i);
        if (!real) {
            try {
                return Long.valueOf(t);
            } catch (NumberFormatException tooBig) {
                return Double.valueOf(t);
            }
        }
        return Double.valueOf(t);
    }

    // ------------------------------------------------------------ writing
    /** Writes maps, lists, arrays, strings, numbers, booleans and null as compact JSON. */
    static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, 0);
        return b.toString();
    }

    /** A JSON string literal (with quotes) for {@code s}. */
    static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        quote(b, s);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v, int depth) {
        if (depth > MAX_DEPTH) throw new JsonException("Nesting is too deep");
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            quote(b, (String) v);
        } else if (v instanceof Boolean) {
            b.append(v.toString());
        } else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null");
            else b.append(v.toString());
        } else if (v instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, String.valueOf(e.getKey()));
                b.append(':');
                write(b, e.getValue(), depth + 1);
            }
            b.append('}');
        } else if (v instanceof Iterable) {
            b.append('[');
            boolean first = true;
            for (Object o : (Iterable<?>) v) {
                if (!first) b.append(',');
                first = false;
                write(b, o, depth + 1);
            }
            b.append(']');
        } else if (v instanceof Object[]) {
            b.append('[');
            Object[] a = (Object[]) v;
            for (int k = 0; k < a.length; k++) {
                if (k > 0) b.append(',');
                write(b, a[k], depth + 1);
            }
            b.append(']');
        } else {
            quote(b, v.toString());
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    // control characters and the two line separators that break JavaScript consumers
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        b.append("\\u").append(HEX[(c >> 12) & 15]).append(HEX[(c >> 8) & 15]).append(HEX[(c >> 4) & 15]).append(HEX[c & 15]);
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }

    // ------------------------------------------------------------ safe access
    /**
     * Walks {@code root} along {@code path}: String elements index maps, Integer elements index lists. Returns null when
     * anything on the way is missing or has the wrong type, so callers never need a try/catch just to read a field.
     */
    static Object at(Object root, Object... path) {
        Object cur = root;
        for (Object p : path) {
            if (cur == null) return null;
            if (p instanceof String) {
                if (!(cur instanceof Map)) return null;
                cur = ((Map<?, ?>) cur).get(p);
            } else if (p instanceof Integer) {
                if (!(cur instanceof List)) return null;
                List<?> l = (List<?>) cur;
                int idx = (Integer) p;
                if (idx < 0 || idx >= l.size()) return null;
                cur = l.get(idx);
            } else {
                return null;
            }
        }
        return cur;
    }

    /** The string at {@code path}, or null when it is missing or not a string. */
    static String str(Object root, Object... path) {
        Object v = at(root, path);
        return v instanceof String ? (String) v : null;
    }

    /** The list at {@code path}, or an empty list. */
    @SuppressWarnings("unchecked")
    static List<Object> list(Object root, Object... path) {
        Object v = at(root, path);
        return v instanceof List ? (List<Object>) v : new ArrayList<Object>();
    }
}
