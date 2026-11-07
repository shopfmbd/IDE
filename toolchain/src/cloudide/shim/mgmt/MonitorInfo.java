package cloudide.shim.mgmt;

public class MonitorInfo extends LockInfo {
    private final int stackDepth;
    private final StackTraceElement stackFrame;
    public MonitorInfo(String className, int identityHashCode, int stackDepth, StackTraceElement stackFrame) {
        super(className, identityHashCode);
        this.stackDepth = stackDepth; this.stackFrame = stackFrame;
    }
    public int getLockedStackDepth() { return stackDepth; }
    public StackTraceElement getLockedStackFrame() { return stackFrame; }
}
