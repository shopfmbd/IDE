#!/bin/sh
# Checks the Unsafe stand-in: a class named like IntelliJ's wrapper that reflects on sun.misc.Unsafe.getAndAddInt must,
# after toolchain/patch_platform.py, use cloudide.shim.Unsafe instead (and still add correctly). Classes outside the
# wrapper must not be touched.
set -e
cd "$(dirname "$0")/.."
W=${TMPDIR:-/tmp}/cloudide-unsafe-test
rm -rf "$W" && mkdir -p "$W/src/org/jetbrains/kotlin/com/intellij/util/containers" "$W/out" "$W/shim"
cat > "$W/src/org/jetbrains/kotlin/com/intellij/util/containers/Unsafe.java" <<'JAVA'
package org.jetbrains.kotlin.com.intellij.util.containers;
import java.lang.reflect.*;
public final class Unsafe {
    static final Object unsafe; static final Method add; static final String who;
    static {
        try {
            Class<?> c = Class.forName("sun.misc.Unsafe");
            Field f = c.getDeclaredField("theUnsafe"); f.setAccessible(true);
            unsafe = f.get(null);
            add = c.getDeclaredMethod("getAndAddInt", Object.class, long.class, int.class);
            who = c.getName();
        } catch (Exception e) { throw new Error(e); }
    }
    public static int getAndAddInt(Object o, long off, int d) throws Exception { return (Integer) add.invoke(unsafe, o, off, d); }
    public static String who() { return who; }
}
JAVA
cat > "$W/src/Main.java" <<'JAVA'
import org.jetbrains.kotlin.com.intellij.util.containers.Unsafe;
public class Main { volatile int x = 5;
  public static void main(String[] a) throws Exception {
    Main m = new Main();
    Class<?> real = Class.forName("sun.misc.Unsafe");
    java.lang.reflect.Field tf = real.getDeclaredField("theUnsafe"); tf.setAccessible(true);
    long off = ((sun.misc.Unsafe) tf.get(null)).objectFieldOffset(Main.class.getDeclaredField("x"));
    int old = Unsafe.getAndAddInt(m, off, 3);
    System.out.println(Unsafe.who() + " " + old + " " + m.x);
  } }
JAVA
mkdir -p "$W/src/org/jetbrains/kotlin/cli/jvm/compiler/jarfs"
cat > "$W/src/org/jetbrains/kotlin/cli/jvm/compiler/jarfs/FastJarFileSystem.java" <<'JAVA'
package org.jetbrains.kotlin.cli.jvm.compiler.jarfs;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
@SuppressWarnings("all")
public final class FastJarFileSystem {
    public static String unmap() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
        sun.misc.Unsafe u = (sun.misc.Unsafe) f.get(null);
        u.invokeCleaner(ByteBuffer.allocateDirect(8));
        return u.getClass().getName();
    }
}
JAVA
cat > "$W/src/Main2.java" <<'JAVA'
public class Main2 { public static void main(String[] a) throws Exception {
    System.out.println(org.jetbrains.kotlin.cli.jvm.compiler.jarfs.FastJarFileSystem.unmap()); } }
JAVA
javac -nowarn -source 8 -target 8 -d "$W/shim" src/cloudide/shim/Unsafe.java 2>&1 | grep -E "error" || true
javac -nowarn -source 8 -target 8 -d "$W/out" $(find "$W/src" -name '*.java') 2>&1 | grep -E "error" || true
python3 - "$W" <<'PY'
import sys, zipfile, os
w = sys.argv[1]
with zipfile.ZipFile(w + "/in.jar", "w", zipfile.ZIP_DEFLATED) as z:
    for r, _, fs in os.walk(w + "/out"):
        for f in fs:
            p = os.path.join(r, f); z.write(p, os.path.relpath(p, w + "/out"))
PY
python3 patch_platform.py "$W/in.jar" "$W/patched.jar"
java -Xverify:all -cp "$W/patched.jar:$W/shim" Main | grep -q '^cloudide.shim.Unsafe 5 8$'
java -Xverify:all -cp "$W/patched.jar:$W/shim" Main2 | grep -q '^cloudide.shim.Unsafe$'
echo "unsafe shim test passed"
