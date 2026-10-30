package com.fahim.cloudide;

import android.app.Application;
import android.content.Context;
import androidx.appcompat.app.AppCompatDelegate;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, t) -> {
            AppLog.crash(th, t);
            if (prev != null) prev.uncaughtException(th, t);
        });
        AppLog.i("App", "Started\n" + AppLog.deviceInfo(this));
        applyTheme(this);
        // Unpack the dex jars and android.jar bundled in the APK. Anything that needs them waits for this to finish.
        new Thread(() -> {
            try {
                Toolchain.seedBundled(getApplicationContext());
            } catch (Throwable t) {
                AppLog.e("Toolchain", "Unpacking the bundled toolchain failed", t);
            }
        }, "toolchain-seed").start();
    }

    static void applyTheme(Context c) {
        int m = Prefs.sp(c).getInt("night", 2);
        AppCompatDelegate.setDefaultNightMode(m == 1 ? AppCompatDelegate.MODE_NIGHT_NO
                : m == 2 ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }
}
