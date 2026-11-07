#!/bin/sh
# Compiles and runs the resolver, manifest-merge, JSON, AI, editor-text and Web AI file-logic tests on a plain JDK (no Android needed).
set -e
cd "$(dirname "$0")"
OUT=${TMPDIR:-/tmp}/cloudide-resolver-test
rm -rf "$OUT" && mkdir -p "$OUT"
SRC=../../app/src/main/java/com/fahim/cloudide
javac -nowarn -d "$OUT" $SRC/MavenResolver.java $SRC/GradleDeps.java $SRC/ManifestMerge.java $SRC/MiniJson.java $SRC/AiProtocol.java $SRC/AiClient.java $SRC/EditorText.java $SRC/WebAiProject.java $SRC/WebAiScripts.java $SRC/AiEdits.java $SRC/AiActions.java $SRC/AiSession.java \
  com/fahim/cloudide/ResolverTest.java com/fahim/cloudide/MergeTest.java com/fahim/cloudide/JsonTest.java com/fahim/cloudide/AiProtocolTest.java com/fahim/cloudide/AiClientTest.java com/fahim/cloudide/EditorTextTest.java com/fahim/cloudide/WebAiProjectTest.java com/fahim/cloudide/AiEditsTest.java com/fahim/cloudide/AiActionsTest.java 2>&1 | grep -v "^Note:" || true
java -cp "$OUT" com.fahim.cloudide.ResolverTest
java -cp "$OUT" com.fahim.cloudide.MergeTest
java -cp "$OUT" com.fahim.cloudide.JsonTest
java -cp "$OUT" com.fahim.cloudide.AiProtocolTest
java -cp "$OUT" com.fahim.cloudide.AiClientTest
java -cp "$OUT" com.fahim.cloudide.EditorTextTest
java -cp "$OUT" com.fahim.cloudide.WebAiProjectTest
java -cp "$OUT" com.fahim.cloudide.AiEditsTest
java -cp "$OUT" com.fahim.cloudide.AiActionsTest
