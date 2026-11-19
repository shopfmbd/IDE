package com.fahim.cloudide;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Plain-JDK test for AiEdits: reading requests from a reply, SEARCH/REPLACE edits, and planning a whole reply. */
public class AiEditsTest {
    static int fails = 0, passes = 0;

    static void check(String n, boolean ok, String d) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + n + "  -> " + d); }
    }

    static AiEdits.Fs fs(Map<String, String> files) { return files::get; }

    static String hunk(String search, String rep) { return "<<<<<<< SEARCH\n" + search + "\n=======\n" + rep + "\n>>>>>>> REPLACE"; }

    static AiEdits.Change only(List<AiEdits.Change> l) { return l.size() == 1 ? l.get(0) : null; }

    public static void main(String[] args) {
        // ================================================================ reading a reply
        String reply = "I will rename it and fix it.\nRename: app/src/Old.kt -> app/src/New.kt\n\nEdit: app/src/New.kt\n```kotlin\n" + hunk("val a = 1", "val a = 2") + "\n```\n\nFile: app/src/Extra.kt\n```kotlin\nclass Extra\n```\n";
        List<AiEdits.Op> ops = AiEdits.ops(reply);
        check("ops: rename, edit and file are read in the order written", ops.size() == 3 && ops.get(0).kind == AiEdits.Kind.RENAME && ops.get(1).kind == AiEdits.Kind.EDIT && ops.get(2).kind == AiEdits.Kind.WRITE, ops.size() + "");
        check("ops: rename carries both paths, edit and file carry their text", ops.get(0).path.equals("app/src/Old.kt") && ops.get(0).to.equals("app/src/New.kt") && ops.get(1).text.contains("SEARCH") && ops.get(2).text.startsWith("class Extra"), "");
        String[] renameForms = {"Rename: a/B.kt -> a/C.kt", "**Rename: `a/B.kt` -> `a/C.kt`**", "Move: a/B.kt to a/C.kt", "### Rename: a/B.kt \u2192 a/C.kt", "rename: a/B.kt => a/C.kt"};
        StringBuilder missed = new StringBuilder();
        for (String f : renameForms) { List<AiEdits.Op> o = AiEdits.ops(f); if (o.size() != 1 || !o.get(0).path.equals("a/B.kt") || !o.get(0).to.equals("a/C.kt")) missed.append(f).append(" | "); }
        check("ops: the usual ways of writing a rename are understood", missed.length() == 0, missed.toString());
        check("ops: a rename with an unsafe path is not a request", AiEdits.ops("Rename: a/B.kt -> ../x.kt").isEmpty() && AiEdits.ops("Rename: /etc/a -> b.kt").isEmpty() && AiEdits.ops("Rename: a.kt -> app/logo.png").isEmpty(), "");
        check("ops: sentences that only mention renaming are not requests", AiEdits.ops("I would rename it to something clearer.").isEmpty() && AiEdits.ops("Rename: the file to a better name").isEmpty(), "");
        check("ops: a Rename line inside a code block is code, not a request", AiEdits.ops("File: a/X.md\n```md\nRename: a/B.kt -> a/C.kt\n```").size() == 1, "");
        check("ops: an Edit block still streaming is not a request yet", AiEdits.ops("Edit: a/B.kt\n```kotlin\n<<<<<<< SEARCH\nx").isEmpty(), "");
        check("ops: Edit marks the block as an edit, File does not", AiProtocol.blocks("Edit: a/B.kt\n```\nx\n```").get(0).edit && !AiProtocol.blocks("File: a/B.kt\n```\nx\n```").get(0).edit, "");
        check("ops: every request has its own signature", ops.get(0).sig().length() > 0 && !ops.get(1).sig().equals(ops.get(2).sig()) && ops.get(1).sig().equals(AiEdits.ops(reply).get(1).sig()), "");

        // ================================================================ SEARCH / REPLACE
        String src = "class A {\n    fun f() = 1\n    fun g() = 2\n}\n";
        AiEdits.Edited e = AiEdits.applyHunks(src, hunk("    fun f() = 1", "    fun f() = 10"));
        check("edit: one block changes one place", e.error == null && e.text.equals("class A {\n    fun f() = 10\n    fun g() = 2\n}\n"), e.error + " / " + e.text);
        e = AiEdits.applyHunks(src, hunk("fun f() = 1", "fun f() = 10") + "\n" + hunk("fun g() = 2", "fun g() = 20"));
        check("edit: several blocks apply in order", e.error == null && e.text.contains("f() = 10") && e.text.contains("g() = 20"), e.error);
        e = AiEdits.applyHunks(src, hunk("fun g() = 2", "fun g() = 20") + "\n" + hunk("fun g() = 20", "fun g() = 200"));
        check("edit: a later block sees the result of an earlier one", e.error == null && e.text.contains("g() = 200"), e.error);
        e = AiEdits.applyHunks(src, hunk("    fun g() = 2\n", ""));
        check("edit: an empty replacement deletes code", e.error == null && !e.text.contains("g()") && e.text.contains("f() = 1"), e.error + " / " + e.text);
        e = AiEdits.applyHunks(src, hunk("fun h() = 3", "x"));
        check("edit: text that is not there changes nothing and says why", e.error != null && e.error.contains("not found") && e.text == null, e.error);
        e = AiEdits.applyHunks("a\nb\na\nb\n", hunk("a\nb", "c"));
        check("edit: text that matches twice is refused", e.error != null && e.error.contains("more than one"), e.error);
        e = AiEdits.applyHunks("x = 1   \ny = 2\n", hunk("x = 1\ny = 2", "x = 5\ny = 6"));
        check("edit: spaces at the ends of lines do not stop a match", e.error == null && e.text.startsWith("x = 5\ny = 6"), e.error + " / " + e.text);
        e = AiEdits.applyHunks("x = 1\r\ny = 2\r\n", hunk("y = 2", "y = 3"));
        check("edit: Windows line endings are kept", e.error == null && e.text.equals("x = 1\r\ny = 3\r\n"), e.error + " / " + (e.text == null ? null : e.text.replace("\r", "\\r")));
        e = AiEdits.applyHunks(null, hunk("", "brand new"));
        check("edit: an empty SEARCH writes a new file", e.error == null && e.text.equals("brand new"), e.error);
        e = AiEdits.applyHunks(src, hunk("", "oops"));
        check("edit: an empty SEARCH on a file with content is refused", e.error != null, "");
        e = AiEdits.applyHunks(null, hunk("a", "b"));
        check("edit: editing a file that does not exist points to File:", e.error != null && e.error.contains("File:"), e.error);
        e = AiEdits.applyHunks(src, "<<<<<<< SEARCH\nfun f() = 1\n=======\nfun f() = 2");
        check("edit: an unfinished block is refused", e.error != null && e.error.contains("not finished"), e.error);
        e = AiEdits.applyHunks(src, "just some text");
        check("edit: no block at all is refused", e.error != null, e.error);
        e = AiEdits.applyHunks(src, "<<<<<<<<< SEARCH\nfun f() = 1\n=========\nfun f() = 7\n>>>>>>>>> REPLACE");
        check("edit: marker lines with a different number of symbols still count", e.error == null && e.text.contains("f() = 7"), e.error);

        // ================================================================ planning
        Map<String, String> files = new HashMap<>();
        files.put("app/src/Old.kt", "val a = 1\nval b = 2\n");
        files.put("app/src/Keep.kt", "class Keep\n");
        files.put("app/src/Big.kt", "line\n".repeat(100));

        List<AiEdits.Change> plan = AiEdits.plan(AiEdits.ops(reply), fs(files));
        check("plan: rename + edit of the new path become ONE change, with the old text kept for undo", plan.size() == 2 && plan.get(0).isRename() && plan.get(0).from.equals("app/src/Old.kt") && plan.get(0).rel.equals("app/src/New.kt")
                && plan.get(0).old.equals("val a = 1\nval b = 2\n") && plan.get(0).text.equals("val a = 2\nval b = 2\n") && plan.get(0).problem == null, plan.size() + " " + (plan.isEmpty() ? "" : plan.get(0).describe()));
        check("plan: the new file from the same reply is a separate change", plan.get(1).isNew() && plan.get(1).rel.equals("app/src/Extra.kt") && plan.get(1).problem == null, "");
        check("plan: a change remembers which requests it came from", plan.get(0).sigs.size() == 2 && plan.get(0).sigs.contains(ops.get(0).sig()) && plan.get(0).sigs.contains(ops.get(1).sig()), plan.get(0).sigs.toString());
        check("plan: describe tells what happens", plan.get(0).describe().contains("rename") && plan.get(0).describe().contains("edited") && plan.get(1).describe().startsWith("new"), plan.get(0).describe());

        plan = AiEdits.plan(AiEdits.ops("Rename: app/src/Keep.kt -> app/src/Kept.kt"), fs(files));
        check("plan: a plain rename keeps the text", plan.size() == 1 && plan.get(0).isRename() && plan.get(0).text.equals("class Keep\n") && plan.get(0).problem == null && plan.get(0).describe().equals("rename  app/src/Keep.kt \u2192 app/src/Kept.kt"), plan.size() + "");
        plan = AiEdits.plan(AiEdits.ops("Rename: app/src/Nope.kt -> app/src/X.kt"), fs(files));
        check("plan: renaming a file that is not there is a problem, nothing is invented", only(plan) != null && only(plan).problem != null && only(plan).problem.contains("does not exist"), "");
        plan = AiEdits.plan(AiEdits.ops("Rename: app/src/Keep.kt -> app/src/Old.kt"), fs(files));
        check("plan: renaming onto an existing file is a problem", only(plan) != null && only(plan).problem != null && only(plan).problem.contains("already exists"), "");
        plan = AiEdits.plan(AiEdits.ops("Rename: app/src/Keep.kt -> app/src/Keep.kt"), fs(files));
        check("plan: renaming to the same name is a problem", only(plan) != null && only(plan).problem != null, "");
        plan = AiEdits.plan(AiEdits.ops("Rename: a/Old.kt -> a/Mid.kt\nRename: a/Mid.kt -> a/New.kt".replace("a/Old.kt", "app/src/Old.kt")), fs(files));
        check("plan: a chain of renames is one move from the first name to the last", plan.size() == 1 && plan.get(0).from.equals("app/src/Old.kt") && plan.get(0).rel.equals("a/New.kt") && plan.get(0).problem == null, plan.size() + "");
        plan = AiEdits.plan(AiEdits.ops("Rename: app/src/Keep.kt -> app/src/Kept.kt\nFile: app/src/Keep.kt\n```kotlin\nclass Again\n```"), fs(files));
        boolean flagged = false;
        for (AiEdits.Change c : plan) if (c.rel.equals("app/src/Keep.kt") && c.problem != null) flagged = true;
        check("plan: writing to a path that was just renamed away is flagged", flagged, plan.size() + "");

        plan = AiEdits.plan(AiEdits.ops("Edit: app/src/Old.kt\n```\n" + hunk("val a = 1", "val a = 9") + "\n```\nEdit: app/src/Old.kt\n```\n" + hunk("val b = 2", "val b = 8") + "\n```"), fs(files));
        check("plan: two edits of one file in one reply give one change with both applied", plan.size() == 1 && plan.get(0).text.equals("val a = 9\nval b = 8\n") && plan.get(0).problem == null && plan.get(0).sigs.size() == 2, plan.size() + "");
        plan = AiEdits.plan(AiEdits.ops("Edit: app/src/Old.kt\n```\n" + hunk("val zzz = 1", "x") + "\n```"), fs(files));
        check("plan: an edit that does not match is a problem on that file, the file is unchanged", only(plan) != null && only(plan).problem != null && only(plan).problem.contains("not found"), "");
        plan = AiEdits.plan(AiEdits.ops("Edit: app/src/Gone.kt\n```\n" + hunk("a", "b") + "\n```"), fs(files));
        check("plan: an edit of a file that does not exist is a problem", only(plan) != null && only(plan).problem != null, "");
        plan = AiEdits.plan(AiEdits.ops("Edit: app/src/Old.kt\n```\n" + hunk("val a = 1", "val a = 9") + "\n```\nEdit: app/src/Keep.kt\n```\n" + hunk("nope", "x") + "\n```"), fs(files));
        check("plan: one bad file does not stop the good one", plan.size() == 2 && plan.get(0).problem == null && plan.get(1).problem != null, plan.size() + "");

        plan = AiEdits.plan(AiEdits.ops("File: app/src/Keep.kt\n```kotlin\nclass Keep\n```"), fs(files));
        check("plan: a file that already has that text is left out", plan.isEmpty(), plan.size() + "");
        plan = AiEdits.plan(AiEdits.ops("File: app/src/Keep.kt\n```kotlin\nclass Keep {\n    // ...\n}\n```"), fs(files));
        check("plan: a placeholder in a whole file is flagged", only(plan) != null && only(plan).problem != null, "");
        plan = AiEdits.plan(AiEdits.ops("File: app/src/Big.kt\n```kotlin\nline\n```"), fs(files));
        check("plan: a whole file that shrinks to almost nothing is flagged", only(plan) != null && only(plan).problem != null, "");
        plan = AiEdits.plan(AiEdits.ops("Edit: app/src/Big.kt\n```\n" + hunk("line\n".repeat(90).trim(), "line") + "\n```"), fs(files));
        check("plan: an edit that deletes most of a file on purpose is allowed", only(plan) != null && only(plan).problem == null, plan.size() + " " + (only(plan) == null ? "" : only(plan).problem));
        Map<String, String> locked = new HashMap<>(files);
        locked.put("app/src/Locked.kt", AiEdits.UNREADABLE);
        plan = AiEdits.plan(AiEdits.ops("File: app/src/Locked.kt\n```kotlin\nclass X\n```"), fs(locked));
        check("plan: a file that exists but cannot be read is never overwritten blindly", only(plan) != null && only(plan).problem != null && only(plan).problem.contains("could not be read"), "");
        plan = AiEdits.plan(AiEdits.ops("File: app/src/Created.kt\n```kotlin\nclass Created\n```"), fs(files));
        check("plan: a new file is new", only(plan) != null && only(plan).isNew() && only(plan).problem == null, "");
        plan = AiEdits.plan(Collections.<AiEdits.Op>emptyList(), fs(files));
        check("plan: nothing asked, nothing to do", plan.isEmpty(), "");

        System.out.println(passes + " passed, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
