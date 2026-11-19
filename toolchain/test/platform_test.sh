#!/bin/sh
# Checks toolchain/patch_platform.py on a plain JDK: a class that uses java.lang.management / com.sun.management must,
# after the patch, name neither, and must still run with the shim classes on the class path.
set -e
cd "$(dirname "$0")/.."
W=${TMPDIR:-/tmp}/cloudide-platform-test
rm -rf "$W" && mkdir -p "$W/src/demo" "$W/shim" "$W/out"
cat > "$W/src/demo/M.java" <<'JAVA'
package demo;
import java.lang.management.*;
import java.util.List;
public class M {
    static final ThreadMXBean TB = ManagementFactory.getThreadMXBean();
    public static void main(String[] a) {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        long gc = 0;
        for (GarbageCollectorMXBean b : gcs) gc += b.getCollectionCount() + b.getCollectionTime();
        long alloc = TB instanceof com.sun.management.ThreadMXBean ? ((com.sun.management.ThreadMXBean) TB).getCurrentThreadAllocatedBytes() : -2;
        long mem = ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean ? 1 : 0;
        ThreadInfo[] all = TB.dumpAllThreads(false, false);
        System.out.println("ok " + (rt.getUptime() >= 0) + " " + (heap.getUsed() > 0) + " " + (gc >= 0) + " " + (alloc != -2) + " " + mem
                + " " + (TB.getCurrentThreadCpuTime() >= 0) + " " + (all.length > 0) + " " + (ManagementFactory.getOperatingSystemMXBean().getAvailableProcessors() > 0));
    }
}
JAVA
REL=""; javac --release 8 -version >/dev/null 2>&1 && REL="--release 8" || REL="-source 8 -target 8"
javac -nowarn $REL -d "$W/shim" $(find src/cloudide/shim/mgmt src/cloudide/shim/sunmgmt -name '*.java') 2>&1 | grep -v -i warning || true
javac -nowarn -d "$W/out" "$W/src/demo/M.java"
(cd "$W/out" && jar cf ../in.jar .); (cd "$W/shim" && jar cf ../shim.jar .)
python3 patch_platform.py "$W/in.jar" "$W/patched.jar"
if python3 patch_platform.py --check "$W/in.jar" >/dev/null; then echo "FAIL: test class did not reference java.lang.management"; exit 1; fi
python3 patch_platform.py --check "$W/patched.jar"
# the shim jar is what the app loads with the patched code: it must not name the renamed packages itself
python3 patch_platform.py --check "$W/shim.jar"
java -Xverify:all -cp "$W/patched.jar:$W/shim.jar" demo.M | grep -q '^ok true true true true 1 true true true$'
# scan: against a fake android.jar that has only java/lang/Object-like names, the patched jar's gaps are listed, never an error
python3 patch_platform.py --scan "$W/shim.jar" "$W/patched.jar" > "$W/scan.txt"
grep -q '^scan:' "$W/scan.txt"
echo "patch_platform test passed"
