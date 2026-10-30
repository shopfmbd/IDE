#!/bin/sh
# Compiles and runs the resolver and manifest-merge tests on a plain JDK (no Android needed).
set -e
cd "$(dirname "$0")"
OUT=${TMPDIR:-/tmp}/cloudide-resolver-test
rm -rf "$OUT" && mkdir -p "$OUT"
SRC=../../app/src/main/java/com/fahim/cloudide
javac -nowarn -d "$OUT" $SRC/MavenResolver.java $SRC/GradleDeps.java $SRC/ManifestMerge.java com/fahim/cloudide/ResolverTest.java com/fahim/cloudide/MergeTest.java 2>&1 | grep -v "^Note:" || true
java -cp "$OUT" com.fahim.cloudide.ResolverTest
java -cp "$OUT" com.fahim.cloudide.MergeTest
