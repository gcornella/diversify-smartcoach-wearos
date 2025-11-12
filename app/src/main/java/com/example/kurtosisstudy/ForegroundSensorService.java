package com.example.kurtosisstudy;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.kurtosisstudy.complications.MyProgressComplicationProviderService;
import com.example.kurtosisstudy.complications.MyServiceAliveCheckComplicationProviderService;
import com.example.kurtosisstudy.complications.MyWearTimeComplicationProviderService;
import com.example.kurtosisstudy.receivers.NotWornAlarmReceiver;
import com.example.kurtosisstudy.receivers.RestartReceiver;
import com.example.kurtosisstudy.sensors.SensorHandler;
import com.example.kurtosisstudy.sensors.WatchWearDetector;
import com.example.kurtosisstudy.receivers.HeartbeatCheckWorker;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/*
 * ForegroundSensorService
 * -----------------------
 * Purpose:
 *   - Long-lived Wear OS foreground service that:
 *       • Collects 50 Hz sensor data when the watch is worn AND within active hours.
 *       • Tracks wear / not-wear episodes.
 *       • Periodically aggregates data into per-minute, daily, and weekly metrics.
 *       • Drives goal + wear-time notifications and complications.
 *       • Self-heals if executors or sensors die (watchdog).
 *
 * Main components:
 *   • SensorHandler sensorHandler
 *       - Handles accelerometer at ~50 Hz.
 *       - Started/stopped depending on wear state + active hours.
 *
 *   • WatchWearDetector wearDetector
 *       - Listens to OFFBODY + HR to decide if the watch is worn.
 *       - Persists wear sessions to DB via DataStorageManager.
 *
 *   • Heartbeat (hbHandler + hbTick)
 *       - Writes HEARTBEAT_TIME to shared prefs every 60 s.
 *       - RestartReceiver / HeartbeatCheckWorker use this to detect if the service died.
 *       - onStartCommand() writes an immediate heartbeat and, if the last one is “stale”,
 *         closes the previous ON segment (saveWearStateToDatabaseAt(false, lastHb + 1))
 *         and forces wear state back to UNKNOWN so WatchWearDetector re-validates.
 *
 *   • Wake lock
 *       - acquireWakeLock(): keeps CPU on (PARTIAL_WAKE_LOCK) so periodic tasks and
 *         sensor collection continue with screen off.
 *       - releaseWakeLock(): called in onDestroy() to avoid battery leaks.
 *
 *   • Schedulers (all single-threaded executors)
 *       - periodicCheckScheduler (every 60 s)
 *           · Checks:
 *               · Active hours? (08:00–22:00)
 *               · Is watch worn? (via wearDetector)
 *               · New calendar day? → DataStorageManager.init(...) + saveWearStateToDatabase.
 *               · Battery state every 10 min (charging + battery % logging).
 *           · Logic:
 *               · If within hours AND worn → start SensorHandler (if not already),
 *                 ensure DataStorageManager + NotificationManager are running,
 *                 and dismiss “not worn” notification.
 *               · If within hours BUT not worn → show “watch not worn” notification,
 *                 stop SensorHandler, stop NotificationManager.
 *               · Outside hours → stop SensorHandler + NotificationManager.
 *
 *       - dataStorageScheduler (aligned to minute, then every 1 min)
 *           · Runs only if within active hours.
 *           · If watch is worn:
 *               · Uses one minute of data (2 minutes before computation call) of raw data to:
 *                   · computeAndSaveMinuteAverage(...)
 *                   · computeAndSaveDailyCumulative()
 *                   · computeAndSaveDailyWearTime()
 *           · Every 5 min:
 *               · computeAndSaveAdjustedDailyGoal() (movement goal based on weekly ratio
 *                 and current wear time).
 *           · Every 60 min:
 *               · computeAndSaveWeeklyAverage()
 *               · createAndSaveWeeklyRatios()
 *           · Persists LAST_WEAR_TIME and LAST_HOURLY_SAVE to prefs.
 *
 *       - notificationScheduler (every 1 min, initial delay 35 s)
 *           · Only runs in active hours.
 *           · Calls GoalNotificationManager.notifyIfGoalReached(...) to push progress
 *             notifications (second-study behavior is guarded by TODO comments).
 *
 *       - watchdogScheduler (every 60 min)
 *           · Ensures all three schedulers are alive; recreates them if shutdown.
 *           · Every ~50 min:
 *               · Refreshes WatchWearDetector (stop/start).
 *               · Refreshes SensorHandler (stop + mark inactive).
 *               · Updates LAST_SENSOR_CHECK in prefs.
 *
 *   • Service lifecycle:
 *       - onCreate():
 *           · Creates notification channel.
 *           · Initializes DataStorageManager and study start timestamp.
 *       - onStartCommand():
 *           · Starts foreground notification (health FGS type where supported).
 *           · Loads last heartbeat and detects “cold start” after long downtime.
 *           · Ensures:
 *               · DataStorageManager.init(...)
 *               · initializeLastKnownProgress()
 *               · initializeLastKnownGoal()
 *           · Cancels “resume app” notification from RestartReceiver.
 *           · Refreshes complications: wear time, progress, and “service alive”.
 *           · Acquires wake lock.
 *           · Restores “last run” timestamps for wear time, hourly saves, sensor checks, battery.
 *           · Fixes stale wear state if service died while watch was ON.
 *           · Starts heartbeat loop, SensorHandler, WatchWearDetector, watchdog,
 *             periodic check, data storage, and notification managers.
 *           · Returns START_STICKY so the system restarts it after kill.
 *
 *       - onDestroy():
 *           · Removes foreground notification.
 *           · Forces wear prefs to OFF + UNKNOWN and logs to DB.
 *           · Stops heartbeat, releases wake lock.
 *           · Shuts down SensorHandler + WatchWearDetector.
 *           · Shuts down all schedulers (watchdog, periodic, data, notifications).
 *
 *       - onTaskRemoved():
 *           · Schedules HeartbeatCheckWorker so WorkManager can decide on restart.
 *
 *   • Notifications:
 *       - buildNotification():
 *           · Simple “App working / Service running” foreground card.
 *           · Tapping opens MainActivity with flags suitable for Wear.
 *       - dismissNotWornNotification():
 *           · Cancels “not worn” notification and associated NotWornAlarmReceiver alarm.
 *
 *   • Channels:
 *       - createNotificationChannel():
 *           · Creates NOTIF_CHANNEL_ID with IMPORTANCE_HIGH (can lower for 2nd study).
 *
 * Overall:
 *   - This class is the brain of the runtime system: it orchestrates sensors, wear detection,
 *     aggregation, goals, notifications, and complications, and it aggressively self-repairs
 *     (recreating executors and re-registering sensors) to survive watch quirks, reboots,
 *     and background kills.
 */

public class ForegroundSensorService extends Service{

    // Tag used for logging/debugging
    private static final String TAG = "ForegroundSensorService_KurtosisStudy";

    // Heartbeat (cross-process liveness signal checked by RestartReceiver/UI)
    // If it goes stale, receiver may restart service.
    private static final long   HB_MS   = 60_000L;  // write heartbeat every 60s
    private boolean heartbeatStarted = false;


    // Foreground notification
    private static final int    NOTIF_ID = 1;
    private static final String NOTIF_CHANNEL_ID = "sensor_channel";
    private static final String NOTIF_CHANNEL_NAME = "Service Notifications";
    private static final int NOT_WORN_NOTIF_ID = 8888;

    // Handles sensor registration and streams data at 50 Hz in background.
    private SensorHandler sensorHandler;

    // Checks whether the watch is being worn.
    private WatchWearDetector wearDetector;

    // Watch is worn and within allowed activity hours
    private boolean isWornSensorActive = false;
    private boolean withinHours = false;

    // Schedule calls to the periodic checker for watch worn and active hours
    private ScheduledExecutorService periodicCheckScheduler = Executors.newSingleThreadScheduledExecutor();
    private boolean isPeriodicCheckRunning = false;

    // Schedule the calls to the Data Storage Manager
    private ScheduledExecutorService dataStorageScheduler = Executors.newSingleThreadScheduledExecutor();
    private boolean isDataStorageManagerRunning = false;
    private long lastHourlySaveTaskTimestamp = 0;
    private long lastDailyWearTimeUpdateTimestamp = 0;
    private long lastAllSensorsCheckTimestamp = 0;
    private long lastBatteryCheckTimestamp = 0;

    // Schedule the calls to the Notification Scheduler to check when to provide notifications
    private ScheduledExecutorService notificationScheduler = Executors.newSingleThreadScheduledExecutor();
    private boolean isNotificationManagerRunning = false;

    // A watchdog to protect the system against unexpected shutdowns (every 5 minutes for example)
    private ScheduledExecutorService watchdogScheduler = Executors.newSingleThreadScheduledExecutor();
    private boolean isWatchdogRunning = false;

    // Keep the foreground service active even when screen is off using a wakelock
    private PowerManager.WakeLock wakeLock; // Keep reference to avoid GC
    // You can timeout the wakelock if needed (recommended). In this case it never sleeps.
    // private static final long WAKELOCK_TIMEOUT_MS = TimeUnit.HOURS.toMillis(15);

    // Define preferences of service
    private SharedPreferences prefs, prefsWatchWorn;

    // Restart service
    private static final int RESUME_ID = 42;                // notif presented to the user to restart service in RestartReceiver

    // Heartbeat handler (runs on main thread). Writes timestamp in shared preferences
    // Protection: helps the RestartReceiver class detect if service is killed, and tries to restart it
    private final Handler hbHandler = new Handler(Looper.getMainLooper());
    private final Runnable hbTick = new Runnable() {
        @Override public void run() {
            // Write a cross-process liveness timestamp
            getSharedPreferences(PrefsKeys.HeartBeat.HEARTBEAT_PREFS, MODE_PRIVATE)
                    .edit()
                    .putLong(PrefsKeys.HeartBeat.HEARTBEAT_TIME, System.currentTimeMillis())
                    .apply(); // saves asynchronously (non-blocking)

            // re-schedules the same Runnable to run again after HB_MS creating a loop
            hbHandler.postDelayed(this, HB_MS);
            LogSaver.saveLog("WatchWearDetector_KurtosisStudy", "w", "Heartbeat tick at: "+System.currentTimeMillis());

        }
    };

    // One-time setup when the service is first created by the system
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG,"onCreate() called");

        // Prepare notification channel before we call startForeground() (API 26+ requirement)
        createNotificationChannel();

        // Kick off (start) the heartbeat immediately so external components can "see" us.
        //hbHandler.post(hbTick);
        //hbHandler.postDelayed(hbTick, HB_MS);

        // When the study begins, save the date to know when the week starts (if first entry)
        DataStorageManager.init(getApplicationContext());
        DataStorageManager.startStudyTimestamp();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LogSaver.saveLog(TAG,"d", "onStartCommand() called");

        // Build and show the foreground notification promptly (within ~5s of start)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
        } else {
            startForeground(NOTIF_ID, buildNotification());
        }
        LogSaver.saveLog(TAG,"d", "After call to startForeground() - notification created and being displayed");

        // Write immediate heartbeat so the complication hides quickly
        long now = System.currentTimeMillis();
        SharedPreferences hb = getSharedPreferences(PrefsKeys.HeartBeat.HEARTBEAT_PREFS, MODE_PRIVATE);
        long lastHb = hb.getLong(PrefsKeys.HeartBeat.HEARTBEAT_TIME, 0L);
        boolean stale = lastHb > 0 && now - lastHb > 2 * HB_MS;


        // Ensure lastKnownProgress and lastKnownGoal are set correctly
        DataStorageManager.init(getApplicationContext());
        DataStorageManager.initializeLastKnownProgress();
        DataStorageManager.initializeLastKnownGoal();

        // Close the notification sent from RestartReceiver to the user to restart
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(RESUME_ID); // hide the resume prompt

        // Refresh the "ServiceAlive" complication so it disappears immediately
        MyWearTimeComplicationProviderService.requestComplicationUpdate(getApplicationContext());
        MyProgressComplicationProviderService.requestComplicationUpdate(getApplicationContext());
        MyServiceAliveCheckComplicationProviderService.requestComplicationUpdate(getApplicationContext());

        // Acquires a partial wake lock to keep the CPU running even when the screen is off.
        acquireWakeLock();

        // In case of service restart, recover "update" times for the periodicChecks
        prefs = getApplicationContext().getSharedPreferences(PrefsKeys.FGS.FGS_PREFS, MODE_PRIVATE);
        lastDailyWearTimeUpdateTimestamp = prefs.getLong(PrefsKeys.FGS.LAST_WEAR_TIME, 0);
        lastHourlySaveTaskTimestamp = prefs.getLong(PrefsKeys.FGS.LAST_HOURLY_SAVE, 0);
        lastAllSensorsCheckTimestamp = prefs.getLong(PrefsKeys.FGS.LAST_SENSOR_CHECK, 0);
        lastBatteryCheckTimestamp = prefs.getLong(PrefsKeys.FGS.LAST_BATTERY_CHECK, 0);

        // If watch powered down during the day, next time it powers on doesn't know whether it has been taken off
        prefsWatchWorn = getApplicationContext().getSharedPreferences(PrefsKeys.Wear.WEAR_PREFS, MODE_PRIVATE);
        boolean wasOn = prefsWatchWorn.getBoolean(PrefsKeys.Wear.WEAR_BOOL, false);
        // consider “dead” if >2 heartbeats have passed (2 * HB_MS)
        LogSaver.saveLog("WatchWearDetector_KurtosisStudy", "w", "onStartCommand wear");
        if (stale) {
            // ideally stamp OFF at last heartbeat (+1 ms) so you don’t count the downtime
            // Close previous ON segment neatly if we thought it was ON
            if (wasOn) {
                DataStorageManager.saveWearStateToDatabaseAt(false, lastHb + 1);
                LogSaver.saveLog("WatchWearDetector_KurtosisStudy", "w", "was on, saving to false at: "+lastHb);

            }
            // Force UNKNOWN so WatchWearDetector re-validates immediately
            prefsWatchWorn.edit()
                        //.putBoolean(PrefsKeys.Wear.WEAR_BOOL, false)
                    .putInt(PrefsKeys.Wear.WEAR_STATE, -1)
                    .apply();
            LogSaver.saveLog("WatchWearDetector_KurtosisStudy", "w", "Cold start detected");
        }

        // Update heartbeat immediately so others can see we’re alive
        hb.edit().putLong(PrefsKeys.HeartBeat.HEARTBEAT_TIME, now).apply();

        // start periodic loop AFTER the check, with a delay
        if (!heartbeatStarted) {
            //hbHandler.postDelayed(hbTick, HB_MS);  // delayed first tick
            LogSaver.saveLog("WatchWearDetector_KurtosisStudy", "w", "Heartbeat started");
            hbHandler.post(hbTick);
            heartbeatStarted = true;
        }

        // Initialize accelerometer Sensor Handler
        if (sensorHandler == null) {
            sensorHandler = new SensorHandler(this);
            LogSaver.saveLog(TAG,"d", "SensorHandler registered");
        }

        // Wear Detector Sensor class
        if (wearDetector == null) {
            wearDetector = new WatchWearDetector(this);
            wearDetector.start();
            LogSaver.saveLog(TAG,"d", "WearDetector registered and started");
        }

        // Start the watch dog
        startWatchdog();

        // Start a periodic check to check if the watch is worn and if it's in between "active" hours
        startPeriodicCheck();

        // Starts the data storage manager to allow computing outcomes and saving to db
        startDataStorageManager();

        // Start the notification scheduler
        startNotificationManager();

        return START_STICKY; // Will be recreated after getting killed
    }

    // Checks if the time is between 8am and 10pm
    private boolean isWithinActiveHours() {
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        return hour >= 8 && hour < 22;
    }

    // Acquires a partial wake lock to keep the CPU running even when the screen is off.
    // This prevents the system from pausing scheduled tasks or sensor collection.
    private void acquireWakeLock() {
        LogSaver.saveLog(TAG,"d", "Attempting to acquire wake lock...");
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                LogSaver.saveLog(TAG,"e", "PowerManager is null — cannot acquire wake lock");
                return;
            }

            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KurtosisStudy::DataWakeLock");
            }

            if (!wakeLock.isHeld()) {
                wakeLock.acquire(); // WAKELOCK_TIMEOUT_MS would go here
                LogSaver.saveLog(TAG,"d", "Wake lock acquired forever");
            } else {
                LogSaver.saveLog(TAG,"d", "Wake lock already held");
            }
        } catch (Exception e) {
            LogSaver.saveLog(TAG,"e", "Failed to acquire wake lock: "+e.getMessage());
        }
    }

    // The watchdog restarts sensors and executors
    private void startWatchdog() {
        restartWatchDogIfShutdown();
        if (isWatchdogRunning) return;
        isWatchdogRunning = true;
        try{
            watchdogScheduler.scheduleWithFixedDelay(() -> {
                try {
                    LogSaver.saveLog(TAG,"d", "Watchdog scheduler called");
                    restartPeriodicCheckIfShutdown();
                    restartDataStorageSchedulerIfShutdown();
                    restartNotificationSchedulerIfShutdown();

                    // Check every 30 minutes to refresh sensor registrations in case they fail
                    long now = System.currentTimeMillis();
                    if (now - lastAllSensorsCheckTimestamp >= TimeUnit.MINUTES.toMillis(50)) {

                        // Re-register or refresh WatchWearDetector
                        if (wearDetector != null) {
                            wearDetector.stop();
                            wearDetector.start();
                            LogSaver.saveLog(TAG, "w", "WatchWearDetector re-registered");
                        } else {
                            wearDetector = new WatchWearDetector(this);
                            wearDetector.start();
                            LogSaver.saveLog(TAG, "w", "WatchWearDetector was null during check");
                        }
                        // Re-register or refresh sensorhandler
                        if (sensorHandler != null) {
                            sensorHandler.stop();
                            isWornSensorActive = false;
                            //sensorHandler.start();
                            LogSaver.saveLog(TAG, "w", "sensorHandler re-registered");
                        } else {
                            sensorHandler = new SensorHandler(this);
                            isWornSensorActive = false;
                            //sensorHandler.start();
                            LogSaver.saveLog(TAG, "w", "sensorHandler was null during check");
                        }

                        // Update persistent and in-memory timestamp
                        prefs.edit().putLong(PrefsKeys.FGS.LAST_SENSOR_CHECK, now).apply();
                        lastAllSensorsCheckTimestamp = now;
                    }

                } catch (Throwable t) {
                    LogSaver.saveLog(TAG,"e", "Watchdog failed" + Log.getStackTraceString(t));
                    restartWatchDogIfShutdown();
                }
            }, 60, 60, TimeUnit.MINUTES);
        } catch (RejectedExecutionException ex) {
            LogSaver.saveLog(TAG, "e", "RejectedExecutionException in startNotificationManager: " + ex.getMessage());
            restartWatchDogIfShutdown();
        }
    }

    // Every minute, checks if the watch is worn and within active hours, otherwise it doesn't get data
    private void startPeriodicCheck() {
        restartPeriodicCheckIfShutdown();
        if (isPeriodicCheckRunning) return;
        LogSaver.saveLog(TAG,"d", "startPeriodicCheck()");
        try{
            periodicCheckScheduler.scheduleWithFixedDelay(() -> {
                try {
                    LogSaver.saveLog(TAG,"e", "Periodic check triggered at: " + new Date(System.currentTimeMillis()));
                    // Check if watchdog is active and restarts it if inactive
                    restartWatchDogIfShutdown();

                    // Check wear state and hours
                    withinHours = isWithinActiveHours();
                    boolean watchIsWorn = wearDetector != null && wearDetector.isWorn();
                    LogSaver.saveLog(TAG,"d", "withinHours: " + withinHours + ", watchIsWorn: " + watchIsWorn);

                    // Check if the DB should be reinitialized (new day)
                    if (DataStorageManager.shouldReinitializeDailyDb()) {
                        DataStorageManager.init(getApplicationContext());
                        LogSaver.saveLog(TAG,"d", "New day detected — reinitializing DB...");

                        // Log the current wear state to start the new day's timeline
                        DataStorageManager.saveWearStateToDatabase(watchIsWorn);

                        // If a new day comes, update the wear complication
                        MyWearTimeComplicationProviderService.requestComplicationUpdate(getApplicationContext());
                        MyProgressComplicationProviderService.requestComplicationUpdate(getApplicationContext());
                    }

                    // Check if watch is charging
                    long now = System.currentTimeMillis();
                    boolean shouldCheckBattery = now - lastBatteryCheckTimestamp >= TimeUnit.MINUTES.toMillis(10);
                    if (shouldCheckBattery) {
                        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                        Intent batteryStatus = getApplicationContext().registerReceiver(null, ifilter);
                        if (batteryStatus != null) {
                            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                            boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
                            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                            float batteryPct = level * 100 / (float) scale;
                            LogSaver.saveLog(TAG, "d", "isCharging: " + isCharging + ", batteryPct: " + batteryPct);
                        } else {
                            LogSaver.saveLog(TAG, "w", "batteryStatus is null — unable to check charging state");
                        }
                        lastBatteryCheckTimestamp = now;
                        prefs.edit().putLong(PrefsKeys.FGS.LAST_BATTERY_CHECK, now).apply();

                    }

                    if (withinHours) {
                        if (watchIsWorn) {
                            if (!isWornSensorActive) {
                                LogSaver.saveLog(TAG, "d", "Starting sensorHandler");
                                sensorHandler.start();
                                isWornSensorActive = true;
                                dismissNotWornNotification();
                            }
                            startDataStorageManager();
                            startNotificationManager();
                        } else {
                            // Send notification ONLY if watch is not worn during active hours
                            // TODO - 2nd STUDY (comment next to hide not worn notification)
                            GoalNotificationManager.notificationIfWatchNotWorn(getApplicationContext());

                            // Stop everything if it was running
                            if (isWornSensorActive) {
                                LogSaver.saveLog(TAG, "d", "Stopping sensorHandler");
                                sensorHandler.stop();
                                isWornSensorActive = false;
                            }
                            //stopDataStorageManager();
                            stopNotificationManager();
                        }
                    } else {
                        // Outside of active hours → always stop everything
                        if (isWornSensorActive) {
                            LogSaver.saveLog(TAG, "d", "Stopping sensorHandler");
                            sensorHandler.stop();
                            isWornSensorActive = false;
                        }
                        //stopDataStorageManager();
                        stopNotificationManager();
                    }
                } catch (Throwable t) {
                    LogSaver.saveLog(TAG,"e", "Periodic check failed" + Log.getStackTraceString(t));
                    restartPeriodicCheckIfShutdown();
                }
            }, 0, 60, TimeUnit.SECONDS);
            isPeriodicCheckRunning = true;
            LogSaver.saveLog(TAG, "d", "periodicCheckScheduler task successfully scheduled.");
        } catch (RejectedExecutionException ex) {
            LogSaver.saveLog(TAG, "e", "RejectedExecutionException in startNotificationManager: " + ex.getMessage());
            restartPeriodicCheckIfShutdown();
        }
    }


    private void dismissNotWornNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOT_WORN_NOTIF_ID); // or nm.cancel(tag, NOT_WORN_ID) if you used a tag

        prefsWatchWorn
                .edit()
                .putLong(PrefsKeys.Wear.LAST_WEAR_NOTIF, 0L)
                .apply();

        // optional: cancel any pending exact alarm for the 1s sound fallback
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(
                this, 1234,
                new Intent(this, NotWornAlarmReceiver.class),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        if (am != null && pi != null) {
            am.cancel(pi);
            pi.cancel();
        }

    }

    private void startDataStorageManager() {
        restartDataStorageSchedulerIfShutdown();
        if (isDataStorageManagerRunning) return;
        LogSaver.saveLog(TAG,"d", "startDataStorageManager()");

        try {
            dataStorageScheduler.scheduleWithFixedDelay(() -> {
                try {
                    restartWatchDogIfShutdown();    // Check if watchdog is active
                    long now = System.currentTimeMillis();
                    if (withinHours){
                        //LogSaver.saveLog(TAG,"d", "startDataStorageScheduler called again after 1'");
                        if (wearDetector != null && wearDetector.isWorn()){

                            long startOfLast2Minute = now - TimeUnit.MINUTES.toMillis(2);
                            long endOfLast2Minute = startOfLast2Minute + TimeUnit.MINUTES.toMillis(1);
                            long minuteTimestamp = alignToMinuteTimestamp(startOfLast2Minute);
                            String formattedMinute = formatAlignedMinute(minuteTimestamp);

                            DataStorageManager.computeAndSaveMinuteAverage(startOfLast2Minute, endOfLast2Minute, formattedMinute);
                            DataStorageManager.computeAndSaveDailyCumulative();
                            DataStorageManager.computeAndSaveDailyWearTime(); // Update wear time for the day
                        }

                        // Run once every 5 minutes
                        if (now - lastDailyWearTimeUpdateTimestamp >= TimeUnit.MINUTES.toMillis(5)) {

                            // Update daily goal based on that week's ratio and the remaining wearTime
                            DataStorageManager.computeAndSaveAdjustedDailyGoal();

                            // Update check time
                            lastDailyWearTimeUpdateTimestamp = now;
                            prefs.edit().putLong(PrefsKeys.FGS.LAST_WEAR_TIME, now).apply();
                        }
                    }

                    // Run once every 60 minutes
                    if(now - lastHourlySaveTaskTimestamp >= TimeUnit.MINUTES.toMillis(60)){
                        LogSaver.saveLog(TAG,"d", "Weekly updates called every hour'");
                        // Update weekly average and redefine weekly goals
                        DataStorageManager.computeAndSaveWeeklyAverage();
                        DataStorageManager.createAndSaveWeeklyRatios();

                        // Update check time
                        lastHourlySaveTaskTimestamp = now;
                        prefs.edit().putLong(PrefsKeys.FGS.LAST_HOURLY_SAVE, now).apply();
                    }

                } catch (Throwable t) {
                    LogSaver.saveLog(TAG,"e", "dataStorageScheduler failed" + Log.getStackTraceString(t));
                    restartDataStorageSchedulerIfShutdown();
                }
            }, computeDelayToNextMinuteMillis(), TimeUnit.MINUTES.toMillis(1), TimeUnit.MILLISECONDS);
            isDataStorageManagerRunning = true;
            LogSaver.saveLog(TAG, "d", "dataStorageScheduler task successfully scheduled.");
        } catch (RejectedExecutionException ex) {
            LogSaver.saveLog(TAG, "e", "RejectedExecutionException in startNotificationManager: " + ex.getMessage());
            restartDataStorageSchedulerIfShutdown();
        }
    }

    private void stopDataStorageManager() {
        if (!isDataStorageManagerRunning) return;
        LogSaver.saveLog(TAG, "w", "stopDataStorageManager()");
        DataStorageManager.shutdown();

        dataStorageScheduler.shutdownNow(); // terminate the executor service permanently — it can’t be used again
        isDataStorageManagerRunning = false;

        // Recreate it so you can reschedule later
        dataStorageScheduler = Executors.newSingleThreadScheduledExecutor();
    }

    private void startNotificationManager() {
        restartNotificationSchedulerIfShutdown(); // ensures a fresh executor if dead
        if (isNotificationManagerRunning) return; // skip if already running
        LogSaver.saveLog(TAG,"d", "startNotificationManager()");

        try {
            notificationScheduler.scheduleWithFixedDelay(() -> {
                try {
                    if (isWithinActiveHours()) {
                        LogSaver.saveLog(TAG, "d", "notificationManager call");
                        restartWatchDogIfShutdown();
                        // TODO - 2nd STUDY (comment next to hide notifications)
                        GoalNotificationManager.notifyIfGoalReached(getApplicationContext());
                    } else {
                        LogSaver.saveLog(TAG, "w", "Skipping notificationManager call — outside active hours");
                    }
                } catch (Exception e) {
                    LogSaver.saveLog(TAG, "e", "NotificationManager failed: " + e.getMessage());
                    restartNotificationSchedulerIfShutdown(); // refresh executor only
                }
            }, 35, 1, TimeUnit.MINUTES);
            isNotificationManagerRunning = true;
            LogSaver.saveLog(TAG, "d", "notificationScheduler task successfully scheduled.");
        } catch (RejectedExecutionException ex) {
            LogSaver.saveLog(TAG, "e", "RejectedExecutionException in startNotificationManager: " + ex.getMessage());
            restartNotificationSchedulerIfShutdown();
        }
    }


    private void stopNotificationManager() {
        if (!isNotificationManagerRunning) return;
        LogSaver.saveLog(TAG, "w", "stopNotificationManager()");
        notificationScheduler.shutdownNow(); // terminate the executor service permanently — it can’t be used again
        isNotificationManagerRunning = false;
        // Recreate it so you can reschedule later
        notificationScheduler = Executors.newSingleThreadScheduledExecutor();
    }


    private void restartWatchDogIfShutdown() {
        if (watchdogScheduler == null || watchdogScheduler.isShutdown() || watchdogScheduler.isTerminated()) {
            LogSaver.saveLog(TAG,"w","Restarting WatchDog");
            watchdogScheduler = Executors.newSingleThreadScheduledExecutor();
            isWatchdogRunning = false;
            startWatchdog(); // reschedule immediately
        }
    }

    private void restartPeriodicCheckIfShutdown() {
        if (periodicCheckScheduler.isShutdown()||periodicCheckScheduler==null||periodicCheckScheduler.isTerminated()) {
            LogSaver.saveLog(TAG,"w", "Restarting periodicCheckScheduler");
            periodicCheckScheduler = Executors.newSingleThreadScheduledExecutor();
            isPeriodicCheckRunning = false;
            startPeriodicCheck();
        }
    }

    private void restartDataStorageSchedulerIfShutdown() {
        if (dataStorageScheduler.isShutdown()||dataStorageScheduler==null||dataStorageScheduler.isTerminated()) {
            LogSaver.saveLog(TAG,"w", "Restarting dataStorageScheduler");
            dataStorageScheduler = Executors.newSingleThreadScheduledExecutor();
            isDataStorageManagerRunning = false;
            startDataStorageManager();
        }
    }

    private void restartNotificationSchedulerIfShutdown(){
        if (notificationScheduler.isShutdown()||notificationScheduler==null||notificationScheduler.isTerminated()) {
            LogSaver.saveLog(TAG,"w", "Restarting notificationScheduler");
            notificationScheduler = Executors.newSingleThreadScheduledExecutor();
            isNotificationManagerRunning = false;
            startNotificationManager();
        }
    }

    // Calculates a delay to the next minute, if we're in 08:31:12, it calculates the time to 08:32:00
    private long computeDelayToNextMinuteMillis() {
        Calendar nextMinute = Calendar.getInstance();
        nextMinute.add(Calendar.MINUTE, 2);
        nextMinute.set(Calendar.SECOND, 0);
        nextMinute.set(Calendar.MILLISECOND, 0);
        return nextMinute.getTimeInMillis() - System.currentTimeMillis();
    }

    public static String formatAlignedMinute(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.US);
        return sdf.format(new Date(timestamp));
    }

    // Gets the string of exactly 2 minutes before it is called, if its 08:31:01, it returns 08:29
    private long alignToMinuteTimestamp(long timeMillis) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timeMillis);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    // Releases the wake lock when service is stopped to avoid battery drain.
    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                LogSaver.saveLog(TAG,"d", "Wake lock released");
            }
        } catch (Exception e) {
            LogSaver.saveLog(TAG,"e", "Failed to release wake lock: "+e.getMessage());
        }
    }

    private void forceWatchWornOff() {
        boolean wasOn = prefsWatchWorn.getBoolean(PrefsKeys.Wear.WEAR_BOOL, false);
        if (wasOn) {
            DataStorageManager.saveWearStateToDatabase(false);
        }
        prefsWatchWorn.edit()
                .putBoolean(PrefsKeys.Wear.WEAR_BOOL, false)
                .putInt(PrefsKeys.Wear.WEAR_STATE, -1)
                .apply();
        LogSaver.saveLog("WatchWearDetector_KurtosisStudy", "d", "forceOff wrote OFF (wasOn="+wasOn+")");
    }

    @Override
    public void onDestroy() {
        LogSaver.saveLog(TAG, "d", "ForegroundSensorService being destroyed");

        // Remove persistent notification BEFORE cleanup
        stopForeground(STOP_FOREGROUND_REMOVE);

        // If destroyed/battery down, set wear state to 0 (not worn)
        forceWatchWornOff();

        // Stop heartbeat loop
        hbHandler.removeCallbacksAndMessages(null);
        hbHandler.removeCallbacks(hbTick);
        heartbeatStarted = false;
        LogSaver.saveLog(TAG, "d", "Heartbeat handler destroyed");

        // Release the wakelock to prevent resource leak
        releaseWakeLock();

        if (sensorHandler != null) {
            sensorHandler.shutdown();
            sensorHandler = null;
        }

        if (wearDetector != null) {
            wearDetector.stop();
            wearDetector = null;
        }

        if (watchdogScheduler != null && !watchdogScheduler.isShutdown()) {
            watchdogScheduler.shutdownNow(); // Cleanup
            LogSaver.saveLog(TAG, "w", "watchdogScheduler shut down");
        }
        isWatchdogRunning = false;

        if (periodicCheckScheduler != null && !periodicCheckScheduler.isShutdown()) {
            periodicCheckScheduler.shutdownNow();
            LogSaver.saveLog(TAG, "w", "PeriodicCheckScheduler shut down");
        }
        isPeriodicCheckRunning = false;

        if (dataStorageScheduler != null && !dataStorageScheduler.isShutdown()) {
            dataStorageScheduler.shutdownNow(); // Cleanup
            LogSaver.saveLog(TAG, "w", "dataStorageScheduler shut down");
        }
        isDataStorageManagerRunning = false;

        if (notificationScheduler != null && !notificationScheduler.isShutdown()) {
            notificationScheduler.shutdownNow(); // Cleanup
            LogSaver.saveLog(TAG, "w", "NotificationScheduler shut down");
        }
        isNotificationManagerRunning = false;

        super.onDestroy(); // always call this last
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        LogSaver.saveLog(TAG, "w", "onTaskRemoved() triggered — restarting service.");
        HeartbeatCheckWorker.enqueueNow(getApplicationContext()); // let Worker decide
        //restartService();
        super.onTaskRemoved(rootIntent);
    }

    /*private void restartService() {
        // Restarts the service if last heartbeat was long before RESTART_COOLDOWN_MS
        long now = System.currentTimeMillis();
        if (now - lastRestartAttemptElapsed < RESTART_COOLDOWN_MS) {
            LogSaver.saveLog(TAG, "w", "Restart skipped (cooldown)");
            return;
        }
        lastRestartAttemptElapsed = now;

        try {
            LogSaver.saveLog(TAG, "w", "Requesting ForegroundSensorService restart…");
            ContextCompat.startForegroundService(
                    getApplicationContext(),
                    new Intent(getApplicationContext(), ForegroundSensorService.class)
            );
        } catch (RuntimeException e) {
            // Android 12+ may block FGS launches from background
            LogSaver.saveLog(TAG, "w", "Immediate FGS start not allowed, using short alarm fallback: " + e);
            scheduleNearTermRestartFallback(getApplicationContext(), 5_000L); // 5s later
        }
    }

    // One-shot fallback that doesn't require prompting for exact-alarm permission
    private void scheduleNearTermRestartFallback(Context ctx, long delayMs) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pi = PendingIntent.getBroadcast(
                ctx,
                2002, // stable reqCode for de-dupe
                new Intent(ctx, RestartReceiver.class), // your heartbeat-aware receiver
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        long when = SystemClock.elapsedRealtime() + delayMs;

        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
                } else {
                    // Graceful fallback: inexact but soon
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
            }
        } catch (SecurityException se) {
            // OEM policy blocked exact: use non-exact fallback
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
        }
    }

     */

    // Service notification
    private Notification buildNotification() {
        // Adding
        Intent i = new Intent(this, MainActivity.class)
                .setAction("OPEN_FROM_FGS")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NEW_TASK); // Wear often expects this

        PendingIntent pi = PendingIntent.getActivity(
                this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);


        return new NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                .setContentTitle("App working")
                .setContentText("Service running")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true) // Persistent; user cannot swipe it away. Required for FGS
                //.setPriority(NotificationCompat.PRIORITY_HIGH) // don’t need setPriority(); on O+/Wear, channel importance wins.
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                //.setGroup("service_group")
                .setContentIntent(pi)   // ← makes the card tappable/“promotable”
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    // Service notification channel
    private void createNotificationChannel() {
        Log.d(TAG, "Creating notification channel");

        NotificationChannel channel = new NotificationChannel(
                NOTIF_CHANNEL_ID,
                NOTIF_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
        );
        // TODO - 2nd STUDY (SET IMPORTANCE TO LOW AS NO NOTIFICATIONS ARE DISPLAYED)

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
            Log.d(TAG, "Notification channel registered");
        } else {
            Log.d(TAG, "Notification channel null");
        }

    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
