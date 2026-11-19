package com.fahim.cloudide;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Finds which languages a project uses. The rules mirror the detection script in the "auto" build
 * workflow (assets/templates/workflows/auto.yml), so the labels shown in the app match what is built.
 */
final class Detect {
    private Detect() { }

    private static final Set<String> PRUNE = new HashSet<>(Arrays.asList(
            "node_modules", "build", "target", ".git", ".gradle", "dist", "vendor", ".dart_tool",
            "cloudide-out", ".cloudide", ".venv", "venv", "Pods", ".idea"));
    private static final int MAX_DIR_DEPTH = 3; // files up to 4 levels deep, like the workflow

    /** Directories (relative, "." = root) that contain a file with the given name. */
    private static final class Found {
        final TreeMap<String, List<String>> byName = new TreeMap<>();

        List<String> of(String name) {
            List<String> l = byName.get(name);
            return l == null ? new ArrayList<String>() : l;
        }
    }

    private static boolean wanted(String n) {
        return n.equals("settings.gradle") || n.equals("settings.gradle.kts") || n.equals("build.gradle")
                || n.equals("build.gradle.kts") || n.equals("pubspec.yaml") || n.equals("package.json")
                || n.equals("Cargo.toml") || n.equals("pom.xml") || n.equals("go.mod") || n.equals("pyproject.toml")
                || n.equals("setup.py") || n.equals("CMakeLists.txt") || n.equals("Makefile") || n.equals("makefile")
                || n.equals("GNUmakefile") || n.endsWith(".sln") || n.endsWith(".csproj") || n.endsWith(".java");
    }

    private static void walk(File dir, String rel, int depth, Found out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String n = k.getName();
            if (k.isDirectory()) {
                if (depth < MAX_DIR_DEPTH && !PRUNE.contains(n)) walk(k, rel.equals(".") ? n : rel + "/" + n, depth + 1, out);
            } else if (wanted(n)) {
                String key = n.endsWith(".sln") ? "*.sln" : n.endsWith(".csproj") ? "*.csproj" : n.endsWith(".java") ? "*.java" : n;
                List<String> l = out.byName.get(key);
                if (l == null) {
                    l = new ArrayList<>();
                    out.byName.put(key, l);
                }
                if (!l.contains(rel)) l.add(rel);
            }
        }
    }

    /** True when child is strictly inside parent. */
    static boolean under(String child, String parent) {
        if (child.equals(parent)) return false;
        if (parent.equals(".")) return true;
        return (child + "/").startsWith(parent + "/");
    }

    private static boolean underOrEq(String c, String p) { return c.equals(p) || under(c, p); }

    private static boolean underAny(String c, List<String> parents) {
        for (String p : parents) if (under(c, p)) return true;
        return false;
    }

    private static boolean underOrEqAny(String c, List<String> parents) {
        for (String p : parents) if (underOrEq(c, p)) return true;
        return false;
    }

    private static File at(File root, String rel) { return rel.equals(".") ? root : new File(root, rel); }

    /** Does any build.gradle(.kts) up to 3 levels below dir match the regex? */
    private static boolean gradleMentions(File root, String rel, String regex) {
        return scanGradle(at(root, rel), 0, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    private static boolean scanGradle(File dir, int depth, Pattern p) {
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File k : kids) {
            if (k.isDirectory()) {
                if (depth < 2 && !k.getName().equals("node_modules") && scanGradle(k, depth + 1, p)) return true;
            } else if (k.getName().equals("build.gradle") || k.getName().equals("build.gradle.kts")) {
                try {
                    if (p.matcher(readText(k)).find()) return true;
                } catch (IOException ignored) {
                }
            }
        }
        return false;
    }

    private static String readText(File f) throws IOException { return Io.read(f); }

    private static boolean nativeSkip(File root, String d, List<String> gradle, List<String> flutter) {
        if ((d + "/").contains("/src/main/") || (d + "/").startsWith("src/main/")) return true;
        for (String g : gradle) if (underOrEq(d, g) && gradleMentions(root, g, "externalNativeBuild|ndkBuild|cmake")) return true;
        return underOrEqAny(d, flutter);
    }

    private static boolean has(File root, String d, String name) { return new File(at(root, d), name).exists(); }

    /** Labels in build order, e.g. [Node, Flutter, Android, Go]. Empty when nothing is recognised. */
    static List<String> languages(File root) {
        List<String> out = new ArrayList<>();
        if (new File(root, "build.sh").exists()) {
            out.add("build.sh");
            return out;
        }
        Found f = new Found();
        walk(root, ".", 0, f);

        List<String> flutter = new ArrayList<>();
        for (String d : f.of("pubspec.yaml")) {
            try {
                String t = readText(new File(at(root, d), "pubspec.yaml"));
                if (Pattern.compile("^\\s*flutter:", Pattern.MULTILINE).matcher(t).find()) flutter.add(d);
            } catch (IOException ignored) {
            }
        }

        List<String> gradleAll = new ArrayList<>(f.of("settings.gradle"));
        for (String d : f.of("settings.gradle.kts")) if (!gradleAll.contains(d)) gradleAll.add(d);
        if (gradleAll.isEmpty() && (new File(root, "build.gradle").exists() || new File(root, "build.gradle.kts").exists()))
            gradleAll.add(".");
        List<String> gradle = new ArrayList<>();
        for (String g : gradleAll) if (!underAny(g, flutter)) gradle.add(g);

        List<String> node = new ArrayList<>(), nodeAll = f.of("package.json");
        for (String d : nodeAll) if (!underAny(d, nodeAll) && !underOrEqAny(d, flutter)) node.add(d);

        List<String> rust = new ArrayList<>(), cargoAll = f.of("Cargo.toml");
        for (String d : cargoAll) {
            boolean skip = underAny(d, cargoAll) || underOrEqAny(d, flutter);
            for (String g : gradle) if (underOrEq(d, g) && gradleMentions(root, g, "cargo|rust")) skip = true;
            if (!skip) rust.add(d);
        }

        List<String> maven = new ArrayList<>(), pomAll = f.of("pom.xml");
        for (String d : pomAll) if (!underAny(d, pomAll)) maven.add(d);

        List<String> py = new ArrayList<>(f.of("pyproject.toml"));
        for (String d : f.of("setup.py")) if (!py.contains(d)) py.add(d);

        List<String> sln = f.of("*.sln"), dotnet = new ArrayList<>(sln);
        for (String d : f.of("*.csproj")) if (!underOrEqAny(d, sln)) dotnet.add(d);

        List<String> cmake = new ArrayList<>(), cmakeAll = f.of("CMakeLists.txt");
        for (String d : cmakeAll) if (!nativeSkip(root, d, gradle, flutter) && !underAny(d, cmakeAll)) cmake.add(d);

        List<String> make = new ArrayList<>(), makeAll = new ArrayList<>(f.of("Makefile"));
        for (String n : new String[]{"makefile", "GNUmakefile"}) for (String d : f.of(n)) if (!makeAll.contains(d)) makeAll.add(d);
        for (String d : makeAll) {
            boolean skip = nativeSkip(root, d, gradle, flutter);
            for (String m : new String[]{"CMakeLists.txt", "Cargo.toml", "package.json", "go.mod", "pom.xml", "pyproject.toml", "setup.py"})
                if (has(root, d, m)) skip = true;
            if (!skip) make.add(d);
        }

        if (!node.isEmpty()) out.add("Node");
        if (!flutter.isEmpty()) out.add("Flutter");
        if (!rust.isEmpty()) out.add("Rust");
        if (!gradle.isEmpty()) {
            boolean android = false;
            for (String g : gradle) if (gradleMentions(root, g, "com\\.android")) android = true;
            out.add(android ? "Android" : "Gradle");
        }
        if (!maven.isEmpty()) out.add("Maven");
        if (gradle.isEmpty() && maven.isEmpty() && flutter.isEmpty() && !f.of("*.java").isEmpty()) out.add("Java");
        if (!f.of("go.mod").isEmpty()) out.add("Go");
        if (!py.isEmpty()) out.add("Python");
        if (!dotnet.isEmpty()) out.add(".NET");
        if (!cmake.isEmpty()) out.add("CMake");
        if (!make.isEmpty()) out.add("Make");
        return out;
    }

    /** "Android, Rust, Go" or "Folder". */
    static String label(File root) {
        List<String> l = languages(root);
        if (l.isEmpty()) return "Folder";
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() == 0 ? "" : ", ").append(s);
        return sb.toString();
    }

    /** First line "# cloudide:<id>" marks a workflow written by CloudIDE; returns the id or "". */
    static String markerKind(String text) {
        if (text == null) return "";
        String first = text.split("\n", 2)[0].trim();
        String p = "# cloudide:";
        return first.toLowerCase(Locale.ROOT).startsWith(p) ? first.substring(p.length()).trim() : "";
    }

    private static final Pattern TS = Pattern.compile("^\\d{4}-\\d\\d-\\d\\dT[\\d:.]+Z ");

    /** The per-language PASS/FAIL table printed at the end of an auto build, or "" when absent. */
    static String summary(String logs) {
        StringBuilder sb = new StringBuilder();
        boolean on = false;
        for (String l : logs.split("\n")) {
            String x = TS.matcher(l.replace("\r", "")).replaceFirst("");
            if (!on) {
                if (x.contains("CloudIDE build summary")) on = true;
            } else {
                if (x.startsWith("##[")) break;
                sb.append(x).append('\n');
            }
        }
        return sb.length() == 0 ? "" : "--- Build summary ---\n" + sb.toString().trim() + "\n";
    }
}
