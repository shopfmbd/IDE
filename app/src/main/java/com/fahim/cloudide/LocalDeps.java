package com.fahim.cloudide;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Glue between the build files of a project and MavenResolver: finds the libraries a module (and its local library
 * modules) asks for in build.gradle(.kts) or pom.xml, downloads them into files/m2 and hands back plain files.
 * This is what replaces "Gradle downloads the dependencies" in the on-phone build.
 */
final class LocalDeps {
    static final class Out {
        /** Class jars for the compile class path and for dexing. */
        final List<File> jars = new ArrayList<>();
        /** compileOnly jars: compile class path only. */
        final List<File> compileOnly = new ArrayList<>();
        final List<File> aars = new ArrayList<>();
        /** compileOnly AARs: their classes.jar is needed to compile, but they must not be packaged into the app. */
        final List<File> compileOnlyAars = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
    }

    private LocalDeps() { }

    static File cacheDir(Context c) { return new File(c.getFilesDir(), "m2"); }

    static boolean hasBuildFile(File dir) {
        return new File(dir, "build.gradle").isFile() || new File(dir, "build.gradle.kts").isFile() || new File(dir, "pom.xml").isFile();
    }

    /** Directories of local library modules named by project(":a:b") lines, searched recursively. */
    static List<File> localModules(File proj, File module) {
        List<File> out = new ArrayList<>();
        collect(proj, module, out, 0);
        return out;
    }

    private static void collect(File proj, File module, List<File> out, int depth) {
        if (depth > 6) return;
        GradleDeps g = GradleDeps.read(proj, module);
        for (String path : g.localModules) {
            File d = new File(proj, path.replaceFirst("^:", "").replace(':', '/'));
            if (!d.isDirectory() || d.equals(module) || out.contains(d)) continue;
            out.add(d);
            collect(proj, d, out, depth + 1);
        }
    }

    /**
     * Resolves the dependencies of module and of its local library modules.
     * dropKotlinStdlib: the Kotlin pack already supplies kotlin-stdlib, so a second copy would clash in D8.
     */
    static Out resolve(Context c, File proj, File module, List<File> libModules, boolean dropKotlinStdlib, MavenResolver.Log log) throws IOException {
        Out out = new Out();
        List<File> mods = new ArrayList<>();
        mods.add(module);
        mods.addAll(libModules);
        List<MavenResolver.Dep> deps = new ArrayList<>();
        List<String> boms = new ArrayList<>(), repos = new ArrayList<>();
        for (File m : mods) {
            GradleDeps g;
            boolean gradle = new File(m, "build.gradle").isFile() || new File(m, "build.gradle.kts").isFile();
            if (!gradle && new File(m, "pom.xml").isFile()) g = GradleDeps.readPom(new File(m, "pom.xml"));
            else g = GradleDeps.read(proj, m);
            for (MavenResolver.Dep d : g.deps) {
                boolean dup = false;
                for (MavenResolver.Dep e : deps) if (e.ga().equals(d.ga())) {
                    dup = true;
                    if (MavenResolver.compare(d.version, e.version) > 0) e.version = d.version;
                    if (!d.compileOnly) e.compileOnly = false;
                }
                if (!dup) deps.add(d);
            }
            for (String b : g.boms) if (!boms.contains(b)) boms.add(b);
            for (String r : g.repos) if (!repos.contains(r)) repos.add(r);
            out.notes.addAll(g.notes);
        }
        if (deps.isEmpty()) return out;
        // App settings > Libraries decides where libraries may come from. A file is downloaded once into files/m2
        // and every later build, of any project, reads it from there.
        boolean offline = Repos.offline(c);
        List<String> search = Repos.searchList(c, repos);
        if (search.isEmpty() && !offline) throw new IOException("All library repositories are turned off. Turn at least one on under App settings > Libraries.");
        log.log((offline ? "offline mode: using the library cache only" : "repositories: " + search.size()) + " (App settings > Libraries)");
        MavenResolver r = new MavenResolver(cacheDir(c), search, MavenResolver.http(Repos.waitForJitpack(c) ? 300 : 60), log, false, offline);
        MavenResolver.Result res;
        try {
            res = r.resolve(deps, boms);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            if (offline) throw new IOException(msg);
            String hint = "\nLibraries are downloaded once and then cached on the device, so this build needs a network connection this time.";
            if (msg.contains("not found in")) hint += "\nIf the library lives on GitHub, turn on JitPack; for another site add its address under App settings > Libraries.";
            hint += Hints.moved(msg);
            throw new IOException(msg + hint);
        }
        out.notes.addAll(res.notes);
        for (MavenResolver.Artifact a : res.artifacts) {
            if (dropKotlinStdlib && a.group.equals("org.jetbrains.kotlin") && a.artifact.startsWith("kotlin-stdlib")) continue;
            if (a.aar) (a.compileOnly ? out.compileOnlyAars : out.aars).add(a.file);
            else if (a.compileOnly) out.compileOnly.add(a.file);
            else out.jars.add(a.file);
        }
        return out;
    }

    static long cacheBytes(Context c) { return Io.size(cacheDir(c)); }

    /**
     * Forgets "not found" notes and saved version lists (maven-metadata.xml) so the next build asks the repositories
     * again. Downloaded libraries are kept. Returns how many files were removed.
     */
    static int refreshLookups(Context c) { return refresh(cacheDir(c), 0); }

    private static int refresh(File d, int depth) {
        File[] kids = d.listFiles();
        if (kids == null || depth > 20) return 0;
        int n = 0;
        for (File f : kids) {
            if (f.isDirectory()) n += refresh(f, depth + 1);
            else if (f.getName().endsWith(".missing") || f.getName().equals("maven-metadata.xml")) {
                if (f.delete()) n++;
            }
        }
        return n;
    }
}
