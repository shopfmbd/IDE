package cloudide.shim.mgmt;

public enum MemoryType {
    HEAP("Heap memory"), NON_HEAP("Non-heap memory");
    private final String label;
    MemoryType(String label) { this.label = label; }
    @Override public String toString() { return label; }
}
