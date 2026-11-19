package com.fahim.cloudide;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Works out which language plugins a project needs, by looking at its files with the "detect" rules of the plugin
 * catalog (extensions and marker files). Used when a project opens and before every local build, so the compiler
 * for a language is offered for download the moment the project uses that language.
 */
final class Needs {
    static final class Result {
        /** Languages the project uses, in catalog order. */
        final List<Packs.Pack> used = new ArrayList<>();
        /** Used but not installed, plus what they require, in the order they have to be installed. */
        final List<Packs.Pack> missing = new ArrayList<>();
        /** The core toolchain (ECJ, D8, aapt2, signer, android.jar) is needed and incomplete. */
        boolean coreMissing;
        boolean android, java;

        boolean any() { return coreMissing || !missing.isEmpty(); }

        int mb() {
            int n = coreMissing ? 40 : 0;
            for (Packs.Pack p : missing) n += p.mb;
            return n;
        }
    }

    private static final Set<String> PRUNE = new HashSet<>(Arrays.asList("node_modules", "build", "target", ".git", ".gradle", "dist", "vendor",
            ".dart_tool", "cloudide-out", ".cloudide", ".venv", "venv", "Pods", ".idea", "__pycache__", "assets", "res", "third_party"));
    private static final Set<String> FLUTTER_PLATFORMS = new HashSet<>(Arrays.asList("android", "ios", "linux", "macos", "windows", "web"));

    private Needs() { }

    static Result scan(Context c, File proj) {
        final Result r = new Result();
        final List<String> names = new ArrayList<>();
        walk(proj, "", 0, names);
        for (String n : names) {
            if (n.endsWith(".java")) r.java = true;
            if (n.equals("AndroidManifest.xml")) r.android = true;
        }
        for (Packs.Pack p : Packs.all(c)) {
            if (p.manual) continue;
            boolean used = false;
            for (String n : names) {
                if (p.owns(n)) {
                    used = true;
                    break;
                }
            }
            if (used) r.used.add(p);
        }
        for (Packs.Pack p : r.used) addMissing(c, p, r.missing);
        if (r.android || r.java || has(r, "kotlin")) {
            List<String> miss = Toolchain.missing(c);
            if (!r.android) miss.remove("aapt2"); // plain Java does not need aapt2
            r.coreMissing = !miss.isEmpty();
        }
        return r;
    }

    private static boolean has(Result r, String id) {
        for (Packs.Pack p : r.used) if (p.id.equals(id)) return true;
        return false;
    }

    private static void addMissing(Context c, Packs.Pack p, List<Packs.Pack> out) {
        if (Packs.installed(c, p.id)) return;
        for (String req : p.requires) {
            Packs.Pack q = Packs.find(c, req);
            if (q != null) addMissing(c, q, out);
        }
        if (p.meta) return; // nothing to download itself
        for (Packs.Pack o : out) if (o.id.equals(p.id)) return;
        out.add(p);
    }

    /** File names only, one entry per file; directories that never hold sources of the project are skipped. */
    private static void walk(File dir, String rel, int depth, List<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        boolean flutter = false;
        for (File k : kids) if (k.getName().equals("pubspec.yaml")) {
            try {
                flutter = Pattern.compile("^\\s*flutter:", Pattern.MULTILINE).matcher(Io.read(k)).find();
            } catch (IOException ignored) {
            }
        }
        for (File k : kids) {
            String n = k.getName();
            if (k.isDirectory()) {
                if (depth >= 4 || PRUNE.contains(n)) continue;
                if (flutter && FLUTTER_PLATFORMS.contains(n)) continue;
                String sub = rel.isEmpty() ? n : rel + "/" + n;
                // NDK folders of an Android app are not built by the local engine
                if (sub.contains("src/main") && (n.equals("cpp") || n.equals("jni"))) continue;
                walk(k, sub, depth + 1, out);
            } else {
                out.add(n);
            }
        }
    }

    /** "Rust, Go" for a dialog. */
    static String names(List<Packs.Pack> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(i == l.size() - 1 ? " and " : ", ");
            sb.append(l.get(i).label);
        }
        return sb.toString();
    }

    /** Dialog text: what the project uses, what is missing and how big the download is. */
    static String message(Result r) {
        StringBuilder sb = new StringBuilder();
        if (!r.used.isEmpty()) sb.append("This project uses ").append(names(r.used)).append(".\n\n");
        else if (r.android || r.java) sb.append("This project is ").append(r.android ? "an Android app" : "a Java project").append(".\n\n");
        sb.append("Not installed yet:\n");
        if (r.coreMissing) sb.append("  • Java and Android build tools (aapt2, ECJ, D8, signer), about 40 MB\n");
        for (Packs.Pack p : r.missing) sb.append("  • ").append(p.label).append(", about ").append(p.mb).append(" MB\n");
        sb.append("\nDownload size about ").append(r.mb()).append(" MB. Wi-Fi is recommended. They are stored on the device, so this happens once.");
        return sb.toString();
    }

    static String skipKey(File proj) { return "plug_skip_" + proj.getName(); }

    /** Ids the person said "not now, don't ask again" to for this project. */
    static Set<String> skipped(Context c, File proj) {
        Set<String> s = new HashSet<>();
        String v = Prefs.str(c, skipKey(proj));
        if (!v.isEmpty()) s.addAll(Arrays.asList(v.split(",")));
        return s;
    }

    static void skip(Context c, File proj, Result r) {
        Set<String> s = skipped(c, proj);
        for (Packs.Pack p : r.missing) s.add(p.id);
        if (r.coreMissing) s.add("core");
        Prefs.put(c, skipKey(proj), Toolchain.join(new ArrayList<>(s), ","));
    }

    /** True when everything that is missing was already declined for this project. */
    static boolean allSkipped(Context c, File proj, Result r) {
        Set<String> s = skipped(c, proj);
        if (r.coreMissing && !s.contains("core")) return false;
        for (Packs.Pack p : r.missing) if (!s.contains(p.id)) return false;
        return true;
    }

    /** Downloads the core toolchain (if needed) and every missing plugin. Throws on the first failure. */
    static void installAll(Context c, Result r, Gh.Log log) throws IOException {
        if (r.coreMissing) {
            String url = Prefs.str(c, "tc_url");
            if (url.isEmpty()) url = Packs.baseUrl(c) + "toolchain-arm64.zip";
            log.log("downloading the core toolchain");
            Toolchain.download(c, url, log);
        }
        for (Packs.Pack p : r.missing) {
            log.log("--- " + p.label);
            Packs.installWithDeps(c, p.id, log);
        }
    }
}
