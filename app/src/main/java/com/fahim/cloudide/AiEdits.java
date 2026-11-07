package com.fahim.cloudide;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an assistant reply asks to do to the project, and what that comes to once it is checked. No Android in here, so it is tested on a
 * plain JDK and used by both the AI panel and the AI website screen.
 *
 * <p>A reply can ask for three things, in the order it writes them:
 * <ul>
 *   <li><b>File: path</b> and a code block: create the file, or replace it completely.</li>
 *   <li><b>Edit: path</b> and a code block of {@code <<<<<<< SEARCH / ======= / >>>>>>> REPLACE} blocks: change part of a file, so a big
 *       file does not have to be sent back whole. Each SEARCH must match the file's text and match in one place only.</li>
 *   <li><b>Rename: old/path -> new/path</b>: move a file. Later steps in the same reply use the new path.</li>
 * </ul>
 * {@link #plan} runs all of it on a virtual copy of the files and returns one {@link Change} per file that would end up different, so
 * nothing is written until every step has been checked. A step that cannot work (text not found, a path that is not allowed, a file
 * that is already there) puts a reason on that file's change instead of guessing.
 */
final class AiEdits {
    private AiEdits() {}

    enum Kind { WRITE, EDIT, RENAME }

    /** One request from a reply. For a rename, {@code path} is the old path and {@code to} the new one. */
    static final class Op {
        final Kind kind;
        final String path, to, text;

        Op(Kind kind, String path, String to, String text) {
            this.kind = kind;
            this.path = path;
            this.to = to;
            this.text = text;
        }

        /** Identifies this exact request, so a button can show that it was already done. */
        String sig() {
            return kind == Kind.RENAME ? "R|" + path + "|" + to : kind.name().charAt(0) + "|" + path + "|" + text.length() + "|" + text.hashCode();
        }
    }

    /** Reads a project file's current text. Null when it does not exist; {@link #UNREADABLE} when it exists but cannot be read. */
    interface Fs {
        String read(String rel);
    }

    static final String UNREADABLE = "\u0000unreadable";

    /** The final state of one path. {@code from} is set when the file was moved there; {@code old} is then the text it had at the old path. */
    static final class Change {
        final String rel, from, old, text, problem;
        final Set<String> sigs;

        Change(String rel, String from, String old, String text, String problem, Set<String> sigs) {
            this.rel = rel;
            this.from = from;
            this.old = old;
            this.text = text;
            this.problem = problem;
            this.sigs = sigs;
        }

        boolean isRename() { return from != null; }

        boolean isNew() { return from == null && old == null; }

        Change withProblem(String p) { return new Change(rel, from, old, text, p, sigs); }

        /** A short name for lists: the path, or "old -> new" for a move. */
        String label() { return from != null ? from + " \u2192 " + rel : rel; }

        /** What this change does, for a confirmation list. */
        String describe() {
            if (text == null) return label();
            if (from != null) return "rename  " + from + " \u2192 " + rel + (text.equals(old) ? "" : "  (and edited)");
            if (old == null) return "new     " + rel + "  (" + AiProtocol.countLines(text) + " lines)";
            return "edit    " + rel + "  (" + AiProtocol.countLines(old) + " \u2192 " + AiProtocol.countLines(text) + " lines)";
        }
    }

    // ------------------------------------------------------------ reading the requests
    private static final Pattern RENAME_LINE = Pattern.compile("(?i)^[\\s>#*_-]*(?:rename|move)\\s*:\\s*[*_]*\\s*[`\"']?([^`\"'\\s*]+)[`\"']?\\s+(?:->|=>|\\u2192|to)\\s+[`\"']?([^`\"'\\s*]+)[`\"']?[*_]*\\s*$");

    static Op of(AiProtocol.Block b) { return new Op(b.edit ? Kind.EDIT : Kind.WRITE, b.path, null, b.text); }

    /** The requests of a reply in the order written. Unfinished code blocks and unsafe paths are not requests. */
    static List<Op> ops(String md) {
        List<Op> out = new ArrayList<>();
        for (AiProtocol.Block b : AiProtocol.blocks(md)) {
            if (b.code) {
                if (!b.open && b.path != null) out.add(of(b));
                continue;
            }
            for (String line : b.text.split("\n")) {
                Matcher m = RENAME_LINE.matcher(line);
                if (!m.matches()) continue;
                String from = AiProtocol.cleanPath(m.group(1)), to = AiProtocol.cleanPath(m.group(2));
                if (from != null && to != null) out.add(new Op(Kind.RENAME, from, to, null));
            }
        }
        return out;
    }

    // ------------------------------------------------------------ SEARCH / REPLACE
    private static final Pattern SEARCH = Pattern.compile("<{5,9}\\s*SEARCH\\s*"), DIVIDER = Pattern.compile("={5,9}"), REPLACE = Pattern.compile(">{5,9}\\s*REPLACE\\s*");

    /** Result of editing a text: the new text, or why it could not be done. */
    static final class Edited {
        final String text, error;

        Edited(String text, String error) {
            this.text = text;
            this.error = error;
        }
    }

    /**
     * Applies the SEARCH/REPLACE blocks in {@code block} to {@code text} (null for a file that does not exist). A SEARCH matches exactly, or
     * when exact fails, ignoring spaces at the ends of lines. It must match in one place: nothing is changed on a miss or a double match.
     * An empty SEARCH is only for a new or empty file.
     */
    static Edited applyHunks(String text, String block) {
        List<String[]> hunks = new ArrayList<>();
        List<String> search = null, rep = null;
        int state = 0;
        for (String line : block.replace("\r\n", "\n").split("\n", -1)) {
            String t = line.trim();
            if (state == 0 && SEARCH.matcher(t).matches()) { state = 1; search = new ArrayList<>(); continue; }
            if (state == 1 && DIVIDER.matcher(t).matches()) { state = 2; rep = new ArrayList<>(); continue; }
            if (state == 2 && REPLACE.matcher(t).matches()) {
                hunks.add(new String[]{String.join("\n", search), String.join("\n", rep)});
                state = 0;
                continue;
            }
            if (state == 1) search.add(line);
            else if (state == 2) rep.add(line);
        }
        if (state != 0) return new Edited(null, "a SEARCH/REPLACE block is not finished (it needs the ======= line and the >>>>>>> REPLACE line)");
        if (hunks.isEmpty()) return new Edited(null, "it has no <<<<<<< SEARCH ... ======= ... >>>>>>> REPLACE block");

        boolean crlf = text != null && text.contains("\r\n");
        String w = text == null ? null : text.replace("\r\n", "\n");
        for (int n = 0; n < hunks.size(); n++) {
            String s = hunks.get(n)[0], r = hunks.get(n)[1];
            String which = hunks.size() > 1 ? "SEARCH block " + (n + 1) : "the SEARCH text";
            if (s.isEmpty()) {
                if (w == null || w.isEmpty()) { w = r; continue; }
                return new Edited(null, "an empty SEARCH only works for a new or empty file");
            }
            if (w == null) return new Edited(null, "the file does not exist; use a File: line to create it");
            int i = w.indexOf(s);
            if (i >= 0) {
                if (w.indexOf(s, i + s.length()) >= 0) return new Edited(null, which + " matches more than one place; add a few lines around it so it matches once");
                w = w.substring(0, i) + r + w.substring(i + s.length());
                continue;
            }
            String[] wl = w.split("\n", -1), sl = s.split("\n", -1);
            int hit = -1, hits = 0;
            for (int a = 0; a + sl.length <= wl.length; a++) {
                boolean ok = true;
                for (int k = 0; k < sl.length && ok; k++) ok = trimEnd(wl[a + k]).equals(trimEnd(sl[k]));
                if (ok) { hit = a; hits++; }
            }
            if (hits == 0) return new Edited(null, which + " was not found; it must match the file exactly, indentation included");
            if (hits > 1) return new Edited(null, which + " matches more than one place; add a few lines around it so it matches once");
            List<String> lines = new ArrayList<>();
            for (int a = 0; a < hit; a++) lines.add(wl[a]);
            for (String x : r.split("\n", -1)) lines.add(x);
            for (int a = hit + sl.length; a < wl.length; a++) lines.add(wl[a]);
            w = String.join("\n", lines);
        }
        return new Edited(crlf ? w.replace("\n", "\r\n") : w, null);
    }

    private static String trimEnd(String s) {
        int e = s.length();
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) e--;
        return s.substring(0, e);
    }

    // ------------------------------------------------------------ planning
    private static final class Rec {
        String orig, now, from, fromOrig, problem;
        boolean whole, edited, absorbed;
        final String path;
        final Set<String> sigs = new LinkedHashSet<>();

        Rec(String path) { this.path = path; }
    }

    private static Rec rec(Map<String, Rec> recs, Fs fs, String path) {
        Rec r = recs.get(path);
        if (r != null) return r;
        r = new Rec(path);
        String t = fs.read(path);
        if (t != null && t.equals(UNREADABLE)) r.problem = "the current file could not be read, so it is not changed blindly";
        else r.orig = r.now = t;
        recs.put(path, r);
        return r;
    }

    /**
     * Runs the requests in order on a virtual copy of the files and returns one change per file that ends up different. A file that already
     * has the wanted text is left out. A change with a {@code problem} must not be written.
     */
    static List<Change> plan(List<Op> ops, Fs fs) {
        Map<String, Rec> recs = new LinkedHashMap<>();
        for (Op op : ops) {
            Rec r = rec(recs, fs, op.path);
            if (op.kind == Kind.RENAME) {
                rename(recs, fs, op, r);
                continue;
            }
            r.sigs.add(op.sig());
            if (r.problem != null) continue;
            if (r.absorbed) {
                r.problem = "this path was renamed earlier in the same reply";
                continue;
            }
            if (op.kind == Kind.WRITE) {
                // a code block has no final line break; keep the file's habit (new files get one, like most source files)
                String t = op.text;
                if ((r.now == null || r.now.endsWith("\n")) && !t.endsWith("\n")) t += "\n";
                r.now = t;
                r.whole = true;
            } else {
                Edited e = applyHunks(r.now, op.text);
                if (e.error != null) r.problem = e.error;
                else {
                    r.now = e.text;
                    r.edited = true;
                }
            }
        }
        List<Change> out = new ArrayList<>();
        for (Rec r : recs.values()) {
            if (r.now == null && r.problem == null) continue; // a file that was only moved away
            String old = r.from != null ? r.fromOrig : r.orig;
            String problem = r.problem;
            if (problem == null && r.from == null && old != null && old.equals(r.now)) continue; // nothing to change
            if (problem == null) problem = AiProtocol.unsafeReason(old, r.now, r.whole && !r.edited && r.from == null);
            if (problem == null && r.now.length() > AiProtocol.MAX_EDIT_CHARS) problem = "it is too large";
            out.add(new Change(r.path, r.from, old, r.now, problem, r.sigs));
        }
        return out;
    }

    private static void rename(Map<String, Rec> recs, Fs fs, Op op, Rec src) {
        Rec dst = rec(recs, fs, op.to);
        dst.sigs.add(op.sig());
        if (op.path.equals(op.to)) {
            dst.problem = "the new name is the same as the old one";
        } else if (src.problem != null) {
            dst.problem = "the file " + op.path + " could not be prepared: " + src.problem;
        } else if (src.now == null) {
            dst.problem = op.path + " does not exist";
        } else if (dst.problem != null) {
            // keeps its own reason
        } else if (dst.now != null || dst.absorbed) {
            dst.problem = op.to + " already exists";
        } else {
            dst.now = src.now;
            dst.from = src.from != null ? src.from : op.path;
            dst.fromOrig = src.from != null ? src.fromOrig : src.orig;
            dst.whole = src.whole;
            dst.edited = src.edited;
            dst.sigs.addAll(src.sigs);
            src.sigs.clear();
            src.now = null;
            src.absorbed = true;
        }
    }
}
