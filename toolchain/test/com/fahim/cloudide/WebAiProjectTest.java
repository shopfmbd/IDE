package com.fahim.cloudide;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Plain-JDK test for the Web AI screen's file logic and page scripts: context, reply cleaning, planning, writing, undo. */
public class WebAiProjectTest {
    static int fails = 0, passes = 0;

    static void check(String n, boolean ok, String d) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + n + "  -> " + d); }
    }

    static void put(File root, String rel, String text) throws Exception {
        File f = new File(root, rel);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    static String get(File root, String rel) throws Exception {
        return new String(Files.readAllBytes(new File(root, rel).toPath()), StandardCharsets.UTF_8);
    }

    public static void main(String[] a) throws Exception {
        File proj = Files.createTempDirectory("webai").toFile();
        put(proj, "app/build.gradle", "plugins { id 'com.android.application' }\n");
        put(proj, "app/src/main/java/A.kt", "class A {\n    fun f() = 1\n}\n");
        put(proj, "app/src/main/res/x.xml", "<a/>\n");
        put(proj, "local.properties", "sdk.dir=/x\n");
        put(proj, "app/logo.png", "binary");
        put(proj, "app/build/out.kt", "generated");
        put(proj, ".cloudide/old.txt", "tool");
        put(proj, "README.md", "# hi\n");

        // ---- context
        AiProtocol.Context c = WebAiProject.context(proj, "Add a dark mode switch", true);
        check("context: file list skips binaries, build output and tool folders", c.tree.contains("app/src/main/java/A.kt") && !c.tree.contains("app/logo.png") && !c.tree.contains("app/build/out.kt") && !c.tree.contains(".cloudide/old.txt"), c.tree.toString());
        boolean hasLocal = false;
        for (String[] f : c.code) if (f[0].equals("local.properties")) hasLocal = true;
        check("context: whole code includes sources but never credential files", c.code.size() >= 4 && !hasLocal && c.codeTotal == c.code.size(), c.code.size() + " of " + c.codeTotal);
        check("context: build files come first", c.code.get(0)[0].equals("app/build.gradle"), c.code.get(0)[0]);
        AiProtocol.Context tree = WebAiProject.context(proj, "", false);
        check("context: without source only the list is sent, and an empty task is replaced", tree.code.isEmpty() && !tree.task.isEmpty() && !tree.tree.isEmpty(), tree.task);
        String pr = WebAiProject.prompt(c);
        check("prompt: explains File lines, then the project, then the task", pr.indexOf("File: path") > 0 && pr.indexOf("File: path") < pr.indexOf("<project_code") && pr.indexOf("<project_code") < pr.indexOf("<task>") && pr.contains("dark mode"), pr);

        // ---- cleaning a reply read from a page
        String page = "Done. Updated the activity:\n\nFile: app/src/main/java/A.kt\n\nkotlinCopy\n\n```kotlin\nclass A {\n    fun f() = 2\n    fun g() = 3\n}\n```\n\nFile: app/src/main/res/y.xml\n\nxmlCopyDownload\n\n```xml\n<b/>\n```\n\nThat is all.\n";
        List<AiEdits.Change> plans = WebAiProject.plan(proj, page);
        check("reply: header words between the File line and the code are dropped, both files found", plans.size() == 2 && plans.get(0).rel.equals("app/src/main/java/A.kt") && plans.get(1).rel.equals("app/src/main/res/y.xml"), plans.size() + "");
        check("reply: replace vs new is told apart and the old text is kept", !plans.get(0).isNew() && plans.get(0).old.contains("= 1") && plans.get(1).isNew() && plans.get(0).problem == null, "");
        check("reply: a file keeps its trailing newline style", plans.get(0).text.endsWith("}\n") && plans.get(1).text.equals("<b/>\n"), plans.get(0).text);
        String within = "File: a/B.kt\n```kotlin\nval s = \"\"\"\nkotlin\nCopy\n\"\"\"\n```";
        check("reply: words that look like labels inside the code are untouched", WebAiProject.cleanReply(within).contains("kotlin\nCopy\n\"\"\""), WebAiProject.cleanReply(within));
        check("reply: nothing to write when there are no File lines", WebAiProject.plan(proj, "Just advice.\n```kotlin\nval a = 1\n```").isEmpty(), "");
        check("reply: an identical file is left out", WebAiProject.plan(proj, "File: README.md\n```md\n# hi\n```").isEmpty(), "");
        List<AiEdits.Change> bad = WebAiProject.plan(proj, "File: app/src/main/java/A.kt\n```kotlin\nclass A {\n    // ...\n}\n```");
        check("reply: a placeholder is flagged, not written", bad.size() == 1 && bad.get(0).problem != null, bad.size() + "");
        check("reply: an unfinished block (cut off reply) is never planned", WebAiProject.plan(proj, "File: a/C.kt\n```kotlin\nclass C {").isEmpty(), "");
        check("reply: the last mention of a path wins", WebAiProject.plan(proj, "File: n/N.kt\n```kotlin\nv1\n```\nFile: n/N.kt\n```kotlin\nv2\n```").get(0).text.startsWith("v2"), "");

        // ---- writing and undo
        List<String> written = WebAiProject.apply(proj, plans);
        check("apply: both files written", written.size() == 2 && get(proj, "app/src/main/java/A.kt").contains("= 2") && get(proj, "app/src/main/res/y.xml").equals("<b/>\n"), written.toString());
        check("apply: the backup batch lists what an undo would do", WebAiProject.lastBatch(proj) != null && WebAiProject.lastBatch(proj).size() == 2, "" + WebAiProject.lastBatch(proj));
        check("apply: the project tree for the next request does not include the backups", !WebAiProject.context(proj, "x", false).tree.toString().contains("web-ai-undo"), "");
        Thread.sleep(5);
        WebAiProject.apply(proj, WebAiProject.plan(proj, "File: README.md\n```md\n# changed\n```"));
        check("apply: a second batch is separate", get(proj, "README.md").equals("# changed\n"), "");
        List<String> undone = WebAiProject.undoLast(proj);
        check("undo: the newest batch only", undone.size() == 1 && get(proj, "README.md").equals("# hi\n") && get(proj, "app/src/main/java/A.kt").contains("= 2"), undone + "");
        undone = WebAiProject.undoLast(proj);
        check("undo: replaced file restored and created file removed", undone.size() == 2 && get(proj, "app/src/main/java/A.kt").contains("= 1") && !new File(proj, "app/src/main/res/y.xml").exists(), undone + "");
        check("undo: nothing left to undo", WebAiProject.undoLast(proj) == null && WebAiProject.lastBatch(proj) == null, "");
        List<String> none = WebAiProject.apply(proj, WebAiProject.plan(proj, "File: app/src/main/java/A.kt\n```kotlin\nclass A {\n    // ...\n}\n```"));
        check("apply: a flagged file is not written", none.isEmpty() && get(proj, "app/src/main/java/A.kt").contains("= 1"), "");
        for (int i = 0; i < 14; i++) {
            Thread.sleep(3);
            WebAiProject.apply(proj, WebAiProject.plan(proj, "File: README.md\n```md\n# v" + i + "\n```"));
        }
        File[] batches = new File(proj, WebAiProject.UNDO_DIR).listFiles();
        check("apply: only the newest ten backups are kept", batches != null && batches.length == 10, batches == null ? "none" : "" + batches.length);

        // ---- partial edits, new files and renames, on disk
        put(proj, "app/src/Util.kt", "object Util {\n    fun one() = 1\n    fun two() = 2\n}\n");
        String editReply = "Edit: app/src/Util.kt\n```kotlin\n<<<<<<< SEARCH\n    fun two() = 2\n=======\n    fun two() = 22\n    fun three() = 3\n>>>>>>> REPLACE\n```\n";
        List<AiEdits.Change> ep = WebAiProject.plan(proj, editReply);
        check("edit: a partial edit plans against the real file", ep.size() == 1 && ep.get(0).problem == null && ep.get(0).text.contains("two() = 22") && ep.get(0).text.contains("one() = 1"), ep.size() + "");
        Thread.sleep(3);
        WebAiProject.apply(proj, ep);
        check("edit: only the SEARCH part changed on disk", get(proj, "app/src/Util.kt").equals("object Util {\n    fun one() = 1\n    fun two() = 22\n    fun three() = 3\n}\n"), get(proj, "app/src/Util.kt"));
        List<String> bl = WebAiProject.lastBatch(proj);
        check("edit: the backup batch names the file", bl != null && bl.size() == 1 && bl.get(0).equals("app/src/Util.kt"), "" + bl);
        WebAiProject.undoLast(proj);
        check("edit: undo gives back the whole original file", get(proj, "app/src/Util.kt").equals("object Util {\n    fun one() = 1\n    fun two() = 2\n}\n"), get(proj, "app/src/Util.kt"));
        check("edit: an edit whose SEARCH is not in the file writes nothing", WebAiProject.apply(proj, WebAiProject.plan(proj, "Edit: app/src/Util.kt\n```\n<<<<<<< SEARCH\nfun nope() = 0\n=======\nx\n>>>>>>> REPLACE\n```")).isEmpty() && get(proj, "app/src/Util.kt").contains("two() = 2\n"), "");

        String moveReply = "Rename: app/src/Util.kt -> app/src/util/Helpers.kt\nEdit: app/src/util/Helpers.kt\n```kotlin\n<<<<<<< SEARCH\nobject Util {\n=======\nobject Helpers {\n>>>>>>> REPLACE\n```\nFile: app/src/util/Notes.md\n```md\n# notes\n```\n";
        List<AiEdits.Change> mp = WebAiProject.plan(proj, moveReply);
        check("rename: move + edit of the new path + a new file in a new folder", mp.size() == 2 && mp.get(0).isRename() && mp.get(0).text.startsWith("object Helpers {") && mp.get(1).isNew() && mp.get(0).problem == null && mp.get(1).problem == null, mp.size() + "");
        Thread.sleep(3);
        List<String> touched = WebAiProject.apply(proj, mp);
        check("rename: the old file is gone, the new one has the edited text, the new file exists", !new File(proj, "app/src/Util.kt").exists() && get(proj, "app/src/util/Helpers.kt").startsWith("object Helpers {") && get(proj, "app/src/util/Notes.md").equals("# notes\n"), "");
        check("rename: both paths are reported so the editor can refresh the old tab and the new one", touched.contains("app/src/Util.kt") && touched.contains("app/src/util/Helpers.kt") && touched.contains("app/src/util/Notes.md"), touched.toString());
        bl = WebAiProject.lastBatch(proj);
        check("rename: the backup batch describes the move and the new file", bl != null && bl.size() == 2 && bl.get(0).contains("Helpers.kt \u2192") && bl.get(1).contains("created"), "" + bl);
        check("rename: the project file list shows the new path", WebAiProject.context(proj, "x", false).tree.contains("app/src/util/Helpers.kt") && !WebAiProject.context(proj, "x", false).tree.contains("app/src/Util.kt"), "");
        touched = WebAiProject.undoLast(proj);
        check("rename: undo moves it back with the ORIGINAL text and removes the new file", new File(proj, "app/src/Util.kt").exists() && get(proj, "app/src/Util.kt").startsWith("object Util {") && !new File(proj, "app/src/util/Helpers.kt").exists() && !new File(proj, "app/src/util/Notes.md").exists(), "" + touched);
        check("rename: undo reports old and new paths", touched != null && touched.contains("app/src/Util.kt") && touched.contains("app/src/util/Helpers.kt"), "" + touched);
        check("rename: a rename onto a file that exists is planned as a problem and nothing moves", WebAiProject.apply(proj, WebAiProject.plan(proj, "Rename: app/src/Util.kt -> app/src/main/java/A.kt")).isEmpty() && new File(proj, "app/src/Util.kt").exists(), "");

        // ---- a link that points outside the project
        File outside = Files.createTempDirectory("outside").toFile();
        boolean linked = false;
        try {
            Files.createSymbolicLink(new File(proj, "evil").toPath(), outside.toPath());
            linked = true;
        } catch (Exception e) { /* no symlink support here */ }
        if (linked) {
            List<AiEdits.Change> evil = WebAiProject.plan(proj, "File: evil/steal.txt\n```\nx\n```");
            WebAiProject.apply(proj, evil);
            check("safety: a symlink out of the project is refused", evil.size() == 1 && evil.get(0).problem != null && !new File(outside, "steal.txt").exists(), evil.size() + "");
            check("safety: the file list does not follow the link", !WebAiProject.context(proj, "x", false).tree.toString().contains("evil/"), "");
        }

        // ---- page scripts
        check("js string: quotes, backslashes, line breaks and separators are escaped", WebAiScripts.jsString("a\"b\\c\nd\u2028e</script>").equals("\"a\\\"b\\\\c\\nd\\u2028e</script>\""), WebAiScripts.jsString("a\"b\\c\nd\u2028e"));
        check("js string: control characters become \\u escapes", WebAiScripts.jsString("a\u0001b").equals("\"a\\u0001b\""), "");
        check("sites: three known, unknown id falls back to the first, every site has selectors", WebAiScripts.SITES.length == 3 && WebAiScripts.byId("nope") == WebAiScripts.SITES[0] && WebAiScripts.byId("deepseek").url.startsWith("https://") && WebAiScripts.byId("chatgpt").replySelectors.length > 0, "");
        check("scripts: reading only touches the page, no network or app bridge", !WebAiScripts.readScript(WebAiScripts.SITES[0].replySelectors).contains("fetch(") && !WebAiScripts.insertScript("x").contains("XMLHttpRequest"), "");

        System.out.println(passes + " passed, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
