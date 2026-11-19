#!/bin/sh
# Checks toolchain/patch_java9.py on a plain JDK: a class that uses Runtime.version() / Runtime.Version must, after the
# patch, name neither, and must still run (with the shim on the class path) and print the same shape of output.
set -e
cd "$(dirname "$0")/.."
W=${TMPDIR:-/tmp}/cloudide-patch-test
rm -rf "$W" && mkdir -p "$W/src/demo" "$W/shim" "$W/out"
cat > "$W/src/demo/T.java" <<'JAVA'
package demo;
import java.util.*;
public class T {
    static Runtime.Version held = Runtime.version();
    static long big = 123456789012L; static double dd = 1.5;
    public static void main(String[] a) {
        Runtime.Version v = Runtime.version();
        Runtime.Version p = Runtime.Version.parse("17.0.2+8");
        Optional<String> o = v.pre();
        Runnable r = () -> System.out.println("ok " + held.feature() + " " + (v.compareTo(p) > 0) + " " + big + " " + dd + " " + o.isPresent());
        r.run();
    }
}
JAVA
REL="--release 8"; javac $REL -version >/dev/null 2>&1 || REL="-source 8 -target 8"
javac -nowarn $REL -d "$W/shim" src/cloudide/shim/RtVersion.java 2>&1 | grep -v -i warning || true
javac -nowarn -d "$W/out" "$W/src/demo/T.java"
(cd "$W/out" && jar cf ../in.jar .) ; (cd "$W/shim" && jar cf ../shim.jar .)
python3 patch_java9.py "$W/in.jar" "$W/patched.jar"
if python3 patch_java9.py --check "$W/in.jar" >/dev/null; then echo "FAIL: test class did not reference Runtime\$Version"; exit 1; fi
python3 patch_java9.py --check "$W/patched.jar"
java -Xverify:all -cp "$W/patched.jar:$W/shim.jar" demo.T | grep -q '^ok 21 true 123456789012 1.5 false$'
echo "patch_java9 test passed"
