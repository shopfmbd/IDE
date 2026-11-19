package com.fahim.cloudide;

import java.util.*;

/** Plain-JDK test for ManifestMerge. */
public class MergeTest {
    static int fails = 0, passes = 0;

    static void check(String n, boolean ok, String d) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + n + "  -> " + d); }
    }

    public static void main(String[] a) throws Exception {
        String app = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.me.app\">\n"
                + "<uses-permission android:name=\"android.permission.INTERNET\"/>\n<application android:label=\"x\">\n<activity android:name=\".Main\" android:exported=\"true\"/>\n</application>\n</manifest>\n";
        String lib = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" xmlns:tools=\"http://schemas.android.com/tools\" package=\"androidx.startup\">\n"
                + "<uses-permission android:name=\"android.permission.INTERNET\"/>\n<uses-permission android:name=\"android.permission.WAKE_LOCK\"/>\n"
                + "<application>\n<provider android:name=\"androidx.startup.InitializationProvider\" android:authorities=\"${applicationId}.androidx-startup\" android:exported=\"false\" tools:node=\"merge\">\n"
                + "<meta-data android:name=\"androidx.emoji2.text.EmojiCompatInitializer\" android:value=\"androidx.startup\"/>\n</provider>\n"
                + "<service android:name=\".Svc\"/>\n<activity android:name=\".Main\"/>\n</application>\n</manifest>\n";
        List<String> notes = new ArrayList<>();
        String out = ManifestMerge.merge(app, "com.me.app", Arrays.asList(new String[]{"androidx.startup", lib}, new String[]{"androidx.startup", lib}), notes);
        check("provider added", out.contains("androidx.startup.InitializationProvider"), out);
        check("applicationId filled", out.contains("com.me.app.androidx-startup") && !out.contains("${applicationId}"), out);
        check("relative service made absolute", out.contains("androidx.startup.Svc"), out);
        check("permission added once", count(out, "WAKE_LOCK") == 1 && count(out, "android.permission.INTERNET") == 1, out);
        check("app activity kept and lib .Main is a different class", out.contains("\".Main\"") && out.contains("androidx.startup.Main"), out);
        check("tools attrs dropped", !out.contains("tools:node"), out);
        check("duplicate lib merged once", count(out, "InitializationProvider") == 1, "" + count(out, "InitializationProvider"));
        check("meta-data inside provider kept", out.contains("EmojiCompatInitializer"), out);
        check("permission before application", out.indexOf("WAKE_LOCK") < out.indexOf("<application"), out);
        String lib2 = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.other\"><application><activity android:name=\"com.me.app.Main\"/></application></manifest>";
        String out2 = ManifestMerge.merge(app, "com.me.app", Collections.singletonList(new String[]{"com.other", lib2}), new ArrayList<String>());
        check("same class declared by app (relative) and lib (absolute) is not duplicated", count(out2, "Main") == 1, out2);
        check("relative app name kept verbatim", out2.contains("\".Main\""), out2);
        // a library manifest with a DOCTYPE (external entity) is refused, not parsed
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE m [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.evil\"><application><service android:name=\".S\" android:label=\"&e;\"/></application></manifest>";
        List<String> n3 = new ArrayList<>();
        String out3 = ManifestMerge.merge(app, "com.me.app", Collections.singletonList(new String[]{"com.evil", evil}), n3);
        check("DOCTYPE in a library manifest is not merged", !out3.contains("com.evil.S") && n3.toString().contains("not merged"), n3.toString());
        System.out.println(passes + " passed, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }

    static int count(String s, String x) {
        int n = 0, i = 0;
        while ((i = s.indexOf(x, i)) >= 0) { n++; i += x.length(); }
        return n;
    }
}
