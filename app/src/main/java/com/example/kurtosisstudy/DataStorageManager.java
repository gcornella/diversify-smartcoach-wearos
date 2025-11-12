package com.example.kurtosisstudy;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.example.kurtosisstudy.complications.MyComplicationProviderService;
import com.example.kurtosisstudy.complications.MyProgressComplicationProviderService;
import com.example.kurtosisstudy.complications.MyWearTimeComplicationProviderService;
import com.example.kurtosisstudy.db.AdjustedDailyGoalEntity;
import com.example.kurtosisstudy.db.DailyCumulativeEntity;
import com.example.kurtosisstudy.db.DailyDatabase;
import com.example.kurtosisstudy.db.DailyWearTimeDao;
import com.example.kurtosisstudy.db.DailyWearTimeEntity;
import com.example.kurtosisstudy.db.MainResultsDatabase;
import com.example.kurtosisstudy.db.MinuteAverageEntity;
import com.example.kurtosisstudy.db.NotificationEntity;
import com.example.kurtosisstudy.db.SensorSampleEntity;
import com.example.kurtosisstudy.db.StudyMetaDao;
import com.example.kurtosisstudy.db.StudyMetaEntity;
import com.example.kurtosisstudy.db.WearSessionEntity;
import com.example.kurtosisstudy.db.WeeklyAverageDao;
import com.example.kurtosisstudy.db.WeeklyAverageEntity;
import com.example.kurtosisstudy.db.WeeklyRatioDao;
import com.example.kurtosisstudy.db.WeeklyRatioEntity;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

import java.time.*;
import java.time.temporal.ChronoUnit;

public class DataStorageManager {
    // Define context and shared preferences
    private static Context appContext;
    private static SharedPreferences prefsDataStorage, prefsAdminSettings;

    // Tag used for logging/debugging
    private static final String TAG = "DataStorageManager_KurtosisStudy";

    // inject if this varies per user
    // My watch kurt is 10004,
    // my watch active was 66002, now good one 70005;
    // ryan 1001
    // brent 5002
    // brent new 1111 - 5002
    // bob 7005
    // yunp new 9001 -6002
    // RAMC 12345
    // HINW 4001
    // GALJ 4321

    // Select an id to differentiate between subjects
    private static int NEW_USER_ID = 1;

    // Define databases
    private static DailyDatabase db = null;
    private static MainResultsDatabase mainResultsDb;
    private static String currentMainDbName = null;   // track the open main DB file name

    // One executor for long-running tasks
    private static ExecutorService analyticsExecutor = Executors.newSingleThreadExecutor();
    // Use synchronized to prevent race conditions if multiple methods try to check/recreate the executor at the same time.
    private static final Object executorLock = new Object();


    // Class initialization
    public static synchronized void init(Context context) {
        Log.d(TAG, "Just entered init: " + db + ", and: " + mainResultsDb);

        // Store application-wide context to avoid memory leaks
        appContext = context.getApplicationContext();

        // Load current user id from AdminSettings
        prefsAdminSettings =  appContext.getSharedPreferences(PrefsKeys.Settings.SETTINGS_PREFS, Context.MODE_PRIVATE);
        int newUserId = prefsAdminSettings.getInt(PrefsKeys.Settings.USER_ID, 1);

        // Access shared preferences to persist the currently initialized DB name and date
        prefsDataStorage = appContext.getSharedPreferences(PrefsKeys.Data.PREFS, Context.MODE_PRIVATE);

        // Detect change of user
        int lastUserId = prefsDataStorage.getInt(PrefsKeys.Data.LAST_USER_ID, -1);
        boolean userChanged = (newUserId != lastUserId);

        // TODO JUST TEST
        //prefsDataStorage.edit().putInt(PrefsKeys.Data.WEEK_ID, 2).apply();

        // If user changed, reset per-user state (DBs + relevant prefs)
        if (userChanged) {
            Log.d(TAG, "User changed: " + lastUserId + " -> " + newUserId + " — resetting per-user state");

            // Close old DBs if open
            if (db != null && db.isOpen()) {
                db.close();
                db = null;
            }
            if (mainResultsDb != null) {
                mainResultsDb.close();
                mainResultsDb = null;
            }
            currentMainDbName = null;

            // Clear per-user prefs that must restart from week 1 and fresh day DB
            prefsDataStorage.edit()
                    .remove(PrefsKeys.Data.WEEK_ID)
                    .remove(PrefsKeys.Data.STUDY_START_TIME)
                    .remove(PrefsKeys.Data.CURRENT_DB_NAME)
                    .remove(PrefsKeys.Data.TODAY_DATE)
                    .putInt(PrefsKeys.Data.LAST_USER_ID, newUserId)
                    .apply();
        }

        // Keep the NEW_USER_ID cached
        NEW_USER_ID = newUserId;

        // Start the executor that will manage all long running operations outside the main ui thread
        ensureAnalyticsExecutorAlive();

        // (Re)build MainResults DB if needed OR if name changed
        String desiredMainName = "main_results_db_" + NEW_USER_ID;
        if (mainResultsDb == null || !desiredMainName.equals(currentMainDbName)) {
            if (mainResultsDb != null) {
                mainResultsDb.close();
            }
            mainResultsDb = Room.databaseBuilder(appContext, MainResultsDatabase.class, desiredMainName)
                    .build();
            currentMainDbName = desiredMainName;
            Log.d(TAG, "Initialized MainResults DB: " + desiredMainName);
        }

        // Generate today's database name (e.g., User01_2025_08_04)
        String todayDbName = "User"+NEW_USER_ID+"_" + getDayForDB();

        // Get the last initialized daily database name from persistent storage
        String savedDbName = prefsDataStorage.getString(PrefsKeys.Data.CURRENT_DB_NAME, "");

        // If the day has changed or DB name is different, close and create new DB
        if (!todayDbName.equals(savedDbName)) {
            if (db != null && db.isOpen()) {
                db.close(); // Cleanly close yesterday's DB
                Log.d(TAG, "Closed previous daily DB: " + savedDbName);
            }

            // Build a new DB instance for the new day
            db = Room.databaseBuilder(appContext, DailyDatabase.class, todayDbName)
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .build();

            // Update memory and persistent references
            prefsDataStorage.edit()
                    .putString(PrefsKeys.Data.CURRENT_DB_NAME, todayDbName)
                    .putString(PrefsKeys.Data.TODAY_DATE, getDayForDB())
                    .putInt(PrefsKeys.Data.WEEK_ID, getWeekFromPrefs())
                    .apply();

            Log.d(TAG, "New day, new daily DB: " + todayDbName);

            // Same day, but DB has not yet been initialized (e.g. app restart)
        } else if (db == null) {
            db = Room.databaseBuilder(appContext, DailyDatabase.class, todayDbName)
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .build();

            Log.d(TAG, "Re-attaching to the existing DB file for today: " + todayDbName);
        }

        // Initialize week id to 1 (if did not exist in shared prefs)
        if (!prefsDataStorage.contains(PrefsKeys.Data.WEEK_ID)) {
            prefsDataStorage.edit().putInt(PrefsKeys.Data.WEEK_ID, 1).apply();
        }

        // Set the week id based on the date
        scheduleWeekRefreshAsync();

    }

    // Restart the analytics executor if shutdown.
    private static void ensureAnalyticsExecutorAlive() {
        synchronized (executorLock) {
            if (analyticsExecutor == null || analyticsExecutor.isShutdown() || analyticsExecutor.isTerminated()) {
                LogSaver.saveLog(TAG,"w", "ensureAnalyticsExecutorAlive - AnalyticsExecutor was shutdown — restarting.");
                analyticsExecutor = Executors.newSingleThreadExecutor();
            }
        }
    }

    public static MainResultsDatabase getMainDatabase() {
        if (mainResultsDb == null){
            LogSaver.saveLog(TAG,"e", "Trying to return mainResultsDb but its null");
        }
        return mainResultsDb;
    }

    public static DailyDatabase getDailyDatabase() {
        if (db == null){
            LogSaver.saveLog(TAG,"e", "Trying to return DailyDatabase but its null");
        }
        return db;
    }

    // Check if a new day has come. This is called inside FGS-periodicCheckScheduler() every minute
    public static boolean shouldReinitializeDailyDb() {
        if (prefsDataStorage == null) return true;
        String lastSavedDate = prefsDataStorage.getString(PrefsKeys.Data.TODAY_DATE, "");
        return !getDayForDB().equals(lastSavedDate);
    }

    // Week computation (DB + prefs)
    // Kicks an async DB read to compute week ID and persist a value to prefs. */
    private static void scheduleWeekRefreshAsync() {
        ensureAnalyticsExecutorAlive();
        try {
            analyticsExecutor.execute(() -> {
                int dbWeek = computeWeekFromDb();       // get from db based on "start study timestamp"
                int current = getWeekFromPrefs();       // get from sharedprefs storage
                int chosen = Math.max(current, dbWeek); // never go backwards, choose the largest id

                // Today's a new week:
                if (chosen != current) {
                    prefsDataStorage.edit().putInt(PrefsKeys.Data.WEEK_ID, chosen).apply();
                    // Notify the complications rendering based on week
                    MyComplicationProviderService.requestComplicationUpdate(appContext);
                    MyProgressComplicationProviderService.requestComplicationUpdate(appContext);
                }
                LogSaver.saveLog(TAG, "i",
                        "Week refresh -> prefs=" + current + " db=" + dbWeek + " chosen=" + chosen);
            });
        } catch (RejectedExecutionException rex) {
            LogSaver.saveLog(TAG, "e", "Executor rejected week refresh: " + rex.getMessage());
        }
    }

    // Computes week from SharedPreferences; if missing/unavailable, sets to week 1.
    public static int getWeekFromPrefs() {
        return (prefsDataStorage != null) ? prefsDataStorage.getInt(PrefsKeys.Data.WEEK_ID, 1) : 1;
    }

    // Computes week from Room database: if missing/unavailable, uses prefs. Call off main thread.
    private static int computeWeekFromDb() {
        try {
            if (mainResultsDb == null) return getWeekFromPrefs();
            // Get metadata db
            StudyMetaEntity meta = mainResultsDb.studyMetaDao().getMeta();
            if (meta == null) {
                LogSaver.saveLog(TAG, "w", "StudyMeta missing; defaulting to week 1");
                return 1;
            }

            // Choose your study timezone. If you know it, hardcode it, e.g. "America/Los_Angeles".
            ZoneId zone = ZoneId.systemDefault(); // or ZoneId.of("America/Los_Angeles")

            // Get start date based on startOfStudyTimestamp
            LocalDate startDate = Instant.ofEpochMilli(meta.startOfStudyTimestamp)
                    .atZone(zone).toLocalDate();
            // Get today's date based on now (System.currentTimeMillis())
            LocalDate todayDate = Instant.ofEpochMilli(System.currentTimeMillis())
                    .atZone(zone).toLocalDate();

            // Days elapsed from first day of study
            long days = ChronoUnit.DAYS.between(startDate, todayDate);  // 0 for day1, 7 for day8, etc.
            int weekNumber = (int)(days / 7) + 1;                       // day1..day7 -> week1, day8..day14 -> week2
            weekNumber = Math.max(1, Math.min(12, weekNumber));

            // (Optional) mirror study start into prefs once, for faster cold starts later
            if (!prefsDataStorage.contains(PrefsKeys.Data.STUDY_START_TIME)) {
                prefsDataStorage.edit().putLong(PrefsKeys.Data.STUDY_START_TIME, meta.startOfStudyTimestamp).apply();
            }
            return weekNumber;
        } catch (Exception e) {
            LogSaver.saveLog(TAG, "e", "computeWeekFromDbOrPrefs error: " + e.getMessage());
            return getWeekFromPrefs();
        }
    }

    // Get today's date in a specific format
    public static String getDayForDB() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy_MM_dd", Locale.US);
        return sdf.format(new Date());
    }

    // Set or save the timestamp from the start of the study, if not already on the db
    public static void startStudyTimestamp() {
        ensureAnalyticsExecutorAlive();
        Runnable task = () -> {
            try {
                StudyMetaDao metaDao = mainResultsDb.studyMetaDao();
                StudyMetaEntity existing = metaDao.getMeta();

                long startMs;
                String startDateStr;
                // If no value existed, save it to the studyMeta db, otherwise just read the value from the db
                if (existing == null) {
                    startMs = System.currentTimeMillis();
                    startDateStr = getDayForDB();
                    StudyMetaEntity meta = new StudyMetaEntity(startMs, startDateStr, false);
                    metaDao.insert(meta);
                    LogSaver.saveLog(TAG, "d", "Study metadata initialized at: " + startDateStr);
                } else {
                    startMs = existing.startOfStudyTimestamp;
                    startDateStr = existing.startOfStudyDate;
                    LogSaver.saveLog(TAG, "d", "Study metadata already exists: " + startDateStr);
                }

                // Mirror start timestamp to prefs so cold starts can compute week quickly
                prefsDataStorage.edit().putLong(PrefsKeys.Data.STUDY_START_TIME, startMs).apply();

                // Refresh the cached week again
                int current = getWeekFromPrefs();
                int dbWeek  = computeWeekFromDb();
                int chosen  = Math.max(current, dbWeek);
                if (chosen != current) {
                    prefsDataStorage.edit().putInt(PrefsKeys.Data.WEEK_ID, chosen).apply();
                    MyComplicationProviderService.requestComplicationUpdate(appContext);
                    MyProgressComplicationProviderService.requestComplicationUpdate(appContext);
                }
            } catch (Exception e) {
                LogSaver.saveLog(TAG, "e", "Failed to start study timestamp " + e.getMessage());
            }
        };

        try {
            analyticsExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            LogSaver.saveLog(TAG, "e", "Executor was shut down — recreating: " + e.getMessage());
            synchronized (executorLock) {
                analyticsExecutor = Executors.newSingleThreadExecutor();
            }
            ensureAnalyticsExecutorAlive();
            analyticsExecutor.execute(task);
        }
    }
    
    // Whenever FGS is called, get movement progress from cumulative db and
    // set the last known progress to shared prefs
    public static void initializeLastKnownProgress() {
        ensureAnalyticsExecutorAlive();

        Runnable task = () -> {
            final int DEFAULT_PROGRESS_MIN = 0;
            try {
                if (mainResultsDb == null) {
                    LogSaver.saveLog(TAG, "w", "DB is null; defaulting progress to " + DEFAULT_PROGRESS_MIN);
                    prefsDataStorage.edit().putInt(PrefsKeys.Data.LAST_KNOWN_PROGRESS, DEFAULT_PROGRESS_MIN).apply();
                    return;
                }
                String today = getDayForDB();
                DailyCumulativeEntity last = mainResultsDb.dailyCumulativeDao().getLastEntryForDay(today);
                int lastProgress = (last != null) ? last.cumulative : DEFAULT_PROGRESS_MIN;
                prefsDataStorage.edit().putInt(PrefsKeys.Data.LAST_KNOWN_PROGRESS, lastProgress).apply();
                
                if (last != null) {
                    LogSaver.saveLog(TAG, "d", "initializeLastKnownProgress (minutes) from DB: " + lastProgress);
                } else {
                    LogSaver.saveLog(TAG, "d", "initializeLastKnownProgress No progress found. Defaulting progress (minutes) to " + lastProgress);
                }
            } catch (Exception e) {
                prefsDataStorage.edit().putInt(PrefsKeys.Data.LAST_KNOWN_PROGRESS, DEFAULT_PROGRESS_MIN).apply();
                LogSaver.saveLog(TAG, "e", "Failed to load lastKnownProgress: " + e.getMessage() + ". Defaulted to " + DEFAULT_PROGRESS_MIN);
            }
        };

        try {
            analyticsExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            LogSaver.saveLog(TAG,"e", "Executor was shut down unexpectedly — recreating and retrying: "+ e.getMessage());
            synchronized (executorLock) {
                analyticsExecutor = Executors.newSingleThreadExecutor();
            }
            // Optional, defensively ensure it's alive before retrying (not strictly needed here)
            ensureAnalyticsExecutorAlive();
            analyticsExecutor.execute(task);
        }
    }

    // Whenever FGS is called, get movement adjusted goal from adjustedDailyGoalDao db and
    // set the last goal to shared prefs (in minutes)
    public static void initializeLastKnownGoal() {
        ensureAnalyticsExecutorAlive();

        Runnable task = () -> {
            final int DEFAULT_GOAL_MIN = 480;
            try {
                if (db == null) {
                    LogSaver.saveLog(TAG, "w", "DB is null; defaulting progress to " + DEFAULT_GOAL_MIN);
                    prefsDataStorage.edit().putInt(PrefsKeys.Data.LAST_KNOWN_GOAL, DEFAULT_GOAL_MIN).apply();
                    return;
                }
                AdjustedDailyGoalEntity last = db.adjustedDailyGoalDao().getLastGoal();
                int lastAdjustedDailyGoal = (last != null && last.adjustedDailyGoal != 0) ? last.adjustedDailyGoal : DEFAULT_GOAL_MIN;
                prefsDataStorage.edit().putInt(PrefsKeys.Data.LAST_KNOWN_GOAL, lastAdjustedDailyGoal).apply();

                if (last != null) {
                    LogSaver.saveLog(TAG, "d", "initializeLastKnownGoal (minutes) from DB: " + lastAdjustedDailyGoal);
                } else {
                    LogSaver.saveLog(TAG, "d", "No goal found. Defaulting lastKnownGoal (minutes) to " + lastAdjustedDailyGoal);
                }
            } catch (Exception e) {
                prefsDataStorage.edit().putInt(PrefsKeys.Data.LAST_KNOWN_GOAL, DEFAULT_GOAL_MIN).apply();
                LogSaver.saveLog(TAG, "e", "Failed to load lastKnownGoal (minutes): " + e.getMessage()
                        + ". Defaulted to " + DEFAULT_GOAL_MIN);
            }
        };
        try {
            analyticsExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            LogSaver.saveLog(TAG,"e", "Executor was shut down unexpectedly — recreating and retrying: "+ e.getMessage());
            synchronized (executorLock) {
                analyticsExecutor = Executors.newSingleThreadExecutor();
            }
            // Optional, defensively ensure it's alive before retrying (not strictly needed here)
            ensureAnalyticsExecutorAlive();
            analyticsExecutor.execute(task);
        }
    }

    // Get the timestamp of the last time the watch was removed from the wrist
    public static long getLastNotWornTimestampToday() {
        ensureAnalyticsExecutorAlive();

        Callable<Long> task = () -> {
            try {
                String today = getDayForDB();  // e.g. "2025-08-01"
                Long ts = mainResultsDb.wearSessionDao().getLastNotWornTimestampForDate(today);
                LogSaver.saveLog(TAG, "d", "getLastNotWornTimestampToday: " + ts);
                return (ts != null) ? ts : -1L;
            } catch (Exception e) {
                LogSaver.saveLog(TAG, "e", "Failed to get last not-worn timestamp: " + e.getMessage());
                return -1L;
            }
        };

        try {
            Future<Long> future = analyticsExecutor.submit(task);
            return future.get(); // Synchronously waits for the result
        } catch (Exception e) {
            LogSaver.saveLog(TAG, "e", "Executor submission failed: " + e.getMessage());
            return -1L;
        }
    }

    // Save 0 when the watch was removed, and 1 when put on. Also save timestamps, and day.
    public static void saveWearStateToDatabase(boolean isWorn){
        ensureAnalyticsExecutorAlive();

        Runnable task = () -> {
            try{
                WearSessionEntity wearSession = new WearSessionEntity(System.currentTimeMillis(), getDayForDB(), isWorn);
                mainResultsDb.wearSessionDao().insert(wearSession);
                LogSaver.saveLog(TAG,"d", "saveWearStateToDatabase()");
            }catch (Exception e) {
                LogSaver.saveLog(TAG,"e", "Failed to insert wear state entity: "+ e.getMessage());
            }
        };

        try {
            analyticsExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            LogSaver.saveLog(TAG,"e", "Executor was shut down unexpectedly — recreating and retrying: "+ e.getMessage());
            synchronized (executorLock) {
                analyticsExecutor = Executors.newSingleThreadExecutor();
            }
            // Optional, defensively ensure it's alive before retrying (not strictly needed here)
            ensureAnalyticsExecutorAlive();
            analyticsExecutor.execute(task);
        }
    }

    // DataStorageManager.java
    public static void saveWearStateToDatabaseAt(boolean isWorn, long whenMillis) {
        ensureAnalyticsExecutorAlive();

        final long ts = whenMillis;
        final String day = getDayForDB(); // overload that accepts a timestamp

        Runnable task = () -> {
            try {
                WearSessionEntity wearSession = new WearSessionEntity(ts, day, isWorn);
                mainResultsDb.wearSessionDao().insert(wearSession);
                LogSaver.saveLog(TAG, "d", "saveWearStateToDatabaseAt(ts=" + ts + ", worn=" + isWorn + ")");
            } catch (Exception e) {
                LogSaver.saveLog(TAG, "e", "Failed to insert wear state entity @ts: " + e.getMessage());
            }
        };

        try {
            analyticsExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            LogSaver.saveLog(TAG, "e", "Executor was shut down — recreating: " + e.getMessage());
            synchronized (executorLock) {
                analyticsExecutor = Executors.newSingleThreadExecutor();
            }
            ensureAnalyticsExecutorAlive();
            analyticsExecutor.execute(task);
        }
    }



    // Save the content and time when the notifications are displayed
    public static void saveNotificationTimestamp(int level, String message){
        ensureAnalyticsExecutorAlive();

        Runnable task = () -> {
            try{
                // Create a new entry with the current timestamp
                NotificationEntity notification = new NotificationEntity(System.currentTimeMillis(), level, message);
                mainResultsDb.notificationDao().insert(notification);
                LogSaver.saveLog(TAG,"d", "saveNotificationTimestamp()");
            }catch (Exception e) {
                LogSaver.saveLog(TAG,"e", "Failed to insert notification entry: "+ e.getMessage());
            }
        };

        try {
            analyticsExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            LogSaver.saveLog(TAG,"e", "Executor was shut down unexpectedly — recreating and retrying: "+ e.getMessage());
            synchronized (executorLock) {
                analyticsExecutor = Executors.newSingleThreadExecutor();
            }

            // Optional, defensively ensure it's alive before retrying (not strictly needed here)
            ensureAnalyticsExecutorAlive();
            analyticsExecutor.execute(task);
        }
    }

    // Get the cumulative progress from a window of 30 minutes before calling this method.
    // If in the last 30 minutes the user has moved less than 6 minutes, flag window as INACTIVE
    public static boolean getActivityDuringPrev30Minutes() {
        ensureAnalyticsExecutorAlive();

        Callable<Boolean> task = () -> {
            try {
                long timestamp30MinutesAgo = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(30);
                List<Integer> averages = db.minuteAverageDao().getMinuteAveragesSince(timestamp30MinutesAgo);

                int seconds = 0;
                for (int value : averages) {
                    seconds += value;
                }
                LogSaver.saveLog(TAG,"d", "Was active during the previous 30'?: "+ (seconds >= 360));
                return seconds >= 360; // TODO: true => if they have been active at least 6 minutes during the previous 30' (20%) , false = inactive
            } catch (Exception e) {
                LogSaver.saveLog(TAG, "e", "Failed to get 30-minute average: " + e.getMessage());
                return false;
            }
        };

        try {
            Future<Boolean> future = analyticsExecutor.submit(task);
            return future.get(); // Wait synchronously for result
        } catch (Exception e) {
            LogSaver.saveLog(TAG, "e", "Executor submission failed: " + e.getMessage());
            return false;
        }
    }

    // Get the movement progress value at exactly 30' before this method was called.
    public static int getCumulative30MinsBefore() {
        ensureAnalyticsExecutorAlive();

        Callable<Integer> task = () -> {
            try {
                long thirtyMinutesAgo = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(30);
                Integer cumulativeThen = mainResultsDb.dailyCumulativeDao().getClosestEntryBefore(thirtyMinutesAgo);
                if (cumulativeThen != null) {
                    Log.d(TAG, "Cumulative 30' ago was exactly: " + cumulativeThen);
                    return cumulativeThen;
                } else {
                    Log.w(TAG, "No entry found before 30 minutes ago.");
                    return 0;
                }
            } catch (Exception e) {
                LogSaver.saveLog(TAG, "e", "Failed to get cumulative: " + e.getMessage());
                return 0;
            }
        };

        try {
            Future<Integer> future = analyticsExecutor.submit(task);
            return future.get(); // Waits for result
        } catch (Exception e) {
            LogSaver.saveLog(TAG, "e", "Executor failed: " + e.getMessage());
            return 0;
        }
    }

    // Save the raw data optimally
    public static void snapshotAndSaveBuffers(
            long[] timestamps,
            float[] xBuffer,
            float[] yBuffer,
            float[] zBuffer,
            float[] angleBuffer,
            float[] inclinationBuffer,
            float[] stdBuffer,
            float[] rawKurtosisBuffer,
            float[] rawGMACBuffer,
            int[] kurtosisBuffer,
            int[] activityBuffer,
            int size){

        ensureAnalyticsExecutorAlive();
        Runnable task = () -> {
            try {
                List<SensorSampleEntity> entries = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    entries.add(new SensorSampleEntity(
                            timestamps[i],
                            xBuffer[i],
                            yBuffer[i],
                            zBuffer[i],
                            angleBuffer[i],
                            inclinationBuffer[i],
                            stdBuffer[i],
                            rawKurtosisBuffer[i],
                            rawGMACBuffer[i],
                            kurtosisBuffer[i],
                            activityBuffer[i]
                    ));
                }
                db.sensorSampleDao().insertAll(entries);
                LogSaver.saveLog(TAG,"w", "DAO SAVED " + entries.size() + " samples");
            } catch (Exception e) {
                LogSaver.saveLog(TAG,"e", "Failed to save buffer "+ e.getMessage());
            }
        };

        try {
            analyticsExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            LogSaver.saveLog(TAG,"e", "Executor was shut down unexpectedly — recreating and retrying: "+ e.getMessage());
            synchronized (executorLock) {
                analyticsExecutor = Executors.newSingleThreadExecutor();
            }

            // Optional, defensively ensure it's alive before retrying (not strictly needed here)
            ensureAnalyticsExecutorAlive();
            analyticsExecutor.execute(task);
        }
    }

    // Computes active and diverse seconds for a given minute, e.g. 08:01 -> 49 seconds,
    // taking the 50Hz samples and converting to seconds, then saving to MinuteAverage.
    public static void computeAndSaveMinuteAverage(long start, long end, String alignedMinute) {
        ensureAnalyticsExecutorAlive();

        Runnable task = () -> {
            try{
                List<SensorSampleEntity> kurtosisOrGMACValues = db.sensorSampleDao().getkurtosisOrGMACValuesInRange(start, end);
                // LogSaver.saveLog(TAG,"w","Getting kurtosisOrGMACValues from  " + start + " to " + end +" with diff: "+ (end-start)+" and size: "+kurtosisOrGMACValues.size());
                if (kurtosisOrGMACValues == null || kurtosisOrGMACValues.isEmpty()) {
                    LogSaver.saveLog(TAG,"w", "No kurtosisOrGMACValues values found between " + start + " and " + end);
                    return;
                }
                float averages = 0f;
                float secondarys = 0f;
                for (SensorSampleEntity e : kurtosisOrGMACValues) {
                    averages += e.activity; // TODO
                    secondarys += e.kurtosis;
                }
                int averageSeconds = (int) averages / 50; // I want to know how many seconds I have with kurtosisOrGMACValues of 1 --> (kurtosisOrGMACValues.size()
                int secondarySeconds = (int) secondarys / 50;

                // Save that minute average (in average seconds)
                MinuteAverageEntity entry = new MinuteAverageEntity(alignedMinute, System.currentTimeMillis(), averageSeconds, secondarySeconds);
                db.minuteAverageDao().insertOrUpdate(entry);

            }catch (Exception e) {
                LogSaver.saveLog(TAG,"e", "Failed to computeAndSaveMinuteAverage"+ e.getMessage());
            }
        };

        try {
            analyticsExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            LogSaver.saveLog(TAG,"e", "Executor was shut down unexpectedly — recreating and retrying: "+ e.getMessage());
            synchronized (executorLock) {
                analyticsExecutor = Executors.newSingleThreadExecutor();
            }

            // Optional, defensively ensure it's alive before retrying (not strictly needed here)
            ensureAnalyticsExecutorAlive();
            analyticsExecutor.execute(task);
        }
    }

    // Sums all minutes for today, updates lastKnownProgress,
    // refreshes MyProgressComplicationProviderService, and saves to DailyCumulative.
    public static void computeAndSaveDailyCumulative() {
        ensureAnalyticsExecutorAlive();

        Runnable task = () -> {
            try{
                int week = getWeekFromPrefs();
                String day = getDayForDB();

                List<MinuteAverageEntity> entries = db.minuteAverageDao().getAllMinuteAverages();

                if (entries == null || entries.isEmpty()) {
                    LogSaver.saveLog(TAG,"w", "No averages found for today: " + day );
                    return;
                }

                int dailyCumulative = 0;
                int dailySecondaryCumulative = 0;
                for (MinuteAverageEntity e : entries) {
                    dailyCumulative += e.average;
                    dailySecondaryCumulative += e.secondary;
                }

                int cumulativeMinutes = dailyCumulative / 60; // I want to know how many minutes I have with kurtosisOrGMACValues of 1 --> (kurtosisOrGMACValues.size()
                int cumulativeSecondaryMinutes = dailySecondaryCumulative / 60;

                LogSaver.saveLog(TAG,"d", "cumulativeMinutes: " +  cumulativeMinutes);
                prefsDataStorage.edit().putInt(PrefsKeys.Data.LAST_KNOWN_PROGRESS, cumulativeMinutes).apply();

                // Save daily cumulative
                DailyCumulativeEntity dailyResult = new DailyCumulativeEntity(day, System.currentTimeMillis(), week, cumulativeMinutes, cumulativeSecondaryMinutes);
                mainResultsDb.dailyCumulativeDao().insert(dailyResult);

                // 🔔 Push an immediate complication refresh
                MyProgressComplicationProviderService.requestComplicationUpdate(appContext);
                Log.w("DebuggingKurto", "cumulativeMinutes: "+ cumulativeMinutes);

                LogSaver.saveLog(TAG,"d", "computeAndSaveDailyCumulative() finished");

            }catch (Exception e) {
                LogSaver.saveLog(TAG,"e", "Failed to computeAndSaveDailyCumulative"+ e.getMessage());
            }
        };
        try {
            analyticsExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            LogSaver.saveLog(TAG,"e", "Executor was shut down unexpectedly — recreating and retrying: "+ e.getMessage());
            synchronized (executorLock) {
                analyticsExecutor = Executors.newSingleThreadExecutor();
            }
            ensureAnalyticsExecutorAlive();
            analyticsExecutor.execute(task);
        }
    }

    // Uses worn/not-worn session marks from WearSession within 08:00–22:00,
    // refreshes MyWearTimeComplicationProviderService, and updates DailyWearTime.
    public static void computeAndSaveDailyWearTime() {
        ensureAnalyticsExecutorAlive();

        Runnable task = () -> {
            try {
                String day = getDayForDB();
                int week = getWeekFromPrefs();
                List<WearSessionEntity> sessions = mainResultsDb.wearSessionDao().getSessionsForDate(day);

                int wornMinutes;
                int notWornMinutes;

                if (sessions == null || sessions.isEmpty()) {     // covers @Nullable cases too
                    wornMinutes    = 0;
                    notWornMinutes = 14 * 60;  // 840
                }else{
                    long windowStart = getTodayAtHour(8);   // 08:00
                    long windowEnd = getTodayAtHour(22);  // 22:00
                    long now = System.currentTimeMillis();
                    long tailEnd = Math.min(now, windowEnd);

                    long wornMillis = 0;
                    long notWornMillis = 0;

                    boolean currentState = false;            // Start day “not worn”
                    long previousTs = windowStart;

                    // Walk through events that fall inside the 8-22 window
                    for (WearSessionEntity session : sessions) {
                        if (session.timestamp < windowStart) {
                            // Remember the most recent state before 08:00
                            currentState = session.isWorn;
                            continue;                   // still skip counting time before 08:00
                        }
                        if (session.timestamp > windowEnd)   break;      // we’re done

                        long delta = session.timestamp - previousTs;
                        if (currentState) wornMillis += delta;
                        else notWornMillis += delta;
                        currentState = session.isWorn;   // flip state
                        previousTs   = session.timestamp;

                    }

                    // Tail from last event to 22:00
                    // Clamp tail end to [now, 22:00] so we never count past 22:00
                    if (previousTs < tailEnd) {
                        long delta = tailEnd - previousTs;
                        if (currentState) wornMillis += delta;
                        else              notWornMillis += delta;
                    }

                    wornMinutes = (int) (wornMillis / 60_000L);
                    notWornMinutes = (int) (notWornMillis / 60_000L);
                }

                DailyWearTimeEntity existing = mainResultsDb.dailyWearTimeDao().getLastEntryForDay(day);
                // Just update if wornMinutes or notWornMinutes value hasn’t changed since last write
                if (existing == null || existing.wornMinutes != wornMinutes || existing.notWornMinutes != notWornMinutes) {
                    DailyWearTimeEntity entry = new DailyWearTimeEntity(System.currentTimeMillis(), week, day, wornMinutes, notWornMinutes);
                    mainResultsDb.dailyWearTimeDao().insertOrUpdate(entry);

                    // Push an immediate complication refresh
                    MyWearTimeComplicationProviderService.requestComplicationUpdate(appContext);
                }
                LogSaver.saveLog(TAG, "d", "Updated daily wear time: " + wornMinutes + " min for " + day);

            } catch (Exception e) {
                LogSaver.saveLog(TAG, "e", "❌ Failed to computeAndSaveDailyWearTime: " + e.getMessage());
            }
        };

        try {
            analyticsExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            LogSaver.saveLog(TAG, "e", "Executor shut down unexpectedly. Recreating: " + e.getMessage());
            synchronized (executorLock) {
                analyticsExecutor = Executors.newSingleThreadExecutor();
            }
            ensureAnalyticsExecutorAlive();
            analyticsExecutor.execute(task);
        }
    }

    // Get the timestamp from an exact hour given today's date.
    private static long getTodayAtHour(int hour) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    // Calculates the ratio between active or diverse time with respect to wear time for all that week, and saves to WeeklyAverage.
    public static void  computeAndSaveWeeklyAverage() {
        ensureAnalyticsExecutorAlive();
        Runnable task = () -> {
            try{
                int week = getWeekFromPrefs();
                // Fallback default in case of no valid data // 20% of 14hours of activity
                float weeklyAverage = 0.2f;

                // Fetch both movement and wear entries for the given week
                List<DailyWearTimeEntity> wearEntries = mainResultsDb.dailyWearTimeDao().getEntriesForWeek(week);

                // Validate presence of data
                boolean missingData = false;
                if (wearEntries == null || wearEntries.isEmpty()) {
                    LogSaver.saveLog(TAG, "w", "No wear time entries found for week " + week);
                    missingData = true;
                }

                if (!missingData) {
                    // Weighted average calculation (days with less worn time have less importance)
                    float totalMovement = 0f;
                    float totalWearMinutes = 0f;
                    int validDays = 0;
                    for (DailyWearTimeEntity wear : wearEntries) {
                        // Need a minimum of 8hours of worn time per day
                        if (wear.wornMinutes <= 0) continue;
                        if (wear.wornMinutes < 480) {
                            LogSaver.saveLog(TAG, "i",
                                    "Skipping " + wear.day + " (wear=" + wear.wornMinutes + "m < " + 480 + "m)");
                            continue;
                        }

                        DailyCumulativeEntity lastMovement =
                                mainResultsDb.dailyCumulativeDao().getLastEntryForDay(wear.day);

                        if (lastMovement != null) {
                            totalMovement    += lastMovement.cumulative;
                            totalWearMinutes += wear.wornMinutes;
                            validDays++;
                        }
                    }
                    LogSaver.saveLog(TAG, "d", "Weekly total movement: " + totalMovement + "; totalWearMinutes: " + totalWearMinutes);

                    // Final weighted average (only if we have valid data)
                    if (totalWearMinutes > 0f) {
                        weeklyAverage = totalMovement / totalWearMinutes;
                        LogSaver.saveLog(TAG, "i", "Week " + week + ": Weighted avg = " +
                                weeklyAverage + " counts/min from " + validDays + " valid days");
                    }
                    else {
                        LogSaver.saveLog(TAG, "w", "No valid wear data this week, using fallback = " + weeklyAverage);
                    }
                    if (Float.isNaN(weeklyAverage) || Float.isInfinite(weeklyAverage)) {
                        weeklyAverage = 0.2f;
                    }
                } else {
                    LogSaver.saveLog(TAG, "w", "Missing data for week " + week + ", using fallback = " + weeklyAverage);
                }

                // Save result to DB
                WeeklyAverageEntity result = new WeeklyAverageEntity(System.currentTimeMillis(), week, weeklyAverage);
                mainResultsDb.weeklyAverageDao().insertOrUpdate(result);
                LogSaver.saveLog(TAG, "d", "Saved weekly avg: week=" + week + ", avg=" + weeklyAverage);

            }catch (Exception e) {
                LogSaver.saveLog(TAG,"e", "Failed to computeAndSaveWeeklyAverage"+ e.getMessage());
            }
        };
        try {
            analyticsExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            LogSaver.saveLog(TAG,"e", "Executor was shut down unexpectedly — recreating and retrying: "+ e.getMessage());
            synchronized (executorLock) {
                analyticsExecutor = Executors.newSingleThreadExecutor();
            }
            ensureAnalyticsExecutorAlive();
            analyticsExecutor.execute(task);
        }
    }

    // When a new week is detected, generates a goal ratio for that incoming week, and saves to WeeklyRatio.
    public static void createAndSaveWeeklyRatios() {
        ensureAnalyticsExecutorAlive();
        Runnable task = () -> {
            try{
                WeeklyAverageDao resultDao = mainResultsDb.weeklyAverageDao();
                WeeklyRatioDao ratioDao = mainResultsDb.weeklyRatioDao();

                // Get all weekly movement averages
                List<WeeklyAverageEntity> weeklyResults = resultDao.getAllSortedByWeek();
                if (weeklyResults == null || weeklyResults.isEmpty()) {
                    LogSaver.saveLog(TAG,"w", "No weekly results available to generate goals.");
                    return;
                }

                // Map results by week number
                Map<Integer, WeeklyAverageEntity> resultMap = new HashMap<>();
                for (WeeklyAverageEntity result : weeklyResults) {
                    resultMap.put(result.weekNumber, result);
                }

                // Sort week numbers
                List<Integer> sortedWeeks = new ArrayList<>(resultMap.keySet());
                Collections.sort(sortedWeeks);

                for (int weekId : sortedWeeks) {
                    /*if (weekId > 5) {
                        LogSaver.saveLog(TAG, "i", "Week " + weekId + " has no ratio (post-intervention).");
                        continue; // No ratios after week 5
                    }*/

                    WeeklyRatioEntity existingRatio = ratioDao.getRatioByWeek(weekId);
                    float ratioValue;

                    if (existingRatio != null) {
                        // Goal ratio already set, no need to compute
                        ratioValue = existingRatio.ratioValue;
                        Log.d(TAG, "Ratio already exists for week " + weekId + ": " + ratioValue);
                    } else {
                        if (weekId == 1) {
                            ratioValue = 0f; // Baseline week
                        } else if (weekId == 2) {
                            WeeklyAverageEntity week1 = resultMap.get(1);
                            ratioValue = (week1 != null) ? week1.goalAchieved * 1.25f : 0.2f;
                        } else {
                            WeeklyAverageEntity prevWeekAverage = resultMap.get(weekId - 1);
                            WeeklyRatioEntity prevRatio = ratioDao.getRatioByWeek(weekId - 1);

                            if (prevWeekAverage != null && prevRatio != null) {
                                if (prevWeekAverage.goalAchieved >= prevRatio.ratioValue) {
                                    ratioValue = prevRatio.ratioValue * 1.10f; // Success → increase
                                } else {
                                    ratioValue = prevRatio.ratioValue * 0.95f; // Failure → decrease
                                }
                            } else {
                                ratioValue = 0.2f;
                                LogSaver.saveLog(TAG, "w", "Missing previous data for week " + weekId);
                            }
                        }

                        // Save new goal to DB
                        WeeklyRatioEntity goalEntry = new WeeklyRatioEntity(System.currentTimeMillis(), weekId, ratioValue);
                        ratioDao.insertOrUpdate(goalEntry);
                        LogSaver.saveLog(TAG, "d", "Generated goal for week " + weekId + ": " + ratioValue);
                    }
                    prefsDataStorage.edit().putFloat(PrefsKeys.Data.LAST_KNOWN_RATIO, ratioValue).apply();
                }
            }catch (Exception e) {
                LogSaver.saveLog(TAG,"e", "Failed to computeAndSaveWeeklyAverage"+ e.getMessage());
            }
        };

        try {
            analyticsExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            LogSaver.saveLog(TAG,"e", "Executor was shut down unexpectedly — recreating and retrying: "+ e.getMessage());
            synchronized (executorLock) {
                analyticsExecutor = Executors.newSingleThreadExecutor();
            }
            ensureAnalyticsExecutorAlive();
            analyticsExecutor.execute(task);
        }
    }

    // Projects today’s goal from weekly ratio and effective wear time (≥8h floor),
    // updates lastKnownGoal + MyProgressComplicationProviderService, and saves to AdjustedDailyGoal.
    public static void computeAndSaveAdjustedDailyGoal() {
        ensureAnalyticsExecutorAlive();
        Runnable task = () -> {
            try{
                final int DAY_WINDOW_MIN = 14 * 60;   // 8:00–22:00 = 840
                final int MIN_WEAR_FLOOR = 8 * 60;    // 8 hours = 480

                String day = getDayForDB();
                int week = getWeekFromPrefs();

                DailyWearTimeDao dailyWearTimeDao = mainResultsDb.dailyWearTimeDao();
                WeeklyRatioDao weeklyRatioDao = mainResultsDb.weeklyRatioDao();

                // Get latest not-worn minutes for today (0..840). If none yet, assume 0 at 8:00.
                DailyWearTimeEntity wearEntry = dailyWearTimeDao.getLastEntryForDay(day);
                int notWornMinutes = (wearEntry != null) ? wearEntry.notWornMinutes : 0;
                notWornMinutes = Math.max(0, Math.min(DAY_WINDOW_MIN, notWornMinutes));

                // Available wear time so far today (bounded to 0..840).
                int availableWearMinutes = DAY_WINDOW_MIN - notWornMinutes;

                // Enforce a minimum wear-time floor of 8h for goal projection.
                // If user has less than 8h available (because of off-body time), we still project as if 8h.
                int effectiveWearMinutes = Math.max(availableWearMinutes, MIN_WEAR_FLOOR);

                // Weekly movement-per-wear ratio (e.g., 100 movement mins / 300 wear mins = 0.3333f).
                WeeklyRatioEntity ratioEntry = weeklyRatioDao.getRatioByWeek(week);
                float weekRatio = (ratioEntry != null) ? ratioEntry.ratioValue : 0f;
                if (!Float.isFinite(weekRatio) || weekRatio < 0f) weekRatio = 0f;

                // Projected daily goal = ratio * effective wear time (round to nearest int).
                int adjustedDailyGoal = Math.round(weekRatio * effectiveWearMinutes);

                // Save to SharedPreferences
                prefsDataStorage.edit().putInt(PrefsKeys.Data.LAST_KNOWN_GOAL, adjustedDailyGoal).apply();

                // Save to db
                AdjustedDailyGoalEntity adjusted = new AdjustedDailyGoalEntity(
                        System.currentTimeMillis(),
                        week,
                        day,
                        adjustedDailyGoal
                );
                db.adjustedDailyGoalDao().insert(adjusted);
                Log.w("DebuggingKurto", "Adjusted goal: "+ adjustedDailyGoal);

                LogSaver.saveLog(
                        TAG,
                        "d",
                        "Adjusted goal => week=" + week
                                + ", day=" + day
                                + ", ratio=" + weekRatio
                                + ", notWorn=" + notWornMinutes
                                + ", availableWear=" + availableWearMinutes
                                + ", effectiveWear=" + effectiveWearMinutes
                                + ", goal=" + adjustedDailyGoal
                );
                // 🔔 Push an immediate complication refresh
                MyProgressComplicationProviderService.requestComplicationUpdate(appContext);

            }catch (Exception e) {
                LogSaver.saveLog(TAG,"e", "Failed to computeAndSaveAdjustedDailyGoal"+ e.getMessage());
            }
        };
        try {
            analyticsExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            LogSaver.saveLog(TAG,"e", "Executor was shut down unexpectedly — recreating and retrying: "+ e.getMessage());
            synchronized (executorLock) {
                analyticsExecutor = Executors.newSingleThreadExecutor();
            }
            ensureAnalyticsExecutorAlive();
            analyticsExecutor.execute(task);
        }
    }

    // Shutdown everything to avoid cache problems and save exit
    public static void shutdown() {

        // Shutdown analyticsExecutor
        analyticsExecutor.shutdown(); // Prevent new tasks
        try {
            if (!analyticsExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                LogSaver.saveLog(TAG,"w", "Timeout waiting for analyticsExecutor to finish.");
            }
        } catch (InterruptedException e) {
            LogSaver.saveLog(TAG,"e", "Interrupted while shutting down analyticsExecutor"+e.getMessage());
        }

        // Close database if open
        if (db != null && db.isOpen()) {
            db.close();
        }
        // Close main results database
        if (mainResultsDb != null && mainResultsDb.isOpen()) {
            mainResultsDb.close();
        }
    }
}
