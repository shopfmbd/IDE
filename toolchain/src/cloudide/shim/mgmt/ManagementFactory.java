package cloudide.shim.mgmt;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stand-in for java.lang.management.ManagementFactory. Android has no java.lang.management, but kotlinc (and parts of
 * IntelliJ code inside it) read JVM, memory, thread and OS figures from it. toolchain/patch_platform.py points every
 * reference at this package. The numbers are best effort (Runtime, /proc); the compiler only logs them.
 * Keep this file Java 8 source: it is compiled with --release 8.
 */
public final class ManagementFactory {
    public static final String CLASS_LOADING_MXBEAN_NAME = "java.lang:type=ClassLoading";
    public static final String COMPILATION_MXBEAN_NAME = "java.lang:type=Compilation";
    public static final String MEMORY_MXBEAN_NAME = "java.lang:type=Memory";
    public static final String OPERATING_SYSTEM_MXBEAN_NAME = "java.lang:type=OperatingSystem";
    public static final String RUNTIME_MXBEAN_NAME = "java.lang:type=Runtime";
    public static final String THREAD_MXBEAN_NAME = "java.lang:type=Threading";

    private static final long START = System.currentTimeMillis();
    private static final long START_NANO = System.nanoTime();

    private ManagementFactory() { }

    public static RuntimeMXBean getRuntimeMXBean() { return RUNTIME; }
    public static MemoryMXBean getMemoryMXBean() { return MEMORY; }
    public static ThreadMXBean getThreadMXBean() { return THREADS; }
    public static ClassLoadingMXBean getClassLoadingMXBean() { return CLASSES; }
    public static CompilationMXBean getCompilationMXBean() { return COMPILATION; }
    public static OperatingSystemMXBean getOperatingSystemMXBean() { return OS; }
    public static List<GarbageCollectorMXBean> getGarbageCollectorMXBeans() { return Collections.<GarbageCollectorMXBean>emptyList(); }
    public static List<MemoryManagerMXBean> getMemoryManagerMXBeans() { return Collections.<MemoryManagerMXBean>emptyList(); }
    public static List<MemoryPoolMXBean> getMemoryPoolMXBeans() { return Collections.<MemoryPoolMXBean>emptyList(); }

    // ------------------------------------------------------------------ helpers

    private static long pid() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/stat"))) {
            String l = r.readLine();
            return l == null ? -1 : Long.parseLong(l.substring(0, l.indexOf(' ')));
        } catch (Exception e) {
            return -1;
        }
    }

    /** Value in bytes of a /proc/meminfo line such as "MemTotal:  3800000 kB", or -1. */
    private static long meminfo(String key) {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            for (String l; (l = r.readLine()) != null; ) {
                if (l.startsWith(key + ":")) return Long.parseLong(l.substring(key.length() + 1).replaceAll("[^0-9]", "")) * 1024L;
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private static ThreadGroup root() {
        ThreadGroup g = Thread.currentThread().getThreadGroup();
        while (g.getParent() != null) g = g.getParent();
        return g;
    }

    private static Thread[] threads() {
        Thread[] a = new Thread[root().activeCount() + 8];
        int n = root().enumerate(a, true);
        Thread[] out = new Thread[n];
        System.arraycopy(a, 0, out, 0, n);
        return out;
    }

    // ------------------------------------------------------------------ beans

    private static final RuntimeMXBean RUNTIME = new RuntimeMXBean() {
        private final long pid = pid();
        public long getPid() { return pid; }
        public String getName() { return pid + "@android"; }
        public String getVmName() { return System.getProperty("java.vm.name", "Dalvik"); }
        public String getVmVendor() { return System.getProperty("java.vm.vendor", "The Android Project"); }
        public String getVmVersion() { return System.getProperty("java.vm.version", ""); }
        public String getSpecName() { return "Java Virtual Machine Specification"; }
        public String getSpecVendor() { return "Oracle Corporation"; }
        public String getSpecVersion() { return "21"; }
        public String getManagementSpecVersion() { return "2.0"; }
        public String getClassPath() { return System.getProperty("java.class.path", ""); }
        public String getLibraryPath() { return System.getProperty("java.library.path", ""); }
        public boolean isBootClassPathSupported() { return false; }
        public String getBootClassPath() { throw new UnsupportedOperationException("boot class path not supported"); }
        public List<String> getInputArguments() { return Collections.<String>emptyList(); }
        public long getUptime() { return System.currentTimeMillis() - START; }
        public long getStartTime() { return START; }
        public Map<String, String> getSystemProperties() {
            Map<String, String> m = new HashMap<String, String>();
            for (String k : System.getProperties().stringPropertyNames()) m.put(k, System.getProperty(k));
            return Collections.unmodifiableMap(m);
        }
    };

    private static final MemoryMXBean MEMORY = new MemoryMXBean() {
        private boolean verbose;
        public int getObjectPendingFinalizationCount() { return 0; }
        public MemoryUsage getHeapMemoryUsage() {
            Runtime rt = Runtime.getRuntime();
            long total = rt.totalMemory();
            return new MemoryUsage(total, Math.max(0, total - rt.freeMemory()), total, rt.maxMemory());
        }
        public MemoryUsage getNonHeapMemoryUsage() { return new MemoryUsage(0, 0, 0, -1); }
        public boolean isVerbose() { return verbose; }
        public void setVerbose(boolean value) { verbose = value; }
        public void gc() { System.gc(); }
    };

    private static final class Threads implements cloudide.shim.sunmgmt.ThreadMXBean {
        private int peak;
        private boolean cpuTime = true, allocated = true;

        private Thread find(long id) {
            for (Thread t : threads()) if (t.getId() == id) return t;
            return null;
        }
        private ThreadInfo info(long id, int depth) {
            Thread t = find(id);
            if (t == null) return null;
            StackTraceElement[] st = t.getStackTrace();
            if (depth >= 0 && depth < st.length) {
                StackTraceElement[] c = new StackTraceElement[depth];
                System.arraycopy(st, 0, c, 0, depth);
                st = c;
            }
            return new ThreadInfo(t, st);
        }
        public int getThreadCount() { int n = threads().length; peak = Math.max(peak, n); return n; }
        public int getPeakThreadCount() { return Math.max(peak, getThreadCount()); }
        public long getTotalStartedThreadCount() { return getThreadCount(); }
        public int getDaemonThreadCount() { int n = 0; for (Thread t : threads()) if (t.isDaemon()) n++; return n; }
        public long[] getAllThreadIds() {
            Thread[] ts = threads();
            long[] ids = new long[ts.length];
            for (int i = 0; i < ts.length; i++) ids[i] = ts[i].getId();
            return ids;
        }
        public ThreadInfo getThreadInfo(long id) { return info(id, 0); }
        public ThreadInfo[] getThreadInfo(long[] ids) { return getThreadInfo(ids, 0); }
        public ThreadInfo getThreadInfo(long id, int maxDepth) { return info(id, maxDepth); }
        public ThreadInfo[] getThreadInfo(long[] ids, int maxDepth) {
            ThreadInfo[] out = new ThreadInfo[ids.length];
            for (int i = 0; i < ids.length; i++) out[i] = info(ids[i], maxDepth);
            return out;
        }
        public ThreadInfo[] getThreadInfo(long[] ids, boolean lockedMonitors, boolean lockedSynchronizers) { return getThreadInfo(ids, Integer.MAX_VALUE); }
        public ThreadInfo[] dumpAllThreads(boolean lockedMonitors, boolean lockedSynchronizers) { return getThreadInfo(getAllThreadIds(), -1); }
        public boolean isThreadContentionMonitoringSupported() { return false; }
        public boolean isThreadContentionMonitoringEnabled() { return false; }
        public void setThreadContentionMonitoringEnabled(boolean enable) { throw new UnsupportedOperationException("thread contention monitoring not supported"); }
        public long getCurrentThreadCpuTime() { return System.nanoTime() - START_NANO; }
        public long getCurrentThreadUserTime() { return getCurrentThreadCpuTime(); }
        public long getThreadCpuTime(long id) { return find(id) == null ? -1 : 0; }
        public long getThreadUserTime(long id) { return find(id) == null ? -1 : 0; }
        public boolean isThreadCpuTimeSupported() { return true; }
        public boolean isCurrentThreadCpuTimeSupported() { return true; }
        public boolean isThreadCpuTimeEnabled() { return cpuTime; }
        public void setThreadCpuTimeEnabled(boolean enable) { cpuTime = enable; }
        public long[] findMonitorDeadlockedThreads() { return null; }
        public long[] findDeadlockedThreads() { return null; }
        public void resetPeakThreadCount() { peak = 0; }
        public boolean isObjectMonitorUsageSupported() { return false; }
        public boolean isSynchronizerUsageSupported() { return false; }
        public long getThreadAllocatedBytes(long id) { return -1; }
        public long getCurrentThreadAllocatedBytes() { Runtime rt = Runtime.getRuntime(); return Math.max(0, rt.totalMemory() - rt.freeMemory()); }
        public boolean isThreadAllocatedMemorySupported() { return true; }
        public boolean isThreadAllocatedMemoryEnabled() { return allocated; }
        public void setThreadAllocatedMemoryEnabled(boolean enable) { allocated = enable; }
    }
    private static final ThreadMXBean THREADS = new Threads();

    private static final ClassLoadingMXBean CLASSES = new ClassLoadingMXBean() {
        private boolean verbose;
        public long getTotalLoadedClassCount() { return 0; }
        public int getLoadedClassCount() { return 0; }
        public long getUnloadedClassCount() { return 0; }
        public boolean isVerbose() { return verbose; }
        public void setVerbose(boolean value) { verbose = value; }
    };

    private static final CompilationMXBean COMPILATION = new CompilationMXBean() {
        public String getName() { return "ART"; }
        public boolean isCompilationTimeMonitoringSupported() { return false; }
        public long getTotalCompilationTime() { throw new UnsupportedOperationException("compilation time monitoring not supported"); }
    };

    private static final OperatingSystemMXBean OS = new cloudide.shim.sunmgmt.OperatingSystemMXBean() {
        public String getName() { return System.getProperty("os.name", "Linux"); }
        public String getArch() { return System.getProperty("os.arch", "aarch64"); }
        public String getVersion() { return System.getProperty("os.version", ""); }
        public int getAvailableProcessors() { return Runtime.getRuntime().availableProcessors(); }
        public double getSystemLoadAverage() {
            try (BufferedReader r = new BufferedReader(new FileReader("/proc/loadavg"))) {
                String l = r.readLine();
                return l == null ? -1 : Double.parseDouble(l.substring(0, l.indexOf(' ')));
            } catch (Exception e) {
                return -1;
            }
        }
        public long getCommittedVirtualMemorySize() { return -1; }
        public long getTotalSwapSpaceSize() { return Math.max(0, meminfo("SwapTotal")); }
        public long getFreeSwapSpaceSize() { return Math.max(0, meminfo("SwapFree")); }
        public long getProcessCpuTime() { return System.nanoTime() - START_NANO; }
        public long getFreePhysicalMemorySize() { return Math.max(0, meminfo("MemAvailable")); }
        public long getTotalPhysicalMemorySize() { return Math.max(0, meminfo("MemTotal")); }
        public double getSystemCpuLoad() { return -1; }
        public double getProcessCpuLoad() { return -1; }
        public double getCpuLoad() { return -1; }
    };
}
