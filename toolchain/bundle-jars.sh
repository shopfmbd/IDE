#!/usr/bin/env bash
# Builds the on-device compiler jars and puts them into app/src/main/assets/toolchain so they ship INSIDE the APK.
# Same result as .github/workflows/toolchain.yml, but written next to the app instead of into a release zip.
# Needs: JDK 21 (jmod; the Java 21 stubs come from its java.base), python3, curl, zip/unzip, and an Android platform (ANDROID_HOME/platforms/android-NN/android.jar).
# Usage: toolchain/bundle-jars.sh [android-platform, e.g. android-36]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/toolchain"
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
mkdir -p "$DEST"

# VERSIONS.txt carries "java9-shim" once the jars were built with the Runtime.version() patch (see below).
# Jars from an older build lack it, so they are rebuilt instead of kept.
if [ -s "$DEST/ecj.dex.jar" ] && [ -s "$DEST/d8.dex.jar" ] && [ -s "$DEST/sign.dex.jar" ] && [ -s "$DEST/android.jar" ] && [ -s "$DEST/jdk-stubs.jar" ] \
   && grep -q "java9-shim 2" "$DEST/VERSIONS.txt" 2>/dev/null; then
  echo "assets/toolchain already holds the five patched jars; delete them to rebuild."; exit 0
fi
rm -f "$DEST/VERSIONS.txt"

latest() { curl -fsSL "$1/maven-metadata.xml" | sed -n 's:.*<release>\(.*\)</release>.*:\1:p' | head -1; }
ECJ=$(latest https://repo1.maven.org/maven2/org/eclipse/jdt/ecj)
R8=$(latest https://dl.google.com/android/maven2/com/android/tools/r8)
SIG=$(latest https://dl.google.com/android/maven2/com/android/tools/build/apksig)
echo "ecj $ECJ  r8 $R8  apksig $SIG"
cd "$W"
curl -fsSL -o ecj.jar    "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/$ECJ/ecj-$ECJ.jar"
curl -fsSL -o r8.jar     "https://dl.google.com/android/maven2/com/android/tools/r8/$R8/r8-$R8.jar"
curl -fsSL -o apksig.jar "https://dl.google.com/android/maven2/com/android/tools/build/apksig/$SIG/apksig-$SIG.jar"

P="${1:-}"
# Android 17 = API 37: fetch that platform when the SDK on this machine does not have it yet
# API 37+ is published as android-37.0 (not android-37); try both names
for id in android-37.0 android-37; do
  [ -d "$ANDROID_HOME/platforms/$id" ] || { yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;$id" >/dev/null 2>&1 || true; }
  [ -d "$ANDROID_HOME/platforms/$id" ] && break
done || true
[ -z "$P" ] && P=$(ls "$ANDROID_HOME/platforms" | sort -V | tail -1)
cp "$ANDROID_HOME/platforms/$P/android.jar" "$DEST/android.jar"

# javax.lang.model / javax.tools / javax.annotation.processing: ECJ links against them, Android does not have them
JH="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
"$JH/bin/jmod" extract --dir jc "$JH/jmods/java.compiler.jmod"
rm -f jc/classes/module-info.class
(cd jc/classes && zip -qr ../../javax-stubs.jar .)

ANDROID_JAR="$DEST/android.jar"
# jdk-stubs.jar: Java 9-21 platform classes that android.jar lacks (Record, MatchException, runtime.ObjectMethods,
# SwitchBootstraps, StringConcatFactory, java.lang.constant ...). ECJ at Java 9+ reads them for records, switch patterns
# and string concatenation. Only classes android.jar does NOT have are taken, so nothing in android.jar is shadowed.
# They are compile-time only: D8 desugars the uses, and the jar is never given to D8.
"$JH/bin/jmod" extract --dir jb "$JH/jmods/java.base.jmod"
unzip -Z1 "$ANDROID_JAR" | sort -u > have.txt
( cd jb/classes && find java/lang/Record.class java/lang/MatchException.class java/lang/runtime java/lang/constant \
    java/lang/invoke/StringConcatFactory.class java/lang/invoke/StringConcatException.class java/lang/invoke/TypeDescriptor*.class java/lang/Enum\$EnumDesc.class \
    java/lang/invoke/LambdaMetafactory.class java/lang/invoke/LambdaConversionException.class java/lang/invoke/SerializedLambda.class \
    java/util/SequencedCollection.class java/util/SequencedMap.class java/util/SequencedSet.class \
    -name '*.class' 2>/dev/null | sort -u ) > want.txt
grep -vxFf have.txt want.txt > take.txt || true
echo "jdk-stubs: $(wc -l < take.txt) classes android.jar lacks"
if [ -s take.txt ]; then (cd jb/classes && zip -q ../../jdk-stubs.jar -@ < ../../take.txt); else echo 'android.jar already has everything' > NOTE.txt && zip -q jdk-stubs.jar NOTE.txt; fi
cp jdk-stubs.jar "$DEST/jdk-stubs.jar"

mkdir -p sm && javac -d sm -cp apksig.jar --release 17 "$ROOT/toolchain/src/cloudide/sign/SignMain.java"
(cd sm && zip -qr ../signmain.jar .)

# Android has no Runtime.version() / Runtime.Version (Java 9). The newest ECJ calls it, D8 cannot desugar it, and the dexed
# compiler then dies with "NoSuchMethodError: No static method version()Ljava/lang/Runtime$Version;". patch_java9.py points
# those references at cloudide.shim.RtVersion (a small Java 8 class dexed with each tool) before D8 runs.
mkdir -p shim && javac -d shim --release 8 "$ROOT/toolchain/src/cloudide/shim/RtVersion.java"
(cd shim && zip -qr ../rtshim.jar .)
for j in ecj r8 apksig javax-stubs; do
  python3 "$ROOT/toolchain/patch_java9.py" $j.jar $j.patched.jar && mv $j.patched.jar $j.jar
done

python3 "$ROOT/toolchain/patch_java9.py" --check ecj.jar r8.jar apksig.jar javax-stubs.jar

dex() { # out.jar inputs...
  o=$1; shift
  java -cp r8.jar com.android.tools.r8.D8 --release --min-api 26 --lib "$DEST/android.jar" --output "$o" "$@" 2>&1 | tail -n 30 || true
  test -s "$o"
}
dex "$DEST/ecj.dex.jar"  ecj.jar javax-stubs.jar rtshim.jar
dex "$DEST/d8.dex.jar"   r8.jar rtshim.jar
dex "$DEST/sign.dex.jar" signmain.jar apksig.jar rtshim.jar

# D8 drops non-class files (ECJ message bundles). Put them back.
DEST="$DEST" python3 - <<'PY'
import zipfile, shutil, os
D = os.environ['DEST']
def merge(dex, *srcs):
    tmp = dex + '.tmp'
    with zipfile.ZipFile(dex) as z: have = set(z.namelist())
    shutil.copy(dex, tmp)
    with zipfile.ZipFile(tmp, 'a', zipfile.ZIP_DEFLATED) as out:
        for s in srcs:
            with zipfile.ZipFile(s) as zs:
                for n in zs.namelist():
                    if n.endswith('/') or n.endswith('.class') or n in have: continue
                    if n.startswith('META-INF/') and n.rsplit('.',1)[-1].upper() in ('SF','RSA','DSA','EC','MF'): continue
                    if n == 'module-info.class': continue
                    out.writestr(n, zs.read(n)); have.add(n)
    os.replace(tmp, dex)
merge(D + '/ecj.dex.jar', 'ecj.jar')
merge(D + '/d8.dex.jar', 'r8.jar')
merge(D + '/sign.dex.jar', 'apksig.jar')
PY
unzip -l "$DEST/ecj.dex.jar" | grep -q 'classes.dex'
unzip -l "$DEST/ecj.dex.jar" | grep -q '\.properties' || { echo 'ECJ message bundles missing'; exit 1; }
# fail the build if any dexed tool still names the Java 9 class Android lacks
python3 "$ROOT/toolchain/patch_java9.py" --check "$DEST/ecj.dex.jar" "$DEST/d8.dex.jar" "$DEST/sign.dex.jar"
printf 'ECJ %s\nR8/D8 %s\napksig %s\n%s\njava9-shim 2\n' "$ECJ" "$R8" "$SIG" "$P" > "$DEST/VERSIONS.txt"
ls -l "$DEST"
