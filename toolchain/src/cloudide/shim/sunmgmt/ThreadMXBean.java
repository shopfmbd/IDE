package cloudide.shim.sunmgmt;

/** Stand-in for com.sun.management.ThreadMXBean. The compiler casts the platform bean to it for allocation counters. */
public interface ThreadMXBean extends cloudide.shim.mgmt.ThreadMXBean {
    long getThreadAllocatedBytes(long id);
    long getCurrentThreadAllocatedBytes();
    boolean isThreadAllocatedMemorySupported();
    boolean isThreadAllocatedMemoryEnabled();
    void setThreadAllocatedMemoryEnabled(boolean enable);
}
