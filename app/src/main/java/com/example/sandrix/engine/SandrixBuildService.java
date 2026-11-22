package com.example.sandrix.engine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.R;

/**
 * Data-sync foreground service keeping long-running Sandrix APK builds alive when backgrounded.
 */
public final class SandrixBuildService extends Service {

    public static final String CHANNEL_ID = "sandrix_build_channel";
    public static final int NOTIFICATION_ID = 4091;

    private static final String ACTION_START_OR_UPDATE = "com.example.sandrix.action.BUILD_START_OR_UPDATE";
    private static final String ACTION_STOP = "com.example.sandrix.action.BUILD_STOP";
    private static final String EXTRA_STATUS = "extra_build_status";

    public static void startBuild(Context context, String statusText) {
        if (context == null) return;
        try {
            Intent intent = new Intent(context, SandrixBuildService.class);
            intent.setAction(ACTION_START_OR_UPDATE);
            intent.putExtra(EXTRA_STATUS, statusText != null ? statusText : "Building APK...");
            ContextCompat.startForegroundService(context, intent);
        } catch (Throwable ignored) {
        }
    }

    public static void updateStage(Context context, String statusText) {
        if (context == null) return;
        try {
            Intent intent = new Intent(context, SandrixBuildService.class);
            intent.setAction(ACTION_START_OR_UPDATE);
            intent.putExtra(EXTRA_STATUS, statusText != null ? statusText : "Building APK...");
            context.startService(intent);
        } catch (Throwable ignored) {
        }
    }

    public static void stopBuild(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(context, SandrixBuildService.class);
            intent.setAction(ACTION_STOP);
            context.stopService(intent);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START_OR_UPDATE;
        if (ACTION_STOP.equals(action)) {
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        String status = intent != null ? intent.getStringExtra(EXTRA_STATUS) : null;
        if (status == null || status.trim().isEmpty()) {
            status = getString(R.string.build_service_default_status);
        }

        Notification notification = buildNotification(status);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopForegroundCompat();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            //noinspection deprecation
            stopForeground(true);
        }
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.build_service_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription(getString(R.string.build_service_channel_desc));
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String statusText) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_build)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(statusText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
