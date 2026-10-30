package cloudide.shim.mgmt;

public interface ClassLoadingMXBean extends PlatformManagedObject {
    long getTotalLoadedClassCount();
    int getLoadedClassCount();
    long getUnloadedClassCount();
    boolean isVerbose();
    void setVerbose(boolean value);
}
