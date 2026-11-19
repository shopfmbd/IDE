package com.fahim.cloudide;

import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Plain-JDK test for the sandbox: file safety, zip handling, and the HTTP client against the real sandbox-server/server.py. */
public class SandboxTest {
    static int fails = 0, passes = 0;

    static void check(String n, boolean ok, String d) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + n + "  -> " + d); }
    }

    static boolean throwsIae(Callable c) {
        try { c.run(); return false; } catch (IllegalArgumentException e) { return true; } catch (Exception e) { return false; }
    }

    interface Callable { void run() throws Exception; }

    static File tmp(String n) throws IOException { return Files.createTempDirectory(n).toFile(); }

    static File zipOf(File out, String[][] entries) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out))) {
            for (String[] e : entries) {
                z.putNextEntry(new ZipEntry(e[0]));
                z.write(e[1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return out;
    }

    public static void main(String[] a) throws Exception {
        files();
        server();
        System.out.println(passes + " passed, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }

    static void files() throws Exception {
        File root = tmp("sbx");
        check("resolve inside", SandboxFiles.resolve(root, "a/b.txt").getPath().startsWith(root.getCanonicalPath()), "x");
        check("resolve empty is root", SandboxFiles.resolve(root, "").equals(root.getCanonicalFile()), "x");
        check("resolve ..", throwsIae(() -> SandboxFiles.resolve(root, "../x")), "x");
        check("resolve a/../..", throwsIae(() -> SandboxFiles.resolve(root, "a/../../x")), "x");
        check("resolve absolute", throwsIae(() -> SandboxFiles.resolve(root, "/etc/passwd")), "x");
        check("resolve windows drive", throwsIae(() -> SandboxFiles.resolve(root, "C:/x")), "x");
        File link = new File(root, "lnk");
        try {
            Files.createSymbolicLink(link.toPath(), tmp("outside").toPath());
            check("resolve symlink out", throwsIae(() -> SandboxFiles.resolve(root, "lnk/f.txt")), "x");
        } catch (UnsupportedOperationException | IOException e) { /* no symlinks here */ }

        // unzip: a good entry lands, evil ones do not
        File z = zipOf(new File(root, "t.zip"), new String[][]{{"ok/a.txt", "hello"}, {"../../evil.txt", "x"}, {"/abs.txt", "x"}, {"d/e/f.txt", "deep"}});
        File dest = new File(root, "out");
        SandboxFiles.Unzipped u = SandboxFiles.unzip(z, dest);
        check("unzip files", u.files == 2 && u.skipped == 2, u.files + "/" + u.skipped);
        check("unzip content", new String(Files.readAllBytes(new File(dest, "ok/a.txt").toPath()), StandardCharsets.UTF_8).equals("hello"), "x");
        check("unzip no escape", !new File(root.getParentFile(), "evil.txt").exists() && !new File(dest.getParentFile().getParentFile(), "evil.txt").exists(), "x");
        check("unzip summary", u.summary().contains("2 files") && u.summary().contains("skipped"), u.summary());

        // zip round trip
        File back = new File(root, "back.zip");
        int n = SandboxFiles.zip(dest, back);
        check("zip count", n == 2, "n=" + n);
        File dest2 = new File(root, "out2");
        check("zip roundtrip", SandboxFiles.unzip(back, dest2).files == 2 && new File(dest2, "d/e/f.txt").isFile(), "x");

        // not a zip
        File bad = new File(root, "bad.zip");
        Files.write(bad.toPath(), "nope".getBytes());
        boolean threw = false;
        try { SandboxFiles.unzip(bad, new File(root, "x")); } catch (IOException e) { threw = true; }
        check("unzip bad zip", threw, "x");

        // listing, reading
        String l = SandboxFiles.list(root, dest, 3, 50);
        check("list tree", l.contains("ok/") && l.contains("a.txt") && l.contains("d/"), l);
        check("list empty", SandboxFiles.list(root, new File(root, "nothing"), 2, 10).equals("(empty)\n"), "x");
        check("read text", SandboxFiles.readText(new File(dest, "ok/a.txt"), 100).equals("hello"), "x");
        File bin = new File(root, "b.bin");
        Files.write(bin.toPath(), new byte[]{1, 2, 0, 3});
        check("read binary refused", throwsIae(() -> SandboxFiles.readText(bin, 100)), "x");
        File big = new File(root, "big.txt");
        Files.write(big.toPath(), new String(new char[5000]).replace('\0', 'x').getBytes());
        String cut = SandboxFiles.readText(big, 1000);
        check("read cut", cut.startsWith("xxx") && cut.contains("[cut:"), cut.substring(Math.max(0, cut.length() - 40)));

        // names and commands
        check("runLine python", SandboxFiles.runLine("Python", "snippets/main.py").equals("python3 'snippets/main.py'"), "x");
        check("runLine quote", SandboxFiles.runLine("bash", "a'b").contains("'a'\\''b'"), SandboxFiles.runLine("bash", "a'b"));
        check("runLine unknown", SandboxFiles.runLine("cobol", "x") == null, "x");
        check("codeName", "Main.java".equals(SandboxFiles.codeName("java")) && SandboxFiles.codeName("zzz") == null, "x");
        check("folderFor zip", SandboxFiles.folderFor("My App (1).zip").equals("My_App__1_"), SandboxFiles.folderFor("My App (1).zip"));
        check("folderFor path", SandboxFiles.folderFor("../../x/evil.zip").equals("evil"), SandboxFiles.folderFor("../../x/evil.zip"));
        check("folderFor dots", !SandboxFiles.folderFor("...zip").startsWith("."), SandboxFiles.folderFor("...zip"));
        check("safeName", SandboxFiles.safeName("../a b$.txt").equals("a b_.txt"), SandboxFiles.safeName("../a b$.txt"));
        check("safeName empty", SandboxFiles.safeName("").equals("file") && SandboxFiles.safeName(null).equals("file"), "x");
        check("size", SandboxFiles.size(2048).equals("2.0 KB") && SandboxFiles.size(5).equals("5 B"), "x");

        SandboxFiles.deleteTree(root);
        check("deleteTree", !root.exists(), "x");

        // client address checks
        check("checkUrl empty", SandboxClient.checkUrl("") != null, "x");
        check("checkUrl scheme", SandboxClient.checkUrl("ftp://x") != null, "x");
        check("checkUrl ok", SandboxClient.checkUrl("https://x.example") == null && SandboxClient.checkUrl("http://10.0.0.2:8080") == null, "x");

        // the action layer knows the sandbox
        check("known actions", AiActions.known("sandbox_run") && AiActions.known("sandbox_export") && !AiActions.known("sandbox_nope"), "x");
        for (String nm : AiActions.SANDBOX) check("known " + nm, AiActions.isSandbox(nm) && AiActions.known(nm), nm);
        String rules = AiActions.rules("full", "remote");
        check("rules mention sandbox", rules.contains("sandbox_run") && rules.contains("own Linux server"), "x");
        check("rules local", AiActions.rules("full", "local").contains("on this phone"), "x");
        check("rules off", !AiActions.rules("full", "off").contains("sandbox_run") && !AiActions.rules("full").contains("sandbox_run"), "x");
        AiActions.Parsed p = AiActions.parse("```cloudide-action\n[{\"action\":\"sandbox_run\",\"command\":\"ls\"},{\"action\":\"sandbox_code\",\"language\":\"python\",\"code\":\"print(1)\"}]\n```");
        check("parse sandbox", p.actions.size() == 2 && p.actions.get(0).describe().startsWith("sandbox run") && p.actions.get(1).describe().contains("python"), p.actions.size() + " " + p.problems);
    }

    static boolean has(String cmd) {
        try {
            Process pr = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
            pr.getInputStream().readAllBytes();
            return pr.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    static void server() throws Exception {
        File script = new File("../../sandbox-server/server.py");
        if (!script.isFile() || !has("python3")) {
            System.out.println("skipped the server test (no python3 or no sandbox-server/server.py)");
            return;
        }
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        File work = tmp("sbxsrv");
        ProcessBuilder pb = new ProcessBuilder("python3", script.getPath());
        pb.environment().put("SANDBOX_TOKEN", "test-token-0123456789");
        pb.environment().put("SANDBOX_ROOT", work.getPath());
        pb.environment().put("PORT", String.valueOf(port));
        pb.environment().put("MAX_TIMEOUT", "20");
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(work, "server.log"));
        Process srv = pb.start();
        try {
            String base = "http://127.0.0.1:" + port;
            SandboxClient c = new SandboxClient(base, "test-token-0123456789", "sessiontest");
            String health = null;
            for (int i = 0; i < 50 && health == null; i++) {
                try { health = c.health(); } catch (IOException e) { Thread.sleep(100); }
            }
            check("server health", health != null && health.contains("sandbox"), String.valueOf(health));

            boolean refused = false;
            try { new SandboxClient(base, "wrong-token", "s").health(); } catch (IOException e) { refused = e.getMessage().contains("refused the token"); }
            check("server refuses a bad token", refused, "x");

            SandboxFiles.Exec ex = c.exec("echo hi && python3 -c 'print(6*7)'", "", 20);
            check("exec output", ex.ok() && ex.out.contains("hi") && ex.out.contains("42"), ex.out);
            ex = c.exec("exit 3", "", 20);
            check("exec exit code", ex.code == 3 && !ex.ok(), "code " + ex.code);
            ex = c.exec("echo oops >&2; false", "", 20);
            check("exec stderr merged", ex.out.contains("oops") && !ex.ok(), ex.out);
            long t0 = System.currentTimeMillis();
            ex = c.exec("sleep 30", "", 1);
            check("exec timeout", ex.timedOut && System.currentTimeMillis() - t0 < 10000, "timedOut=" + ex.timedOut);
            boolean badCwd = false;
            try { c.exec("ls", "../..", 5); } catch (IOException e) { badCwd = true; }
            check("exec cwd outside refused", badCwd, "x");

            c.write("notes/a.txt", "hello world");
            check("write+read", c.read("notes/a.txt", 1000).equals("hello world"), "x");
            check("list", c.list("", 2).contains("notes/"), c.list("", 2));
            boolean escaped = false;
            try { c.read("../../etc/passwd", 100); } catch (IOException e) { escaped = true; }
            check("read outside refused", escaped, "x");

            File zip = zipOf(new File(work, "up.zip"), new String[][]{{"proj/main.py", "print('from zip')"}, {"proj/data/x.txt", "x"}, {"../../evil.txt", "bad"}});
            c.upload("uploads/up.zip", zip);
            String sum = c.unzip("uploads/up.zip", "uploads/up");
            check("unzip summary", sum.contains("2 files") && sum.contains("skipped"), sum);
            ex = c.exec("python3 uploads/up/proj/main.py", "", 20);
            check("run code from zip", ex.ok() && ex.out.contains("from zip"), ex.out);
            check("evil entry not extracted", !new File(work, "evil.txt").exists() && !new File(work, "sessiontest/evil.txt").exists(), "x");

            File dl = new File(work, "dl.zip");
            c.download("uploads/up/proj", dl);
            File un = new File(work, "dl");
            check("download folder as zip", SandboxFiles.unzip(dl, un).files == 2 && new File(un, "main.py").isFile(), "x");
            File one = new File(work, "one.txt");
            c.download("notes/a.txt", one);
            check("download file", new String(Files.readAllBytes(one.toPath())).equals("hello world"), "x");
            boolean missing = false;
            try { c.download("nope", new File(work, "n")); } catch (IOException e) { missing = e.getMessage().contains("404"); }
            check("download missing", missing, "x");

            c.reset();
            check("reset empties", !c.list("", 3).contains("notes"), c.list("", 3));
            // a second session does not see the first one's files
            SandboxClient other = new SandboxClient(base, "test-token-0123456789", "othersession");
            other.write("only-mine.txt", "x");
            check("sessions are separate", !c.list("", 2).contains("only-mine"), "x");
        } finally {
            srv.destroy();
            srv.waitFor();
            SandboxFiles.deleteTree(work);
        }
    }
}
