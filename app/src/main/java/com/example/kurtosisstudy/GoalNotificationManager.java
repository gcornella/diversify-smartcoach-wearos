package com.example.kurtosisstudy;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.app.AlarmManager;
import androidx.core.app.NotificationCompat;

import com.example.kurtosisstudy.db.DailyCumulativeEntity;
import com.example.kurtosisstudy.db.WearSessionDao;
import com.example.kurtosisstudy.db.WearSessionEntity;
import com.example.kurtosisstudy.receivers.NotWornAlarmReceiver;

import java.util.Calendar;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.example.kurtosisstudy.PrefsKeys;

/*
 * GoalNotificationManager — Summary
 * ---------------------------------
 * Central controller for all notifications in the app:
 *   • Daily goal / progress nudges (levels 1–4)
 *   • “Watch not worn” reminders (level 5)
 *   • Optional debug test notifications (hardPostTest)
 *
 * HOW IT WORKS
 * ------------
 * All public calls (notifyIfGoalReached / notificationIfWatchNotWorn)
 * run on a background single-thread executor so UI/FGS threads never block.
 *
 * 1) Goal & Progress Notifications
 * --------------------------------
 * Sent only during the intervention weeks (week 2–5).
 * Logic:
 *   • Reset daily counters every new day.
 *   • Max 10 notifications/day.
 *   • Min 60 minutes between notifications.
 *   • Only if watch is worn.
 *   • Computes:
 *       - Today's dynamic daily goal
 *       - Expected progress slope since the last continuous worn segment
 *       - Recent 30-min activity level
 *   • Levels:
 *       1 → First time reaching the daily goal
 *       2 → On track but needs encouragement
 *       3 → Behind the expected slope (inactive)
 *       4 → Goal surpassed but currently inactive
 *   • Records notification timestamps in the DB for analysis.
 *
 * 2) “Watch Not Worn” Notifications (level 5)
 * ------------------------------------------
 *   • Only after 10:00 AM.
 *   • If watch was taken off for ≥30 minutes and last reminder ≥30 minutes ago.
 *   • Sends a gentle message (“Wear me to earn 🏆”)
 *   • Schedules a 1-second alarm as an optional fallback sound.
 *
 * 3) Debug Notification (hardPostTest)
 * ------------------------------------
 * Creates a fresh HIGH-importance channel every time
 * and posts a simple test card to verify notification behavior on-watch.
 *
 * PREFS USED
 * ----------
 *   • NOTIF_PREFS → Goal state, counts, timestamps
 *   • DATA_PREFS  → LAST_KNOWN_PROGRESS / LAST_KNOWN_GOAL
 *   • WEAR_PREFS  → Current wear state & last not-worn notif time
 *
 * NOTES
 * -----
 *   • Always uses a notification channel (IMPORTANCE_HIGH).
 *   • All heavy data access uses DataStorageManager (DB reads, cumulative values).
 *   • All scheduling, filtering, and nudge logic is centralized here.
 */

public class GoalNotificationManager {
    private static final String TAG = "GoalNotificationManager_KurtosisStudy";

    private static SharedPreferences prefsNotifications, prefsWatchWorn, prefsDataStorage;

    private static final String CHANNEL_ID = "goal_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_NAME = "Goal Notifications";

    private static String message = "Keep moving!";
    private static String titleMessage = "Great job!";
    private static int level = 0;
    private static Random random = new Random();
    private static boolean condition = false;
    private static final int NOTIFICATION_FREQUENCY = 60; // MINUTES

    // Different messages for different behaviors
    // Level 1 – Goal reached
    private static final String[] level1_titleMessage = {
            "🏁 Goal Achieved!",
            "🔥 You Crushed It!",
            "🎉 Daily Goal Met!"
    };
    private static final String[] level1_message = {
            "🎉 Goal reached! Amazing! Keep going and beat your record!",
            "💪 You did it! Already at today's goal! But don't stop here!",
            "🔥 Crushing it! Why stop now? Keep the streak alive!"
    };

    // Level 2 – On track recently, but goal not yet reached
    private static final String[] level2_titleMessage = {
            "⏳ Almost There!",
            "🚀 Keep It Going!",
            "💪 Great Progress!"
    };
    private static final String[] level2_message = {
            "🚀 Almost there! Keep it going! Just move a bit more!",
            "👍 Great pace! Just a bit more to hit your goal!",
            "💥 You're on fire! Push through and finish strong!"
    };

    // Level 3 – Behind & inactive – gentle nudge
    private static final String[] level3_titleMessage = {
            "💪 Let’s Get Moving!",
            "💪 Been inactive? Let's move!",
            "🚀 Inactivity detected"
    };
    private static final String[] level3_message = {
            "🌟 Let’s move a bit! Click on the button 'I want to exercise' and do a short exercise",
            "⏳ A short exercise can help. Tap 'I want to exercise' and move for ~5 minutes",
            "📈 Let’s turn this around! Select 'I want to exercise' to increase your activity"
    };
    // Level 4 – Goal reached and even moving more
    private static final String[] level4_titleMessage = {
            "🏆 Beyond the Goal!",
            "🏆 You're Unstoppable!",
            "🏆 Record breaker!"
    };
    private static final String[] level4_message = {
            "😲 You’re still moving? Beyond impressive! Wow!",
            "🤯 Goal smashed! And you're still going strong! Amazing!",
            "😱 Unstoppable! You've passed your goal and you're still going!"
    };

    // Run all notification computations off the main thread.
    private static final java.util.concurrent.ExecutorService notifExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Public entrypoint: safe to call from UI/receivers/services. */
    public static void notifyIfGoalReached(Context context) {
        final Context app = context.getApplicationContext();
        notifExecutor.execute(() -> notifyIfGoalReachedImpl(app));
    }

    public static void notifyIfGoalReachedImpl(Context context) {
        // Just for debugging to see that notifications work
        // hardPostTest(context);

        // Get shared prefs for goals and data
        prefsNotifications = context.getApplicationContext().getSharedPreferences(PrefsKeys.Notif.NOTIF_PREFS, Context.MODE_PRIVATE);
        prefsDataStorage = context.getApplicationContext().getSharedPreferences(PrefsKeys.Data.PREFS, Context.MODE_PRIVATE);
        prefsWatchWorn = context.getApplicationContext().getSharedPreferences(PrefsKeys.Wear.WEAR_PREFS, Context.MODE_PRIVATE);

        // check week
        int weekId = DataStorageManager.getWeekFromPrefs();
        if (weekId == 1 || weekId >= 6) return;

        // New day check
        String today = DataStorageManager.getDayForDB(); // yyyy-MM-dd
        String lastDay = prefsNotifications.getString(PrefsKeys.Notif.LAST_DAY_CHECKED, "");
        if (!today.equals(lastDay)) {
            LogSaver.saveLog(TAG,"d", "Preparing new day shared preferences");
            prefsNotifications.edit()
                    .putBoolean(PrefsKeys.Notif.GOAL_REACHED_TODAY, false)
                    .putString(PrefsKeys.Notif.LAST_DAY_CHECKED, today)
                    .putInt(PrefsKeys.Notif.NOTIFS_SHOWED, 0)
                    .putLong(PrefsKeys.Notif.LAST_NOTIF_TIME, 0L)
                    .apply();
        }

        // Check if user has been shown more than 12 notifications already (early exit check)
        int notificationsShowed = prefsNotifications.getInt(PrefsKeys.Notif.NOTIFS_SHOWED, 0);
        if (notificationsShowed >= 10){
            LogSaver.saveLog(TAG,"w", "notificationsShowed >= 10");
            return;
        }

        // Check if less than 45' has passed since last notification (early exit check)
        long lastNotificationTimestamp = prefsNotifications.getLong(PrefsKeys.Notif.LAST_NOTIF_TIME, 0L);
        if (System.currentTimeMillis() - lastNotificationTimestamp < TimeUnit.MINUTES.toMillis(NOTIFICATION_FREQUENCY)) return;

        // Check if watch is not worn, then exit
        boolean isWorn = prefsWatchWorn.getBoolean(PrefsKeys.Wear.WEAR_BOOL, true);
        if (!isWorn) return;

        boolean goalReachedAlready = prefsNotifications.getBoolean(PrefsKeys.Notif.GOAL_REACHED_TODAY, false);
        Log.d(TAG,"goalReachedAlready: " + goalReachedAlready + "; notification number: " + notificationsShowed);

        // Get progress and dailyAdjustedGoal
        int progress = prefsDataStorage.getInt(PrefsKeys.Data.LAST_KNOWN_PROGRESS, 0);
        int dailyGoal = prefsDataStorage.getInt(PrefsKeys.Data.LAST_KNOWN_GOAL, 480);
        LogSaver.saveLog(TAG,"w", "Reading from SharedPrefs, progress: " + progress + ", and goal: " + dailyGoal);

        // Get Wear Start Time (not earlier than 8:00 AM) ---
        WearSessionDao wearDao = DataStorageManager.getMainDatabase().wearSessionDao();
        List<WearSessionEntity> todayWearSessions = wearDao.getSessionsForDate(today);
        if (todayWearSessions == null || todayWearSessions.isEmpty()) {
            LogSaver.saveLog(TAG, "w", "No wear session entries for today, skipping.");
            return;
        }
        long eightAmMillis = getTodayAtHour(8);     // Get timestamp at 8am
        long tenPmMillis = getTodayAtHour(22);      // Get timestamp at 10pm

        boolean inStreak = false;     // have we found the trailing run of 1s (going backward)?
        long slopeStartTime = -1L;

        for (int i = todayWearSessions.size() - 1; i >= 0; i--) {
            WearSessionEntity session = todayWearSessions.get(i);

            long t = session.timestamp;
            boolean w = session.isWorn;

            // Skip events after the window
            if (t > tenPmMillis) continue;

            // If we crossed before window start
            if (t < eightAmMillis) {
                if (inStreak) {
                    Log.d(TAG,"We already found a run of 1s in the window — keep its start.");
                    break;
                }
                Log.d(TAG,"No 1s seen in the window yet. Carry state to 8am: if worn before 8am, " +
                        "clamp start to 8am; otherwise, nothing found.");
                slopeStartTime = w ? eightAmMillis : -1L;
                break;
            }

            if (!inStreak) {
                if (w) {
                    Log.d(TAG,"We just hit the last block of 1s (from the end)");
                    inStreak = true;
                    slopeStartTime = t; // start with this 1
                } else {
                    Log.d(TAG,"Still in trailing 0s, keep going back");
                }
            } else { // already inside a run of 1s
                if (w) {
                    Log.d(TAG,"Push the start earlier while we keep seeing 1s");
                    slopeStartTime = t;
                } else {
                    Log.d(TAG,"Found the 0 before the run → the start is the earliest 1 we tracked");
                    break;
                }
            }
        }
        LogSaver.saveLog(TAG, "d", "slopeStartTime: " + slopeStartTime);
        if (slopeStartTime == -1L) return;

        // Get cumulative value close to the slopeStartTime
        DailyCumulativeEntity startEntry = DataStorageManager.getMainDatabase().dailyCumulativeDao().getClosestBefore(slopeStartTime, today);
        float progressAtSlopeStart = (startEntry != null) ? startEntry.cumulative : 0f;
        LogSaver.saveLog(TAG, "d", "progressAtSlopeStart: " + progressAtSlopeStart);

        // Calculate slope rate (counts per worn minute)
        long denom = tenPmMillis - slopeStartTime;
        if (denom <= 0) return;

        float expectedSlope = (dailyGoal - progressAtSlopeStart) / (float) denom;
        float expectedCount = progressAtSlopeStart + expectedSlope * (System.currentTimeMillis() - slopeStartTime);
        LogSaver.saveLog(TAG,"w", "Expected count for this time is: " + expectedCount + ", and I am at: " + progress);

        // Calculate if 30 minute window of time before was active or not, and only notify if inactive
        boolean previous30MinActive = DataStorageManager.getActivityDuringPrev30Minutes();
        LogSaver.saveLog(TAG,"w", "previous30MinActive: " + previous30MinActive);

        // Calculate the activity at exactly 30 minutes before, interpolate and if on track, don’t notify
        int activity30MinAgo = DataStorageManager.getCumulative30MinsBefore();
        LogSaver.saveLog(TAG,"d", "activity at timestamp exactly 30MinAgo: " + activity30MinAgo);
        if (activity30MinAgo == 0) return; // no data from 30' before

        float deltaExpected = expectedSlope * (30 * 60 * 1000); // expected progress in 30min considering adjusted slope concerted to ms
        float deltaMovementThirtyMinutes = progress - activity30MinAgo;
        LogSaver.saveLog(TAG,"w", "deltaExpected: " + deltaExpected + "; deltaMovementThirtyMinutes: " + deltaMovementThirtyMinutes );


        condition = false;
        if (progress >= dailyGoal) {
            if (!goalReachedAlready) {
                // First time reaching the goal today
                message = level1_message[random.nextInt(3)];
                titleMessage = level1_titleMessage[random.nextInt(3)];
                condition = true;
                level = 1;
                // Mark goal as reached
                prefsNotifications.edit().putBoolean(PrefsKeys.Notif.GOAL_REACHED_TODAY, true).apply();
            } else if (!previous30MinActive){
                message = level4_message[random.nextInt(3)];
                titleMessage = level4_titleMessage[random.nextInt(3)];
                condition = true;
                level = 4;
            }
        } else{
            if (progress < expectedCount && !previous30MinActive) {
                condition = true;
                if (deltaMovementThirtyMinutes <= deltaExpected) {
                    message = level3_message[random.nextInt(3)];
                    titleMessage = level3_titleMessage[random.nextInt(3)];
                    level = 3;
                } else {
                    message = level2_message[random.nextInt(3)];
                    titleMessage = level2_titleMessage[random.nextInt(3)];
                    level = 2;
                }
            }
        }

        if (condition) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

            if (manager != null) {
                // Create channel if not already present
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH
                );
                channel.enableVibration(true);
                channel.setVibrationPattern(new long[]{0,500,250,500});
                channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);

                manager.createNotificationChannel(channel);

                Intent intent = new Intent(context, ExerciseActivity.class)
                        .setAction("open_from_notification") // helps PI uniqueness
                        .putExtra(ExerciseActivity.EXTRA_LAUNCH_SOURCE, ExerciseActivity.SOURCE_NOTIFICATION);

                PendingIntent actionIntent = PendingIntent.getActivity(
                        context,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

                // Build the action
                NotificationCompat.Action openAction = new NotificationCompat.Action.Builder(
                        android.R.drawable.ic_menu_view, // icon
                        "I want to exercise",    // button text
                        actionIntent            // what it does
                ).build();

                Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

                NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.star_on)
                        .setContentTitle(titleMessage)
                        .setContentText(message)
                        .setPriority(NotificationCompat.PRIORITY_HIGH) // channel still decides
                        .setAutoCancel(false) // tapping does NOT dismiss.
                        .setContentIntent(actionIntent)                // <-- REQUIRED for a proper card
                        .setOngoing(true)                             // <-- don't mark user alerts ongoing
                        .setOnlyAlertOnce(false)
                        .setSound(soundUri)
                        .setVibrate(new long[]{0, 500, 250, 500}) // keep it just in case, although channel wins
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .addAction(openAction);

                manager.notify(NOTIFICATION_ID, builder.build());

                // Increase the notification counter
                notificationsShowed++;
                prefsNotifications.edit()
                        .putInt(PrefsKeys.Notif.NOTIFS_SHOWED, notificationsShowed)
                        .putLong(PrefsKeys.Notif.LAST_NOTIF_TIME, System.currentTimeMillis())
                        .apply();

                LogSaver.saveLog(TAG, "w", String.format("Notification level %d displayed with message: %s", level, message));

                // Save the timestamp of when the notification has been shown to a database table
                DataStorageManager.saveNotificationTimestamp(level, message);
            }
        }
    }

    public static void hardPostTest(Context ctx) {
        String cid = "goal_test_" + android.os.SystemClock.uptimeMillis(); // brand-new channel
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        // Log environment — on WATCH, permission, app-level toggle, and channel state
        boolean onWatch = ctx.getPackageManager().hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_WATCH);
        boolean enabled = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled();
        String permLog = "";
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            int p = ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS);
            permLog = " postPerm=" + (p == android.content.pm.PackageManager.PERMISSION_GRANTED);
        }
        android.util.Log.d("HARDTEST", "onWatch=" + onWatch + " areEnabled=" + enabled + permLog);

        // Create a fresh, alerting channel
        NotificationChannel ch = new NotificationChannel(
                cid, "Goal Debug", NotificationManager.IMPORTANCE_HIGH);
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 300, 150, 300});
        nm.createNotificationChannel(ch);

        // Simple content intent (some Wear builds hide cards with no content intent)
        PendingIntent content = PendingIntent.getActivity(
                ctx, 777, new Intent(ctx, ExerciseActivity.class).setAction("dbg"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, cid)
                .setSmallIcon(android.R.drawable.star_on)
                .setContentTitle("Ping from WATCH")
                .setContentText("If you see this, notifications work.")
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(false)
                .setOnlyAlertOnce(false)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(content);

        int id = (int) (System.currentTimeMillis() & 0x7FFFFFFF); // brand-new notif ID
        android.util.Log.d("HARDTEST", "about to notify id=" + id + " ch=" + cid);
        nm.notify(id, b.build());
        android.util.Log.d("HARDTEST", "posted id=" + id);
    }


    // --- Helper method ---
    private static long getTodayAtHour(int hour) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    public static void notificationIfWatchNotWorn(Context context) {
        final Context app = context.getApplicationContext();
        notifExecutor.execute(() -> notificationIfWatchNotWornImpl(app));
    }

    public static void notificationIfWatchNotWornImpl(Context context) {
        LogSaver.saveLog(TAG, "w", "notificationIfWatchNotWorn() called");

        prefsWatchWorn = context.getApplicationContext().getSharedPreferences(PrefsKeys.Wear.WEAR_PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();

        // Don't notify before 10am as users might be sleeping
        long tenAmMillis = getTodayAtHour(10);
        if (now < tenAmMillis) return;

        // 1. Get last not-worn timestamp for today
        long lastNotWornTimestamp = DataStorageManager.getLastNotWornTimestampToday();
        if (lastNotWornTimestamp == -1L) return;

        // 2. Check if it's been worn again since (you could also use getLastWearSession() if needed)
        long lastNotifTime = prefsWatchWorn.getLong(PrefsKeys.Wear.LAST_WEAR_NOTIF, 0);

        // 3. How long has it been not worn
        long minutesSinceNotWorn = TimeUnit.MILLISECONDS.toMinutes(now - lastNotWornTimestamp);
        long minutesSinceLastNotif = TimeUnit.MILLISECONDS.toMinutes(now - lastNotifTime);

        // 4. Notify every 45 minutes while not worn
        boolean notWornNotification = false;
        level = 5; // 5 means it's a worn notification
        titleMessage = "👋 Miss me?";
        message = "Wear me to earn 🏆";
        LogSaver.saveLog(TAG, "w", "minutesSinceNotWorn: " + minutesSinceNotWorn + "; minutesSinceLastNotif: "+minutesSinceLastNotif);

        if (minutesSinceNotWorn >= 30 && minutesSinceLastNotif >= 30) {
            notWornNotification = true;
            prefsWatchWorn.edit().putLong(PrefsKeys.Wear.LAST_WEAR_NOTIF, now).apply();
            LogSaver.saveLog(TAG, "w", "Not-worn notification sent (mins since removed: " + minutesSinceNotWorn + ")");
        }

        if (notWornNotification) {
            // Save the timestamp of when the notification has been shown to a database table
            DataStorageManager.saveNotificationTimestamp(level, message);

            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

            if (alarmManager == null) {
                Log.e(TAG, "❌ AlarmManager is null — cannot schedule alarm");
                return;
            }

            // Define the intent
            Intent alarmIntent = new Intent(context, NotWornAlarmReceiver.class);
            PendingIntent alarmPendingIntent = PendingIntent.getBroadcast(
                    context,
                    1234,
                    alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            // Compute the trigger time
            long triggerAtMillis = System.currentTimeMillis() + 1000; // 1 sec delay

            // 🔐 Handle exact alarm permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!alarmManager.canScheduleExactAlarms()) {
                    Log.w(TAG, "⚠️ Exact alarm not permitted — prompt user to enable in settings");

                    Intent intent2 = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                    intent2.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent2);
                    return; // Do not proceed if not allowed
                }
            }

            try {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        alarmPendingIntent
                );
                Log.d(TAG, "⏰ Fallback alarm scheduled to trigger sound in 1s");
            } catch (SecurityException e) {
                Log.e(TAG, "❌ SecurityException while scheduling alarm: " + e.getMessage());
            }
        }
    }
}
