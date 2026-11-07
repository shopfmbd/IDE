package cloudide.shim.sunmgmt;

/** Stand-in for com.sun.management.OperatingSystemMXBean. */
public interface OperatingSystemMXBean extends cloudide.shim.mgmt.OperatingSystemMXBean {
    long getCommittedVirtualMemorySize();
    long getTotalSwapSpaceSize();
    long getFreeSwapSpaceSize();
    long getProcessCpuTime();
    long getFreePhysicalMemorySize();
    long getTotalPhysicalMemorySize();
    double getSystemCpuLoad();
    double getProcessCpuLoad();
    double getCpuLoad();
}
