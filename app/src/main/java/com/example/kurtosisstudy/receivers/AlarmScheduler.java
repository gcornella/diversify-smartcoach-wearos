package com.example.kurtosisstudy.receivers;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

public final class AlarmScheduler {
    private static final String TAG = "AlarmScheduler_KurtosisStudy";
    private static final int REQ_ONESHOT_BASE = 2000; // we’ll add the delay minutes as offset

    private AlarmScheduler() {}

    /** Schedule a one-shot ping to RestartReceiver in delayMs. */
    public static void scheduleOneShot(Context context, long delayMs) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        // Unique request code per delay so immediate and +3min do not overwrite each other
        int reqCode = REQ_ONESHOT_BASE + (int) Math.max(0, delayMs / 60000L);

        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                reqCode,
                new Intent(context, RestartReceiver.class).putExtra("src", "ALARM_ONE_SHOT_" + delayMs),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        long when = SystemClock.elapsedRealtime() + Math.max(0, delayMs);

        if (Build.VERSION.SDK_INT >= 31) {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
        } else {
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
        }
        Log.d(TAG, "One-shot alarm scheduled in " + delayMs + "ms (req=" + reqCode + ")");
    }
}
