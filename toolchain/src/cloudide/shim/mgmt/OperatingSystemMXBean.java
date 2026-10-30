package cloudide.shim.mgmt;

public interface OperatingSystemMXBean extends PlatformManagedObject {
    String getName();
    String getArch();
    String getVersion();
    int getAvailableProcessors();
    double getSystemLoadAverage();
}
