package cloudide.shim.mgmt;

public class MemoryUsage {
    private final long init, used, committed, max;

    public MemoryUsage(long init, long used, long committed, long max) {
        if (init < -1 || max < -1 || used < 0 || committed < 0) throw new IllegalArgumentException("bad memory usage");
        this.init = init; this.used = used; this.committed = committed; this.max = max;
    }
    public long getInit() { return init; }
    public long getUsed() { return used; }
    public long getCommitted() { return committed; }
    public long getMax() { return max; }
    @Override public String toString() {
        return "init = " + init + "(" + (init >> 10) + "K) used = " + used + "(" + (used >> 10) + "K) committed = " + committed
                + "(" + (committed >> 10) + "K) max = " + max + "(" + (max >> 10) + "K)";
    }
}
