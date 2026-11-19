#!/usr/bin/env bash
# Builds libexec_hook.so and libcloudexec.so for arm64-v8a with the Android NDK and puts them in jniLibs.
#   NDK:  $ANDROID_NDK_HOME (GitHub's ubuntu runners have it), or pass NDK=/path
#   API:  29 (the libraries are only ever loaded on Android 10+; the app itself keeps minSdk 26)
# Host mode (no NDK, for testing the logic on Linux):  native/build.sh host
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
if [ "${1:-}" = host ]; then
  out="${OUT:-$here/build-host}"; mkdir -p "$out"
  gcc -O2 -Wall -fPIC -shared -o "$out/libexec_hook.so" "$here/exec_hook.c" -ldl
  gcc -O2 -Wall -fPIE -pie -o "$out/libcloudexec.so" "$here/cloudexec.c"
  echo "host build in $out"; exit 0
fi
NDK="${NDK:-${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_ROOT:-}}}}"
[ -n "$NDK" ] && [ -d "$NDK" ] || { echo "Set NDK or ANDROID_NDK_HOME to an Android NDK (r26+)." >&2; exit 1; }
case "$(uname -s)" in Darwin) tag=darwin-x86_64;; *) tag=linux-x86_64;; esac
CC="$NDK/toolchains/llvm/prebuilt/$tag/bin/aarch64-linux-android${API:-29}-clang"
STRIP="$NDK/toolchains/llvm/prebuilt/$tag/bin/llvm-strip"
[ -x "$CC" ] || { echo "Compiler not found: $CC" >&2; exit 1; }
out="${OUT:-$here/../app/src/main/jniLibs/arm64-v8a}"; mkdir -p "$out"
common=(-O2 -Wall -Wextra -Wno-unused-parameter -fno-stack-protector -fvisibility=hidden -Wl,-z,max-page-size=16384 -Wl,-z,noexecstack -Wl,--build-id=none)
"$CC" "${common[@]}" -fPIC -shared -Wl,-soname,libexec_hook.so -o "$out/libexec_hook.so" "$here/exec_hook.c"
"$CC" "${common[@]}" -fPIE -pie -o "$out/libcloudexec.so" "$here/cloudexec.c"
"$STRIP" --strip-unneeded "$out/libexec_hook.so" "$out/libcloudexec.so"
chmod 755 "$out/libexec_hook.so" "$out/libcloudexec.so"
file "$out/libexec_hook.so" "$out/libcloudexec.so" 2>/dev/null || true
echo "built into $out"
