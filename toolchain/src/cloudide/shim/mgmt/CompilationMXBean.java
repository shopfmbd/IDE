package cloudide.shim.mgmt;

public interface CompilationMXBean extends PlatformManagedObject {
    String getName();
    boolean isCompilationTimeMonitoringSupported();
    long getTotalCompilationTime();
}
