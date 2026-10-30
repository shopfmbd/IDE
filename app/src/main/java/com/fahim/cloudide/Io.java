package com.fahim.cloudide;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class Io {
    private static final int BUF = 16 * 1024;

    /** Reads the stream to the end and closes it, also when reading fails. */
    static byte[] bytes(InputStream in) throws IOException {
        try (InputStream src = in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(Math.max(BUF, src.available()));
            byte[] b = new byte[BUF];
            int n;
            while ((n = src.read(b)) != -1) o.write(b, 0, n);
            return o.toByteArray();
        }
    }

    static String read(File f) throws IOException {
        return new String(bytes(new FileInputStream(f)), StandardCharsets.UTF_8);
    }

    /** Copies in to out without holding the whole content in memory. Does not close either stream. */
    static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[BUF];
        int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
    }

    static void write(File f, String s) throws IOException {
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** True when f is a symbolic link (it is then removed, never followed). */
    static boolean isLink(File f) {
        try {
            return java.nio.file.Files.isSymbolicLink(f.toPath());
        } catch (Exception e) {
            return false;
        }
    }

    /** Deletes a file or folder tree. A symbolic link is removed itself; the folder it points to is left alone. */
    static void delete(File f) {
        if (!isLink(f)) {
            File[] k = f.listFiles();
            if (k != null) for (File c : k) delete(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** True when child is root itself or lies inside it (symbolic links and ".." resolved). */
    static boolean inside(File root, File child) {
        try {
            String r = root.getCanonicalPath(), c = child.getCanonicalPath();
            return c.equals(r) || c.startsWith(r.endsWith(File.separator) ? r : r + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    /** A name that is free in dir: "a.txt" becomes "a_copy.txt", then "a_copy2.txt", and so on. */
    static File freeCopy(File dir, String name) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        File f = new File(dir, stem + "_copy" + ext);
        for (int i = 2; f.exists(); i++) f = new File(dir, stem + "_copy" + i + ext);
        return f;
    }

    static void copy(File s, File d) throws IOException {
        if (s.isDirectory()) {
            if (isLink(s)) return; // a link to a folder could point back into the tree being copied
            d.mkdirs();
            File[] k = s.listFiles();
            if (k != null) for (File f : k) copy(f, new File(d, f.getName()));
        } else {
            if (d.getParentFile() != null) d.getParentFile().mkdirs();
            try (InputStream in = new FileInputStream(s); FileOutputStream o = new FileOutputStream(d)) {
                pipe(in, o);
            }
        }
    }

    static long size(File f) {
        if (f.isFile()) return f.length();
        if (isLink(f)) return 0;
        long n = 0;
        File[] k = f.listFiles();
        if (k != null) for (File c : k) n += size(c);
        return n;
    }

    static String rel(File root, File f) {
        String r = root.getAbsolutePath(), p = f.getAbsolutePath();
        if (p.equals(r)) return "";
        return p.startsWith(r + File.separator) ? p.substring(r.length() + 1) : p;
    }

    static boolean isBinary(String n) {
        return n.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpg|jpeg|gif|webp|ico|jar|aar|apk|aab|zip|so|dex|ttf|otf|keystore|jks|class|mp3|mp4|ogg|pdf|bin)");
    }

    static boolean skipDir(String n) {
        return n.equals("build") || n.equals(".gradle") || n.equals(".git") || n.equals(".idea")
                || n.equals(".cloudide") || n.equals("node_modules");
    }

    static void collect(File dir, List<File> out) {
        File[] k = dir.listFiles();
        if (k == null) return;
        Arrays.sort(k);
        for (File f : k) {
            String n = f.getName();
            if (f.isDirectory()) {
                if (!skipDir(n) && !isLink(f)) collect(f, out);
            } else if (f.length() < 25L * 1024 * 1024 && !n.equals("local.properties") && !n.endsWith(".iml")) {
                out.add(f);
            }
        }
    }

    static List<File> projectFiles(File root) {
        List<File> l = new ArrayList<>();
        collect(root, l);
        return l;
    }

    /** Git blob SHA-1 of a file, identical to what GitHub stores. */
    static String sha(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(("blob " + f.length() + "\0").getBytes(StandardCharsets.UTF_8));
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[BUF];
            int n;
            while ((n = in.read(b)) != -1) md.update(b, 0, n);
        }
        final char[] hex = "0123456789abcdef".toCharArray();
        byte[] d = md.digest();
        char[] out = new char[d.length * 2];
        for (int i = 0; i < d.length; i++) {
            out[i * 2] = hex[(d[i] >> 4) & 0xF];
            out[i * 2 + 1] = hex[d[i] & 0xF];
        }
        return new String(out);
    }

    static void template(Context c, String dir, String rel, File out, Map<String, String> vars, String pkg) throws IOException {
        String full = rel.isEmpty() ? dir : dir + "/" + rel;
        String[] kids = c.getAssets().list(full);
        if (kids != null && kids.length > 0) {
            for (String k : kids) template(c, dir, rel.isEmpty() ? k : rel + "/" + k, out, vars, pkg);
            return;
        }
        String s = new String(bytes(c.getAssets().open(full)), StandardCharsets.UTF_8);
        for (Map.Entry<String, String> e : vars.entrySet()) s = s.replace(e.getKey(), e.getValue());
        String path = rel.replace("PKGPATH", pkg.replace('.', '/'));
        // "dot_gitignore" -> ".gitignore": only a file or folder NAME that starts with dot_ is renamed
        StringBuilder fixed = new StringBuilder();
        for (String part : path.split("/")) {
            if (fixed.length() > 0) fixed.append('/');
            fixed.append(part.startsWith("dot_") ? "." + part.substring(4) : part);
        }
        write(new File(out, fixed.toString()), s);
    }

    /** Extracts a GitHub zipball into out, dropping the top-level folder. Returns the relative paths of the files written. */
    static List<String> unzipStrip(byte[] zip, File out) throws IOException {
        List<String> written = new ArrayList<>();
        out.mkdirs();
        String base = out.getCanonicalPath() + File.separator;
        try (ZipInputStream zi = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zi.getNextEntry()) != null) {
                String n = e.getName();
                int i = n.indexOf('/');
                if (i < 0) continue;
                n = n.substring(i + 1);
                if (n.isEmpty()) continue;
                File f = new File(out, n);
                if (!f.getCanonicalPath().startsWith(base)) continue; // zip-slip guard
                if (e.isDirectory()) {
                    f.mkdirs();
                    continue;
                }
                f.getParentFile().mkdirs();
                try (FileOutputStream o = new FileOutputStream(f)) {
                    pipe(zi, o);
                }
                written.add(n);
            }
        }
        return written;
    }
}
