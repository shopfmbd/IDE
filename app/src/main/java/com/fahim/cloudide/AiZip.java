package com.fahim.cloudide;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs a whole project into one .zip for the AI assistant. Plain JDK (no Android), so it is easy to test.
 * Left out, the same way the text attachment does it: build output, .git and other tool folders, binary files,
 * credentials (keystores, .env ...). Text files go in with key-like strings masked ({@link AiProtocol#redact}).
 */
final class AiZip {
    private AiZip() { }

    /** Largest text file packed, and the largest zip built (the Files API accepts up to 500 MB, a phone should not try that). */
    static final long MAX_FILE = 2L * 1024 * 1024, MAX_ZIP = 30L * 1024 * 1024;

    static final class Result {
        File zip;
        int files, skipped;
        long bytes;
        boolean truncated;

        String summary() {
            String s = files + (files == 1 ? " file, " : " files, ") + size(bytes);
            if (skipped > 0) s += ", " + skipped + " left out";
            if (truncated) s += ", size limit reached";
            return s;
        }
    }

    /** Writes {@code out} (replacing it) with the project's files under a single top folder named like the project. */
    static Result build(File proj, File out) throws IOException {
        Result r = new Result();
        r.zip = out;
        String top = proj.getName().isEmpty() ? "project" : proj.getName();
        try (ZipOutputStream zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            zo.setLevel(6);
            add(zo, proj, proj, top, r, out);
        }
        r.bytes = out.length();
        return r;
    }

    private static void add(ZipOutputStream zo, File root, File dir, String top, Result r, File self) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        Arrays.sort(kids);
        for (File f : kids) {
            if (r.truncated) return;
            if (f.equals(self) || Files.isSymbolicLink(f.toPath())) continue;
            String n = f.getName();
            if (f.isDirectory()) {
                if (AiProtocol.skippedDir(n)) { r.skipped++; continue; }
                add(zo, root, f, top, r, self);
                continue;
            }
            String rel = root.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/');
            if (AiProtocol.binaryName(n) || AiProtocol.isSecretFile(rel) || AiProtocol.skipForCode(rel) || f.length() > MAX_FILE) {
                r.skipped++;
                continue;
            }
            byte[] raw = Files.readAllBytes(f.toPath());
            if (looksBinary(raw)) { r.skipped++; continue; }
            byte[] data = AiProtocol.redact(new String(raw, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            if (r.bytes + data.length > MAX_ZIP) { r.truncated = true; return; }
            ZipEntry e = new ZipEntry(top + "/" + rel);
            zo.putNextEntry(e);
            zo.write(data);
            zo.closeEntry();
            r.files++;
            r.bytes += data.length; // uncompressed size, so the limit is about what the model has to unpack
        }
    }

    private static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", b / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
    }

    private static boolean looksBinary(byte[] b) {
        int n = Math.min(b.length, 4096);
        for (int i = 0; i < n; i++) if (b[i] == 0) return true;
        return false;
    }
}
