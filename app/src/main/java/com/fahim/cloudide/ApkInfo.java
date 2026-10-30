package com.fahim.cloudide;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only analysis of an APK (or AAR/ZIP): size breakdown, ABIs, signature schemes, entry access. */
final class ApkInfo {
    static final class Entry {
        final String name;
        final long size, packed;

        Entry(String name, long size, long packed) {
            this.name = name;
            this.size = size;
            this.packed = packed;
        }
    }

    final File file;
    final List<Entry> entries = new ArrayList<>();
    final TreeSet<String> abis = new TreeSet<>();
    long dexBytes, libBytes, resBytes, assetBytes, otherBytes;
    int dexCount, fileCount;
    boolean v1, v2plus;
    boolean hasManifest;

    private ApkInfo(File f) { file = f; }

    static ApkInfo read(File f) throws IOException {
        ApkInfo a = new ApkInfo(f);
        boolean sf = false, key = false;
        try (ZipFile z = new ZipFile(f)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName();
                long size = Math.max(0, e.getSize()), packed = Math.max(0, e.getCompressedSize());
                a.entries.add(new Entry(n, size, packed));
                a.fileCount++;
                String l = n.toLowerCase(Locale.ROOT);
                if (l.matches("classes\\d*\\.dex")) {
                    a.dexCount++;
                    a.dexBytes += packed;
                } else if (l.startsWith("lib/")) {
                    a.libBytes += packed;
                    String[] p = n.split("/");
                    if (p.length >= 3) a.abis.add(p[1]);
                } else if (l.startsWith("res/") || l.equals("resources.arsc")) {
                    a.resBytes += packed;
                } else if (l.startsWith("assets/")) {
                    a.assetBytes += packed;
                } else {
                    a.otherBytes += packed;
                }
                if (l.equals("androidmanifest.xml")) a.hasManifest = true;
                if (l.startsWith("meta-inf/") && l.endsWith(".sf")) sf = true;
                if (l.startsWith("meta-inf/") && (l.endsWith(".rsa") || l.endsWith(".dsa") || l.endsWith(".ec"))) key = true;
            }
        }
        a.v1 = sf && key;
        a.v2plus = hasSigningBlock(f);
        Collections.sort(a.entries, (x, y) -> Long.compare(y.packed, x.packed));
        return a;
    }

    /** APK Signature Scheme v2/v3 stores a block whose footer magic sits right before the central directory. */
    static boolean hasSigningBlock(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            int max = (int) Math.min(len, 22 + 65535);
            byte[] tail = new byte[max];
            r.seek(len - max);
            r.readFully(tail);
            for (int i = max - 22; i >= 0; i--) {
                if (tail[i] == 0x50 && tail[i + 1] == 0x4b && tail[i + 2] == 0x05 && tail[i + 3] == 0x06) {
                    long cd = (tail[i + 16] & 0xffL) | (tail[i + 17] & 0xffL) << 8
                            | (tail[i + 18] & 0xffL) << 16 | (tail[i + 19] & 0xffL) << 24;
                    if (cd < 32) return false;
                    r.seek(cd - 16);
                    byte[] m = new byte[16];
                    r.readFully(m);
                    return new String(m, "ISO-8859-1").equals("APK Sig Block 42");
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        return String.format(Locale.US, "%.2f MB", b / 1048576.0);
    }

    String signatures() {
        if (v2plus && v1) return "v1 + v2/v3";
        if (v2plus) return "v2/v3";
        if (v1) return "v1 (jar) only";
        return "unsigned";
    }

    String summary() {
        StringBuilder s = new StringBuilder();
        s.append("File size: ").append(size(file.length())).append('\n');
        s.append("Files inside: ").append(fileCount).append('\n');
        s.append("Signatures: ").append(signatures()).append('\n');
        s.append("DEX: ").append(dexCount).append(dexCount == 1 ? " file, " : " files, ").append(size(dexBytes)).append('\n');
        s.append("Native libraries: ").append(abis.isEmpty() ? "none" : abis.toString().replaceAll("[\\[\\]]", "") + " (" + size(libBytes) + ")").append('\n');
        s.append("Resources: ").append(size(resBytes)).append('\n');
        s.append("Assets: ").append(size(assetBytes)).append('\n');
        s.append("Other: ").append(size(otherBytes));
        return s.toString();
    }

    /** Copies one entry to out. Only the entry's bytes are written, to a path the caller chooses. */
    void extract(String name, File out) throws IOException {
        try (ZipFile z = new ZipFile(file)) {
            ZipEntry e = z.getEntry(name);
            if (e == null) throw new IOException("Entry not found: " + name);
            if (out.getParentFile() != null) out.getParentFile().mkdirs();
            try (InputStream in = z.getInputStream(e); FileOutputStream o = new FileOutputStream(out)) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
            }
        }
    }

    /** First max bytes of an entry as text, or null when it looks binary. */
    String text(String name, int max) throws IOException {
        try (ZipFile z = new ZipFile(file)) {
            ZipEntry e = z.getEntry(name);
            if (e == null) throw new IOException("Entry not found: " + name);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = z.getInputStream(e)) {
                byte[] b = new byte[4096];
                int n;
                while (bo.size() < max && (n = in.read(b)) > 0) bo.write(b, 0, Math.min(n, max - bo.size()));
            }
            byte[] d = bo.toByteArray();
            for (int i = 0; i < Math.min(d.length, 1024); i++) if (d[i] == 0) return null;
            return new String(d, "UTF-8");
        }
    }

    /** Safe file name for an extracted entry (no folders). */
    static String baseName(String entry) {
        int i = entry.lastIndexOf('/');
        String n = i >= 0 ? entry.substring(i + 1) : entry;
        return n.isEmpty() ? "entry" : n;
    }
}
