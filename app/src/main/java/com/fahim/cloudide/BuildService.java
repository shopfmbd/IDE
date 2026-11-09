package com.fahim.cloudide;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Runs while an on-device build is going. A foreground service keeps Android from moving the build to the slow
 * background CPU group, from freezing it when the screen turns off or you switch app, and from killing it when memory is low.
 */
public class BuildService extends Service {
    private static final String CHANNEL = "build";
    private static volatile boolean up;

    static boolean isUp() { return up; }

    /** Builds that currently want the service. Two builds can overlap, so the first to finish must not stop it for the other. */
    private static int users;

    static void start(Context c) {
        synchronized (BuildService.class) { users++; }
        try {
            c.startForegroundService(new Intent(c, BuildService.class));
        } catch (Throwable ignored) {
            // the build still runs, just without the boost
        }
    }

    /** Stops the service once it has really started (stopping before startForeground would crash the app). */
    static void stop(final Context c) {
        synchronized (BuildService.class) {
            if (--users > 0) return; // another build is still running
            users = 0;
        }
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            int tries;
            @Override public void run() {
                if (up || ++tries > 20) {
                    try { c.stopService(new Intent(c, BuildService.class)); } catch (Throwable ignored) { }
                } else {
                    h.postDelayed(this, 500);
                }
            }
        });
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Builds", NotificationManager.IMPORTANCE_LOW));
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setContentTitle("Building your app")
                    .setContentText("CloudIDE is compiling on this phone")
                    .setSmallIcon(R.drawable.ic_file)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(7, n);
            up = true;
        } catch (Throwable t) {
            // Android crashes an app that was told to start a foreground service and never does: show a plain notification first
            try {
                Notification n = new Notification.Builder(this, CHANNEL).setContentTitle("Building your app").setSmallIcon(R.drawable.ic_file).build();
                startForeground(7, n);
            } catch (Throwable ignored) {
                // nothing more to try
            }
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        up = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
