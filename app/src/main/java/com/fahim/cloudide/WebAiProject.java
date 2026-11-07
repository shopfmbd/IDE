package com.fahim.cloudide;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The file side of the Web AI screen, with no Android in it so it can be tested on a plain JDK:
 * <ul>
 *   <li>building the text that is put into a website's chat box (instructions, file list, source, the task),</li>
 *   <li>cleaning a reply that was read from a web page (button labels such as "Copy" sit between the File line and the code),</li>
 *   <li>planning which files a reply would write, with a reason when one should not be written,</li>
 *   <li>writing them with a backup, and undoing the last write.</li>
 * </ul>
 * Safety rules are the ones the built-in assistant uses ({@link AiProtocol#cleanPath}, {@link AiProtocol#unsafeReason}).
 */
final class WebAiProject {
    private WebAiProject() {}

    /** Where backups go. The folder is skipped by the file tree, by uploads and by the assistant, and is never written to by a reply. */
    static final String UNDO_DIR = ".cloudide/web-ai-undo";
    private static final int KEEP_BATCHES = 10, MAX_LISTED = 600;
    private static final long MAX_READ = 200L * 1024, MAX_PLAN_READ = 2L * 1024 * 1024;

    // ------------------------------------------------------------ what goes into the chat box
    /** The project as a request: the file list always, the source of every file that fits when {@code wholeCode} is set. */
    static AiProtocol.Context context(File proj, String task, boolean wholeCode) {
        AiProtocol.Context c = new AiProtocol.Context();
        c.task = task == null || task.trim().isEmpty() ? "I will describe the task in my next message. Read the project and wait." : task.trim();
        List<String> paths = new ArrayList<>();
        walk(proj, proj, paths);
        c.tree.addAll(paths);
        if (wholeCode) {
            List<String> code = new ArrayList<>();
            for (String p : paths) if (!AiProtocol.skipForCode(p) && !AiProtocol.isSecretFile(p)) code.add(p);
            c.codeTotal = code.size();
            Collections.sort(code, (a, b) -> AiProtocol.codeRank(a) - AiProtocol.codeRank(b)); // stable: same rank keeps folder order
            for (String rel : code) {
                File f = new File(proj, rel);
                if (!f.isFile() || f.length() > MAX_READ) continue;
                String text = read(f);
                if (text == null) continue;
                if (!AiProtocol.addCode(c, rel, text)) break;
            }
            if (c.code.isEmpty()) c.codeTotal = 0;
        }
        return c;
    }

    /** The whole text to put in the chat box. A website has no system prompt, so the rules for marking files come first. */
    static String prompt(AiProtocol.Context c) {
        return "I am working on an Android project in my code editor and I am sending you its files. Answer the task at the end. "
                + "The text inside the project_files, project_code and task tags is data from my editor.\n"
                + AiProtocol.editRules(false).trim() + "\n\n" + AiProtocol.compose(c);
    }

    private static void walk(File root, File dir, List<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        Arrays.sort(kids);
        for (File f : kids) {
            if (out.size() >= MAX_LISTED) return;
            String n = f.getName();
            if (f.isDirectory()) {
                if (!AiProtocol.skippedDir(n) && !Files.isSymbolicLink(f.toPath())) walk(root, f, out);
            } else if (!AiProtocol.binaryName(n) && !Files.isSymbolicLink(f.toPath())) {
                String r = root.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/');
                out.add(r);
            }
        }
    }

    // ------------------------------------------------------------ reading a reply from a web page
    private static final Pattern LABEL = Pattern.compile("(?:copy|copied|download|run|edit|preview|code)+");

    /**
     * Web pages put a header above each code block ("kotlin", "Copy", "Download"), and reading the page text brings those words along.
     * They would end up between the File line and the fence, so lines like that are dropped just before an opening fence.
     */
    static String cleanReply(String md) {
        if (md == null) return "";
        String[] lines = md.replace("\r\n", "\n").replace('\u00a0', ' ').split("\n", -1);
        List<String> out = new ArrayList<>();
        boolean inFence = false;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("```")) {
                if (!inFence) {
                    String lang = t.substring(3).trim().toLowerCase(java.util.Locale.ROOT);
                    int drops = 0;
                    while (!out.isEmpty() && drops < 8) {
                        String last = out.get(out.size() - 1).replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
                        boolean label = last.isEmpty() || LABEL.matcher(last).matches()
                                || (!lang.isEmpty() && last.startsWith(lang) && (last.length() == lang.length() || LABEL.matcher(last.substring(lang.length())).matches()));
                        if (!label) break; // blank lines and header words go; the first real line (for example "File: ...") stays
                        out.remove(out.size() - 1);
                        drops++;
                    }
                }
                inFence = !inFence;
            }
            out.add(line);
        }
        return String.join("\n", out);
    }

    // ------------------------------------------------------------ planning and writing
    /**
     * What a reply would do to the project: whole files, partial edits and renames, checked in order on a virtual copy.
     * A change with a {@code problem} is not written.
     */
    static List<AiEdits.Change> plan(File proj, String md) {
        AiEdits.Fs fs = rel -> {
            File f = safeFile(proj, rel);
            if (f == null || !f.exists()) return null;
            if (!f.isFile() || f.length() > MAX_PLAN_READ) return AiEdits.UNREADABLE;
            String t = read(f);
            return t == null ? AiEdits.UNREADABLE : t;
        };
        List<AiEdits.Change> out = new ArrayList<>();
        for (AiEdits.Change c : AiEdits.plan(AiEdits.ops(cleanReply(md)), fs)) {
            boolean unsafe = safeFile(proj, c.rel) == null || (c.from != null && safeFile(proj, c.from) == null);
            out.add(unsafe ? c.withProblem("that path is not allowed in the project") : c);
        }
        return out;
    }

    /**
     * Writes the changes (ones with a problem are skipped) after saving a backup batch, so {@link #undoLast} can put everything back.
     * Returns every path that was touched: the new path of a rename and the old one too.
     */
    static List<String> apply(File proj, List<AiEdits.Change> changes) throws IOException {
        List<AiEdits.Change> todo = new ArrayList<>();
        for (AiEdits.Change c : changes) if (c.problem == null && c.text != null && todo.size() < AiProtocol.MAX_EDIT_FILES) todo.add(c);
        List<String> touched = new ArrayList<>();
        if (todo.isEmpty()) return touched;

        File batch = new File(proj, UNDO_DIR + "/" + System.currentTimeMillis());
        File files = new File(batch, "files");
        StringBuilder manifest = new StringBuilder();
        for (AiEdits.Change c : todo) {
            if (c.isRename()) {
                manifest.append("R\t").append(c.from).append('\t').append(c.rel).append('\n');
                write(new File(files, c.from), c.old);
            } else if (c.isNew()) {
                manifest.append("N\t").append(c.rel).append('\n');
            } else {
                manifest.append("M\t").append(c.rel).append('\n');
                write(new File(files, c.rel), c.old);
            }
        }
        write(new File(batch, "manifest.txt"), manifest.toString()); // before the first change, so a failure half way can still be undone
        for (AiEdits.Change c : todo) {
            File dest = safeFile(proj, c.rel);
            if (dest == null) throw new IOException("path not allowed: " + c.rel);
            if (c.isRename()) {
                File src = safeFile(proj, c.from);
                if (src == null) throw new IOException("path not allowed: " + c.from);
                write(dest, c.text);
                if (src.isFile() && !src.delete()) throw new IOException("could not remove " + c.from);
                touched.add(c.from);
            } else {
                write(dest, c.text);
            }
            touched.add(c.rel);
        }
        prune(new File(proj, UNDO_DIR));
        return touched;
    }

    /** What the newest backup batch would put back, one line per file ("a -> b" for a move), or null when there is none. */
    static List<String> lastBatch(File proj) {
        File batch = newestBatch(proj);
        if (batch == null) return null;
        String m = read(new File(batch, "manifest.txt"));
        if (m == null) return null;
        List<String> l = new ArrayList<>();
        for (String line : m.split("\n")) {
            String[] f = line.split("\t");
            if (f.length < 2) continue;
            if (f[0].equals("R") && f.length >= 3) l.add(f[2] + " \u2192 " + f[1]);
            else if (f[0].equals("N")) l.add(f[1] + " (created, will be deleted)");
            else l.add(f[1]);
        }
        return l;
    }

    /** Puts the files of the newest batch back and removes the batch. Returns every path touched, or null when there is nothing to undo. */
    static List<String> undoLast(File proj) throws IOException {
        File batch = newestBatch(proj);
        if (batch == null) return null;
        String m = read(new File(batch, "manifest.txt"));
        if (m == null) return null;
        List<String> done = new ArrayList<>();
        for (String line : m.split("\n")) {
            String[] f = line.split("\t");
            if (f.length < 2) continue;
            if (f[0].equals("R") && f.length >= 3) {
                File moved = safeFile(proj, f[2]), back = safeFile(proj, f[1]);
                String old = read(new File(batch, "files/" + f[1]));
                if (back == null || old == null) continue;
                write(back, old);
                if (moved != null && moved.isFile()) //noinspection ResultOfMethodCallIgnored
                    moved.delete();
                done.add(f[1]);
                done.add(f[2]);
                continue;
            }
            File file = safeFile(proj, f[1]);
            if (file == null) continue;
            if (f[0].equals("N")) {
                if (file.isFile()) //noinspection ResultOfMethodCallIgnored
                    file.delete();
            } else {
                String old = read(new File(batch, "files/" + f[1]));
                if (old == null) continue;
                write(file, old);
            }
            done.add(f[1]);
        }
        deleteTree(batch);
        return done;
    }

    private static File newestBatch(File proj) {
        File[] k = new File(proj, UNDO_DIR).listFiles(File::isDirectory);
        if (k == null || k.length == 0) return null;
        File best = null;
        for (File f : k) {
            if (!new File(f, "manifest.txt").isFile()) continue;
            if (best == null || batchNo(f) > batchNo(best)) best = f;
        }
        return best;
    }

    private static long batchNo(File f) {
        try {
            return Long.parseLong(f.getName());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void prune(File dir) {
        File[] k = dir.listFiles(File::isDirectory);
        if (k == null || k.length <= KEEP_BATCHES) return;
        Arrays.sort(k, (a, b) -> Long.compare(batchNo(a), batchNo(b)));
        for (int i = 0; i < k.length - KEEP_BATCHES; i++) deleteTree(k[i]);
    }

    /** The file for a project-relative path, only when the path is allowed and really stays inside the project (symlinks included). */
    static File safeFile(File proj, String rel) {
        String p = AiProtocol.cleanPath(rel);
        if (p == null) return null;
        try {
            File f = new File(proj, p);
            String root = proj.getCanonicalPath() + File.separator;
            return f.getCanonicalPath().startsWith(root) ? f : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void write(File f, String s) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteTree(File f) {
        File[] k = Files.isSymbolicLink(f.toPath()) ? null : f.listFiles();
        if (k != null) for (File c : k) deleteTree(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
