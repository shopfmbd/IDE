package cloudide.shim.mgmt;

/** Snapshot of one thread, built from Thread.getStackTrace(). Lock and timing data is not available on Android and reads as empty / -1. */
public class ThreadInfo {
    private final long id;
    private final String name;
    private final Thread.State state;
    private final StackTraceElement[] trace;

    public ThreadInfo(Thread t, StackTraceElement[] trace) {
        this.id = t.getId(); this.name = t.getName(); this.state = t.getState(); this.trace = trace == null ? new StackTraceElement[0] : trace;
    }
    public long getThreadId() { return id; }
    public String getThreadName() { return name; }
    public Thread.State getThreadState() { return state; }
    public long getBlockedTime() { return -1; }
    public long getBlockedCount() { return -1; }
    public long getWaitedTime() { return -1; }
    public long getWaitedCount() { return -1; }
    public LockInfo getLockInfo() { return null; }
    public String getLockName() { return null; }
    public long getLockOwnerId() { return -1; }
    public String getLockOwnerName() { return null; }
    public StackTraceElement[] getStackTrace() { return trace; }
    public boolean isSuspended() { return false; }
    public boolean isInNative() { return false; }
    public MonitorInfo[] getLockedMonitors() { return new MonitorInfo[0]; }
    public LockInfo[] getLockedSynchronizers() { return new LockInfo[0]; }
    @Override public String toString() {
        StringBuilder sb = new StringBuilder("\"" + name + "\" Id=" + id + " " + state + "\n");
        for (StackTraceElement e : trace) sb.append("\tat ").append(e).append('\n');
        return sb.append('\n').toString();
    }
}
