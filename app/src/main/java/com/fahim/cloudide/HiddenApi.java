package com.fahim.cloudide;

import java.lang.reflect.Method;

/**
 * Lets code loaded into this process (kotlinc and the IntelliJ classes inside it) use reflection on Android's non-SDK
 * interfaces, for example sun.misc.Unsafe.getAndAddInt, which the platform hides from apps and reports as
 * NoSuchMethodException. This is a developer tool compiling code on the phone, not a store feature.
 *
 * How: dalvik.system.VMRuntime.setHiddenApiExemptions({"L"}) is called through "meta reflection" (reflection on the
 * reflection methods themselves), so the call comes from the boot class path, which is not restricted.
 * Known to work on Android 9 to 13; newer releases may close it. It is best effort and never throws.
 *
 * It is only used when a compile fails with the symptom it cures (see KotlinTool.compile), not before every compile:
 * the Kotlin pack ships a stand-in for sun.misc.Unsafe, so most phones never need it.
 */
final class HiddenApi {
    private static boolean done;
    private static boolean ok;
    private static String result = "hidden API exemption not needed (kotlinc ran without it)";

    private HiddenApi() { }

    /** Returns a short status for the log. Runs once per process. */
    static synchronized String relax() {
        if (done) return result;
        done = true;
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntime = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntime, "getRuntime", null);
            Method setExemptions = (Method) getDeclaredMethod.invoke(vmRuntime, "setHiddenApiExemptions", new Class[]{String[].class});
            Object rt = getRuntime.invoke(null);
            setExemptions.invoke(rt, (Object) new String[]{"L"});
            result = "hidden API exemption set";
            ok = true;
        } catch (Throwable t) {
            result = "hidden API exemption failed: " + Toolchain.describe(t);   // the real cause, not just InvocationTargetException
        }
        return result;
    }

    /** True once relax() has been called in this process, whether it worked or not. */
    static synchronized boolean tried() { return done; }

    /** True when the exemption is in place. */
    static synchronized boolean active() { return ok; }

    static synchronized String status() { return result; }
}
