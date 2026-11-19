package cloudide.shim;

import java.lang.reflect.Field;

/**
 * Stand-in for sun.misc.Unsafe, used only by IntelliJ's own wrapper class inside kotlinc
 * (org/jetbrains/kotlin/com/intellij/util/containers/Unsafe, see toolchain/patch_platform.py).
 *
 * Why: that wrapper reflects on sun.misc.Unsafe.getAndAddInt(Object,long,int) and throws when it is not found
 * (Android hides it or does not have it). Here the getAnd* family is built from the primitives Android does have:
 * getIntVolatile / getLongVolatile / getObjectVolatile and compareAndSwap*.
 * Everything else is passed straight through to the real Unsafe.
 */
@SuppressWarnings({"unchecked", "deprecation", "restriction"})
public final class Unsafe {
    private static final Unsafe theUnsafe = new Unsafe(real());
    private final sun.misc.Unsafe u;

    private Unsafe(sun.misc.Unsafe u) { this.u = u; }

    private static sun.misc.Unsafe real() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (Throwable t) {
            throw new Error("cloudide.shim.Unsafe: cannot reach sun.misc.Unsafe: " + t, t);
        }
    }

    public static Unsafe getUnsafe() { return theUnsafe; }

    // ---- built from primitives
    public final int getAndAddInt(Object o, long off, int delta) {
        int v;
        do { v = u.getIntVolatile(o, off); } while (!u.compareAndSwapInt(o, off, v, v + delta));
        return v;
    }
    public final long getAndAddLong(Object o, long off, long delta) {
        long v;
        do { v = u.getLongVolatile(o, off); } while (!u.compareAndSwapLong(o, off, v, v + delta));
        return v;
    }
    public final int getAndSetInt(Object o, long off, int nv) {
        int v;
        do { v = u.getIntVolatile(o, off); } while (!u.compareAndSwapInt(o, off, v, nv));
        return v;
    }
    public final long getAndSetLong(Object o, long off, long nv) {
        long v;
        do { v = u.getLongVolatile(o, off); } while (!u.compareAndSwapLong(o, off, v, nv));
        return v;
    }
    public final Object getAndSetObject(Object o, long off, Object nv) {
        Object v;
        do { v = u.getObjectVolatile(o, off); } while (!u.compareAndSwapObject(o, off, v, nv));
        return v;
    }

    // ---- pass-through
    public boolean compareAndSwapInt(Object o, long off, int e, int x) { return u.compareAndSwapInt(o, off, e, x); }
    public boolean compareAndSwapLong(Object o, long off, long e, long x) { return u.compareAndSwapLong(o, off, e, x); }
    public boolean compareAndSwapObject(Object o, long off, Object e, Object x) { return u.compareAndSwapObject(o, off, e, x); }

    public int getInt(Object o, long off) { return u.getInt(o, off); }
    public void putInt(Object o, long off, int x) { u.putInt(o, off, x); }
    public long getLong(Object o, long off) { return u.getLong(o, off); }
    public void putLong(Object o, long off, long x) { u.putLong(o, off, x); }
    public Object getObject(Object o, long off) { return u.getObject(o, off); }
    public void putObject(Object o, long off, Object x) { u.putObject(o, off, x); }

    public int getIntVolatile(Object o, long off) { return u.getIntVolatile(o, off); }
    public void putIntVolatile(Object o, long off, int x) { u.putIntVolatile(o, off, x); }
    public long getLongVolatile(Object o, long off) { return u.getLongVolatile(o, off); }
    public void putLongVolatile(Object o, long off, long x) { u.putLongVolatile(o, off, x); }
    public Object getObjectVolatile(Object o, long off) { return u.getObjectVolatile(o, off); }
    public void putObjectVolatile(Object o, long off, Object x) { u.putObjectVolatile(o, off, x); }

    public void putOrderedInt(Object o, long off, int x) { u.putOrderedInt(o, off, x); }
    public void putOrderedLong(Object o, long off, long x) { u.putOrderedLong(o, off, x); }
    public void putOrderedObject(Object o, long off, Object x) { u.putOrderedObject(o, off, x); }

    public long objectFieldOffset(Field f) { return u.objectFieldOffset(f); }
    public long staticFieldOffset(Field f) { return u.staticFieldOffset(f); }
    public Object staticFieldBase(Field f) { return u.staticFieldBase(f); }
    public int arrayBaseOffset(Class<?> c) { return u.arrayBaseOffset(c); }
    public int arrayIndexScale(Class<?> c) { return u.arrayIndexScale(c); }
    public Object allocateInstance(Class<?> c) throws InstantiationException { return u.allocateInstance(c); }
    public void throwException(Throwable t) { u.throwException(t); }
    public void ensureClassInitialized(Class<?> c) { u.ensureClassInitialized(c); }
    public void park(boolean absolute, long time) { u.park(absolute, time); }
    public void unpark(Object thread) { u.unpark(thread); }

    /**
     * kotlinc's fast jar file system unmaps memory-mapped jars with this (a JDK 9 method). Android has no way to do that;
     * the garbage collector releases a mapped buffer once nothing refers to it, so there is nothing to do here.
     */
    public void invokeCleaner(java.nio.ByteBuffer buffer) { }

    // fences: newer Android has them, older does not; a volatile access is a full barrier either way
    private static volatile int fence;
    public void loadFence() { fullFence(); }
    public void storeFence() { fullFence(); }
    public void fullFence() { fence = fence + 1; }
}
