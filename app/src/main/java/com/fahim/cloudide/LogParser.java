package com.fahim.cloudide;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns a GitHub Actions log into a list of file/line problems. */
final class LogParser {
    private LogParser() { }

    static final Pattern LOC = Pattern.compile("/home/runner/work/[^/]+/[^/]+/([^\\s:]+):(\\d+)(?::(\\d+))?");
    static final String TS = "^\\d{4}-\\d\\d-\\d\\dT[\\d:.]+Z\\s?";

    static List<Diag> parse(String log) {
        List<Diag> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String[] lines = log.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].replaceFirst(TS, "").replace("\r", "");
            Matcher m = LOC.matcher(s);
            if (m.find()) {
                String low = s.toLowerCase(Locale.ROOT);
                int sev = 0;
                if (s.startsWith("e: ") || low.contains("error")) sev = 1;
                else if (s.startsWith("w: ") || low.contains("warning")) sev = 2;
                if (sev == 0) continue;
                String msg = s.substring(m.end()).replaceFirst("^[: ]*(?:AAPT: )?(?:error|warning)?:?\\s*", "").trim();
                if (msg.isEmpty() && i + 1 < lines.length) msg = lines[i + 1].replaceFirst(TS, "").trim();
                String key = m.group(1) + ":" + m.group(2) + ":" + msg;
                if (seen.add(key))
                    out.add(new Diag(m.group(1), Integer.parseInt(m.group(2)), m.group(3) == null ? 0 : Integer.parseInt(m.group(3)), msg, sev));
            } else if (s.contains("What went wrong:") && i + 1 < lines.length) {
                String msg = lines[i + 1].replaceFirst(TS, "").trim();
                if (seen.add("G:" + msg)) out.add(new Diag("", 0, 0, msg, 1));
            }
        }
        return out;
    }
}
