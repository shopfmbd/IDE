package cloudide.shim.mgmt;

public interface MemoryManagerMXBean extends PlatformManagedObject {
    String getName();
    boolean isValid();
    String[] getMemoryPoolNames();
}
