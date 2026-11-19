# Running native packs at targetSdk 37

Android 10+ refuses `execve()` of files in an app's own storage when targetSdk >= 29 (SELinux: no `execute_no_trans` on
`app_data_file`, error=13). Mapping such a file with the linker is still allowed, and so is exec of files in `nativeLibraryDir`.
Everything here builds on those two facts. Same idea as Termux's `termux-exec` "system linker exec" mode.

## Parts
| File | Where it ends up | Job |
|---|---|---|
| `native/exec_hook.c` -> `libexec_hook.so` | `jniLibs/arm64-v8a` -> nativeLibraryDir | `LD_PRELOAD`. Hooks `execve execv execvp execvpe execl execle execlp fexecve posix_spawn posix_spawnp` and `readlink("/proc/self/exe")` |
| `native/cloudexec.c` -> `libcloudexec.so` | same | Executable the app can start directly. First program from Java, Go `-toolexec`/`-exec`/`CC`, static-binary loader |
| `native/cx_common.h` | (header) | The decision logic, async-signal-safe (no malloc/stdio) |

Rules: file under `CLOUDIDE_EXEC_ROOTS` (files, cache, external files dir, both `/data/user/0` and `/data/data` spellings)
-> dynamic ELF becomes `linker64 <file> args`; `#!` script becomes `<interpreter> [arg] <script> args` (interpreter resolved
again, 4 levels; `/usr/bin/env x`, `/bin/sh` and friends are mapped to `$PREFIX/bin` or `/system/bin`); static ELF becomes
`libcloudexec.so --static <file>`. Paths outside the roots are untouched, except that a plain exec that fails with EACCES
is retried through the linker. `LD_PRELOAD` is put back into the environment on every exec, so `env -i` does not break the chain.
`/system/bin` programs keep working: the library only needs libc, and their own children are covered too.

## Wiring (done)
- `app/build.gradle`: `useLegacyPackaging = true`, `extractNativeLibs="true"`, `abiFilters arm64-v8a`. Keep them.
- `NativeExec.env()` adds `LD_PRELOAD`, `CLOUDIDE_PREFIX`, `CLOUDIDE_EXEC_ROOTS`, `CLOUDIDE_PRELOAD`, `CLOUDIDE_LAUNCHER`;
  `LD_LIBRARY_PATH` is unchanged. For Go also `GOFLAGS=-toolexec=.. -exec=.. -buildvcs=false` and `CC="<launcher> clang"`.
- `NativeExec.run()` starts the first program through the launcher, then falls back to `linker64 <file>`.
- CI: `build.yml` runs `native/test/host_test.sh` and `native/build.sh` before Gradle.
- Debug: set `CLOUDIDE_EXEC_DEBUG=1` in the environment to see each rewrite on stderr. `CLOUDIDE_EXEC_DISABLE=1` turns the hook off.

## Self-test (Settings > Check toolchain)
Without any pack: launcher starts; a copy of `/system/bin/toybox` in app storage is started by `sh`; a `#!` script in app
storage is started by `sh`. With packs: `python -c "import subprocess; subprocess.run(['python','--version'])"`, node
`child_process`, and `go run` (compile and link go through `-toolexec`).

## What was and was not verified
Verified here: the logic, on x86-64 Linux (`native/test/host_test.sh`, 18 cases: ELF, symlink, `#!`, env-shebang,
python subprocess/posix_spawn/execv/execvp, `env -i`, static ET_EXEC and static-PIE via the launcher, `/proc/self/exe`).
glibc's `ld.so prog` stands in for linker64 and removing the x bit stands in for SELinux.
NOT verified: the arm64 build (no NDK in my sandbox, CI builds it), and anything on a real Android device or SELinux policy.
Treat the first CI run plus Check toolchain on a phone as the real test.

## Known limits
1. **Raw-syscall programs.** Go's `os/exec`, and anything static or using `syscall()`/inline `svc`, never calls libc `execve`,
   so LD_PRELOAD cannot see it. Go is covered by `-toolexec`/`-exec`/`CC` (set by `env()`); `go generate`, `go vet` helpers,
   `git`, `pkg-config` that Go starts by name are NOT covered. Other Go programs the user writes that spawn tools will fail
   unless the user spawns through `libcloudexec.so` (path in `$CLOUDIDE_LAUNCHER`).
2. **Static binaries.** `linker64` cannot load them. The launcher's `--static` loader maps them itself and jumps to the
   entry point. Experimental: it needs mmap(PROT_EXEC) of app files to be allowed, assumes the program uses only plain
   syscalls, and was tested only on x86-64. A static program's children are not hooked (it has no libc to preload) unless it is
   itself launched with `-exec`/`-toolexec`.
3. **`/proc/self/exe`** shows the linker, not the program, to anything that bypasses libc (Go `os.Executable`, Rust is fine).
   The hook fixes libc `readlink`. Packs that locate themselves by `/proc/self/exe` through raw syscalls need `GOROOT`-style
   env vars or a patched build.
4. **argv[0]** is the full path, not the typed name (the linker shifts argv). Multicall programs keyed on argv[0] break unless invoked via a link with the right name.
5. **Output files outside filesDir.** `getExternalFilesDir` is a noexec mount: scripts there run, ELF files there cannot be
   mapped. Build outputs to run must be copied under `files/` (`LocalLang.runLast` already does this).
6. **Android versions.** Expected to work on Android 10 to 14. 15, 16, 17: unverified, and Google keeps tightening
   untrusted_app. If `linker64 <file>` itself is refused by a future policy, nothing in this design can work; see fallback.
   Android 9 and below: not needed (plain exec). Devices with 16 KB pages: the libraries are built with max-page-size 16384.
   OEM kernels that block `mmap(PROT_EXEC)` on app files break the linker route too.
7. **`LD_PRELOAD` ignored** for setuid / AT_SECURE processes. `run-as`, `su` children lose the hook.
8. **Speed.** Every exec reads the file header and canonicalises the path: tens of microseconds, irrelevant next to `rustc`.

## Fallback plan
1. Per-language: ship JVM-hosted tooling (Kotlin, ecj, d8 already run in-app) instead of native packs wherever possible.
2. Keep `-Pcloudide.targetSdk=28` as a build flavour for sideloaded/F-Droid builds (full plain exec, no helper needed). Not Play-compatible.
3. Patch pack builds, not the app: for Go, build the toolchain with `os/exec` patched to call `$CLOUDIDE_LAUNCHER`; for static-only tools, rebuild them dynamically against bionic (termux-packages does this by default) or as PIE.
4. Run a language in a sandbox the app owns: proot-style ptrace (slow, and also hits the same exec limit for the tracee) or QEMU-user/wasm: wasmtime-in-JVM or a WASI toolchain avoids exec completely for languages that compile to WASI.
5. Remote: the existing GitHub Actions build path stays for anything that cannot run on the phone.
