package com.example.kurtosisstudy.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.concurrent.TimeUnit;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.example.kurtosisstudy.DataStorageManager;
import com.example.kurtosisstudy.LogSaver;
import com.example.kurtosisstudy.PrefsKeys;
import com.example.kurtosisstudy.complications.MyServiceAliveCheckComplicationProviderService;

public class BootReceiver extends BroadcastReceiver {
    // On reboot, set watch worn to unknown
    public static final int STATE_UNKNOWN = -1;

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent != null ? intent.getAction() : null;
        if (action == null) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_USER_UNLOCKED.equals(action)) {

            Context app = context.getApplicationContext();

            // Set watch worn state to unknown
            app.getSharedPreferences(PrefsKeys.Wear.WEAR_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(PrefsKeys.Wear.WEAR_STATE, STATE_UNKNOWN)
                    .apply();

            // Refresh complication
            MyServiceAliveCheckComplicationProviderService.requestComplicationUpdate(app);

            // Immediate one-off check/recovery (unique, idempotent) and set make sure a 15-min periodic watchdog exists
            //HeartbeatCheckWorker.enqueueNow(app);
            //HeartbeatCheckWorker.enqueuePeriodic(app);

            /*
            // Alarm backstops: one-shot immediately, and one-shot again at +3 minutes
            AlarmScheduler.scheduleOneShot(context, 0L);
            AlarmScheduler.scheduleOneShot(context, 3 * 60_000L);



             */
        }
    }
}
