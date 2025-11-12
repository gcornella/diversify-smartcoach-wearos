package com.example.kurtosisstudy.sensors;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import com.example.kurtosisstudy.DataStorageManager;
import com.example.kurtosisstudy.LogSaver;
import com.example.kurtosisstudy.PrefsKeys;

/*
 * WatchWearDetector
 * -----------------
 * Purpose:
 *   - Detects whether the watch is currently worn or off-wrist using the off-body + heart-rate sensors.
 *   - Keeps a persistent wear state in SharedPreferences + database for use by the FGS and analytics.
 *
 * What it does:
 *   • On start():
 *       - Registers TYPE_LOW_LATENCY_OFFBODY_DETECT to get definitive on/off events.
 *       - If wearState is STATE_UNKNOWN (e.g., after reboot), starts a short heart-rate resolver.
 *   • Off-body sensor:
 *       - Receives 1 = worn, 0 = not worn.
 *       - Immediately stops the HR resolver (off-body is authoritative).
 *       - Resolves UNKNOWN after reboot and ignores duplicate states.
 *   • Heart-rate resolver:
 *       - Runs only when state is UNKNOWN and HR sensor exists.
 *       - If bpm > 0 and accuracy >= MEDIUM before timeout → assumes watch is worn.
 *       - If timeout reached while still UNKNOWN → assumes not worn.
 *   • applyState(...):
 *       - Updates in-memory flags (isWorn, wearState).
 *       - Persists WEAR_BOOL and WEAR_STATE to WEAR_PREFS.
 *       - Calls DataStorageManager.saveWearStateToDatabase(worn) for a DB-safe record.
 *   • stop():
 *       - Unregisters off-body listener and stops the HR resolver.
 *
 * Notes:
 *   • isWorn() is a quick accessor used by the ForegroundSensorService and other components.
 *   • Design avoids flipping from ON to OFF based only on noisy HR; OFF is trusted from off-body
 *     or from HR timeout when the state was still UNKNOWN.
 */

public class WatchWearDetector implements SensorEventListener {

    // Tag used for logging/debugging
    private static final String TAG = "WatchWearDetector_KurtosisStudy";

    // Prefs + keys
    private SharedPreferences prefsWatchWorn;

    private final SensorManager sensorManager;
    private final Sensor offBodySensor;
    private final Sensor heartRateSensor;
    private final Context context;         // Needed to access DB

    private boolean isRegistered = false;
    private volatile boolean isWorn;

    // Wear state variable management
    private volatile int wearState;
    public static final int STATE_UNKNOWN = -1;
    public static final int STATE_OFF     = 0;
    public static final int STATE_ON      = 1;
    private boolean heartRateActive = false;
    private long heartRateStartMs = 0L;
    private static final int HR_TIMEOUT_MS = 20000; // give PPG time to lock (20s)
    private static final int HR_MIN_ACCURACY = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM;

    private boolean lastWorn;
    private int lastState;

    public WatchWearDetector(Context context) {
        LogSaver.saveLog(TAG,"d", "WatchWearDetector initializing");
        this.context = context;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        // will only be called when sensor detects a change in the on-body/off-body state of the watch.
        offBodySensor = sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT);
        // Also use a heart rate sensor on reboot
        // If the watch powered off while wearing the watch, on reboot it can still have "watch is worn = true" which would still record even if the watch is not worn
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);

        // Read preferences (last worn boolean, and last wear state {-1 if reboot})
        prefsWatchWorn = context.getApplicationContext().getSharedPreferences(PrefsKeys.Wear.WEAR_PREFS, Context.MODE_PRIVATE);
        isWorn = prefsWatchWorn.getBoolean(PrefsKeys.Wear.WEAR_BOOL, false);  // Default to true on first launch
        wearState = prefsWatchWorn.getInt(PrefsKeys.Wear.WEAR_STATE, STATE_UNKNOWN);
        LogSaver.saveLog(TAG,"e", "WatchWearDetector isWorn?;" + isWorn + ", and state is: "+wearState);


        // Load last known from prefs at service start
        lastWorn = isWorn;
        lastState = wearState; // -1 unknown, 0 off, 1 on

        // Save wear boolean to the database as a safeguard
        //DataStorageManager.saveWearStateToDatabase(isWorn);

    }

    public void start() {
        LogSaver.saveLog(TAG,"d", "WatchWearDetector start() called");
        if (offBodySensor != null && !isRegistered) {
            sensorManager.registerListener(this, offBodySensor, SensorManager.SENSOR_DELAY_NORMAL);
            isRegistered = true;
            LogSaver.saveLog(TAG,"d", "WatchWear Listener registered");
        }
        // Absolute resolve if UNKNOWN
        if (wearState == STATE_UNKNOWN) startHrResolver();
    }

    private void startHrResolver() {
        if (heartRateSensor == null) {
            LogSaver.saveLog(TAG,"w","No HR sensor → keep STATE_UNKNOWN until off-body fires");
            return; // don’t force OFF later just because we never had HR
        }
        if (heartRateActive) return;
        heartRateActive = true;
        heartRateStartMs = System.currentTimeMillis();
        sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_FASTEST);
        LogSaver.saveLog(TAG, "d", "heartRate resolver started");
    }
    private void stopHeartRateResolver() {
        if (!heartRateActive) return;
        sensorManager.unregisterListener(this, heartRateSensor);
        heartRateActive = false;
        LogSaver.saveLog(TAG, "d", "heartRate resolver stopped");
    }

    public void stop() {
        LogSaver.saveLog(TAG,"d", "WatchWearDetector stop() called");
        if (isRegistered) {
            sensorManager.unregisterListener(this);
            isRegistered = false;
            LogSaver.saveLog(TAG,"d", "WatchWear Listener unregistered");
        }
        stopHeartRateResolver(); // <— ensure HR flag is cleared
    }

    public boolean isWorn() {
        return isWorn;
    }

    // will be called only when the device detects a change in wear status — not on a fixed schedule.
    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        //LogSaver.saveLog(TAG, "e", "Type: " + type);

        if (type == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) {
            boolean newWorn = (event.values[0] == 1f);  // 1 = worn, 0 = not worn
            LogSaver.saveLog(TAG,"e", "WatchIsWorn onSensorChanged: "+ newWorn);

            // OffBody is definitive → stop HR resolver unconditionally
            stopHeartRateResolver();

            // Accept immediately if we were UNKNOWN — this resolves state after reboot
            if (lastState == STATE_UNKNOWN) {
                applyState(newWorn, newWorn ? STATE_ON : STATE_OFF);
                lastWorn = newWorn;
                lastState = newWorn ? STATE_ON : STATE_OFF;
                return;
            }
            // Ignore duplicates (no edge)
            if (newWorn == lastWorn) {
                LogSaver.saveLog(TAG, "d", "OffBody duplicate (" + newWorn + "), ignoring");
                return;
            }

            applyState(newWorn, newWorn ? STATE_ON : STATE_OFF);
            lastWorn = newWorn;
            lastState = newWorn ? STATE_ON : STATE_OFF;
            return;
        }
        if (type == Sensor.TYPE_HEART_RATE && heartRateActive) {
            float bpm = (event.values != null && event.values.length > 0) ? event.values[0] : 0f;
            int acc = event.accuracy;
            LogSaver.saveLog(TAG, "d", "HR sample bpm=" + bpm + " acc=" + acc);

            if (bpm > 0f && acc >= HR_MIN_ACCURACY) {
                stopHeartRateResolver();
                // HR implies worn; only commit if UNKNOWN or previously OFF
                if (lastState != STATE_ON) {
                    applyState(true, STATE_ON);
                    lastWorn  = true;
                    lastState = STATE_ON;
                }
                return;

            }
            if (System.currentTimeMillis() - heartRateStartMs >= HR_TIMEOUT_MS) {
                stopHeartRateResolver();
                // Only force OFF if still UNKNOWN; avoid false OFF due to HR lock issues
                if (lastState == STATE_UNKNOWN) {
                    applyState(false, STATE_OFF);
                    lastWorn  = false;
                    lastState = STATE_OFF;
                }

            }
        }
    }

    private void applyState(boolean worn, int state) {
        isWorn = worn;
        wearState = state;

        prefsWatchWorn.edit()
                .putBoolean(PrefsKeys.Wear.WEAR_BOOL, worn)
                .putInt(PrefsKeys.Wear.WEAR_STATE, state)
                .apply();
        DataStorageManager.saveWearStateToDatabase(worn);
        LogSaver.saveLog(TAG, "d", "Watch worn: " + worn + ", so state is: " + state + ", so saving: "+worn+" to database");
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used
    }



}
