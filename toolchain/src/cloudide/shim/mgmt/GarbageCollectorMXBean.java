package cloudide.shim.mgmt;

public interface GarbageCollectorMXBean extends MemoryManagerMXBean {
    long getCollectionCount();
    long getCollectionTime();
}
