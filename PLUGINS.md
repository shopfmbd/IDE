# Language plugins

Every language the IDE can compile on the phone is a plugin: one entry in `app/src/main/assets/plugins/catalog.json`.
The app reads that file, then `plugins.json` from the toolchain release (Settings > Update the language list, or
`plugin update`), then any plugin you imported. So a new language needs a catalog entry and a pack zip, not an app update.

## What the IDE does with them
- **Detects** the language from the project's files (`detect.ext`, `detect.files`) when you open a project and before every
  local build, and offers to download what is missing (Download / Not now / Don't ask again). Rust also pulls in C / C++.
- **Installs** `pack-<id>.zip` from the release into `files/usr` (native) or the toolchain folder (Kotlin), checks SHA-256.
- **Builds** with the steps in `build` (project marker steps, then per-file steps), parses compiler messages with `diag`
  into the Problems list, and runs interpreters with `lrun <file>` (`run`).
- Puts every installed plugin on the terminal's PATH, with the `env` it asks for.

## Terminal
    plugins                      list, and what this project uses
    plugin install <id>|needed   download (needed = everything this project is missing)
    plugin remove <id>           remove (libraries other plugins share are kept)
    plugin import <zip>          zip with plugin.json at the top and files under usr/
    plugin update                refresh the language list
    lbuild                       build every language found, on the phone
    lrun main.rb                 run a script with the plugin's interpreter

## Entry format
    { "id": "ruby", "label": "Ruby", "kind": "native", "mb": 60, "requires": ["clang"],
      "termux": "ruby",                           // termux-packages names; packs.yml builds the pack from this
      "detect": { "ext": [".rb"], "files": ["Gemfile"], "skipSuffix": [".d.ts"] },
      "env": { "GEM_HOME": "${HOME}/.gem" },      // ${PREFIX} ${HOME} ${CACHE}
      "probes": [["ruby", "--version"]],          // Check toolchain runs these
      "build": {
        "projects": [ { "marker": "Gemfile", "steps": [["bundle", "install"]] } ],
        "perFile":  [ { "ext": [".rb"], "match": "optional regex on the file text", "steps": [["ruby", "-c", "{file}"]] } ]
      },
      "run": ["ruby", "{file}"],
      "diag": { "re": "^(.+?):(\\d+): (.*)$", "file": 1, "line": 2, "msg": 3, "col": 0, "sev": 0 } }

Placeholders in steps: `{file} {rel} {name} {out} {dir} {cache} {proj}`. `kind` is `native` (arm64 programs in `usr/`),
`jvm` (dex jar run inside the app, only Kotlin) or `meta` (no download, only `requires`, e.g. TypeScript = Node + npm).
`sev` is the regex group whose text says error / warning (0 = always error).

## Shipped
Kotlin, C / C++, Rust, Go, Python, JavaScript / Node, TypeScript, Ruby, PHP, Lua, Perl, Zig, Nim, D, OCaml, Dart, Erlang,
Elixir, plus Java JDK (manual, for `java` / `javac` in the terminal). Java, Android (Java and Kotlin) and Gradle / Maven
JVM projects build inside the app with the core toolchain and need no plugin.

## Not on the phone
Flutter, .NET and Swift have no usable Android build of their SDK. Those projects stay on the GitHub build.
The native packs are built from termux-packages by `.github/workflows/packs.yml`; that workflow has not been run for the
new languages yet, so a job can fail on a package name or size: its log says which. The Kotlin pack and the others are separate jobs.

# Building an APK with SDK commands instead of Gradle

`lbuild` does this in one tap. To see or change every step, use the terminal:

    sdk init        writes build-apk.cmd for this project (once)
    apkbuild        runs it, line by line, showing each command

The commands are real tools: `aapt2` (native), `ecj`, `kotlinc` and `d8` (run inside the app), plus glue: `deps` (downloads the
libraries named in build.gradle / pom.xml from Google Maven and Maven Central, cached for offline builds), `mkmanifest` (adds
package=, merges library manifests), `mkbuildconfig`, `mkjar`, `apkpack`, `apksigner` (debug key, v1+v2+v3), `zipalign`, `unjar`.
Variables like `$PKG $MINSDK $ANDROID_JAR $WORK $OUT $DEPS_CP` are filled from the project; `set JAVA=11` changes one.
`a && b`, `a ; b`, quotes and `*` / `**` wildcards work. Any other line goes to the Android shell.

## What replaces the Android Gradle plugin, and what does not
Replaced: resource compile and link, R and BuildConfig, manifest merge (permissions, providers, services, receivers, activities
from AARs and local modules), Maven dependency download (POM and Gradle module metadata, BOM platforms, version catalogs,
highest version wins), local `project(":lib")` modules, ECJ, D8 multidex, aligned zip, v1+v2+v3 debug signing.
Not replaced: annotation processors (kapt, ksp, annotationProcessor are skipped and reported), the Compose compiler plugin,
R8 shrinking, release keystores, build variants / flavors, view binding and data binding code generation, Gradle plugins and custom tasks.
