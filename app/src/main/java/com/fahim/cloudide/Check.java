package com.fahim.cloudide;

import android.util.Xml;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONTokener;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/** Instant on-device checks: XML and JSON well-formedness. */
final class Check {
    private static final Pattern JSON_POS = Pattern.compile("at character (\\d+)");

    private Check() { }

    static List<Diag> run(String rel, String text) {
        List<Diag> out = new ArrayList<>();
        String n = rel.toLowerCase(Locale.ROOT);
        try {
            if (n.endsWith(".xml")) {
                XmlPullParser p = Xml.newPullParser();
                p.setInput(new StringReader(text));
                while (p.next() != XmlPullParser.END_DOCUMENT) { }
            } else if (n.endsWith(".json")) {
                JSONTokener t = new JSONTokener(text);
                t.nextValue();
                if (t.nextClean() != 0) throw t.syntaxError("Unexpected trailing content");
            }
        } catch (XmlPullParserException e) {
            String m = String.valueOf(e.getMessage());
            int i = m.indexOf(" (position");
            if (i > 0) m = m.substring(0, i);
            out.add(new Diag(rel, Math.max(1, e.getLineNumber()), Math.max(0, e.getColumnNumber()), m, 1));
        } catch (JSONException e) {
            String m = String.valueOf(e.getMessage());
            Matcher mt = JSON_POS.matcher(m);
            int line = 1;
            if (mt.find()) {
                int off = Integer.parseInt(mt.group(1));
                for (int i = 0; i < off && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
            }
            int c = m.indexOf(" of ");
            if (c > 0) m = m.substring(0, c);
            out.add(new Diag(rel, line, 0, m, 1));
        } catch (Exception ignored) {
        }
        return out;
    }
}
