package cloudide.shim.mgmt;

public interface MemoryMXBean extends PlatformManagedObject {
    int getObjectPendingFinalizationCount();
    MemoryUsage getHeapMemoryUsage();
    MemoryUsage getNonHeapMemoryUsage();
    boolean isVerbose();
    void setVerbose(boolean value);
    void gc();
}
