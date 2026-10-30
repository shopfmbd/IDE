package com.fahim.cloudide;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** A small command runner for the IDE terminal: runs "sh -c" in a working folder, keeps the folder between commands. */
final class Shell {
    interface Out { void text(String s); }

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
    static final int MAX_CHARS = 200000;

    final File home;
    volatile File cwd;
    /** Extra environment (PATH with the language packs, HOME, ...). */
    volatile java.util.Map<String, String> env;
    private volatile Process proc;
    private volatile boolean killed;

    Shell(File home) {
        this.home = home;
        this.cwd = home;
    }

    boolean running() { return proc != null; }

    /** Stops the running command (Android 7 has no destroyForcibly, so this is destroy() only). */
    void kill() {
        killed = true;
        Process p = proc;
        if (p != null) p.destroy();
    }

    /** "~/app" style path of the current folder, for the prompt. */
    String where() {
        try {
            String h = home.getCanonicalPath(), c = cwd.getCanonicalPath();
            if (c.equals(h)) return "~";
            if (c.startsWith(h + "/")) return "~" + c.substring(h.length());
            return c;
        } catch (IOException e) {
            return cwd.getPath();
        }
    }

    /** Resolves a path typed after "cd". */
    File resolve(String arg) throws IOException {
        String a = arg.trim();
        if (a.length() >= 2 && (a.startsWith("\"") && a.endsWith("\"") || a.startsWith("'") && a.endsWith("'")))
            a = a.substring(1, a.length() - 1);
        File f;
        if (a.isEmpty() || a.equals("~")) f = home;
        else if (a.startsWith("~/")) f = new File(home, a.substring(2));
        else if (a.startsWith("/")) f = new File(a);
        else f = new File(cwd, a);
        return f.getCanonicalFile();
    }

    /** Runs one command line and returns its exit code. Blocks until it ends. Output goes to out as it arrives. */
    int run(String line, Out out) {
        String cmd = line.trim();
        if (cmd.isEmpty()) return 0;
        killed = false;

        // "cd" has to change this object's folder, so it is handled here, not by a child process
        if (cmd.equals("cd") || (cmd.startsWith("cd ") && cmd.indexOf("&&") < 0 && cmd.indexOf(';') < 0 && cmd.indexOf('|') < 0)) {
            try {
                File d = resolve(cmd.length() > 2 ? cmd.substring(3) : "");
                if (!d.isDirectory()) {
                    out.text("cd: not a folder: " + cmd.substring(Math.min(3, cmd.length())).trim() + "\n");
                    return 1;
                }
                cwd = d;
                return 0;
            } catch (IOException e) {
                out.text("cd: " + e.getMessage() + "\n");
                return 1;
            }
        }

        ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
        pb.directory(cwd);
        pb.redirectErrorStream(true);
        if (env != null) pb.environment().putAll(env);
        pb.environment().put("HOME", env != null && env.containsKey("HOME") ? env.get("HOME") : home.getPath());
        pb.environment().put("PWD", cwd.getPath());
        pb.environment().put("TERM", "dumb");
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            out.text("cannot run: " + e.getMessage() + "\n");
            return 127;
        }
        proc = p;
        try {
            try {
                p.getOutputStream().close(); // no keyboard input for the program
            } catch (IOException ignored) {
            }
            // Read on a helper thread: a child process (sleep, "cmd &") can hold the pipe open after the shell is gone
            final InputStream in = p.getInputStream();
            final Process fp = p;
            final int[] total = {0};
            Thread reader = new Thread(() -> {
                try {
                    Reader r = new InputStreamReader(in, "UTF-8");
                    char[] buf = new char[2048];
                    int n;
                    while ((n = r.read(buf)) > 0) {
                        total[0] += n;
                        out.text(ANSI.matcher(new String(buf, 0, n)).replaceAll(""));
                        if (total[0] > MAX_CHARS) {
                            out.text("\n[output cut off after " + MAX_CHARS + " characters]\n");
                            fp.destroy();
                            break;
                        }
                    }
                } catch (IOException ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();
            int code = p.waitFor();
            reader.join(killed ? 300 : 1500); // let the last output arrive, but never hang on a leftover child
            try {
                in.close();
            } catch (IOException ignored) {
            }
            if (killed) out.text("\n[stopped]\n");
            return code;
        } catch (InterruptedException e) {
            p.destroy();
            return 130;
        } finally {
            proc = null;
        }
    }

    /** Splits "build assembleRelease  test" into words, the way the terminal builtins read arguments. */
    static List<String> words(String s) {
        List<String> w = new ArrayList<>();
        for (String x : s.trim().split("\\s+")) if (!x.isEmpty()) w.add(x);
        return w;
    }
}
