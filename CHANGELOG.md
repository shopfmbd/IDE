# 4.5.10

- The zip no longer contains a `.git` folder. It carried a remote and author of another account, so unzipping it into a repository made git treat the project folder as a nested repository (an arrow folder on GitHub, no files) and pushes went to the wrong place.
- tools/termux-push.sh: no account or repository is baked in. Owner and repository come from the working copy's `origin` remote, then the GitHub CLI login; the git name and email come from git config. It only asks when none of these is known. It also deletes any `.git` found inside a zip before copying, and accepts a zip whose files are directly inside (no `IDE-main` folder).

# 4.5.9

- tools/termux-push.sh: signs in through the browser with GitHub CLI (code shown, approved at github.com/login/device) instead of asking for a token. The token prompt remains only as a fallback.

# 4.5.8

- New App settings > Libraries page: choose where libraries come from. Built-in repositories (Google Maven, Maven Central, JitPack, Gradle Plugin Portal, a Central mirror on Google Cloud, Aliyun, Clojars, JBoss, Huawei) each have a switch, you can add your own Maven repository address, and repositories written in a project's build files are still searched first (switch). JitPack is on by default, which fixes `com.github.*` libraries such as `com.github.yausername.youtubedl-android:library` ("not found in 3 repositories").
- Download once, use offline: a library is downloaded the first time and every later build of any project reads the saved file. New Offline mode switch uses the cache only and stops with a clear message when a library is missing. New "Look again for missing libraries" button.
- Fixed: a "not found" note was kept forever, so adding a repository after a failed build changed nothing. A note now only counts for the same repository list, and for 24 hours.
- Fixed: an error or "build queued" web page from a repository could be saved as a POM or jar and then reused. It is rejected and deleted.
- JitPack waits up to 5 minutes for the first build of a library instead of failing after 1 minute. The "not found" error now lists the repositories that were tried.
- New tests: repository list changes, expired and old-format notes, offline mode, error pages.

# 4.5.6

- tools/termux-push.sh: fixed the causes of two warnings. apt-get replaces pkg (pkg calls the interactive apt, which warns "no stable CLI interface"), and the push uses the plain repository URL with the token passed through a temporary GIT_ASKPASS helper instead of embedding it in the URL (an embedded login made git print "redirecting to"). The token is also no longer on the command line.

# 4.5.5

- Folder picker: "Open folder in place", "Import folder (copy)", the Projects folder setting and toolchain folder import use the system folder picker instead of a typed path.
- No hardcoded GitHub account. The toolchain and language pack URLs are built from the username in Settings > GitHub (and ask for it when it is empty). New Email field sets the author of pushed commits. tools/termux-push.sh asks for the username and email instead of baking one in.
- Quality pass. Fixes: deleting a project or folder that contains a symbolic link no longer deletes the files the link points to; new file, rename and duplicate can no longer step outside the project (../) or silently overwrite an existing file; renaming a project keeps its own GitHub token, and deleting one forgets it (a new project with the same name no longer inherits it); with auto save off, Back and Close project ask before saving or discarding instead of always saving; "Get APK" now picks the newest APK; JSON formatting no longer turns "/" into "\\/"; the terminal no longer loses keyboard focus while output scrolls; a finished background job can no longer crash a screen that was already closed; Clone lists more than the first 100 repositories; file badges (modified/new) are correct right after Clone or Pull.
- Performance: the editor no longer re-counts every line of the file on each frame or cursor move (large files scroll and type smoothly); the project list is loaded off the UI thread and the search box filters in memory; terminal and build log append output instead of re-laying out 100-200 KB each time; Settings measures cache folders in the background; copying files streams instead of loading them into memory.
- Build engine review. Fixes: `${applicationId}` in the app's own manifest is now filled in (provider authorities and FileProvider broke on device builds); library files named `META-INF/services/...License...` were dropped from the APK, which could break ServiceLoader; commented-out lines in build.gradle (`// minSdk 21`) were read as real settings; `releaseImplementation` libraries were added to debug builds; compileOnly AARs were packaged into the APK; catalog version tables (`{ strictly = "1.2" }`) were read as text; a malicious POM could write outside the library cache (`..` in group/artifact/version), and a BOM that imports itself overflowed the stack; library manifests with a DOCTYPE are no longer parsed; project names containing a space broke `$PROJ`, `$SRC`, `$WORK` and shell lines in the terminal build script; builds for non-Android languages now keep running with the screen off (foreground service, shared between overlapping builds); a failed language-plugin step no longer leaves its error pattern active for the next job; a catalog pattern with a non-numeric line number no longer aborts the build; Cancel now really stops a program that ignores SIGTERM; the self-test can no longer hang on a stuck program; output of aapt2 with non-ASCII characters is no longer garbled; Remove of a language pack now also deletes its symbolic links; an imported plugin.json can no longer use a path-like id or a link name that leaves the app folder; the APK writer refuses more than 65535 files instead of writing a corrupt zip; upper/lower-casing no longer depends on the phone's language (Turkish "I" broke file-type and version matching).
- New tests (toolchain/test/run.sh): release-only dependencies, version tables, unsafe repository paths, self-importing BOM, DOCTYPE in a library manifest.
- Added tools/termux-push.sh: unpacks the project in Termux, backs it up to internal storage (zip + git bundle, also after every commit) and pushes it to GitHub. The default toolchain release owner is now claudeai420420-svg.
- Fixed: the lower part of the editor showed line numbers but no code (the editor had a 90dp bottom padding, and Android clips text at the padding).
- Cleanup: Tools.java split into Diag, Check, LogParser, Fmt and Snippets; one shared build callback; removed unused code (CloudBuild.ensureWorkflow, NativeExec.hookReady, SettingsActivity.packsMenu, HomeActivity.kindOf, unused imports); UI text moved to strings.xml; committed __pycache__ removed and .gitignore completed.
- kotlinc: the hidden-API exemption is no longer set before every compile. It is applied (and retried once) only when a compile fails with the sun.misc.Unsafe symptom; a failure now reports its real cause instead of InvocationTargetException.
- The build log starts with a "Speed boost: ON/PARTIAL/OFF" line showing CPU priority, wake lock and foreground service.
- On-device builds run with a speed boost: higher CPU priority, a wake lock and a foreground service (notification "Building your app") so Android does not slow, freeze or kill the build on low-end phones.
- Manifest merge: a provider declared by several libraries (androidx.startup InitializationProvider) now gets the meta-data of all of them, and tools:node="remove" in the app manifest is honored. Fixes "ProcessLifecycleInitializer cannot be initialized lazily" crashes of locally built apps.
- Local builds add a crash logger to the app (cloudide.crashlog: provider + CrashActivity). A crash opens a screen with the stack trace, Copy and Share; a copy is saved as crash-last.txt in the app's external files folder. Add an empty .nocrashlog file to the project to turn it off.
- Local builds now package Java resources of libraries (META-INF/services ...), build only changed resources and reuse compiled code when nothing changed.
- kotlinc no longer prints "your JDK doesn't seem to support mapped buffer unmapping". Its fast jar file system calls
  `sun.misc.Unsafe.invokeCleaner`, which Android lacks; that class is now pointed at `cloudide.shim.Unsafe`, which has a no-op
  `invokeCleaner` (the garbage collector releases mapped jars). Needs a rebuilt Kotlin pack. The warning is not filtered out:
  if it still appears, the fix did not apply.
- Jetpack Compose projects: the Kotlin pack now carries the Compose compiler plugin (same version as kotlinc, classes inside
  `kotlinc.dex.jar`, service files in `compose-plugin.jar`). The app passes `-Xplugin=compose-plugin.jar` when a Kotlin source
  uses `androidx.compose` or `@Composable`. Without the plugin the build failed in code generation at `remember { ... }`.
  An old pack without the plugin gives a clear message instead. The JDK workaround flag is no longer sent up front
  (Kotlin 2.4 warns about it); it is only tried when kotlinc refuses the runtime.
- Kotlin pack: the build now picks the newest *stable* Kotlin (x.y.z) by default instead of the newest upload, which can be a
  Beta/RC (2.5.0-Beta2 was the newest upload on 2026-10-26). New workflow input `kotlin`: `stable` (default), `latest`, or an exact version.
- kotlinc on Android: IntelliJ's `Unsafe` wrapper is redirected to `cloudide.shim.Unsafe` (no `getAndAddInt` on Android);
  `PathUtil` can find its jar (resource lookup answered by the class loader); trove4j is bundled.
- kotlinc retries instead of fixed flags: the Java 9 style runtime is first handled with `-Xallow-pre-17-runtime-jdk`, then (Kotlin
  2.5.20-Beta1 and later) by reporting `java.specification.version` as 17 while the compiler runs; if the LightTree front-end fails
  in `TreeRawFirDeclarationBuilderProxy` the compile is repeated with `-Xuse-fir-lt=false` (PSI). The choice is remembered per app run.
- Self-test: shows which front-end was used, a two-file Kotlin build (data class, extension, sealed class, lambdas), and the
  causes of a failing compile.

# 4.5.0
- Native packs now run at targetSdk 37. `libexec_hook.so` (LD_PRELOAD) sends exec / posix_spawn of files in the app's folders
  through `/system/bin/linker64`, handles `#!` scripts (including `#!/usr/bin/env x`), and fixes `/proc/self/exe`.
  `libcloudexec.so` starts the first program, is Go's `-toolexec` / `-exec` / `CC` wrapper, and has an experimental loader for
  static programs. Check toolchain tests a program started by a program (python -> python, node -> node, sh -> script).
- Build: `native/build.sh` (NDK) runs in `.github/workflows/build.yml`; `native/test/host_test.sh` tests the logic on Linux.
- Known limits and fallbacks: NATIVE_EXEC.md.

## targetSdk 37 + direct APK download
- CloudIDE now targets Android 17 (API 37). Android refuses plain exec from app storage at this target, so native pack programs (clang, rust, go, python, node) are started through `/system/bin/linker64`. The old check that blocked them outright is gone; Check toolchain really starts each tool and reports OK/FAIL. Programs those tools start themselves may still be refused; use `cloudide.targetSdk=28` in gradle.properties if you need that. Java, Kotlin and Android APK builds are unaffected.
- The build workflow uploads a single `CloudIDE.apk` as a direct download (no zip, no nested folders).

# 4.5.8 - professional menus and settings

- **Languages page**: every language is its own row with its status (Installed, Not installed with size, Requires). Tap a language to download it from your GitHub release. Downloading no longer needs any file.
- **Install from file** accepts a pack-<language>.zip, the zip GitHub gives you for a workflow artifact (the pack is unpacked from it), or a plugin zip with plugin.json. Before, only plugin zips worked and anything else failed with "no plugin.json at its top level".
- **Private repositories**: if a language download answers 404 and a GitHub token is saved, the app retries through the GitHub API. A 404 now says that the workflow has to publish the pack first.

- **Build dialog**: it said "Downloaded from GitHub into the IDE" even for local builds. It now reports "Built locally." or "Built with GitHub Actions."
- **Build method** is named Local or GitHub Actions everywhere.
- **Project settings** is a bottom sheet with a fixed Cancel / Save bar. It opens with the Build method choice. Local shows no GitHub options, only a shortcut to Build tools. GitHub Actions shows Repository, Workflow and Advanced options (compiler, Gradle task, workflow file, output files, versions, project token). Upload and Download on a Local project open the sheet with the repository fields.
- **App settings** opens a plain list of topics with icons: Appearance, Editor, Build tools, Languages, GitHub account, Storage, Diagnostics, About. Each topic is its own page with grouped cards. The status lists that used to fill the first page now live on their own pages.
- **Editor menu** grouped as Build and install, GitHub, Files and search, Edit text, View, then Project settings, App settings and Close project.
- Messages and dialogs were reworded to be shorter and neutral, and error messages point at the new settings paths.

# CloudIDE 4.4.0 - crash reports, app log, tidier Settings, bug fixes

## Finding bugs (new)
- **App log** (`AppLog`): timestamped, saved to `files/logs/cloudide.log` (rotates at 256 KB), so it survives a crash. Errors that used to vanish into `catch` blocks in the editor, import, clone, create-project and Settings jobs are now recorded with their stack trace.
- **Crash handler**: an uncaught exception is written to the log before the app dies. On the next start a dialog says "CloudIDE closed unexpectedly", shows the error and opens the log.
- **Logs and diagnostics screen** (`LogActivity`): filter Everything / Warnings and errors / Errors only, errors in red and warnings in amber, copy, share as a file, clear, jump to end. It opens from Home (menu), Settings > Diagnostics and Editor > View > App log and diagnostics. The report starts with app version, Android version, device, memory and toolchain status.
- Settings > Diagnostics > **Verbose log**: also records every build output line (off by default).
- Startup: if unpacking the bundled toolchain threw anything other than IOException, the background thread killed the app. It is now caught and logged.

## Settings
- Reordered by how often they are used: Appearance, Editor, GitHub account, Build on this phone, Languages and compilers, Storage, Diagnostics, About. Shorter section names.
- "Remove token" asks first and is hidden when no token is saved.
- The projects folder is checked (absolute, creatable, writable) before it is saved. Before, a bad path was stored and project listing silently broke.
- Long jobs (toolchain, plugins, import) no longer touch dialogs after the screen was closed.

## Bugs fixed
- Build log froze the UI on long builds: the whole log (up to 200 KB) was re-drawn for every line. Lines are now batched and drawn about 8 times a second.
- Rename project accepted names with `/` (which moves the folder), existing names and empty text. It now validates, and the "last opened" order follows the rename.
- Import folder silently merged into an existing project, could copy a folder into itself, and ran on the UI thread. It now refuses both cases and copies in the background with a progress dialog.
- Failed New project / Clone left a half-made folder that then blocked the same name. It is deleted on failure.

## Not verified
There was no Android SDK, network or device here, so the app was **not built or run**. Checked: every changed file parses and every project symbol they use resolves (compiled with a plain JDK, ignoring missing Android classes). Not checked: the build itself, layouts, lint, Java flow analysis beyond a manual read. If Gradle reports an error, copy it from the CI log and send it.

# CloudIDE 4.3.4 - Android 17 (API 37) projects

- Local build reads `compileSdk` (also `compileSdkVersion`, `android-37`) from build.gradle(.kts). If it is newer than the bundled android.jar, the log says so instead of failing later with unresolved symbols. Default target for projects without one is now 37.
- New project templates use compileSdk / targetSdk 37.
- `bundle-jars.sh`, `toolchain.yml` and `packs.yml` install `platforms;android-37` when the runner lacks it, so android.jar is the Android 17 one.

## Not verified
No network or SDK here: the scripts were not run and the app was not compiled. To get the Android 17 android.jar, rebuild the toolchain (delete assets/toolchain jars first) or re-run toolchain.yml.

# CloudIDE 4.3.3 - fix: ECJ "No static method version()Ljava/lang/Runtime$Version" and the dexdump self-test

- **Cause of "FAIL ECJ Java 21 / 17 ... NoSuchMethodError ... Runtime$Version":** the newest ECJ calls `Runtime.version()` (Java 9). Android has no `Runtime.version()` and no `Runtime.Version`, D8 cannot desugar them, so the dexed compiler died before it read any source. Every level of the ladder failed the same way, which is why no Java version worked.
- **Fix:** new `toolchain/patch_java9.py` rewrites ECJ, R8 and apksig *before* D8 runs. It edits only the class-file constant pool: every `java/lang/Runtime$Version` becomes `cloudide/shim/RtVersion`, and the single `Runtime.version()` call becomes `RtVersion.current()`. `toolchain/src/cloudide/shim/RtVersion.java` is a small Java 8 class with the same API (`feature()`, `parse()`, `compareTo()` ...). It reports Java 21 (override with `-Dcloudide.java.feature=N`). The shim is dexed into `ecj.dex.jar`, `d8.dex.jar` and `sign.dex.jar`.
- `bundle-jars.sh` and `toolchain.yml` run the patch, then fail the build if any dex still names `Runtime$Version`. They write `java9-shim 1` into `VERSIONS.txt`; `bundle-jars.sh` no longer skips a rebuild when old jars lack that line.
- The app replaces an older installed ecj/d8/sign/android.jar/jdk-stubs set with the bundled one when the bundled `VERSIONS.txt` has `java9-shim` and the installed one does not. No manual "remove toolchain" step. Downloaded `aapt2` and language packs are untouched.
- Self-test: `dexdump starts` no longer fails. dexdump with no file prints its error to Android's log, so it exits 1 with no output; any exit code below 126 now counts as "started". A real failure prints its exit code.
- If a tool still hits `Runtime$Version`, the error now says why and what to do.
- Test: `toolchain/test/patch_test.sh` (needs a JDK).

## Not verified
There was no network or Android device here, so the jars were not rebuilt and ECJ was not run on a phone. Verified on a plain JDK 21: the patcher on a compiled class that uses `Runtime.version()`, `Runtime.Version.parse`, a static field, long/double constants and a lambda (it runs under `-Xverify:all` after patching), and the shim's `parse` / `toString` / `compareTo` against the real `Runtime.Version`. Not verified: that the real ECJ jar has no *other* Java 9+ API call Android lacks. If Self-test now fails with a different `NoSuchMethodError` / `NoClassDefFoundError`, send me that text; it will be the next missing API.

# CloudIDE 4.3.2 - Java 21 in the local build

- Local builds now compile at the language level the project asks for, up to **Java 21** (records, sealed types, switch patterns, text blocks, `var`). Plain Java / Kotlin projects and the language packs default to 21. `sourceCompatibility` / `targetCompatibility = JavaVersion.VERSION_21`, `JavaLanguageVersion.of(21)`, `jvmToolchain(21)` and `release = 21` are all read from build.gradle(.kts). Kotlin gets `-jvm-target 21 / 17 / 11 / 1.8` to match.
- Cause of "only very old Java": at Java 9+ ECJ cannot use `-bootclasspath`, and android.jar lacks the Java 9-21 classes ECJ needs (`Record`, `MatchException`, `runtime.ObjectMethods`, `StringConcatFactory` ...). Any failure then fell straight back to Java 8.
- New `assets/toolchain/jdk-stubs.jar`: only the JDK classes android.jar does not have, taken from JDK 21 `java.base` by `toolchain/bundle-jars.sh` / `toolchain.yml`. It is added behind android.jar on ECJ's class path at Java 9+, compile time only (D8 desugars the uses; it is never dexed or packaged). It is copied into `files/toolchain` on first use like the other bundled jars.
- Retry is now a ladder, 21 -> 17 -> 11 -> 8, and only when ECJ reports missing platform classes. A normal error in your code stops at once. If a lower level was used, the log says so.
- Settings > Toolchain > Self-test tries 21, 17, 11, 8 (with records, sealed types and a pattern switch where the level allows) and reports the first that works.
- To get the stub jar: rebuild with `toolchain/bundle-jars.sh` (needs a JDK 21 with `jmods`; `build.yml` already runs it), or re-run `toolchain.yml`. If `assets/toolchain` already holds the four old jars, delete them first, otherwise the script exits early.

## Not verified
Not run on a phone and not run against a real ECJ: there was no network or Android SDK here. Checked on a plain JDK only: the level ladder, the clamp, and the ECJ argument rewrite. Whether ECJ accepts android.jar + the stubs as a Java 21 platform is the thing to confirm with the Self-test. Library calls newer than android.jar (for example `List.reversed()`) still need a recent `android.jar` platform, and pattern-matching `switch` may still need a D8 that desugars `SwitchBootstraps`; if the Self-test passes at 17 but not 21, that is the cause and the ladder will keep builds working at 17.

# CloudIDE 4.3.1 - build tools packed inside the APK

- The Android SDK build-tools (`aapt2`, `aapt`, `zipalign`, `aidl`, `dexdump`, `split-select`, arm64 static builds) ship in `app/src/main/jniLibs/arm64-v8a` as `lib*.so`, so Android runs them from the app's native library folder. No download, no Termux.
- The compiler jars (`ecj.dex.jar`, `d8.dex.jar`, `sign.dex.jar`, `android.jar`, `VERSIONS.txt`) are read from `app/src/main/assets/toolchain` and unpacked to `files/toolchain` on first use (`Toolchain.seedBundled`). Downloaded or imported files are never overwritten.
- `toolchain/bundle-jars.sh` builds those jars (same steps as `toolchain.yml`); `build.yml` runs it before Gradle, so CI output is an APK that compiles APKs by itself. Commit the four jars into `assets/toolchain` to skip the step.
- Terminal: new `aapt`, `aidl`, `dexdump`, `split-select`; `zipalign -f -p 4 in out` uses the real tool, the old two-argument form still works. Check toolchain tests every bundled tool.
- Fix: local Java compile failed with "option -bootclasspath not supported at compliance level 9 and above". ECJ now gets android.jar on -classpath at Java 9+, and the retry at Java 8 also triggers on that message.
- Nothing else changed: editor, GitHub build, plugins, language packs, Settings > Toolchain Download / Import / Remove all behave as before.

# CloudIDE 4.3 - language plugins, Maven without Gradle, SDK tools in the terminal

## Language plugins (see PLUGINS.md)
- Every language is a catalog entry (`assets/plugins/catalog.json`), not code. New: TypeScript, Ruby, PHP, Lua, Perl, Zig, Nim, D, OCaml, Dart, Erlang, Elixir, Java JDK (manual).
  Plugins can be added without an app update: `plugin update` reads `plugins.json` from the release, `plugin import <zip>` takes a zip with its own plugin.json.
- The IDE detects the languages a project uses when it opens and before every local build, and offers to download the missing compilers (Download / Not now / Don't ask again). Plugins that need another one (Rust needs C / C++) pull it in. Switch: Settings > Offer missing compilers.
- Terminal: `plugins`, `plugin install|remove|import|update`, `lrun <file>` for every interpreter plugin. Problems list understands each plugin's messages.
- Removing a plugin no longer deletes libraries another installed plugin uses. `packs.yml` builds packs from the catalog and publishes `plugins.json`.

## Gradle and the Android Gradle plugin are not needed for the dependency part
- New `MavenResolver`: downloads libraries from Google Maven, Maven Central and `maven { url }` repositories named in the project. POM and Gradle module metadata, transitive dependencies, BOM `platform(...)`, version catalogs (`libs.versions.toml`), `$variables`, exclusions, highest version wins, SHA-1 check, cached in `files/m2` so the second build is offline. 28 plain-JDK tests in `toolchain/test`.
- Android builds now merge library manifests (androidx.startup provider, services, permissions), take AAR assets, support `src/main/kotlin`, and build local `project(":lib")` modules.
- Gradle / Maven projects without Android (Java, Kotlin) now build on the phone with those libraries instead of being "NOT LOCAL".

## SDK tools in the terminal
- `aapt2`, `ecj`, `kotlinc`, `d8`, `apksigner`, `zipalign`, `mkjar`, `unjar`, `apkpack`, `mkmanifest`, `mkbuildconfig`, `deps`, `sdk`, `apkbuild`. `sdk init` writes `build-apk.cmd`, `apkbuild` runs it: the whole APK pipeline as commands you can read and change. Chains (`&&`, `;`), quotes, `$VARS` and wildcards work.

## Not verified
Built and unit-tested without an Android SDK or network: the resolver, Gradle-file reader and manifest merger run on a plain JDK; the rest compiles against stubs. Nothing here has been run on a phone, and the new native packs have not been built by CI yet.

# CloudIDE 4.2 - every language builds on the phone (no Termux)

Local build (project settings > "On this phone", or `lbuild`) now covers more than Java. `lbuild` looks at the project and builds each language it finds:

| Found | Built with | Pack |
|---|---|---|
| Android app (Java) | aapt2, ECJ, D8, apksig (as in 4.1) | core |
| Android app with Kotlin | kotlinc, then ECJ, D8 | Kotlin |
| Plain Java | ECJ, D8; output `.jar` and a dex jar | core |
| Plain Kotlin | kotlinc, ECJ for mixed Java, D8 | Kotlin |
| C, C++ | clang (several `main()` files become several programs) | C / C++ |
| CMake, Make | cmake + ninja/make, make | C / C++ |
| Rust | cargo build --release | Rust |
| Go | go build ./... | Go |
| Python | pip install -r requirements.txt, syntax check of every .py | Python |
| Node | npm install, npm run build; `node --check` for loose .js | Node |
| build.sh | sh, with every installed pack on PATH (`$CLOUDIDE_OUT` is the output folder) | none |

Not local, reported as "NOT LOCAL" in the build summary: Gradle and Maven dependency builds, Flutter / Dart, .NET. They stay on the GitHub build.

## Language packs (Settings > Language packs)
One download per language, kept on the device. `packs.yml` builds them and publishes `pack-<id>.zip` plus `packs.json` (SHA-256 sums) into the same `toolchain` release.
- **Kotlin** is a dex jar that runs inside the app like ECJ. No exec permission needed, works on every Android version. Experimental: kotlinc is a large program and needs a lot of memory.
- **C / C++, Rust, Go, Python, Node** are Android arm64 programs unpacked into `files/usr`, built from termux-packages with this app's package name as the prefix. They are separate from the Termux app.

## targetSdk is now 28 by default
Android 10+ refuses to start programs from an app's own storage unless the app targets API 28 or lower (Termux works under the same rule). `cloudide.targetSdk` in gradle.properties picks it: 28 = native packs work, 35 = only the in-app engines (Java, Kotlin, APK). Check toolchain says which one you have. Not for Google Play.

## Also new
- Terminal: installed packs are on PATH (`clang`, `make`, `cmake`, `cargo`, `go`, `python`, `pip`, `node`, `npm`). New commands `lrun` (run the last build, `lrun main.py`, `lrun main.js`) and `packs`.
- Problems list understands clang, rustc, go, Python and Node error lines.
- `toolchain.yml` no longer deletes the release, so the pack assets survive.

# CloudIDE 4.1 - build on the phone (no Termux)

New **Build mode: On this phone** (project settings, or the `lbuild` terminal command). Pipeline, all inside the app:
aapt2 compile + link -> ECJ -> D8 -> 4-byte aligned zip -> apksig (v1+v2+v3) -> APK in the same place cloud builds land.

Why ECJ failed before: current ECJ links against `javax.lang.model`, `javax.tools` and `javax.annotation.processing`.
Android has none of them, so only old ECJ versions loaded. `toolchain.yml` now takes the real classes from the JDK
`java.compiler` module and puts them in `ecj.dex.jar` next to the latest ECJ, so the newest ECJ runs unchanged.

Setup, once:
1. GitHub > Actions > "Build on-device toolchain" > Run workflow. It builds ecj.dex.jar (latest ECJ + javax), d8.dex.jar (latest R8),
   sign.dex.jar (apksig), takes android.jar from the runner and fetches an arm64 aapt2. Output: release asset and artifact `toolchain-arm64.zip`.
2. In the app: Settings > Local build > Toolchain > Download (or Import zip). Then "Check toolchain" shows what works.
3. aapt2 runs by exec, then through /system/bin/linker64 (Android 10+ rule). Best: put libaapt2.so in app/src/main/jniLibs/arm64-v8a
   before building CloudIDE; the app already extracts native libs (useLegacyPackaging) so it runs from nativeLibraryDir.

Supported locally: Java projects (Gradle or flat layout), res, assets, jniLibs, .jar/.aar in libs/, BuildConfig, R for aar packages, Java 8 to 21+ source level.
Not local: Kotlin, Maven dependency download, annotation processors, ProGuard/R8 shrinking, release keystores (debug key only).
Needs Android 8.0+ (the compilers use java.nio.file). New template: "Java - On-device build (no libraries)".

# CloudIDE 4.0 - current toolchain (versions checked 22 Oct 2026)

| Tool | Now | Notes |
|---|---|---|
| Gradle | 9.8.2 | first line with Java 27 support is 9.8.0; 9.8.1 fixed three high-severity daemon issues |
| Android Gradle plugin | 9.4.1 | needs Gradle 9.6+, supports API 37, JDK 17+ |
| Kotlin | 2.4.10 | built into AGP 9 (no kotlin-android plugin); pinned for Compose so the compiler plugin matches |
| JDK | 21 default, 27 selectable | Temurin 27 is published; Gradle 9.8+ runs on it |
| compileSdk / targetSdk | 36 / 36 in new projects | this IDE app stays at target 35 until tested on Android 16 |
| ECJ | 3.46.100 (opt-in) | plain Java projects only; Java 27 support not confirmed |
| GitHub Actions | checkout v7, setup-java v6, upload-artifact v7, cache v6, setup-node v7, setup-gradle v5 | all Node 24. setup-gradle stays on v5: v6 defaults to a proprietary caching service |

- Build files use property syntax (`compileSdk = 36`), which works in old and new Android DSLs. AGP 9 dropped the
  old method style (`compileSdkVersion 36`).
- New: plain Java builds. When a repo has .java files and no Gradle/Maven project, `auto` compiles them with javac,
  or with Eclipse's ECJ when "Java compiler" is set to `ecj` in project settings. Output is a jar.
- New: version check before every Gradle build. Warns when the JDK is newer than the Gradle can run on, when the
  Android Gradle plugin needs a newer Gradle, or when the JDK is too old, and says which setting to change.
  Uses the project's own Gradle wrapper version when it has one.
- Version tips after a failed build now name the Gradle needed for the Java version in the error.
- Existing projects keep their saved Gradle and JDK settings; only new projects get the new defaults.
- Flutter and Node workflows keep JDK 17 (Flutter projects bring their own older Gradle wrapper).

# CloudIDE 3.4

- Version tips: when a build fails because of a JDK / Gradle / Android Gradle plugin / compileSdk mismatch
  (for example "Unsupported class file major version 71" = a tool too old for Java 27), the build log ends with
  a plain-language tip saying which setting to change.
- Fix: leaving "Gradle version" blank in project settings fell back to the old 8.9. It now falls back to 8.13.
- Settings labels now say what is accepted: any exact Gradle version or `current` (newest stable), any Temurin JDK.

# CloudIDE 3.3

- Saving build results no longer depends on an APK. A successful build that makes only files (C, Rust, Go,
  Python... builds) now offers "Save to folder" (one file, or all files as one .zip) or "Files...".
- Three-dot menu: new "APK and build files" and "Settings" entries (this project's GitHub and build settings,
  or the app's theme, editor and accounts).
- The build panel button is shown after any successful build: "Get APK" or "Build files".
- "Install last APK" with no APK now says what the build did make and opens those files.
- Keyboard is dismissed when you open the Problems or Build log tabs, so they are not squeezed.

# CloudIDE 3.2

- Terminal tab in the bottom panel (also Project > Terminal). Runs the Android shell in your project folder and
  remembers the folder between commands. Built-ins: `build [task]`, `push`, `apk`, `outputs`, `history`,
  `problems`, `langs`, `open <file>`, `clear`, `help`. Stop button ends a running command. Tap the prompt for history.
- Error bar under the editor: shows the compiler message for the line you are on, with Prev / Next to jump between
  the problems in the file. Tap the message to copy it.
- After a build, the APK downloaded from GitHub is offered as Install / Save to folder / Later. "Get APK" in the
  build panel and the Build outputs list also offer Save to a folder (any folder you pick), Share and Inspect.
- Fix: project detection used `java.nio.file`, which crashes on Android 7. Now uses APIs available on API 24.

# CloudIDE 3.1

## Multi-language builds ("auto" build type, now the default for imported projects)
The workflow looks at the repo when it runs and builds everything it finds, up to 3 folders deep:

| Found | Built with |
|---|---|
| `build.sh` in the root | only that script (your own build always wins) |
| `settings.gradle(.kts)` | Gradle: Android, Kotlin, Java, Compose, NDK. Plain JVM projects use `assemble` |
| `pubspec.yaml` with `flutter:` | `flutter build apk --debug` (creates `android/` if missing) |
| `package.json` | npm / yarn / pnpm install, then `npm run build` |
| `Cargo.toml` | `cargo build --release` |
| `pom.xml` | Maven `package` |
| `go.mod` | `go build ./...` |
| `pyproject.toml` / `setup.py` | `python -m build` |
| `*.sln` / `*.csproj` | `dotnet publish` |
| `CMakeLists.txt` / `Makefile` | cmake / make |

- Nothing is built twice: Flutter's `android/`, C++ that Gradle compiles, Rust that Gradle drives, workspace
  members and `node_modules` are skipped.
- One failing language does not stop the others. The build ends with a PASS/FAIL table, shown in the app.
- Outputs land in `cloudide-out/<language>/`, APKs and AABs are picked up from Gradle's folders.
- Existing projects keep their saved build type. Choose `auto` in the project's build settings to switch.
- A project that already has its own `build.yml` is never overwritten: CloudIDE writes `cloudide.yml` next to it.
- Project > "Regenerate build workflow" rewrites the workflow file on demand.

# CloudIDE 3.0

## Build system
- Android Gradle Plugin 8.5.2 -> 8.13.0, Gradle 8.9 -> 8.13 (workflow default and new-project default)
- compileSdk 34 -> 36, targetSdk 34 -> 35
- AppCompat 1.7.1, Material 1.13.0, RecyclerView 1.4.0, CoordinatorLayout 1.3.0, Activity 1.10.1
- Gradle parallel + build cache enabled
- New-project templates upgraded to the same toolchain. Kotlin 1.9.24 -> 2.2.20 (Compose now uses the
  Compose compiler plugin instead of `composeOptions`), Compose BOM 2025.06.01, `kotlinOptions` -> `compilerOptions`
- Generated apps respect system bars (targetSdk 35 forces edge-to-edge)

## Android 15/16 support
- Edge-to-edge with proper insets on every screen, including keyboard (IME) handling in the editor
- Predictive back (OnBackPressedDispatcher) replaces the deprecated `onBackPressed`
- Adaptive + themed (monochrome) launcher icon

## New features
- Quick open (Ctrl+P, or Project > Quick open file): fuzzy file finder, open files ranked first
- Build task picker: debug/release APK, release AAB, lint, unit tests, clean build, or a custom task.
  Outputs are collected automatically for each task (AAB, lint reports, test results)
- Build history per project (result, duration, run number, link to the GitHub run)
- Build log actions: copy, share, open run on GitHub, history
- APK inspector (Build outputs > Inspect): size breakdown, DEX count, ABIs, signature schemes
  (v1 and v2/v3 detected), largest files, text preview, extract and share
- Home screen: search, recent-first sorting, project type, last-build status with time
- Settings: build downloads storage usage with one-tap cleanup, live version number

## Look
- New palette (graphite + lagoon teal, amber reserved for build actions), WCAG AA contrast in light and dark
- New shape scale, monospace wordmark, color-coded project tiles, actionable empty states
- "Lagoon Dark" / "Lagoon Light" editor themes, used by Auto

## Fixes
- Build fix: `colorPrimary` now read from `androidx.appcompat.R` (Material 1.13 no longer exposes it)
- setup-java action bumped to v5

## Not changed on purpose
- Other GitHub Actions stay on `@v4` (checkout, setup-gradle, cache, upload-artifact)
- targetSdk stays at 35. Move to 36 after testing on an Android 16 device.
