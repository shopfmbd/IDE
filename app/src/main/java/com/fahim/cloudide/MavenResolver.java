package com.fahim.cloudide;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Downloads library dependencies the way Gradle would, without Gradle: POM and Gradle module metadata, transitive
 * dependencies, BOM platforms, exclusions, and "highest version wins". Files are cached by Maven layout, so a second
 * build is offline. No Android classes are used here, so it also runs on a plain JDK (see toolchain/test).
 *
 * Known limits (reported as notes, not hidden): no dependency locking, no capability conflicts, version ranges
 * pick their lower bound, local project(":lib") modules are not handled here, annotation processors (kapt, ksp,
 * annotationProcessor) are not run by the phone toolchain.
 */
final class MavenResolver {
    interface Fetch { byte[] get(String url) throws IOException; }

    interface Log { void log(String s); }

    /** A requested dependency. version may be empty when a BOM supplies it. */
    static final class Dep {
        final String group, artifact;
        String version;
        /** true for compileOnly: on the compile class path, not packaged into the APK. */
        boolean compileOnly;
        final Set<String> excludes = new HashSet<>();

        Dep(String g, String a, String v) {
            group = g;
            artifact = a;
            version = v == null ? "" : v;
        }

        String ga() { return group + ":" + artifact; }

        @Override
        public String toString() { return group + ":" + artifact + (version.isEmpty() ? "" : ":" + version); }
    }

    /** One resolved file. */
    static final class Artifact {
        final String group, artifact, version;
        final File file;
        final boolean aar, compileOnly;

        Artifact(String g, String a, String v, File f, boolean aar, boolean compileOnly) {
            group = g;
            artifact = a;
            version = v;
            file = f;
            this.aar = aar;
            this.compileOnly = compileOnly;
        }

        @Override
        public String toString() { return group + ":" + artifact + ":" + version + (aar ? " (aar)" : ""); }
    }

    static final class Result {
        final List<Artifact> artifacts = new ArrayList<>();
        final List<String> notes = new ArrayList<>();

        List<File> jars(boolean forPackaging) {
            List<File> l = new ArrayList<>();
            for (Artifact a : artifacts) if (!a.aar && !(forPackaging && a.compileOnly)) l.add(a.file);
            return l;
        }

        List<File> aars(boolean forPackaging) {
            List<File> l = new ArrayList<>();
            for (Artifact a : artifacts) if (a.aar && !(forPackaging && a.compileOnly)) l.add(a.file);
            return l;
        }
    }

    static final String GOOGLE = "https://dl.google.com/android/maven2/";
    static final String CENTRAL = "https://repo1.maven.org/maven2/";

    private final File cache;
    private final List<String> repos = new ArrayList<>();
    private final Fetch fetch;
    private final Log log;
    private final Map<String, Pom> poms = new HashMap<>();
    private final Map<String, Module> modules = new HashMap<>();
    private final Set<String> missing = new HashSet<>();
    private final Result res = new Result();

    private final boolean offline;
    /** Identifies the repository list. A "not found" marker only counts while the list is the same one that wrote it. */
    private final String fingerprint;

    MavenResolver(File cache, List<String> extraRepos, Fetch fetch, Log log) {
        this(cache, extraRepos, fetch, log, true, false);
    }

    /**
     * repoList is searched in order. addDefaults adds Google and Maven Central after it (the app passes its own full
     * list from App settings > Libraries and sets this to false). offline: only the cache is used, nothing is downloaded.
     */
    MavenResolver(File cache, List<String> repoList, Fetch fetch, Log log, boolean addDefaults, boolean offline) {
        this.cache = cache;
        this.fetch = fetch;
        this.log = log == null ? s -> { } : log;
        this.offline = offline;
        for (String r : repoList) addRepo(r);
        if (addDefaults) {
            addRepo(GOOGLE);
            addRepo(CENTRAL);
        }
        this.fingerprint = sha1(String.join("\n", repos).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void addRepo(String r) {
        if (r == null || r.trim().isEmpty()) return;
        r = r.trim();
        if (!r.endsWith("/")) r += "/";
        if (!repos.contains(r)) repos.add(r);
    }

    // ------------------------------------------------------------------ public

    /** Resolves everything. boms are "g:a:v" platforms (their dependencyManagement supplies missing versions). */
    Result resolve(List<Dep> direct, List<String> boms) throws IOException {
        Map<String, String> managed = new HashMap<>();
        for (String b : boms) {
            String[] p = b.split(":");
            if (p.length < 3) continue;
            try {
                Pom bom = pom(p[0], p[1], p[2]);
                if (bom != null) for (Map.Entry<String, String> e : bom.managed(this).entrySet()) if (!managed.containsKey(e.getKey())) managed.put(e.getKey(), e.getValue());
            } catch (IOException e) {
                res.notes.add("BOM " + b + " could not be read: " + e.getMessage());
            }
        }
        Map<String, String> sel = new LinkedHashMap<>();
        Map<String, Boolean> compileOnly = new HashMap<>();
        Map<String, Node2> info = new HashMap<>();
        ArrayDeque<Dep> q = new ArrayDeque<>(direct);
        Set<String> directKeys = new HashSet<>();
        for (Dep d : direct) directKeys.add(d.ga());
        int guard = 0;
        while (!q.isEmpty()) {
            if (++guard > 20000) throw new IOException("Dependency graph is too large or cyclic (stopped after 20000 steps)");
            Dep d = q.poll();
            String ga = d.ga();
            String v = d.version;
            if (v.isEmpty()) v = managed.containsKey(ga) ? managed.get(ga) : "";
            v = concrete(d.group, d.artifact, v);
            if (v.isEmpty()) {
                if (directKeys.contains(ga)) throw new IOException("No version for " + ga + ". Add a version, or a platform(...) that manages it.");
                res.notes.add("skipped " + ga + " (no version)");
                continue;
            }
            // compileOnly only stays compileOnly when nothing else asks for it normally
            Boolean prevCo = compileOnly.get(ga);
            compileOnly.put(ga, prevCo == null ? d.compileOnly : prevCo && d.compileOnly);
            String cur = sel.get(ga);
            if (cur != null && compare(cur, v) >= 0) continue;
            sel.put(ga, v);
            Node2 n;
            try {
                n = node(d.group, d.artifact, v);
            } catch (IOException e) {
                if (directKeys.contains(ga)) throw e;
                res.notes.add("skipped " + ga + ":" + v + " (" + e.getMessage() + ")");
                sel.remove(ga);
                continue;
            }
            info.put(ga, n);
            for (Dep c : n.deps) {
                if (d.excludes.contains(c.ga()) || d.excludes.contains(c.group + ":*") || d.excludes.contains("*:" + c.artifact) || d.excludes.contains("*:*")) continue;
                c.compileOnly = d.compileOnly;
                c.excludes.addAll(d.excludes);
                q.add(c);
            }
        }
        Set<String> seenFiles = new HashSet<>();
        for (Map.Entry<String, String> e : sel.entrySet()) {
            Node2 n = info.get(e.getKey());
            if (n == null || n.file == null) continue;
            if (!seenFiles.add(n.file.getPath())) continue; // two requests that redirect to the same module
            File f = artifactFile(n);
            res.artifacts.add(new Artifact(n.group, n.artifact, e.getValue(), f, n.aar, Boolean.TRUE.equals(compileOnly.get(e.getKey()))));
        }
        log.log("resolved " + res.artifacts.size() + " artifact(s)");
        return res;
    }

    // ------------------------------------------------------------------ versions

    private static boolean dynamic(String v) { return v.endsWith("+") || v.equals("latest.release") || v.equals("latest.integration") || v.startsWith("[") || v.startsWith("("); }

    /** Turns "1.+", "latest.release" and "[1.0,2.0)" into one version, and "{strictly x}" style leftovers into x. */
    private String concrete(String g, String a, String v) throws IOException {
        v = v.trim();
        if (v.isEmpty() || !dynamic(v)) return v;
        if (v.startsWith("[") || v.startsWith("(")) {
            if (v.length() < 3) throw new IOException("Bad version range \"" + v + "\" for " + g + ":" + a);
            String in = v.substring(1, v.length() - 1);
            String[] p = in.split(",", -1);
            String lo = p[0].trim(), hi = p.length > 1 ? p[1].trim() : "";
            if (v.startsWith("[") && !lo.isEmpty()) return lo;
            if (!hi.isEmpty()) return hi;
            return lo;
        }
        List<String> all = metadataVersions(g, a);
        if (all.isEmpty()) throw new IOException("Cannot resolve dynamic version " + v + " for " + g + ":" + a + " (no maven-metadata.xml)");
        if (v.equals("latest.release") || v.equals("latest.integration") || v.equals("+")) return all.get(all.size() - 1);
        String prefix = v.substring(0, v.length() - 1);
        String best = "";
        for (String x : all) if (x.startsWith(prefix) && (best.isEmpty() || compare(x, best) > 0)) best = x;
        if (best.isEmpty()) throw new IOException("No version of " + g + ":" + a + " matches " + v);
        return best;
    }

    private List<String> metadataVersions(String g, String a) throws IOException {
        List<String> out = new ArrayList<>();
        byte[] b = fetchAny(g.replace('.', '/') + "/" + a + "/maven-metadata.xml", false);
        if (b == null) return out;
        try {
            Document d = parse(b);
            NodeList l = d.getElementsByTagName("version");
            for (int i = 0; i < l.getLength(); i++) {
                String s = l.item(i).getTextContent().trim();
                if (!s.isEmpty() && !s.endsWith("-SNAPSHOT")) out.add(s);
            }
        } catch (Exception e) {
            throw new IOException("Bad maven-metadata.xml for " + g + ":" + a);
        }
        Collections.sort(out, (x, y) -> compare(x, y));
        return out;
    }

    private static final List<String> QUAL = Arrays.asList("dev", "alpha", "a", "beta", "b", "rc", "cr", "m", "milestone", "preview", "ea", "snapshot");

    /** Gradle-like version order: 1.0-alpha1 &lt; 1.0-beta &lt; 1.0-rc1 &lt; 1.0 &lt; 1.0.1. */
    static int compare(String x, String y) {
        List<String> a = parts(x), b = parts(y);
        int n = Math.max(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            String p = i < a.size() ? a.get(i) : null, q = i < b.size() ? b.get(i) : null;
            int c = cmpPart(p, q);
            if (c != 0) return c;
        }
        return 0;
    }

    private static List<String> parts(String v) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int kind = 0; // 1 digit, 2 letter
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '.' || c == '-' || c == '_' || c == '+') {
                if (cur.length() > 0) out.add(cur.toString());
                cur.setLength(0);
                kind = 0;
                continue;
            }
            int k = Character.isDigit(c) ? 1 : 2;
            if (kind != 0 && k != kind && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            kind = k;
            cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static int cmpPart(String p, String q) {
        if (p == null && q == null) return 0;
        boolean pn = p != null && Character.isDigit(p.charAt(0)), qn = q != null && Character.isDigit(q.charAt(0));
        // a missing part is a release: "1.0" is above "1.0-rc1" but below "1.0.1"
        if (p == null) return qn ? (isZero(q) ? 0 : -1) : (rank(q) >= 0 ? 1 : -1);
        if (q == null) return pn ? (isZero(p) ? 0 : 1) : (rank(p) >= 0 ? -1 : 1);
        if (pn && qn) {
            try {
                return Long.compare(Long.parseLong(p), Long.parseLong(q));
            } catch (NumberFormatException e) {
                return p.compareTo(q);
            }
        }
        if (pn) return 1;
        if (qn) return -1;
        int rp = rank(p), rq = rank(q);
        if (rp != rq) return rp < rq ? -1 : 1;
        return p.compareToIgnoreCase(q);
    }

    private static boolean isZero(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) != '0') return false;
        return true;
    }

    /** Position in QUAL for pre-release words, -1 for release words (final, ga, release, sp) and unknown words. */
    private static int rank(String s) {
        int i = QUAL.indexOf(s.toLowerCase(java.util.Locale.ROOT));
        return i;
    }

    // ------------------------------------------------------------------ fetching

    private File cached(String path) { return new File(cache, path); }

    /** Bytes of a repository file from cache or the first repo that has it. null when absent everywhere and !must. */
    private byte[] fetchAny(String path, boolean must) throws IOException {
        // group, artifact and version come from downloaded POM files, so they are not trusted: no ".." or backslash in the path
        if (path.indexOf('\\') >= 0 || path.startsWith("/") || path.equals("..") || path.startsWith("../") || path.contains("/../") || path.endsWith("/.."))
            throw new IOException("Refusing an unsafe repository path: " + path);
        File c = cached(path);
        if (c.isFile() && c.length() > 0) return readAll(c);
        if (offline) {
            // download once, then use: with offline mode on, a file that is not cached is simply not available
            if (must) throw new IOException("Offline mode is on and " + path + " is not in the library cache. Turn offline mode off (App settings > Libraries) to download it once.");
            return null;
        }
        File miss = new File(c.getPath() + ".missing");
        boolean stable = !path.endsWith("maven-metadata.xml"); // metadata changes, version files do not
        if (missing.contains(path) || (stable && markerValid(miss))) {
            if (must) throw new FileNotFoundException(path);
            return null;
        }
        IOException last = null;
        boolean allNotFound = true;
        for (String r : repos) {
            try {
                byte[] b = fetch.get(r + path);
                if (b == null) continue;
                c.getParentFile().mkdirs();
                File tmp = new File(c.getPath() + ".part");
                try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(b); }
                if (!tmp.renameTo(c)) throw new IOException("Cannot write " + c);
                return b;
            } catch (FileNotFoundException nf) {
                // next repository
            } catch (IOException e) {
                last = e;
                allNotFound = false;
            }
        }
        missing.add(path);
        if (allNotFound && stable) {
            try {
                miss.getParentFile().mkdirs();
                try (FileOutputStream o = new FileOutputStream(miss)) { o.write(fingerprint.getBytes("UTF-8")); }
            } catch (IOException ignored) {
            }
        }
        if (must) throw last != null ? last : new FileNotFoundException(path + " is in none of: " + repos);
        return null;
    }

    /** A "not found" note is trusted for a day, and only for the repository list that wrote it (add JitPack and it is searched again). */
    private static final long MISSING_TTL_MS = 24L * 60 * 60 * 1000;

    private boolean markerValid(File miss) {
        if (!miss.isFile()) return false;
        if (System.currentTimeMillis() - miss.lastModified() > MISSING_TTL_MS) return false;
        try {
            return new String(readAll(miss), "UTF-8").trim().equals(fingerprint);
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        }
    }

    private static String dir(String g, String a, String v) { return g.replace('.', '/') + "/" + a + "/" + v + "/"; }

    private Document parse(byte[] b) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // some parsers (Android's) do not know this feature
        }
        DocumentBuilder db = f.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(b));
    }

    // ------------------------------------------------------------------ POM

    static final class Pom {
        String group = "", artifact = "", version = "", packaging = "jar";
        Pom parent;
        final Map<String, String> props = new HashMap<>();
        final Map<String, String> managed = new LinkedHashMap<>();
        final List<String[]> imports = new ArrayList<>();
        final List<Dep> deps = new ArrayList<>();

        private boolean busy; // set while managed() runs on this POM, so a BOM that imports itself (directly or not) ends the loop

        String interp(String s) {
            if (s == null) return "";
            for (int i = 0; i < 12 && s.contains("${"); i++) {
                StringBuilder sb = new StringBuilder();
                int p = 0;
                while (true) {
                    int a = s.indexOf("${", p);
                    if (a < 0) {
                        sb.append(s.substring(p));
                        break;
                    }
                    int b = s.indexOf('}', a);
                    if (b < 0) {
                        sb.append(s.substring(p));
                        break;
                    }
                    sb.append(s, p, a);
                    String k = s.substring(a + 2, b), val = lookup(k);
                    sb.append(val == null ? s.substring(a, b + 1) : val);
                    p = b + 1;
                }
                String n = sb.toString();
                if (n.equals(s)) break;
                s = n;
            }
            return s;
        }

        String lookup(String k) {
            switch (k) {
                case "project.version": case "pom.version": case "version": return version();
                case "project.groupId": case "pom.groupId": return group();
                case "project.artifactId": return artifact;
                case "project.parent.version": case "parent.version": return parent == null ? null : parent.version();
                case "project.parent.groupId": return parent == null ? null : parent.group();
                default: break;
            }
            for (Pom p = this; p != null; p = p.parent) if (p.props.containsKey(k)) return p.props.get(k);
            return null;
        }

        String version() { return !version.isEmpty() ? version : parent != null ? parent.version() : ""; }

        String group() { return !group.isEmpty() ? group : parent != null ? parent.group() : ""; }

        /** dependencyManagement of this POM and its parents, plus imported BOMs. */
        Map<String, String> managed(MavenResolver r) {
            Map<String, String> m = new LinkedHashMap<>();
            if (busy) return m;
            busy = true;
            try {
                fillManaged(r, m);
            } finally {
                busy = false;
            }
            return m;
        }

        private void fillManaged(MavenResolver r, Map<String, String> m) {
            if (parent != null) m.putAll(parent.managed(r));
            for (Map.Entry<String, String> e : managed.entrySet()) m.put(e.getKey(), interp(e.getValue()));
            for (String[] imp : imports) {
                try {
                    Pom b = r.pom(interp(imp[0]), interp(imp[1]), interp(imp[2]));
                    if (b != null) for (Map.Entry<String, String> e : b.managed(r).entrySet()) if (!m.containsKey(e.getKey())) m.put(e.getKey(), e.getValue());
                } catch (IOException ignored) {
                }
            }
        }
    }

    Pom pom(String g, String a, String v) throws IOException {
        String key = g + ":" + a + ":" + v;
        if (poms.containsKey(key)) return poms.get(key);
        byte[] b = fetchAny(dir(g, a, v) + a + "-" + v + ".pom", false);
        if (b == null) {
            poms.put(key, null);
            return null;
        }
        Pom p = new Pom();
        poms.put(key, p); // guards against parent cycles
        try {
            Element root = parse(b).getDocumentElement();
            if (!root.getTagName().equals("project")) throw new Exception("the file is not a POM (a repository error page?)");
            p.group = text(root, "groupId");
            p.artifact = text(root, "artifactId");
            p.version = text(root, "version");
            String pk = text(root, "packaging");
            if (!pk.isEmpty()) p.packaging = pk;
            Element par = child(root, "parent");
            if (par != null) {
                String pg = text(par, "groupId"), pa = text(par, "artifactId"), pv = text(par, "version");
                if (p.group.isEmpty()) p.group = pg;
                if (p.version.isEmpty()) p.version = pv;
                p.parent = pom(pg, pa, pv);
                int hops = 0;
                for (Pom x = p.parent; x != null && hops < 64; x = x.parent, hops++) {
                    if (x == p) { // a parent chain that leads back to this POM
                        p.parent = null;
                        break;
                    }
                }
            }
            Element pr = child(root, "properties");
            if (pr != null) for (Element e : children(pr)) p.props.put(e.getTagName(), e.getTextContent().trim());
            Element dm = child(root, "dependencyManagement");
            Element dmd = dm == null ? null : child(dm, "dependencies");
            if (dmd != null) for (Element e : children(dmd, "dependency")) {
                String dg = text(e, "groupId"), da = text(e, "artifactId"), dv = text(e, "version");
                if ("import".equals(text(e, "scope"))) p.imports.add(new String[]{dg, da, dv});
                else p.managed.put(p.interp(dg) + ":" + p.interp(da), dv);
            }
            Element ds = child(root, "dependencies");
            if (ds != null) for (Element e : children(ds, "dependency")) {
                String scope = text(e, "scope");
                if ("test".equals(scope) || "provided".equals(scope) || "system".equals(scope)) continue;
                if ("true".equals(text(e, "optional"))) continue;
                String type = text(e, "type");
                if (!type.isEmpty() && !type.equals("jar") && !type.equals("aar")) continue;
                Dep d = new Dep(text(e, "groupId"), text(e, "artifactId"), text(e, "version"));
                Element ex = child(e, "exclusions");
                if (ex != null) for (Element x : children(ex, "exclusion")) d.excludes.add(text(x, "groupId") + ":" + text(x, "artifactId"));
                p.deps.add(d);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            poms.remove(key);
            // a repository can answer with an HTML page ("build queued", an error page): never keep that as the POM
            cached(dir(g, a, v) + a + "-" + v + ".pom").delete();
            throw new IOException("Bad POM " + key + ": " + e.getMessage());
        }
        return p;
    }

    private static String text(Element e, String tag) {
        Element c = child(e, tag);
        return c == null ? "" : c.getTextContent().trim();
    }

    private static Element child(Element e, String tag) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && ((Element) n).getTagName().equals(tag)) return (Element) n;
        return null;
    }

    private static List<Element> children(Element e) { return children(e, null); }

    private static List<Element> children(Element e, String tag) {
        List<Element> l = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && (tag == null || ((Element) n).getTagName().equals(tag))) l.add((Element) n);
        return l;
    }

    // ------------------------------------------------------------------ Gradle module metadata

    static final class Module {
        String group, artifact, version;
        /** set when the module redirects to another one (Kotlin Multiplatform root modules do). */
        String[] redirect;
        String file; // relative file name inside the module folder
        final List<Dep> deps = new ArrayList<>();
    }

    /** Reads name.module, picks the Android/JVM runtime variant. null when there is no usable metadata. */
    private Module module(String g, String a, String v) throws IOException {
        String key = g + ":" + a + ":" + v;
        if (modules.containsKey(key)) return modules.get(key);
        Module m = null;
        byte[] b = fetchAny(dir(g, a, v) + a + "-" + v + ".module", false);
        if (b != null) {
            try {
                m = readModule(g, a, v, new String(b, "UTF-8"));
            } catch (Exception e) {
                res.notes.add("module metadata of " + key + " ignored: " + e.getMessage());
            }
        }
        modules.put(key, m);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Module readModule(String g, String a, String v, String json) throws Exception {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) return null;
        List<Object> variants = (List<Object>) ((Map<String, Object>) root).get("variants");
        if (variants == null) return null;
        Map<String, Object> best = null;
        int bestScore = -1;
        for (Object o : variants) {
            Map<String, Object> var = (Map<String, Object>) o;
            Map<String, Object> at = var.get("attributes") instanceof Map ? (Map<String, Object>) var.get("attributes") : new HashMap<String, Object>();
            String usage = String.valueOf(at.get("org.gradle.usage"));
            if (!usage.equals("java-runtime") && !usage.equals("java-api")) continue;
            if (at.containsKey("org.gradle.category") && !"library".equals(at.get("org.gradle.category"))) continue;
            String plat = at.containsKey("org.jetbrains.kotlin.platform.type") ? String.valueOf(at.get("org.jetbrains.kotlin.platform.type")) : "";
            int score;
            if (plat.equals("androidJvm")) score = 30;
            else if (plat.equals("jvm")) score = 20;
            else if (plat.isEmpty()) score = 10;
            else continue; // js, native, wasm
            if (usage.equals("java-runtime")) score += 5;
            String le = String.valueOf(at.get("org.gradle.libraryelements"));
            if (le.equals("aar") || le.equals("jar")) score += 2;
            // variants for other build flavours / docs / sources are not wanted
            if (at.containsKey("org.gradle.docstype")) continue;
            if (score > bestScore) {
                bestScore = score;
                best = var;
            }
        }
        if (best == null) return null;
        Module m = new Module();
        m.group = g;
        m.artifact = a;
        m.version = v;
        Object at = best.get("available-at");
        if (at instanceof Map) {
            Map<String, Object> x = (Map<String, Object>) at;
            m.redirect = new String[]{String.valueOf(x.get("group")), String.valueOf(x.get("module")), String.valueOf(x.get("version"))};
            return m;
        }
        List<Object> files = (List<Object>) best.get("files");
        if (files != null && !files.isEmpty()) {
            Map<String, Object> f = (Map<String, Object>) files.get(0);
            m.file = String.valueOf(f.get("url") != null ? f.get("url") : f.get("name"));
        }
        List<Object> deps = (List<Object>) best.get("dependencies");
        if (deps != null) for (Object o : deps) {
            Map<String, Object> d = (Map<String, Object>) o;
            String ver = "";
            Object vo = d.get("version");
            if (vo instanceof Map) {
                Map<String, Object> vm = (Map<String, Object>) vo;
                for (String k : new String[]{"strictly", "requires", "prefers"}) if (vm.get(k) != null && !String.valueOf(vm.get(k)).isEmpty()) { ver = String.valueOf(vm.get(k)); break; }
            }
            Dep dep = new Dep(String.valueOf(d.get("group")), String.valueOf(d.get("module")), ver);
            Object ex = d.get("excludes");
            if (ex instanceof List) for (Object e : (List<Object>) ex) {
                Map<String, Object> em = (Map<String, Object>) e;
                dep.excludes.add((em.get("group") == null ? "*" : String.valueOf(em.get("group"))) + ":" + (em.get("module") == null ? "*" : String.valueOf(em.get("module"))));
            }
            m.deps.add(dep);
        }
        return m;
    }

    // ------------------------------------------------------------------ one node of the graph

    /** What a module turns into: the file to download and the dependencies to follow. */
    private static final class Node2 {
        String group, artifact, version, relFile;
        boolean aar;
        File file;
        final List<Dep> deps = new ArrayList<>();
    }

    private Node2 node(String g, String a, String v) throws IOException {
        // metadata first, like Gradle; fall back to the POM when it is absent or unusable
        Module m = module(g, a, v);
        int hops = 0;
        while (m != null && m.redirect != null && hops++ < 4) {
            g = m.redirect[0];
            a = m.redirect[1];
            v = m.redirect[2];
            m = module(g, a, v);
        }
        Pom p = pom(g, a, v);
        Node2 n = new Node2();
        n.group = g;
        n.artifact = a;
        n.version = v;
        if (m != null && m.file != null) {
            n.relFile = m.file;
            n.aar = m.file.endsWith(".aar");
            for (Dep d : m.deps) n.deps.add(d);
            // metadata dependencies carry versions, POM management is only a fallback
            if (p != null) fillVersions(n.deps, p);
        } else {
            if (p == null) {
                if (offline) throw new FileNotFoundException(g + ":" + a + ":" + v + " is not in the library cache and offline mode is on");
                throw new FileNotFoundException(g + ":" + a + ":" + v + " not found in " + repos.size() + " repositories (" + repoNames() + ")");
            }
            String pk = p.packaging;
            if (pk.equals("pom")) {
                n.relFile = null;
            } else {
                n.aar = pk.equals("aar");
                n.relFile = a + "-" + v + (n.aar ? ".aar" : ".jar");
            }
            Map<String, String> mg = p.managed(this);
            for (Dep d : p.deps) {
                String dg = p.interp(d.group), da = p.interp(d.artifact), dv = p.interp(d.version);
                if (dv.isEmpty()) dv = mg.containsKey(dg + ":" + da) ? p.interp(mg.get(dg + ":" + da)) : "";
                Dep c = new Dep(dg, da, dv);
                c.excludes.addAll(d.excludes);
                n.deps.add(c);
            }
            if (p.parent != null) addParentDeps(p.parent, n, mg);
        }
        if (n.relFile != null) n.file = new File(cached(dir(g, a, v)), n.relFile);
        return n;
    }

    private String repoNames() {
        StringBuilder sb = new StringBuilder();
        for (String r : repos) sb.append(sb.length() == 0 ? "" : ", ").append(r.replaceFirst("^https?://", "").replaceAll("/$", ""));
        return sb.toString();
    }

    private void addParentDeps(Pom par, Node2 n, Map<String, String> mg) {
        for (Pom x = par; x != null; x = x.parent) {
            for (Dep d : x.deps) {
                String dg = x.interp(d.group), da = x.interp(d.artifact), dv = x.interp(d.version);
                if (dv.isEmpty()) dv = mg.containsKey(dg + ":" + da) ? mg.get(dg + ":" + da) : "";
                boolean have = false;
                for (Dep e : n.deps) if (e.ga().equals(dg + ":" + da)) have = true;
                if (!have) n.deps.add(new Dep(dg, da, dv));
            }
        }
    }

    private void fillVersions(List<Dep> deps, Pom p) {
        Map<String, String> mg = p.managed(this);
        for (Dep d : deps) if (d.version.isEmpty() && mg.containsKey(d.ga())) d.version = p.interp(mg.get(d.ga()));
    }

    /** Downloads the jar / aar of a node (and checks its .sha1 when the repository has one). */
    private File artifactFile(Node2 n) throws IOException {
        String rel = n.relFile;
        String base = dir(n.group, n.artifact, n.version);
        // a module's file url may point at a sibling folder
        String path = rel.contains("/") ? normalize(base + rel) : base + rel;
        File f = cached(path);
        if (!f.isFile() || f.length() == 0) {
            byte[] b = fetchAny(path, true);
            if (b != null && b.length > 0 && b[0] == '<') { // a web page where a jar or aar should be
                f.delete();
                throw new IOException("The server sent a web page instead of " + path + " (the library may still be building, try again in a minute)");
            }
            byte[] sum = fetchAny(path + ".sha1", false);
            if (sum != null && b != null) {
                String want = new String(sum, "UTF-8").trim().split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
                String got = sha1(b);
                if (want.length() == 40 && !want.equals(got)) {
                    f.delete();
                    throw new IOException("Checksum mismatch for " + path + " (the download is damaged)");
                }
            }
        }
        return f;
    }

    private static String normalize(String p) {
        List<String> out = new ArrayList<>();
        for (String s : p.split("/")) {
            if (s.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
            } else if (!s.isEmpty() && !s.equals(".")) out.add(s);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : out) sb.append(sb.length() == 0 ? "" : "/").append(s);
        return sb.toString();
    }

    static String sha1(byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest(b)) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ HTTP

    /** The fetcher used on the phone. 404 becomes FileNotFoundException so the next repository is tried. */
    static Fetch http() { return http(60); }

    /** jitpackReadSeconds: how long to wait for jitpack.io, which builds a library the first time it is asked for it. */
    static Fetch http(int jitpackReadSeconds) {
        return url -> {
            IOException last = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
                try {
                    h.setConnectTimeout(20000);
                    h.setReadTimeout((url.startsWith("https://jitpack.io/") ? Math.max(60, jitpackReadSeconds) : 60) * 1000);
                    h.setInstanceFollowRedirects(true);
                    h.setRequestProperty("User-Agent", "CloudIDE");
                    int code = h.getResponseCode();
                    if (code == 404 || code == 403 || code == 410) throw new FileNotFoundException(url);
                    if (code == 202) throw new IOException("still being prepared by the server: " + url); // try again
                    if (code / 100 != 2) throw new IOException("HTTP " + code + " for " + url);
                    try (InputStream in = h.getInputStream()) {
                        ByteArrayOutputStream o = new ByteArrayOutputStream();
                        byte[] b = new byte[16384];
                        int n;
                        while ((n = in.read(b)) > 0) o.write(b, 0, n);
                        return o.toByteArray();
                    }
                } catch (FileNotFoundException e) {
                    throw e;
                } catch (IOException e) {
                    last = e;
                    try {
                        Thread.sleep(400L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        throw new IOException("interrupted");
                    }
                } finally {
                    h.disconnect();
                }
            }
            throw last == null ? new IOException("download failed: " + url) : last;
        };
    }

    // ------------------------------------------------------------------ tiny JSON reader (org.json is Android only)

    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object parse(String s) throws Exception {
            Json j = new Json(s);
            j.ws();
            Object o = j.value();
            return o;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object value() throws Exception {
            ws();
            if (i >= s.length()) throw new Exception("unexpected end of JSON");
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = str();
                    ws();
                    if (s.charAt(i++) != ':') throw new Exception("expected : in JSON");
                    m.put(k, value());
                    ws();
                    char d = s.charAt(i++);
                    if (d == '}') return m;
                    if (d != ',') throw new Exception("expected , in JSON");
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(value());
                    ws();
                    char d = s.charAt(i++);
                    if (d == ']') return l;
                    if (d != ',') throw new Exception("expected , in JSON");
                }
            }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new Exception("bad JSON at " + i);
            return Double.valueOf(s.substring(st, i));
        }

        private String str() throws Exception {
            if (s.charAt(i) != '"') throw new Exception("expected string in JSON at " + i);
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: sb.append(e);
                    }
                } else sb.append(c);
            }
        }
    }
}
