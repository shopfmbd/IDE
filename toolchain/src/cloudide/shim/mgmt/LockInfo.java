package cloudide.shim.mgmt;

public class LockInfo {
    private final String className;
    private final int identityHashCode;
    public LockInfo(String className, int identityHashCode) {
        if (className == null) throw new NullPointerException("Parameter className cannot be null");
        this.className = className; this.identityHashCode = identityHashCode;
    }
    public String getClassName() { return className; }
    public int getIdentityHashCode() { return identityHashCode; }
    @Override public String toString() { return className + '@' + Integer.toHexString(identityHashCode); }
}
