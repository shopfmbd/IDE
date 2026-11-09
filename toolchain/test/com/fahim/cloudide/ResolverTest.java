package com.fahim.cloudide;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.*;

/** Plain-JDK tests for MavenResolver and GradleDeps. Run: toolchain/test/run.sh */
public class ResolverTest {
    static int fails = 0, passes = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + name + (detail.isEmpty() ? "" : "  -> " + detail)); }
    }

    static final Map<String, byte[]> REPO = new HashMap<>();
    static int hits = 0;

    static void put(String path, String body) { REPO.put("https://dl.google.com/android/maven2/" + path, body.getBytes()); }

    static String pom(String g, String a, String v, String extra) {
        return "<project><groupId>" + g + "</groupId><artifactId>" + a + "</artifactId><version>" + v + "</version>" + extra + "</project>";
    }

    static String dep(String g, String a, String v, String more) {
        return "<dependency><groupId>" + g + "</groupId><artifactId>" + a + "</artifactId>" + (v == null ? "" : "<version>" + v + "</version>") + more + "</dependency>";
    }

    static String deps(String... d) { return "<dependencies>" + String.join("", d) + "</dependencies>"; }

    static void lib(String g, String a, String v, String extraPom, boolean aar) {
        String p = g.replace('.', '/') + "/" + a + "/" + v + "/" + a + "-" + v;
        put(p + ".pom", pom(g, a, v, (aar ? "<packaging>aar</packaging>" : "") + extraPom));
        put(p + (aar ? ".aar" : ".jar"), "bin:" + a + v);
    }

    static MavenResolver resolver(File cache) {
        return new MavenResolver(cache, new ArrayList<String>(), url -> {
            hits++;
            byte[] b = REPO.get(url);
            if (b == null) throw new FileNotFoundException(url);
            return b;
        }, null);
    }

    static MavenResolver.Dep d(String s) {
        String[] p = s.split(":");
        return new MavenResolver.Dep(p[0], p[1], p.length > 2 ? p[2] : "");
    }

    static String names(MavenResolver.Result r) {
        List<String> l = new ArrayList<>();
        for (MavenResolver.Artifact a : r.artifacts) l.add(a.artifact + ":" + a.version + (a.aar ? "@aar" : "") + (a.compileOnly ? "(co)" : ""));
        Collections.sort(l);
        return l.toString();
    }

    public static void main(String[] args) throws Exception {
        File tmp = Files.createTempDirectory("res").toFile();

        // ---- versions
        check("cmp 1.10 > 1.9", MavenResolver.compare("1.10", "1.9") > 0, "");
        check("cmp rc < release", MavenResolver.compare("1.0-rc1", "1.0") < 0, "");
        check("cmp alpha < beta", MavenResolver.compare("1.0-alpha02", "1.0-beta01") < 0, "");
        check("cmp 1.0 == 1.0.0", MavenResolver.compare("1.0", "1.0.0") == 0, "");
        check("cmp 1.0.1 > 1.0", MavenResolver.compare("1.0.1", "1.0") > 0, "");
        check("cmp 2.0-alpha > 1.9", MavenResolver.compare("2.0-alpha1", "1.9") > 0, "");

        // ---- plain transitive, scopes, optional
        lib("t", "c", "1.0", "", false);
        lib("t", "b", "1.0", deps(dep("t", "c", "1.0", "<scope>test</scope>"), dep("t", "opt", "1.0", "<optional>true</optional>"), dep("t", "rt", "1.0", "<scope>runtime</scope>")), false);
        lib("t", "rt", "1.0", "", false);
        lib("t", "a", "1.0", deps(dep("t", "b", "1.0", "")), false);
        MavenResolver r = resolver(new File(tmp, "c1"));
        MavenResolver.Result res = r.resolve(Arrays.asList(d("t:a:1.0")), new ArrayList<String>());
        check("transitive+scopes", names(res).equals("[a:1.0, b:1.0, rt:1.0]"), names(res));

        // ---- highest version wins
        lib("h", "x", "1.0", "", false);
        lib("h", "x", "2.0", "", false);
        lib("h", "p", "1", deps(dep("h", "x", "1.0", "")), false);
        lib("h", "q", "1", deps(dep("h", "x", "2.0", "")), false);
        res = resolver(new File(tmp, "c2")).resolve(Arrays.asList(d("h:p:1"), d("h:q:1")), new ArrayList<String>());
        check("highest wins", names(res).equals("[p:1, q:1, x:2.0]"), names(res));

        // ---- BOM + parent props
        put("b/bom/1.0/bom-1.0.pom", pom("b", "bom", "1.0", "<packaging>pom</packaging><properties><lv>3.1</lv></properties><dependencyManagement><dependencies>"
                + dep("b", "lib", "${lv}", "") + "</dependencies></dependencyManagement>"));
        lib("b", "lib", "3.1", "", false);
        res = resolver(new File(tmp, "c3")).resolve(Arrays.asList(d("b:lib")), Arrays.asList("b:bom:1.0"));
        check("bom version", names(res).equals("[lib:3.1]"), names(res));
        try {
            resolver(new File(tmp, "c3b")).resolve(Arrays.asList(d("b:lib")), new ArrayList<String>());
            check("no version fails", false, "");
        } catch (java.io.IOException e) { check("no version fails", e.getMessage().contains("No version"), e.getMessage()); }

        put("pp/parent/1/parent-1.pom", pom("pp", "parent", "1", "<packaging>pom</packaging><properties><dv>9</dv></properties><dependencyManagement><dependencies>"
                + dep("pp", "dd", "${dv}", "") + "</dependencies></dependencyManagement>"));
        put("pp/child/1/child-1.pom", "<project><parent><groupId>pp</groupId><artifactId>parent</artifactId><version>1</version></parent><artifactId>child</artifactId>"
                + deps(dep("pp", "dd", null, "")) + "</project>");
        put("pp/child/1/child-1.jar", "x");
        lib("pp", "dd", "9", "", false);
        res = resolver(new File(tmp, "c4")).resolve(Arrays.asList(d("pp:child:1")), new ArrayList<String>());
        check("parent props + inherited management", names(res).equals("[child:1, dd:9]"), names(res));

        // ---- aar + exclusions
        lib("e", "ex", "1", "", false);
        lib("e", "keep", "1", "", true);
        lib("e", "top", "1", deps(dep("e", "ex", "1", ""), dep("e", "keep", "1", "")), true);
        MavenResolver.Dep top = d("e:top:1");
        top.excludes.add("e:ex");
        res = resolver(new File(tmp, "c5")).resolve(Arrays.asList(top), new ArrayList<String>());
        check("aar and exclude", names(res).equals("[keep:1@aar, top:1@aar]"), names(res));
        check("aar files", res.aars(true).size() == 2 && res.jars(true).isEmpty(), "");

        // ---- module metadata redirect (multiplatform root -> android variant)
        put("k/kmp/1/kmp-1.module", "{\"variants\":[{\"name\":\"jvmApiElements\",\"attributes\":{\"org.gradle.usage\":\"java-api\",\"org.jetbrains.kotlin.platform.type\":\"jvm\"},"
                + "\"available-at\":{\"url\":\"../../kmp-jvm/1/kmp-jvm-1.module\",\"group\":\"k\",\"module\":\"kmp-jvm\",\"version\":\"1\"}},"
                + "{\"name\":\"androidRuntimeElements\",\"attributes\":{\"org.gradle.usage\":\"java-runtime\",\"org.jetbrains.kotlin.platform.type\":\"androidJvm\"},"
                + "\"available-at\":{\"url\":\"../../kmp-android/1/kmp-android-1.module\",\"group\":\"k\",\"module\":\"kmp-android\",\"version\":\"1\"}},"
                + "{\"name\":\"jsApi\",\"attributes\":{\"org.gradle.usage\":\"kotlin-api\",\"org.jetbrains.kotlin.platform.type\":\"js\"}}]}");
        put("k/kmp/1/kmp-1.pom", pom("k", "kmp", "1", ""));
        put("k/kmp-android/1/kmp-android-1.module", "{\"variants\":[{\"name\":\"releaseRuntimeElements\",\"attributes\":{\"org.gradle.usage\":\"java-runtime\",\"org.jetbrains.kotlin.platform.type\":\"androidJvm\"},"
                + "\"dependencies\":[{\"group\":\"k\",\"module\":\"dep\",\"version\":{\"requires\":\"2\"}}],\"files\":[{\"name\":\"kmp-android-1.aar\",\"url\":\"kmp-android-1.aar\"}]}]}");
        put("k/kmp-android/1/kmp-android-1.aar", "aar-bytes");
        put("k/kmp-android/1/kmp-android-1.pom", pom("k", "kmp-android", "1", "<packaging>aar</packaging>"));
        lib("k", "dep", "2", "", false);
        res = resolver(new File(tmp, "c6")).resolve(Arrays.asList(d("k:kmp:1")), new ArrayList<String>());
        check("module redirect", names(res).equals("[dep:2, kmp-android:1@aar]"), names(res));

        // ---- dynamic versions
        put("d/dyn/maven-metadata.xml", "<metadata><versioning><versions><version>1.0.0</version><version>1.2.5</version><version>1.10.0</version><version>2.0.0</version></versions></versioning></metadata>");
        lib("d", "dyn", "1.10.0", "", false);
        res = resolver(new File(tmp, "c7")).resolve(Arrays.asList(d("d:dyn:1.+")), new ArrayList<String>());
        check("1.+ picks highest 1.x", names(res).equals("[dyn:1.10.0]"), names(res));

        // ---- sha1
        lib("s", "bad", "1", "", false);
        put("s/bad/1/bad-1.jar.sha1", "0000000000000000000000000000000000000000");
        try {
            resolver(new File(tmp, "c8")).resolve(Arrays.asList(d("s:bad:1")), new ArrayList<String>());
            check("sha1 mismatch fails", false, "");
        } catch (java.io.IOException e) { check("sha1 mismatch fails", e.getMessage().contains("Checksum"), e.getMessage()); }
        lib("s", "good", "1", "", false);
        put("s/good/1/good-1.jar.sha1", MavenResolver.sha1("bin:good1".getBytes()) + "  good-1.jar");
        res = resolver(new File(tmp, "c9")).resolve(Arrays.asList(d("s:good:1")), new ArrayList<String>());
        check("sha1 ok", names(res).equals("[good:1]"), names(res));

        // ---- cache: second run makes no requests
        File c10 = new File(tmp, "c10");
        resolver(c10).resolve(Arrays.asList(d("t:a:1.0")), new ArrayList<String>());
        int before = hits;
        res = resolver(c10).resolve(Arrays.asList(d("t:a:1.0")), new ArrayList<String>());
        check("offline second run", hits == before && names(res).equals("[a:1.0, b:1.0, rt:1.0]"), "hits " + (hits - before));

        // ---- compileOnly
        res = resolver(new File(tmp, "c11")).resolve(Arrays.asList(co(d("t:a:1.0"))), new ArrayList<String>());
        check("compileOnly flag", res.jars(true).isEmpty() && res.jars(false).size() == 3, "");

        // ---- missing direct dependency
        try {
            resolver(new File(tmp, "c12")).resolve(Arrays.asList(d("no:such:1")), new ArrayList<String>());
            check("missing direct fails", false, "");
        } catch (java.io.IOException e) { check("missing direct fails", e.getMessage().contains("no:such:1"), e.getMessage()); }
        // missing transitive is a note, not a failure
        lib("m", "okay", "1", deps(dep("m", "gone", "1", "")), false);
        res = resolver(new File(tmp, "c13")).resolve(Arrays.asList(d("m:okay:1")), new ArrayList<String>());
        check("missing transitive noted", names(res).equals("[okay:1]") && !res.notes.isEmpty(), res.notes.toString());

        repoOptions(tmp);

        gradle(tmp);
        System.out.println(passes + " passed, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }

    /** App settings > Libraries: repository list changes, offline mode, damaged downloads. */
    static void repoOptions(File tmp) throws Exception {
        final String central = "https://repo1.maven.org/maven2/", jit = "https://jitpack.io/";
        final Map<String, byte[]> jp = new HashMap<>();
        String base = "com/github/u/r/library/0.1/library-0.1";
        jp.put(jit + base + ".pom", pom("com.github.u.r", "library", "0.1", "").getBytes());
        jp.put(jit + base + ".jar", "bin".getBytes());
        final int[] net = {0};
        MavenResolver.Fetch f = url -> {
            net[0]++;
            byte[] b = jp.get(url);
            if (b == null) throw new FileNotFoundException(url);
            return b;
        };
        List<MavenResolver.Dep> want = Arrays.asList(d("com.github.u.r:library:0.1"));
        File cj = new File(tmp, "c20");

        // not found without JitPack, and the "not found" note is kept for that same list
        try {
            new MavenResolver(cj, Arrays.asList(central), f, null, false, false).resolve(want, new ArrayList<String>());
            check("jitpack: absent without the repository", false, "");
        } catch (java.io.IOException e) {
            check("jitpack: absent without the repository", e.getMessage().contains("not found in 1 repositories") && e.getMessage().contains("repo1.maven.org"), e.getMessage());
        }
        int after = net[0];
        try {
            new MavenResolver(cj, Arrays.asList(central), f, null, false, false).resolve(want, new ArrayList<String>());
        } catch (java.io.IOException ignored) {
        }
        check("same list: no second lookup", net[0] == after, "requests " + (net[0] - after));

        // adding JitPack must find it at once: the old note belongs to the old list
        MavenResolver.Result res = new MavenResolver(cj, Arrays.asList(central, jit), f, null, false, false).resolve(want, new ArrayList<String>());
        check("jitpack: found after the list changed", names(res).equals("[library:0.1]"), names(res));

        // an empty (old format) marker is not trusted
        File old = new File(tmp, "c21");
        File marker = new File(old, "com/github/u/r/library/0.1/library-0.1.pom.missing");
        marker.getParentFile().mkdirs();
        marker.createNewFile();
        res = new MavenResolver(old, Arrays.asList(central, jit), f, null, false, false).resolve(want, new ArrayList<String>());
        check("old empty marker ignored", names(res).equals("[library:0.1]"), names(res));

        // an expired marker is ignored too
        File exp = new File(tmp, "c22");
        MavenResolver m1 = new MavenResolver(exp, Arrays.asList(central), f, null, false, false);
        try {
            m1.resolve(want, new ArrayList<String>());
        } catch (java.io.IOException ignored) {
        }
        File mk = new File(exp, "com/github/u/r/library/0.1/library-0.1.pom.missing");
        check("marker written", mk.isFile() && mk.length() > 0, "");
        mk.setLastModified(System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000);
        int b4 = net[0];
        try {
            new MavenResolver(exp, Arrays.asList(central), f, null, false, false).resolve(want, new ArrayList<String>());
        } catch (java.io.IOException ignored) {
        }
        check("expired marker asks again", net[0] > b4, "");

        // offline: the cache is used, nothing is requested
        MavenResolver.Fetch never = url -> { throw new AssertionError("network used in offline mode: " + url); };
        res = new MavenResolver(cj, Arrays.asList(central, jit), never, null, false, true).resolve(want, new ArrayList<String>());
        check("offline uses the cache", names(res).equals("[library:0.1]"), names(res));
        try {
            new MavenResolver(new File(tmp, "c23"), Arrays.asList(central, jit), never, null, false, true).resolve(want, new ArrayList<String>());
            check("offline: missing library stops", false, "");
        } catch (java.io.IOException e) {
            check("offline: missing library stops", e.getMessage().contains("offline"), e.getMessage());
        }

        // a repository that answers with an HTML page must not leave it behind as the POM
        MavenResolver.Fetch html = url -> "<html><body>Build queued</body></html>".getBytes();
        File ch = new File(tmp, "c24");
        try {
            new MavenResolver(ch, Arrays.asList(jit), html, null, false, false).resolve(want, new ArrayList<String>());
            check("html page is rejected", false, "");
        } catch (java.io.IOException e) {
            check("html page is rejected", e.getMessage().contains("Bad POM"), e.getMessage());
        }
        check("html page is not cached", !new File(ch, base + ".pom").exists(), "");
    }

    static MavenResolver.Dep co(MavenResolver.Dep d) { d.compileOnly = true; return d; }

    static void w(File f, String s) throws Exception {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), s.getBytes("UTF-8"));
    }

    static String list(GradleDeps g) {
        List<String> l = new ArrayList<>();
        for (MavenResolver.Dep d : g.deps) l.add(d + (d.compileOnly ? "(co)" : ""));
        return l.toString();
    }

    static void gradle(File tmp) throws Exception {
        // Groovy
        File p = new File(tmp, "g1");
        w(new File(p, "gradle.properties"), "kotlinVersion=1.9.0\n");
        w(new File(p, "app/build.gradle"), "ext.ver = '1.2.3'\napply plugin: 'com.android.application'\n"
                + "dependencies {\n  // implementation 'no:comment:1'\n  implementation 'androidx.core:core:1.12.0'\n  implementation \"com.x:y:$ver\"\n"
                + "  api group: 'g', name: 'n', version: '2'\n  compileOnly 'a:co:1'\n  testImplementation 'junit:junit:4'\n  implementation platform('b:bom:5')\n"
                + "  implementation 'b:managed'\n  implementation project(':lib')\n  kapt 'k:proc:1'\n  implementation 'o:two:1@aar'\n  implementation \"org.jetbrains.kotlin:kotlin-stdlib:${kotlinVersion}\"\n}\n"
                + "repositories { maven { url 'https://jitpack.io' } }\n");
        GradleDeps g = GradleDeps.read(p, new File(p, "app"));
        check("groovy deps", list(g).equals("[androidx.core:core:1.12.0, com.x:y:1.2.3, g:n:2, a:co:1(co), b:managed, o:two:1, org.jetbrains.kotlin:kotlin-stdlib:1.9.0]"), list(g));
        check("groovy bom", g.boms.equals(Arrays.asList("b:bom:5")), g.boms.toString());
        check("groovy local module", g.localModules.equals(Arrays.asList(":lib")), g.localModules.toString());
        check("groovy repo", g.repos.contains("https://jitpack.io"), g.repos.toString());
        check("kapt noted", g.notes.toString().contains("kapt"), g.notes.toString());

        // Kotlin DSL + catalog
        p = new File(tmp, "g2");
        w(new File(p, "gradle/libs.versions.toml"), "[versions]\ncore = \"1.13.1\"\nappcompat = \"1.7.0\"\n[libraries]\n"
                + "androidx-core-ktx = { group = \"androidx.core\", name = \"core-ktx\", version.ref = \"core\" }\n"
                + "androidx-appcompat = { module = \"androidx.appcompat:appcompat\", version.ref = \"appcompat\" }\n"
                + "material = \"com.google.android.material:material:1.12.0\"\n"
                + "compose-bom = { module = \"androidx.compose:compose-bom\", version = \"2024.06.00\" }\n"
                + "compose-ui = { module = \"androidx.compose.ui:ui\" }\n[bundles]\nui = [\n  \"material\",\n  \"androidx-appcompat\"\n]\n");
        w(new File(p, "app/build.gradle.kts"), "plugins { id(\"com.android.application\") }\ndependencies {\n"
                + "    implementation(libs.androidx.core.ktx)\n    implementation(libs.bundles.ui)\n    implementation(platform(libs.compose.bom))\n"
                + "    implementation(libs.compose.ui)\n    implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")\n"
                + "    debugImplementation(\"x:dbg:1\")\n    androidTestImplementation(\"x:test:1\")\n}\n");
        g = GradleDeps.read(p, new File(p, "app"));
        check("kts catalog deps", list(g).equals("[androidx.core:core-ktx:1.13.1, com.google.android.material:material:1.12.0, androidx.appcompat:appcompat:1.7.0, androidx.compose.ui:ui, com.squareup.okhttp3:okhttp:4.12.0, x:dbg:1]"), list(g));
        check("kts catalog bom", g.boms.equals(Arrays.asList("androidx.compose:compose-bom:2024.06.00")), g.boms.toString());

        // ---- regression tests for the review fixes
        // release-only libraries are not part of an on-device (debug) build
        p = new File(tmp, "g3");
        w(new File(p, "app/build.gradle"), "dependencies {\n    debugImplementation 'x:dbg:1'\n    releaseImplementation 'x:rel:1'\n    implementation 'x:main:1'\n}\n");
        g = GradleDeps.read(p, new File(p, "app"));
        check("release-only dep is skipped", list(g).equals("[x:dbg:1, x:main:1]"), list(g));

        // a quote left open in a comment or text must not swallow the rest of the file
        String stripped = GradleDeps.stripComments("// don't do this\nimplementation 'a:b:1'\n");
        check("stripComments keeps the line after an apostrophe", stripped.contains("a:b:1"), stripped);
        check("stripComments removes a commented value", !GradleDeps.stripComments("// minSdk 21\nminSdk 24").contains("21"), "");
        check("stripComments keeps a URL", GradleDeps.stripComments("url 'https://x.y/z'").contains("https://x.y/z"), "");

        // version tables in a catalog: { strictly = "1.2" }
        p = new File(tmp, "g4");
        w(new File(p, "gradle/libs.versions.toml"), "[versions]\nfoo = { strictly = \"1.2.3\" }\n[libraries]\nfoo = { module = \"a:foo\", version.ref = \"foo\" }\n");
        w(new File(p, "app/build.gradle"), "dependencies {\n    implementation libs.foo\n}\n");
        g = GradleDeps.read(p, new File(p, "app"));
        check("toml version table", list(g).equals("[a:foo:1.2.3]"), list(g));

        // a POM from a repository must not be able to point outside the cache
        lib("evil", "x", "1.0", deps(dep("..", "..", "1", "")), false);
        File cache2 = new File(tmp, "cache-evil");
        try {
            resolver(cache2).resolve(Arrays.asList(d("evil:x:1.0")), new ArrayList<String>());
        } catch (Exception e) {
            // an unsafe transitive dependency is skipped with a note, or rejected: both are fine
        }
        File[] outside = tmp.listFiles((dir, n) -> n.equals("x") || n.equals("1"));
        check("no file written outside the cache", outside == null || outside.length == 0, "");

        // a BOM that imports itself must not recurse forever
        String selfBom = pom("cyc", "bom", "1", "<dependencyManagement><dependencies>"
                + dep("cyc", "bom", "1", "<type>pom</type><scope>import</scope>")
                + dep("cyc", "managed", "9", "") + "</dependencies></dependencyManagement>");
        put("cyc/bom/1/bom-1.pom", selfBom);
        lib("cyc", "managed", "9", "", false);
        MavenResolver.Result cr = resolver(new File(tmp, "cache-cyc")).resolve(Arrays.asList(d("cyc:managed")), Arrays.asList("cyc:bom:1"));
        check("self-importing BOM resolves", names(cr).contains("managed:9"), names(cr));
    }
}
