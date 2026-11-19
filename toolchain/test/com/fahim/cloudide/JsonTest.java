package com.fahim.cloudide;

import java.util.*;

/** Plain-JDK test for MiniJson. */
public class JsonTest {
    static int fails = 0, passes = 0;

    static void check(String n, boolean ok, String d) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + n + "  -> " + d); }
    }

    static boolean rejects(String text) {
        try {
            MiniJson.parse(text);
            return false;
        } catch (MiniJson.JsonException e) {
            return true;
        }
    }

    public static void main(String[] a) {
        // ---- values
        check("string", "hi".equals(MiniJson.parse("\"hi\"")), "");
        check("integer is Long", Long.valueOf(42).equals(MiniJson.parse("42")), "" + MiniJson.parse("42"));
        check("negative", Long.valueOf(-7).equals(MiniJson.parse("-7")), "");
        check("real is Double", Double.valueOf(1.5).equals(MiniJson.parse("1.5")), "");
        check("exponent", Double.valueOf(1500).equals(MiniJson.parse("1.5e3")), "" + MiniJson.parse("1.5e3"));
        check("huge integer falls back to Double", MiniJson.parse("123456789012345678901234567890") instanceof Double, "");
        check("true/false/null", Boolean.TRUE.equals(MiniJson.parse("true")) && Boolean.FALSE.equals(MiniJson.parse("false")) && MiniJson.parse("null") == null, "");
        check("whitespace around", Long.valueOf(1).equals(MiniJson.parse(" \n\t 1 \r\n")), "");

        // ---- structure
        Object o = MiniJson.parse("{\"a\":[1,2,{\"b\":\"c\"}],\"d\":{},\"e\":[]}");
        check("path into object/array/object", "c".equals(MiniJson.str(o, "a", 2, "b")), "" + o);
        check("empty object/array", ((Map<?, ?>) MiniJson.at(o, "d")).isEmpty() && ((List<?>) MiniJson.at(o, "e")).isEmpty(), "");
        check("missing path is null, not an exception", MiniJson.at(o, "x", 0, "y") == null && MiniJson.at(o, "a", 9) == null && MiniJson.at(o, "a", "b") == null, "");
        check("wrong type is null", MiniJson.str(o, "a") == null, "");
        check("key order kept", new ArrayList<>(((Map<?, ?>) MiniJson.parse("{\"z\":1,\"a\":2,\"m\":3}")).keySet()).toString().equals("[z, a, m]"), "");
        check("list() of missing is empty", MiniJson.list(o, "nope").isEmpty(), "");

        // ---- strings and escapes
        check("simple escapes", "a\"b\\c/d\be\ff\ng\rh\ti".equals(MiniJson.parse("\"a\\\"b\\\\c\\/d\\be\\ff\\ng\\rh\\ti\"")), "");
        check("\\u escape", "\u00e9".equals(MiniJson.parse("\"\\u00e9\"")), "");
        check("surrogate pair from two escapes", "\uD83D\uDE00".equals(MiniJson.parse("\"\\ud83d\\ude00\"")), "");
        check("raw non-ASCII kept", "\u09ec \u4e2d \uD83D\uDE00".equals(MiniJson.parse("\"\u09ec \u4e2d \uD83D\uDE00\"")), "");
        check("raw newline inside string is tolerated", "a\nb".equals(MiniJson.parse("\"a\nb\"")), "");

        // ---- rejects
        check("rejects trailing data", rejects("1 2") && rejects("{} x"), "");
        check("rejects unterminated string", rejects("\"abc") && rejects("\"abc\\"), "");
        check("rejects unterminated object/array", rejects("{\"a\":1") && rejects("[1,2"), "");
        check("rejects missing colon/comma", rejects("{\"a\" 1}") && rejects("[1 2]") && rejects("{\"a\":1 \"b\":2}"), "");
        check("rejects unquoted name", rejects("{a:1}"), "");
        check("rejects bad escape/unicode", rejects("\"\\x\"") && rejects("\"\\u12\"") && rejects("\"\\uzzzz\""), "");
        check("rejects bare words", rejects("nul") && rejects("tru") && rejects("hello") && rejects(""), "");
        check("rejects bad numbers", rejects("-") && rejects("1.") && rejects("1e") && rejects("-x"), "");
        check("Bengali digits are not numbers", rejects("\u09e7\u09e8"), "");
        check("null input", rejects(null), "");
        check("tryParse returns null for garbage", MiniJson.tryParse("{oops") == null && MiniJson.tryParse("[1]") != null, "");

        // ---- depth limit protects against stack overflow from hostile input
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 5000; i++) deep.append('[');
        check("runaway nesting is refused, not a StackOverflowError", rejects(deep.toString()), "");
        StringBuilder ok = new StringBuilder();
        for (int i = 0; i < 40; i++) ok.append('[');
        for (int i = 0; i < 40; i++) ok.append(']');
        check("reasonable nesting is fine", !rejects(ok.toString()), "");

        // ---- writer
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", "x");
        m.put("n", 3);
        m.put("on", true);
        m.put("none", null);
        m.put("list", Arrays.asList("a", 1, 2.5));
        check("writes compact JSON", "{\"model\":\"x\",\"n\":3,\"on\":true,\"none\":null,\"list\":[\"a\",1,2.5]}".equals(MiniJson.write(m)), MiniJson.write(m));
        check("quotes and control characters", "\"a\\\"b\\\\c\\n\\t\\u0001\"".equals(MiniJson.quote("a\"b\\c\n\t\u0001")), MiniJson.quote("a\"b\\c\n\t\u0001"));
        check("line separators escaped", "\"\\u2028\\u2029\"".equals(MiniJson.quote("\u2028\u2029")), MiniJson.quote("\u2028\u2029"));
        check("non-ASCII written as is", "\"\u09ec\uD83D\uDE00\"".equals(MiniJson.quote("\u09ec\uD83D\uDE00")), "");
        check("NaN becomes null", "null".equals(MiniJson.write(Double.NaN)) && "null".equals(MiniJson.write(Double.POSITIVE_INFINITY)), "");
        check("object arrays are written", "[1,\"a\"]".equals(MiniJson.write(new Object[]{1, "a"})), "");

        // ---- round trip, including text that looks like JSON or contains every awkward character
        String nasty = "line1\nline2\t\"quoted\" \\ back \u0000 nul \u001f \u2028 \uD83D\uDE00 \u09ec {\"not\":\"json\"} </script>";
        Map<String, Object> rt = new LinkedHashMap<>();
        rt.put("text", nasty);
        rt.put("arr", Arrays.asList(1L, 2L, nasty));
        Object back = MiniJson.parse(MiniJson.write(rt));
        check("round trip keeps awkward text intact", nasty.equals(MiniJson.str(back, "text")) && nasty.equals(MiniJson.str(back, "arr", 2)), "" + back);
        check("round trip keeps numbers", Long.valueOf(2).equals(MiniJson.at(back, "arr", 1)), "");

        System.out.println(passes + " passed, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
