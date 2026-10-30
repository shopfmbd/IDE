package cloudide.shim.mgmt;

public interface MemoryPoolMXBean extends PlatformManagedObject {
    String getName();
    MemoryType getType();
    MemoryUsage getUsage();
    MemoryUsage getPeakUsage();
    void resetPeakUsage();
    boolean isValid();
    String[] getMemoryManagerNames();
}
