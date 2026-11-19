#!/usr/bin/env bash
# CloudIDE custom build. This runs on GitHub (Ubuntu) where gcc, g++, cmake, make,
# Rust, Go, Python, Node, .NET, Java, Gradle and the Android NDK are installed.
# Copy every file you want to download into $OUT.
#   $OUT    output folder (downloaded after the build)
#   $NDK    Android NDK folder
#   $NDKBIN Android NDK compilers (aarch64-linux-android24-clang, ...)
set -e

echo "== C: Linux build, runs here =="
gcc -O2 -o "$OUT/hello-linux" hello.c
"$OUT/hello-linux"

echo "== C: Android arm64 build, runs in Termux =="
"$NDKBIN/aarch64-linux-android24-clang" -O2 -o "$OUT/hello-android" hello.c

# More examples, remove the leading # to use one:
# g++ -O2 -std=c++17 -o "$OUT/app" main.cpp
# cmake -S . -B build && cmake --build build && cp build/app "$OUT/"
# cargo build --release && cp target/release/myapp "$OUT/"
# rustup target add aarch64-linux-android   # then set CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDKBIN/aarch64-linux-android24-clang"
# go build -o "$OUT/app" .
# dotnet publish -c Release -o "$OUT"
# python3 -m pip install build && python3 -m build && cp dist/* "$OUT/"
