package com.fahim.cloudide;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Language plugins (a "pack" is the downloadable part of one): one download per language, kept on the phone, no Termux.
 *
 * What exists is described by plugins/catalog.json in the app assets, then plugins.json from the toolchain release
 * (new languages without an app update, see refreshCatalog), then any plugin.json that came with an imported zip.
 *
 *   jvm packs     dex jars that run inside this app's own process (Kotlin). They need no exec permission, so they
 *                 work on every Android version and every targetSdk. They land in the toolchain folder.
 *   native packs  Android arm64 programs (clang, rust, go, python, node, ruby, php, ...). They are unpacked into
 *                 files/usr, which is the prefix they were built for (see .github/workflows/packs.yml), and are
 *                 started as child processes. Android 10+ only allows that for apps with targetSdk 28 or lower.
 *   meta packs    nothing to download, only "requires" (TypeScript = Node's npm + tsc).
 *
 * The core pack (ECJ, D8, aapt2, signer, android.jar) is managed by Toolchain, not here.
 */
final class Packs {
    /** One language plugin. Built from a catalog entry. */
    static final class Pack {
        final String id, label, what, kind, termux;
        final boolean jvm, meta, manual, builtin;
        final int mb;
        final List<String> requires = new ArrayList<>(), exts = new ArrayList<>(), files = new ArrayList<>(), skipSuffix = new ArrayList<>(), run = new ArrayList<>();
        final List<String[]> probes = new ArrayList<>();
        final Map<String, String> env = new LinkedHashMap<>();
        final JSONObject build, diag;

        Pack(JSONObject j) {
            id = j.optString("id");
            label = j.optString("label", id);
            what = j.optString("what");
            kind = j.optString("kind", "native");
            termux = j.optString("termux");
            jvm = kind.equals("jvm");
            meta = kind.equals("meta");
            manual = j.optBoolean("manual", false);
            builtin = j.optBoolean("builtin", false);
            mb = j.optInt("mb", 0);
            strings(j.optJSONArray("requires"), requires);
            JSONObject d = j.optJSONObject("detect");
            if (d != null) {
                strings(d.optJSONArray("ext"), exts);
                strings(d.optJSONArray("files"), files);
                strings(d.optJSONArray("skipSuffix"), skipSuffix);
            }
            strings(j.optJSONArray("run"), run);
            JSONArray pr = j.optJSONArray("probes");
            if (pr != null) for (int i = 0; i < pr.length(); i++) {
                List<String> one = new ArrayList<>();
                strings(pr.optJSONArray(i), one);
                if (!one.isEmpty()) probes.add(one.toArray(new String[0]));
            }
            JSONObject e = j.optJSONObject("env");
            if (e != null) {
                java.util.Iterator<String> it = e.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    env.put(k, e.optString(k));
                }
            }
            build = j.optJSONObject("build");
            diag = j.optJSONObject("diag");
        }

        private static void strings(JSONArray a, List<String> out) {
            if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        }

        /** Does this project file belong to the language? */
        boolean owns(String fileName) {
            String n = fileName.toLowerCase(java.util.Locale.ROOT);
            for (String s : skipSuffix) if (n.endsWith(s)) return false;
            for (String f : files) {
                if (f.startsWith("*") ? fileName.endsWith(f.substring(1)) : fileName.equals(f)) return true;
            }
            int i = n.lastIndexOf('.');
            return i > 0 && exts.contains(n.substring(i));
        }
    }

    private static volatile List<Pack> cache;
    private static final Object CACHE_LOCK = new Object();

    private Packs() { }

    /** Every known plugin, in catalog order: app assets, then the downloaded catalog, then imported plugin.json files. */
    static List<Pack> all(Context c) {
        List<Pack> l = cache;
        if (l != null) return l;
        synchronized (CACHE_LOCK) {
            if (cache != null) return cache;
            Map<String, JSONObject> m = new LinkedHashMap<>();
            try (InputStream in = c.getAssets().open("plugins/catalog.json")) {
                merge(m, new String(Io.bytes(in), "UTF-8"));
            } catch (Exception e) {
                // the app ships this file; if it is damaged the list is simply shorter
            }
            File remote = new File(pluginDir(c), "catalog.json");
            try {
                if (remote.isFile()) merge(m, Io.read(remote));
            } catch (Exception ignored) {
            }
            File[] kids = pluginDir(c).listFiles();
            if (kids != null) for (File k : kids) {
                File pj = new File(k, "plugin.json");
                try {
                    if (pj.isFile()) {
                        JSONObject o = new JSONObject(Io.read(pj));
                        if (o.optString("id").matches("[a-z0-9][a-z0-9_-]*")) m.put(o.getString("id"), o);
                    }
                } catch (Exception ignored) {
                }
            }
            List<Pack> out = new ArrayList<>();
            for (JSONObject o : m.values()) out.add(new Pack(o));
            cache = Collections.unmodifiableList(out);
            return cache;
        }
    }

    private static void merge(Map<String, JSONObject> m, String json) throws Exception {
        JSONArray a = new JSONObject(json).getJSONArray("plugins");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            String id = o.optString("id");
            if (id.isEmpty() || !id.matches("[a-z0-9][a-z0-9_-]*")) continue;
            // a downloaded entry may not turn a built-in language into something else, only update it
            JSONObject old = m.get(id);
            if (old != null && old.optBoolean("builtin") && !o.optBoolean("builtin")) o.put("builtin", true);
            m.put(id, o);
        }
    }

    static void reload() {
        synchronized (CACHE_LOCK) { cache = null; }
    }

    static File pluginDir(Context c) {
        File d = new File(c.getFilesDir(), "plugins");
        d.mkdirs();
        return d;
    }

    static Pack find(Context c, String id) {
        for (Pack p : all(c)) if (p.id.equals(id)) return p;
        return null;
    }

    /** Downloads plugins.json from the release folder: the list of languages, newer than this app. */
    static int refreshCatalog(Context c, Gh.Log log) throws IOException {
        authToken = Prefs.token(c);
        File tmp = new File(c.getCacheDir(), "plugins.json");
        try {
            fetch(baseUrl(c) + "plugins.json", tmp, null);
            String text = Io.read(tmp);
            Map<String, JSONObject> probe = new LinkedHashMap<>();
            try {
                merge(probe, text);
            } catch (Exception e) {
                throw new IOException("plugins.json is not a valid plugin list: " + e.getMessage());
            }
            Io.write(new File(pluginDir(c), "catalog.json"), text);
            reload();
            log.log(probe.size() + " plugin(s) in the list");
            return probe.size();
        } finally {
            tmp.delete();
        }
    }



    static File prefix(Context c) { return new File(c.getFilesDir(), "usr"); }

    static File home(Context c) {
        File h = new File(c.getFilesDir(), "home");
        h.mkdirs();
        return h;
    }

    private static File marker(Context c, String id) { return new File(new File(c.getFilesDir(), "packs"), id + ".installed"); }

    static boolean installed(Context c, String id) {
        Pack p = find(c, id);
        if (p == null) return false;
        if (p.meta) {
            for (String r : p.requires) if (!installed(c, r)) return false;
            return true;
        }
        if (p.jvm) return Toolchain.file(c, "kotlinc.dex.jar").isFile() && Toolchain.file(c, "kotlin-stdlib.jar").isFile();
        return marker(c, id).isFile();
    }

    static String version(Context c, String id) {
        try {
            File m = marker(c, id);
            if (m.isFile()) return Io.read(m).trim();
        } catch (IOException ignored) {
        }
        return "";
    }

    static List<String> installedIds(Context c) {
        List<String> l = new ArrayList<>();
        for (Pack p : all(c)) if (installed(c, p.id)) l.add(p.id);
        return l;
    }

    /** One line per pack, for Settings and the terminal. */
    static String status(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("core (Java, Android APK): ").append(Toolchain.ready(c) ? "installed" : "not installed").append('\n');
        for (Pack p : all(c)) {
            sb.append(p.id).append(" (").append(p.label).append("): ")
                    .append(installed(c, p.id) ? "installed" + (version(c, p.id).isEmpty() ? "" : " " + version(c, p.id))
                            : p.meta ? "needs " + Toolchain.join(p.requires, ", ") : "not installed, ~" + p.mb + " MB")
                    .append('\n');
        }
        return sb.toString().trim();
    }

    /** Folder that holds the release assets, taken from the toolchain URL in Settings. */
    static String baseUrl(Context c) throws IOException {
        String u = Prefs.str(c, "tc_url");
        if (u.isEmpty()) {
            String owner = Prefs.owner(c);
            if (owner.isEmpty())
                throw new IOException("No GitHub username set. Add it in Settings > GitHub, or enter a toolchain URL in Settings > Build tools.");
            u = "https://github.com/" + owner + "/IDE/releases/download/toolchain/x";
        }
        int i = u.lastIndexOf('/');
        return u.substring(0, i + 1);
    }

    // ------------------------------------------------------------------ install

    /** Installs a plugin and, first, every plugin it requires (Rust needs C / C++ to link). */
    static void installWithDeps(Context c, String id, Gh.Log log) throws IOException {
        Pack p = find(c, id);
        if (p == null) throw new IOException("Unknown language plugin " + id + ". Try App settings > Languages > Refresh language list.");
        for (String r : p.requires) if (!installed(c, r)) installWithDeps(c, r, log);
        if (!installed(c, id) && !p.meta) install(c, id, log);
    }

    static void install(Context c, String id, Gh.Log log) throws IOException {
        authToken = Prefs.token(c);
        Pack p = find(c, id);
        if (p == null) throw new IOException("Unknown pack " + id);
        if (p.meta) return;
        String base = baseUrl(c);
        String want = "", ver = "";
        try {
            File mf = new File(c.getCacheDir(), "packs.json");
            fetch(base + "packs.json", mf, null);
            JSONObject e = new JSONObject(Io.read(mf)).getJSONObject("packs").optJSONObject(id);
            mf.delete();
            if (e != null) {
                want = e.optString("sha256");
                ver = e.optString("version");
            }
        } catch (Exception e) {
            log.log("No packs.json next to the toolchain (" + e.getMessage() + "), skipping the checksum");
        }
        File zip = new File(c.getCacheDir(), "pack-" + id + ".zip");
        try {
            log.log("downloading pack-" + id + ".zip (about " + p.mb + " MB)");
            fetch(base + "pack-" + id + ".zip", zip, log);
            if (!want.isEmpty()) {
                String got = sha256(zip);
                if (!got.equalsIgnoreCase(want)) throw new IOException("Checksum mismatch for " + id + ", the download is damaged. Try again.");
                log.log("checksum ok");
            }
            unpack(c, p, zip, log);
            File m = marker(c, id);
            m.getParentFile().mkdirs();
            Io.write(m, ver.isEmpty() ? "installed" : ver);
        } finally {
            zip.delete();
        }
    }

    private static void unpack(Context c, Pack p, File zip, Gh.Log log) throws IOException {
        if (p.jvm) {
            if (Toolchain.installZip(c, zip, log) == 0) throw new IOException("That zip held none of the " + p.label + " files");
        } else {
            unpackNative(c, p.id, zip, log);
        }
    }

    /** A GitHub Actions artifact downloads as a zip that holds the real pack-<id>.zip. Returns that inner file, or null. */
    private static File unwrapArtifact(Context c, File zip) throws IOException {
        String inner = null;
        int count = 0;
        try (ZipInputStream zi = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = zi.getNextEntry()) != null) {
                count++;
                if (e.getName().matches("pack-[A-Za-z0-9_-]+\\.zip")) inner = e.getName();
            }
        }
        if (inner == null || count != 1) return null;
        File out = new File(c.getCacheDir(), inner);
        try (ZipInputStream zi = new ZipInputStream(new FileInputStream(zip)); FileOutputStream o = new FileOutputStream(out)) {
            ZipEntry e;
            while ((e = zi.getNextEntry()) != null) {
                if (!e.getName().equals(inner)) continue;
                byte[] b = new byte[65536];
                int n;
                while ((n = zi.read(b)) > 0) o.write(b, 0, n);
                break;
            }
        }
        return out;
    }

    static void importZip(Context c, String id, File zip, Gh.Log log) throws IOException {
        Pack p = find(c, id);
        if (p == null) throw new IOException("Unknown pack " + id);
        File inner = unwrapArtifact(c, zip);
        try {
            unpack(c, p, inner != null ? inner : zip, log);
        } finally {
            if (inner != null) inner.delete();
        }
        File m = marker(c, id);
        m.getParentFile().mkdirs();
        Io.write(m, "imported");
    }

    /**
     * Imports a language from any zip the user has: a plugin zip (plugin.json at the top), a pack-<id>.zip from the
     * release, or the zip GitHub makes when a workflow artifact is downloaded. Returns the language id.
     */
    static String importAny(Context c, File zip, Gh.Log log) throws IOException {
        boolean plugin = false;
        try (ZipInputStream zi = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = zi.getNextEntry()) != null) if (e.getName().equals("plugin.json")) plugin = true;
        }
        if (plugin) return importPlugin(c, zip, log);
        File inner = unwrapArtifact(c, zip);
        File pack = inner != null ? inner : zip;
        try {
            java.util.regex.Matcher m = Pattern.compile("pack-([A-Za-z0-9_-]+?)(?: \\(\\d+\\))?\\.zip").matcher(pack.getName());
            if (m.matches() && find(c, m.group(1)) != null) {
                importZip(c, m.group(1), pack, log);
                return m.group(1);
            }
        } finally {
            if (inner != null) inner.delete();
        }
        throw new IOException("This zip is not a language pack. Choose pack-<language>.zip (from the release or a GitHub Actions artifact), "
                + "or a plugin zip that contains plugin.json. Keep the file name, for example pack-kotlin.zip.");
    }

    /**
     * Imports a plugin that brings its own description: a zip with plugin.json at the top and the program files
     * under usr/. Registers the language (so detection, build steps and PATH know it) and unpacks it. Returns the id.
     */
    static String importPlugin(Context c, File zip, Gh.Log log) throws IOException {
        String json = null;
        try (ZipInputStream zi = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = zi.getNextEntry()) != null) {
                if (e.getName().equals("plugin.json")) {
                    json = new String(Io.bytes(zi), "UTF-8");
                    break;
                }
            }
        }
        if (json == null) throw new IOException("That zip has no plugin.json at its top level.");
        String id;
        try {
            JSONObject o = new JSONObject(json);
            id = o.optString("id");
            if (!id.matches("[a-z0-9][a-z0-9_-]*")) throw new IOException("plugin.json needs an \"id\" of lower case letters, digits, - or _");
            if (o.optString("label").isEmpty()) throw new IOException("plugin.json needs a \"label\"");
        } catch (org.json.JSONException e) {
            throw new IOException("plugin.json is not valid JSON: " + e.getMessage());
        }
        File d = new File(pluginDir(c), id);
        d.mkdirs();
        Io.write(new File(d, "plugin.json"), json);
        reload();
        log.log("registered plugin " + id);
        Pack p = find(c, id);
        if (p != null && !p.meta) unpack(c, p, zip, log);
        File m = marker(c, id);
        m.getParentFile().mkdirs();
        Io.write(m, "imported");
        return id;
    }

    static void remove(Context c, String id) {
        Pack p = find(c, id);
        if (p == null) return;
        if (p.jvm) {
            Toolchain.file(c, "kotlinc.dex.jar").delete();
            Toolchain.file(c, "kotlin-stdlib.jar").delete();
            Toolchain.file(c, "compose-plugin.jar").delete();
        } else {
            // Packs share libraries (libc++, zlib...), so a file that another installed pack also lists is kept.
            File dir = new File(c.getFilesDir(), "packs");
            File list = new File(dir, id + ".files");
            java.util.Set<String> shared = new java.util.HashSet<>();
            for (Pack o : all(c)) {
                if (o.id.equals(id) || !installed(c, o.id)) continue;
                try {
                    File ol = new File(dir, o.id + ".files");
                    if (ol.isFile()) for (String line : Io.read(ol).split("\n")) shared.add(line);
                } catch (IOException ignored) {
                }
            }
            try {
                if (list.isFile()) for (String line : Io.read(list).split("\n")) if (!line.isEmpty() && !shared.contains(line)) new File(c.getFilesDir(), line).delete();
            } catch (IOException ignored) {
            }
            list.delete();
        }
        marker(c, id).delete();
    }

    /** Unzips usr/** into files/, makes ELF files and scripts executable and recreates the links listed in LINKS.txt. */
    private static void unpackNative(Context c, String id, File zip, Gh.Log log) throws IOException {
        File root = c.getFilesDir();
        String base = root.getCanonicalPath() + File.separator;
        List<String> written = new ArrayList<>();
        int n = 0;
        try (ZipInputStream zi = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = zi.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory() || !name.startsWith("usr/")) continue;
                File f = new File(root, name);
                if (!f.getCanonicalPath().startsWith(base)) continue; // zip slip
                f.getParentFile().mkdirs();
                f.delete();
                try (FileOutputStream o = new FileOutputStream(f)) {
                    byte[] b = new byte[32768];
                    int r;
                    while ((r = zi.read(b)) > 0) o.write(b, 0, r);
                }
                if (isProgram(f)) {
                    f.setReadable(true, false);
                    f.setExecutable(true, false);
                }
                written.add(name);
                if (++n % 2000 == 0) log.log("unpacked " + n + " files");
            }
        }
        File links = new File(root, "usr/LINKS.txt");
        int ln = 0;
        if (links.isFile()) {
            for (String line : Io.read(links).split("\n")) {
                int i = line.indexOf('\t');
                if (i <= 0) continue;
                File l = new File(root, "usr/" + line.substring(0, i));
                if (!l.getParentFile().getCanonicalPath().startsWith(base)) continue; // a link name must stay inside the app folder
                l.getParentFile().mkdirs();
                l.delete();
                try {
                    Os.symlink(line.substring(i + 1), l.getPath());
                    written.add("usr/" + line.substring(0, i)); // so Remove deletes the link as well
                    ln++;
                } catch (Exception ex) {
                    log.log("link failed: " + line.substring(0, i) + " (" + ex.getMessage() + ")");
                }
            }
        }
        log.log("unpacked " + n + " files and " + ln + " links");
        // remember the file list so Remove can undo exactly this pack
        StringBuilder sb = new StringBuilder();
        for (String w : written) sb.append(w).append('\n');
        Io.write(new File(new File(root, "packs"), id + ".files"), sb.toString());
    }

    /** ELF binary, shared library, or a script that starts with #!. */
    static boolean isProgram(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] h = new byte[4];
            int r = in.read(h);
            if (r >= 2 && h[0] == '#' && h[1] == '!') return true;
            return r == 4 && h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ http

    /** Token used to reach release files when the repository is private. Set by install and refreshCatalog. */
    private static volatile String authToken = "";

    private static final class HttpFail extends IOException {
        final int code;

        HttpFail(int code, String url) {
            super("HTTP " + code + " for " + url.substring(url.lastIndexOf('/') + 1));
            this.code = code;
        }
    }

    private static void fetch(String url, File to, Gh.Log log) throws IOException {
        try {
            get(url, to, log);
        } catch (HttpFail e) {
            // A private repository answers 404 to anonymous downloads. Retry through the API with the saved token.
            if ((e.code == 404 || e.code == 403 || e.code == 401) && !authToken.isEmpty()) {
                if (log != null) log.log("The file is not public. Retrying with your GitHub token");
                if (getPrivate(url, to, log)) return;
            }
            if (e.code == 404) throw new IOException(e.getMessage() + ". The file was not found in the release. Run the \"Build language packs\" workflow, "
                    + "or add a GitHub token under App settings > GitHub account if the repository is private.");
            throw e;
        }
    }

    private static void get(String url, File to, Gh.Log log) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(20000);
        h.setReadTimeout(60000);
        h.setInstanceFollowRedirects(true);
        int code = h.getResponseCode();
        if (code / 100 != 2) throw new HttpFail(code, url);
        copy(h, to, log);
    }

    /** github.com/{owner}/{repo}/releases/download/{tag}/{file} through the REST API, for private repositories. */
    private static boolean getPrivate(String url, File to, Gh.Log log) throws IOException {
        java.util.regex.Matcher m = Pattern.compile("https://github\\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/([^/?#]+)").matcher(url);
        if (!m.matches()) return false;
        String api = "https://api.github.com/repos/" + m.group(1) + "/" + m.group(2) + "/releases/tags/" + m.group(3);
        HttpURLConnection h = (HttpURLConnection) new URL(api).openConnection();
        h.setConnectTimeout(20000);
        h.setReadTimeout(60000);
        h.setRequestProperty("Authorization", "Bearer " + authToken);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        if (h.getResponseCode() / 100 != 2) return false;
        String assetUrl = "";
        try (InputStream in = h.getInputStream()) {
            JSONArray as = new JSONObject(new String(Io.bytes(in), "UTF-8")).optJSONArray("assets");
            for (int i = 0; as != null && i < as.length(); i++)
                if (as.getJSONObject(i).optString("name").equals(m.group(4))) assetUrl = as.getJSONObject(i).optString("url");
        } catch (org.json.JSONException e) {
            return false;
        }
        if (assetUrl.isEmpty()) return false;
        HttpURLConnection d = (HttpURLConnection) new URL(assetUrl).openConnection();
        d.setConnectTimeout(20000);
        d.setReadTimeout(60000);
        d.setInstanceFollowRedirects(false);
        d.setRequestProperty("Authorization", "Bearer " + authToken);
        d.setRequestProperty("Accept", "application/octet-stream");
        int code = d.getResponseCode();
        if (code == 301 || code == 302 || code == 303 || code == 307) {
            String loc = d.getHeaderField("Location");
            if (loc == null) return false;
            get(loc, to, log);   // the storage host must not receive the token
            return true;
        }
        if (code / 100 != 2) return false;
        copy(d, to, log);
        return true;
    }

    private static void copy(HttpURLConnection h, File to, Gh.Log log) throws IOException {
        long total = h.getContentLengthLong(), got = 0, last = 0;
        try (InputStream in = h.getInputStream(); FileOutputStream o = new FileOutputStream(to)) {
            byte[] b = new byte[32768];
            int r;
            while ((r = in.read(b)) > 0) {
                o.write(b, 0, r);
                got += r;
                if (log != null && got - last > 8_000_000) {
                    last = got;
                    log.log("downloaded " + (got >> 20) + (total > 0 ? " of " + (total >> 20) : "") + " MB");
                }
            }
        }
    }

    static String sha256(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(f)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) md.update(b, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** True when plain exec from app storage is allowed (targetSdk 28 or lower, or Android 9 and older). */
    static boolean execDirect(Context c) {
        return Build.VERSION.SDK_INT < 29 || c.getApplicationInfo().targetSdkVersion <= 28;
    }

    /**
     * True when pack programs can be tried. Above targetSdk 28 plain exec is refused, so NativeExec starts the first
     * program through libcloudexec.so / the system linker, and libexec_hook.so (LD_PRELOAD) does the same for every
     * program those start (see NATIVE_EXEC.md). Whether that works on a given phone is shown by Check toolchain,
     * which really starts every installed tool and a child-of-child chain.
     */
    static boolean canExec(Context c) {
        return execDirect(c) || new File("/system/bin/linker64").exists() || new File("/system/bin/linker").exists();
    }
}
