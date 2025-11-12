package com.example.kurtosisstudy.receivers;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.kurtosisstudy.MainActivity;

public class NotWornAlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "NotWornAlarmReceiver_KurtosisStudy";
    private static final String CHANNEL_ID = "wear_not_worn_channel3";
    private static final String CHANNEL_NAME = "Not-Worn Alerts";
    private static final int NOT_WORN_NOTIF_ID = 8888;

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "🔥 Alarm received — checking audio settings");

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        int ringerMode = AudioManager.RINGER_MODE_NORMAL;
        int interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL;

        if (audioManager != null) {
            ringerMode = audioManager.getRingerMode();
        }

        if (notificationManager != null) {
            interruptionFilter = notificationManager.getCurrentInterruptionFilter();
        }

        // 🔕 If fully silenced (ringer silent or DND "none") — skip everything
        if (ringerMode == AudioManager.RINGER_MODE_SILENT ||
                interruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE) {
            Log.d(TAG, "🔕 Device in Silent or DND Total Silence — skipping sound and vibration");
            return;
        }

        // 📳 If in vibrate-only mode
        if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            Log.d(TAG, "📳 Device in Vibrate-only mode — skipping sound, vibrating only");
            vibrateNow(context);
            showNotification(context);
            return;
        }

        // 🔊 Normal mode — play sound and vibrate
        Log.d(TAG, "🔊 Device in Normal mode — playing sound and vibration");

        try {
            Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (soundUri == null) {
                Log.w(TAG, "❗ Alarm URI null, falling back to notification sound...");
                soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }

            Ringtone ringtone = RingtoneManager.getRingtone(context, soundUri);
            if (ringtone != null) {
                ringtone.setStreamType(AudioManager.STREAM_ALARM);
                if (!ringtone.isPlaying()) {
                    ringtone.play();
                    Log.d(TAG, "🔊 Ringtone played successfully");
                }
            } else {
                Log.e(TAG, "❌ Failed to get Ringtone instance");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error playing sound: " + e.getMessage());
        }

        vibrateNow(context);
        showNotification(context);

    }

    private void vibrateNow(Context context) {
        Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createOneShot(800, VibrationEffect.DEFAULT_AMPLITUDE));
            Log.d(TAG, "📳 Vibration triggered");
        }
    }

    private void showNotification(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (manager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("Alerts to wear the watch");
                channel.enableVibration(true);
                manager.createNotificationChannel(channel);
            }

            Intent openIntent = new Intent(context, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    openIntent,
                    PendingIntent.FLAG_IMMUTABLE
            );

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("⌚ Wear me")
                    .setContentText("Let’s keep tracking movement!")
                    //.setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .setFullScreenIntent(pendingIntent, true)
                    .setContentIntent(pendingIntent);

            manager.notify(NOT_WORN_NOTIF_ID, builder.build());
            Log.d(TAG, "🛎️ Notification shown to prompt wearing the watch");
        }
    }


}
