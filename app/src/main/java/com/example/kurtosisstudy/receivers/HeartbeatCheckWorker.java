package com.example.kurtosisstudy.receivers;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.OneTimeWorkRequest;
import androidx.work.ExistingWorkPolicy;
import androidx.work.WorkManager;

import com.example.kurtosisstudy.ForegroundSensorService;
import com.example.kurtosisstudy.PrefsKeys;
import com.example.kurtosisstudy.complications.MyComplicationProviderService;
import com.example.kurtosisstudy.complications.MyProgressComplicationProviderService;
import com.example.kurtosisstudy.complications.MyServiceAliveCheckComplicationProviderService;
import com.example.kurtosisstudy.DataStorageManager;
import com.example.kurtosisstudy.complications.MyWearTimeComplicationProviderService;

public class HeartbeatCheckWorker extends Worker {

    private static final String TAG = "HeartBeatCheckWorker_KurtosisStudy";

    public static final long HEARTBEAT_INTERVAL_MS = 60_000L;  // FGS writes every 60s
    public static final int  HEARTBEAT_GRACE_BEATS = 2;        // tolerate 2 missed beats
    public static final long HEARTBEAT_TTL_MS      = HEARTBEAT_INTERVAL_MS * HEARTBEAT_GRACE_BEATS;
    private static final int RESUME_ID = 42;

    private static final String UNIQUE_ONCE = "hb-check-once";
    private static final String UNIQUE_PERIODIC = "hb-watchdog-15m";

    public HeartbeatCheckWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }

    @NonNull @Override
    public Result doWork() {
        // Use the shared recovery path
        checkAndRecover(getApplicationContext(), "WORKER");
        return Result.success();
    }

    /**
     * SHARED: read HB, decide expired?, normalize wear if needed, try FGS start, else Tap-to-Resume.
     * Safe to call from Worker, RestartReceiver, or anywhere.
     */
    public static void checkAndRecover(Context c, String srcTag) {
        final SharedPreferences hbPrefs =
                c.getSharedPreferences(PrefsKeys.HeartBeat.HEARTBEAT_PREFS, Context.MODE_PRIVATE);

        final long last = hbPrefs.getLong(PrefsKeys.HeartBeat.HEARTBEAT_TIME, 0L);
        final long now  = System.currentTimeMillis();
        final long age  = (last == 0L) ? -1L : (now - last);
        final boolean expired = (last == 0L) || (age > HEARTBEAT_TTL_MS);

        Log.d(TAG, "[" + srcTag + "] HB last=" + last + " ageMs=" + (last == 0L ? "N/A" : age) +
                " expired=" + expired + " ttlMs=" + HEARTBEAT_TTL_MS);

        // Keep complication fresh regardless
        MyServiceAliveCheckComplicationProviderService.requestComplicationUpdate(c);
        MyProgressComplicationProviderService.requestComplicationUpdate(c);
        MyComplicationProviderService.requestComplicationUpdate(c);
        MyWearTimeComplicationProviderService.requestComplicationUpdate(c);

        if (!expired) {
            Log.d(TAG, "[" + srcTag + "] Service healthy → no action.");
            return;
        }

        // Normalize wear + attempt restart
        normalizeWearStateOnExpiredHeartbeat(c, last);
        /*
        try {
            Log.d(TAG, "[" + srcTag + "] Requesting ForegroundSensorService start");
            //ResumeServiceNotification.safeNotify(c, RESUME_ID, ResumeServiceNotification.tapToResume(c));
            //ContextCompat.startForegroundService(c, new Intent(c, ForegroundSensorService.class));
            Log.i(TAG, "[" + srcTag + "] FGS start requested");
        } catch (Throwable t) {
            Log.w(TAG, "[" + srcTag + "] FGS start blocked (" + t.getClass().getSimpleName() + "); posting Tap-to-Resume");
            ResumeServiceNotification.safeNotify(c, RESUME_ID, ResumeServiceNotification.tapToResume(c));
        }

         */
    }

    /** Public so receivers reuse the exact same normalization path. */
    public static void normalizeWearStateOnExpiredHeartbeat(Context c, long lastHb) {
        final SharedPreferences wear =
                c.getSharedPreferences(PrefsKeys.Wear.WEAR_PREFS, Context.MODE_PRIVATE);

        final boolean wasOn  = wear.getBoolean(PrefsKeys.Wear.WEAR_BOOL, false);
        final int prevState  = wear.getInt(PrefsKeys.Wear.WEAR_STATE, -1); // -1 UNKNOWN, 0 OFF, 1 ON

        if (lastHb > 0L && (wasOn || prevState == 1)) {
            try {
                //DataStorageManager.saveWearStateToDatabaseAt(false, lastHb + 1L);
                Log.d(TAG, "Backfilled OFF at lastHB+1ms to close prior ON segment");
            } catch (Throwable t) {
                Log.w(TAG, "Failed to backfill OFF at lastHB+1: " + t);
            }
        }

        wear.edit().putInt(PrefsKeys.Wear.WEAR_STATE, -1 /* UNKNOWN */).apply();
    }

    // ——— Existing helpers (unchanged) ———
    public static void enqueuePeriodic(Context c) {
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                HeartbeatCheckWorker.class, 15, java.util.concurrent.TimeUnit.MINUTES).build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic);
    }

    public static void enqueueNow(Context c) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(HeartbeatCheckWorker.class)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();
        WorkManager.getInstance(c).enqueueUniqueWork(
                UNIQUE_ONCE, ExistingWorkPolicy.KEEP, req);
    }
}
