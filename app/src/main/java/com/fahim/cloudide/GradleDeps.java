package com.fahim.cloudide;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Reads the dependency lines of build.gradle / build.gradle.kts without running Gradle: "g:a:v" strings in Groovy and
 * Kotlin form, group/name/version maps, platform() BOMs, version catalogs (gradle/libs.versions.toml), simple
 * variables ($ver, ${ver}, gradle.properties), and extra maven { url } repositories. Anything it cannot understand
 * is returned in notes, never dropped silently. Pure Java, no Android classes.
 */
final class GradleDeps {
    final List<MavenResolver.Dep> deps = new ArrayList<>();
    final List<String> boms = new ArrayList<>();
    final List<String> repos = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    /** Names of local modules (project(":lib")) the build file mentions. */
    final List<String> localModules = new ArrayList<>();

    private final Map<String, String> vars = new HashMap<>();
    private final Map<String, String> catalog = new HashMap<>();          // alias -> g:a[:v]
    private final Map<String, List<String>> bundles = new HashMap<>();   // alias -> aliases

    /** configs that go on the compile class path and into the APK / that only compile. */
    private static final Pattern CFG = Pattern.compile("^(implementation|api|compile|runtimeOnly|compileOnly|debugImplementation|debugApi)$");

    static GradleDeps read(File projectRoot, File module) {
        GradleDeps g = new GradleDeps();
        g.loadProps(new File(projectRoot, "gradle.properties"));
        g.loadToml(new File(projectRoot, "gradle/libs.versions.toml"));
        File[] settings = {new File(projectRoot, "settings.gradle"), new File(projectRoot, "settings.gradle.kts"),
                new File(projectRoot, "build.gradle"), new File(projectRoot, "build.gradle.kts")};
        List<File> files = new ArrayList<>();
        for (File f : settings) if (f.isFile()) files.add(f);
        for (String n : new String[]{"build.gradle", "build.gradle.kts"}) {
            File f = new File(module, n);
            if (f.isFile() && !files.contains(f)) files.add(f);
        }
        List<String> texts = new ArrayList<>();
        for (File f : files) {
            try {
                texts.add(stripComments(read(f)));
            } catch (IOException e) {
                g.notes.add("cannot read " + f.getName() + ": " + e.getMessage());
            }
        }
        for (String t : texts) g.collectVars(t);
        for (String t : texts) g.collectRepos(t);
        // dependencies come from the module file only (the root build file holds plugin versions, not libraries)
        for (File f : new File[]{new File(module, "build.gradle"), new File(module, "build.gradle.kts")}) {
            if (!f.isFile()) continue;
            try {
                g.parseDeps(stripComments(read(f)));
            } catch (IOException e) {
                g.notes.add("cannot read " + f.getName() + ": " + e.getMessage());
            }
        }
        return g;
    }

    /** Dependencies of a Maven project (pom.xml): compile and runtime scope, versions from this file's properties and dependencyManagement. */
    static GradleDeps readPom(File pom) {
        GradleDeps g = new GradleDeps();
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
            }
            Document d = f.newDocumentBuilder().parse(pom);
            Element root = d.getDocumentElement();
            Map<String, String> props = new HashMap<>();
            Element pr = kid(root, "properties");
            if (pr != null) for (Node n = pr.getFirstChild(); n != null; n = n.getNextSibling())
                if (n instanceof Element) props.put(((Element) n).getTagName(), n.getTextContent().trim());
            String ver = txt(root, "version");
            Element parent = kid(root, "parent");
            if (ver.isEmpty() && parent != null) ver = txt(parent, "version");
            props.put("project.version", ver);
            props.put("version", ver);
            Map<String, String> managed = new HashMap<>();
            Element dm = kid(root, "dependencyManagement");
            Element dmd = dm == null ? null : kid(dm, "dependencies");
            if (dmd != null) for (Node n = dmd.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element)) continue;
                Element e = (Element) n;
                if ("import".equals(txt(e, "scope"))) g.boms.add(pomSubst(txt(e, "groupId"), props) + ":" + pomSubst(txt(e, "artifactId"), props) + ":" + pomSubst(txt(e, "version"), props));
                else managed.put(txt(e, "groupId") + ":" + txt(e, "artifactId"), pomSubst(txt(e, "version"), props));
            }
            Element ds = kid(root, "dependencies");
            if (ds != null) for (Node n = ds.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element)) continue;
                Element e = (Element) n;
                String scope = txt(e, "scope");
                if (scope.equals("test") || scope.equals("system")) continue;
                String ga = txt(e, "groupId") + ":" + txt(e, "artifactId");
                String v = pomSubst(txt(e, "version"), props);
                if (v.isEmpty()) v = managed.containsKey(ga) ? managed.get(ga) : "";
                MavenResolver.Dep dep = new MavenResolver.Dep(pomSubst(txt(e, "groupId"), props), pomSubst(txt(e, "artifactId"), props), v);
                dep.compileOnly = scope.equals("provided");
                Element ex = kid(e, "exclusions");
                if (ex != null) for (Node x = ex.getFirstChild(); x != null; x = x.getNextSibling())
                    if (x instanceof Element) dep.excludes.add(txt((Element) x, "groupId") + ":" + txt((Element) x, "artifactId"));
                g.deps.add(dep);
            }
            Element rs = kid(root, "repositories");
            if (rs != null) for (Node n = rs.getFirstChild(); n != null; n = n.getNextSibling())
                if (n instanceof Element) {
                    String u = txt((Element) n, "url");
                    if (u.startsWith("http")) g.repos.add(u);
                }
            if (parent != null) g.notes.add("the parent POM of " + pom.getName() + " is not read: dependencies declared only in the parent are not included");
        } catch (Exception e) {
            g.notes.add("cannot read " + pom.getName() + ": " + e.getMessage());
        }
        return g;
    }

    private static String pomSubst(String s, Map<String, String> props) {
        for (int i = 0; i < 6 && s.contains("${"); i++) {
            Matcher m = Pattern.compile("\\$\\{([^}]+)\\}").matcher(s);
            StringBuffer sb = new StringBuffer();
            while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(props.containsKey(m.group(1)) ? props.get(m.group(1)) : m.group()));
            m.appendTail(sb);
            s = sb.toString();
        }
        return s;
    }

    private static Element kid(Element e, String tag) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && ((Element) n).getTagName().equals(tag)) return (Element) n;
        return null;
    }

    private static String txt(Element e, String tag) {
        Element c = kid(e, tag);
        return c == null ? "" : c.getTextContent().trim();
    }

    private static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return new String(o.toByteArray(), "UTF-8");
        }
    }

    static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean inStr = false;
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                out.append(c);
                if (c == '\\' && i + 1 < s.length()) out.append(s.charAt(++i));
                else if (c == q || c == '\n') inStr = false; // a quote left open (an apostrophe in text) must not swallow the rest of the file
                continue;
            }
            if (c == '"' || c == '\'') {
                inStr = true;
                q = c;
                out.append(c);
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
                out.append('\n');
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < s.length() && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                i++;
            } else out.append(c);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ variables

    private void loadProps(File f) {
        if (!f.isFile()) return;
        try {
            for (String l : read(f).split("\n")) {
                l = l.trim();
                int i = l.indexOf('=');
                if (l.startsWith("#") || i <= 0) continue;
                vars.put(l.substring(0, i).trim(), l.substring(i + 1).trim());
            }
        } catch (IOException ignored) {
        }
    }

    private static final Pattern VARDEF = Pattern.compile("(?m)^\\s*(?:(?:val|var|def|final|String|const\\s+val|ext\\.|rootProject\\.ext\\.|extra\\[\"?)\\s*)?([A-Za-z_][\\w.]*)\"?\\]?\\s*=\\s*[\"']([^\"'\\n]*)[\"']\\s*$");

    private void collectVars(String t) {
        Matcher m = VARDEF.matcher(t);
        while (m.find()) if (!vars.containsKey(m.group(1))) vars.put(m.group(1), m.group(2));
    }

    private String subst(String s) {
        for (int i = 0; i < 5 && s.indexOf('$') >= 0; i++) {
            Matcher m = Pattern.compile("\\$\\{([\\w.]+)\\}|\\$([A-Za-z_][\\w]*)").matcher(s);
            StringBuffer sb = new StringBuffer();
            boolean any = false;
            while (m.find()) {
                String k = m.group(1) != null ? m.group(1) : m.group(2);
                String v = vars.get(k);
                if (v == null && k.contains(".")) v = vars.get(k.substring(k.lastIndexOf('.') + 1));
                if (v != null) any = true;
                m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
            }
            m.appendTail(sb);
            s = sb.toString();
            if (!any) break;
        }
        return s;
    }

    // ------------------------------------------------------------------ repositories

    private static final Pattern URLS = Pattern.compile("maven\\s*(?:\\{[^}]*?url\\s*=?\\s*(?:uri\\s*\\()?|\\(\\s*(?:url\\s*=\\s*)?(?:uri\\s*\\()?)\\s*[\"']([^\"']+)[\"']");

    private void collectRepos(String t) {
        Matcher m = URLS.matcher(t);
        while (m.find()) {
            String u = subst(m.group(1));
            if (u.startsWith("http") && !repos.contains(u)) repos.add(u);
        }
        if (t.contains("jitpack") && !repos.contains("https://jitpack.io")) repos.add("https://jitpack.io");
    }

    // ------------------------------------------------------------------ version catalog

    private void loadToml(File f) {
        if (!f.isFile()) return;
        String section = "";
        Map<String, String> versions = new HashMap<>();
        Map<String, String> rawLibs = new LinkedHashMap<>();
        try {
            String text = read(f);
            // join multi-line arrays so each entry is one line
            List<String> logical = new ArrayList<>();
            String pending = null;
            for (String l : text.split("\n")) {
                String x = l.replaceAll("\\s+#.*$", "").trim();
                if (pending != null) {
                    pending += " " + x;
                    if (bal(pending) <= 0) {
                        logical.add(pending);
                        pending = null;
                    }
                    continue;
                }
                if (!x.startsWith("[") && x.contains("=") && bal(x) > 0) {
                    pending = x;
                    continue;
                }
                logical.add(x);
            }
            for (String l : logical) {
                l = l.trim();
                if (l.isEmpty() || l.startsWith("#")) continue;
                if (l.startsWith("[")) {
                    section = l.replaceAll("[\\[\\]\\s]", "");
                    continue;
                }
                int eq = l.indexOf('=');
                if (eq <= 0) continue;
                String k = l.substring(0, eq).trim(), v = l.substring(eq + 1).trim();
                if (section.equals("versions")) versions.put(k, versionOf(v));
                else if (section.equals("libraries")) rawLibs.put(k, v);
                else if (section.equals("bundles")) {
                    List<String> l2 = new ArrayList<>();
                    Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(v);
                    while (m.find()) l2.add(norm(m.group(1)));
                    bundles.put(norm(k), l2);
                }
            }
            for (Map.Entry<String, String> e : rawLibs.entrySet()) {
                String v = e.getValue(), coord = "";
                if (v.startsWith("\"")) {
                    coord = unq(v);
                } else {
                    String mod = field(v, "module"), grp = field(v, "group"), nm = field(v, "name");
                    String ver = field(v, "version");
                    String ref = field(v, "version.ref");
                    if (ref.isEmpty()) {
                        Matcher vm = Pattern.compile("version\\s*=\\s*\\{[^}]*?(?:ref|strictly|require|prefer)\\s*=\\s*\"([^\"]+)\"").matcher(v);
                        if (vm.find()) {
                            String r = vm.group(1);
                            ver = versions.containsKey(r) ? versions.get(r) : r;
                        }
                    } else ver = versions.containsKey(ref) ? versions.get(ref) : ref;
                    String ga = !mod.isEmpty() ? mod : grp + ":" + nm;
                    coord = ver.isEmpty() ? ga : ga + ":" + ver;
                }
                catalog.put(norm(e.getKey()), coord);
            }
        } catch (IOException ignored) {
        }
    }

    /** Opening minus closing square brackets outside quotes. */
    private static int bal(String x) {
        int n = 0;
        boolean q = false;
        for (int i = 0; i < x.length(); i++) {
            char c = x.charAt(i);
            if (c == '"') q = !q;
            else if (!q && c == '[') n++;
            else if (!q && c == ']') n--;
        }
        return n;
    }

    private static String field(String inline, String key) {
        Matcher m = Pattern.compile("(?:^|[{,\\s])" + Pattern.quote(key) + "\\s*=\\s*\"([^\"]*)\"").matcher(inline);
        return m.find() ? m.group(1) : "";
    }

    /** A [versions] value: "1.2.3", or a table such as { strictly = "1.2.3" } / { require = ... } / { prefer = ... }. */
    private static String versionOf(String v) {
        if (!v.startsWith("{")) return unq(v);
        Matcher m = Pattern.compile("(?:strictly|require|prefer)\\s*=\\s*\"([^\"]+)\"").matcher(v);
        return m.find() ? m.group(1) : "";
    }

    private static String unq(String v) { return v.replaceAll("^\"|\"$", "").trim(); }

    private static String norm(String alias) { return alias.replace('-', '.').replace('_', '.'); }

    // ------------------------------------------------------------------ dependencies

    private static final Pattern LINE = Pattern.compile("(?m)^\\s*([A-Za-z]+)\\b\\s*(.+?)\\s*$");

    /** "('a:b:1')", "('a:b:1') {" and "platform('x')" all become the bare argument text. */
    static String argOf(String rest) {
        String r = rest.trim();
        if (r.endsWith("{")) r = r.substring(0, r.length() - 1).trim();
        if (r.startsWith("(") && r.endsWith(")")) {
            int depth = 0;
            boolean outer = true;
            for (int i = 0; i < r.length(); i++) {
                char c = r.charAt(i);
                if (c == '(') depth++;
                else if (c == ')' && --depth == 0 && i < r.length() - 1) { outer = false; break; }
            }
            if (outer) r = r.substring(1, r.length() - 1).trim();
        }
        return r;
    }

    private void parseDeps(String t) {
        // only inside "dependencies { ... }" blocks
        int from = 0;
        while (true) {
            Matcher dm = Pattern.compile("(?m)^\\s*dependencies\\s*\\{").matcher(t);
            if (!dm.find(from)) break;
            int open = dm.end() - 1, depth = 0, i = open;
            for (; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) break;
            }
            parseBlock(t.substring(open + 1, Math.min(i, t.length())));
            from = i;
        }
    }

    private void parseBlock(String body) {
        for (String raw : body.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher m = LINE.matcher(line);
            if (!m.matches()) continue;
            String cfg = m.group(1), arg = argOf(m.group(2));
            if (cfg.equals("kapt") || cfg.equals("ksp") || cfg.equals("annotationProcessor") || cfg.equals("kaptDebug")) {
                notes.add("annotation processor " + Trunc.t(arg) + " is not run on the phone (kapt, ksp and annotationProcessor are skipped)");
                continue;
            }
            if (!CFG.matcher(cfg).matches()) continue;
            boolean compileOnly = cfg.equals("compileOnly");
            // platform / enforcedPlatform
            Matcher pf = Pattern.compile("^(?:enforcedPlatform|platform)\\s*\\(\\s*(.+?)\\s*\\)$").matcher(arg);
            if (pf.matches()) {
                String c = coordOf(pf.group(1));
                if (c != null) boms.add(c);
                else notes.add("could not read platform " + Trunc.t(arg));
                continue;
            }
            Matcher pj = Pattern.compile("^project\\s*\\(\\s*(?:path\\s*=\\s*)?[\"']([^\"']+)[\"']").matcher(arg);
            if (pj.find()) {
                localModules.add(pj.group(1));
                continue;
            }
            if (arg.startsWith("files(") || arg.startsWith("fileTree(")) continue; // handled by the libs/ folder scan
            // bundles
            Matcher bd = Pattern.compile("^libs\\.bundles\\.([\\w.]+)").matcher(arg);
            if (bd.find()) {
                List<String> al = bundles.get(norm(bd.group(1)));
                if (al == null) {
                    notes.add("unknown bundle " + bd.group(1));
                    continue;
                }
                for (String a : al) addCoord(catalog.get(a), compileOnly, a);
                continue;
            }
            String c = coordOf(arg);
            if (c == null) {
                if (arg.contains("(") || arg.contains(".")) notes.add("could not read dependency line: " + Trunc.t(line));
                continue;
            }
            addCoord(c, compileOnly, arg);
        }
    }

    private void addCoord(String c, boolean compileOnly, String what) {
        if (c == null) {
            notes.add("catalog entry for " + what + " not found in gradle/libs.versions.toml");
            return;
        }
        String[] p = c.split(":");
        if (p.length < 2) {
            notes.add("bad dependency " + c);
            return;
        }
        MavenResolver.Dep d = new MavenResolver.Dep(p[0], p[1], p.length > 2 ? p[2] : "");
        d.compileOnly = compileOnly;
        for (MavenResolver.Dep e : deps) if (e.ga().equals(d.ga())) {
            if (d.version.length() > 0 && MavenResolver.compare(d.version, e.version) > 0) e.version = d.version;
            if (!compileOnly) e.compileOnly = false;
            return;
        }
        deps.add(d);
    }

    /** "g:a:v" from a quoted string, a group:/name:/version: map, or a libs.x.y catalog accessor. null when unknown. */
    private String coordOf(String arg) {
        arg = arg.trim();
        Matcher q = Pattern.compile("^[\"']([^\"']+)[\"']").matcher(arg);
        if (q.find()) {
            String s = subst(q.group(1));
            // "g:a:v@aar" and classifiers: keep g:a:v
            int at = s.indexOf('@');
            if (at > 0) s = s.substring(0, at);
            String[] p = s.split(":");
            if (p.length >= 4) s = p[0] + ":" + p[1] + ":" + p[2];
            if (s.indexOf('$') >= 0) return null;
            return s;
        }
        Matcher mp = Pattern.compile("group\\s*[=:]\\s*[\"']([^\"']+)[\"']\\s*,\\s*name\\s*[=:]\\s*[\"']([^\"']+)[\"'](?:\\s*,\\s*version\\s*[=:]\\s*[\"']([^\"']+)[\"'])?").matcher(arg);
        if (mp.find()) return subst(mp.group(1)) + ":" + subst(mp.group(2)) + (mp.group(3) == null ? "" : ":" + subst(mp.group(3)));
        Matcher cat = Pattern.compile("^libs\\.([\\w.]+)").matcher(arg);
        if (cat.find()) {
            String key = norm(cat.group(1));
            if (catalog.containsKey(key)) return catalog.get(key);
            // accessors like libs.androidx.core.ktx.get() or libs.versions.x are not libraries
            for (String s : new String[]{".get", ".asProvider"}) if (key.endsWith(s)) key = key.substring(0, key.length() - s.length());
            return catalog.get(key);
        }
        return null;
    }

    private static final class Trunc {
        static String t(String s) { return s.length() > 90 ? s.substring(0, 90) + "..." : s; }
    }
}
