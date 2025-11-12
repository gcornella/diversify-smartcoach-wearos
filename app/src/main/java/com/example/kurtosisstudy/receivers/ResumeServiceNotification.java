// Notifications.java
package com.example.kurtosisstudy.receivers;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.example.kurtosisstudy.ForegroundSensorService;
import com.example.kurtosisstudy.MainActivity;

public final class ResumeServiceNotification {
    private static final String TAG = "ResumeServiceNotification_KurtosisStudy";
    private static final String RESUME_CH = "resume_ch";

    private static final int RC_OPEN_APP = 10;
    private static final int RC_RESUME   = 11;

    public static Notification tapToResume(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(RESUME_CH) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        RESUME_CH, "Resume Service", NotificationManager.IMPORTANCE_HIGH));
            }
        }

        PendingIntent openApp = piOpenApp(c);
        NotificationCompat.Action resumeAction = startServiceAction(c);

        return new NotificationCompat.Builder(c, RESUME_CH)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Problem! App stopped :(")
                .setContentText("Tap to resume the app")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(openApp)
                //.addAction(resumeAction)
                .extend(new NotificationCompat.WearableExtender().addAction(resumeAction))
                .build();
    }

    /** Body tap -> open MainActivity. */
    private static PendingIntent piOpenApp(Context c) {
        Intent i = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, RC_OPEN_APP, i, flags);
    }

    /** Action button -> directly start (or re-start) ForegroundSensorService. */
    private static NotificationCompat.Action startServiceAction(Context c) {
        Intent i = new Intent(c, ForegroundSensorService.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        PendingIntent pi = (Build.VERSION.SDK_INT >= 26)
                ? PendingIntent.getForegroundService(c, RC_RESUME, i, flags)
                : PendingIntent.getService(c, RC_RESUME, i, flags);

        return new NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play,
                "Resume",
                pi
        ).build();
    }

    public static boolean canPostNotifications(@NonNull Context c) {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return NotificationManagerCompat.from(c).areNotificationsEnabled();
        }
    }

    public static void safeNotify(@NonNull Context c, int id, @NonNull Notification n) {
        if (!canPostNotifications(c)) {
            Log.w(TAG, "Notifications disabled or POST_NOTIFICATIONS not granted; skipping notify(id=" + id + ")");
            c.getSharedPreferences("debug", Context.MODE_PRIVATE)
                    .edit().putBoolean("needs_post_notifications", true).apply();
            return;
        }
        try {
            NotificationManagerCompat.from(c).notify(id, n);
        } catch (SecurityException se) {
            Log.w(TAG, "SecurityException posting notification: " + se.getMessage());
        }
    }
}
