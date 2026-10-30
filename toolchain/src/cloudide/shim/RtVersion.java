package cloudide.shim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Stand-in for java.lang.Runtime.Version (Java 9). Android has neither Runtime.version() nor Runtime.Version, but the
 * newest ECJ / R8 / apksig call them. toolchain/patch_java9.py points every such reference at this class before D8 runs,
 * so the dexed tools no longer need the missing platform API.
 *
 * The version it reports is the Java language level the toolchain supports (21), or whatever the system property
 * cloudide.java.feature says. Keep this file Java 8 source: it is compiled with --release 8.
 */
public final class RtVersion implements Comparable<RtVersion> {
    private final List<Integer> nums;
    private final String pre, opt;
    private final Integer build;

    private RtVersion(List<Integer> nums, String pre, Integer build, String opt) {
        this.nums = Collections.unmodifiableList(nums);
        this.pre = pre;
        this.build = build;
        this.opt = opt;
    }

    /** Replaces Runtime.version(); toolchain/patch_java9.py renames the call to current(). */
    public static RtVersion current() {
        int f = 21;
        try {
            String p = System.getProperty("cloudide.java.feature");
            if (p != null) f = Integer.parseInt(p.trim());
        } catch (RuntimeException ignored) { }
        List<Integer> l = new ArrayList<>();
        l.add(f);
        l.add(0);
        l.add(0);
        return new RtVersion(l, null, null, null);
    }

    /** Replaces Runtime.Version.parse(String): "21.0.2+13-LTS" style strings. */
    public static RtVersion parse(String s) {
        if (s == null) throw new NullPointerException();
        String rest = s, optional = null, pre = null;
        Integer build = null;
        int dash = rest.indexOf('-');
        int plus = rest.indexOf('+');
        if (plus >= 0) {
            String after = rest.substring(plus + 1);
            rest = rest.substring(0, plus);
            int d = after.indexOf('-');
            if (d >= 0) { optional = after.substring(d + 1); after = after.substring(0, d); }
            if (!after.isEmpty()) {
                try { build = Integer.valueOf(after); } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid version string: '" + s + "'"); }
            }
            dash = rest.indexOf('-');
        }
        if (dash >= 0) { pre = rest.substring(dash + 1); rest = rest.substring(0, dash); }
        List<Integer> l = new ArrayList<>();
        for (String part : rest.split("\\.", -1)) {
            try { l.add(Integer.valueOf(part)); } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid version string: '" + s + "'"); }
        }
        if (l.isEmpty() || l.get(0) < 1) throw new IllegalArgumentException("Invalid version string: '" + s + "'");
        return new RtVersion(l, pre, build, optional);
    }

    private int at(int i) { return i < nums.size() ? nums.get(i) : 0; }

    public int feature() { return at(0); }
    public int interim() { return at(1); }
    public int update() { return at(2); }
    public int patch() { return at(3); }
    public int major() { return feature(); }
    public int minor() { return interim(); }
    public int security() { return update(); }
    public List<Integer> version() { return nums; }
    public Optional<String> pre() { return Optional.ofNullable(pre); }
    public Optional<Integer> build() { return Optional.ofNullable(build); }
    public Optional<String> optional() { return Optional.ofNullable(opt); }

    @Override public int compareTo(RtVersion o) {
        int c = compareToIgnoreOptional(o);
        if (c != 0) return c;
        String a = opt == null ? "" : opt, b = o.opt == null ? "" : o.opt;
        return a.compareTo(b);
    }

    public int compareToIgnoreOptional(RtVersion o) {
        int n = Math.max(nums.size(), o.nums.size());
        for (int i = 0; i < n; i++) {
            int c = Integer.compare(at(i), o.at(i));
            if (c != 0) return c;
        }
        if (pre != null || o.pre != null) {
            if (pre == null) return 1;
            if (o.pre == null) return -1;
            int c = pre.compareTo(o.pre);
            if (c != 0) return c;
        }
        int b1 = build == null ? 0 : build, b2 = o.build == null ? 0 : o.build;
        return Integer.compare(b1, b2);
    }

    public boolean equalsIgnoreOptional(Object o) {
        return o instanceof RtVersion && compareToIgnoreOptional((RtVersion) o) == 0;
    }

    @Override public boolean equals(Object o) { return o instanceof RtVersion && compareTo((RtVersion) o) == 0; }

    @Override public int hashCode() { return nums.hashCode() * 31 + (pre == null ? 0 : pre.hashCode()) + (build == null ? 0 : build) + (opt == null ? 0 : opt.hashCode()); }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nums.size(); i++) { if (i > 0) sb.append('.'); sb.append(nums.get(i)); }
        if (pre != null) sb.append('-').append(pre);
        if (build != null) sb.append('+').append(build);
        if (opt != null) sb.append(build == null ? "+-" : "-").append(opt);
        return sb.toString();
    }
}
