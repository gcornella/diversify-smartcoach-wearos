package com.example.kurtosisstudy;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.wear.widget.BoxInsetLayout;

import java.util.ArrayList;
import java.util.concurrent.Executors;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.example.kurtosisstudy.cloud.CloudStorageActivity;
import com.example.kurtosisstudy.complications.MyComplicationProviderService;
import com.example.kurtosisstudy.complications.MyProgressComplicationProviderService;
import com.example.kurtosisstudy.complications.MyServiceAliveCheckComplicationProviderService;
import com.example.kurtosisstudy.complications.MyWearTimeComplicationProviderService;
import com.example.kurtosisstudy.db.AdjustedDailyGoalDao;
import com.example.kurtosisstudy.db.AdjustedDailyGoalEntity;
import com.example.kurtosisstudy.db.DailyCumulativeDao;
import com.example.kurtosisstudy.db.DailyCumulativeEntity;
import com.example.kurtosisstudy.db.DailyDatabase;
import com.example.kurtosisstudy.db.MainResultsDatabase;
import com.example.kurtosisstudy.receivers.HeartbeatCheckWorker;
/*
 * MainActivity — Core Summary (Ultra-Short)
 * ----------------------------------------
 * PURPOSE:
 *   • Launches & monitors the ForegroundSensorService (50 Hz sensing + wear detection).
 *   • Shows TODAY’s active minutes + dynamic daily goal.
 *   • Keeps the app alive under Wear OS battery/Doze restrictions.
 *
 * KEY FUNCTIONS:
 *   1) Service Control
 *      - Starts FGS on launch/resume (idempotent).
 *      - Uses heartbeat timestamp to check if service is alive (FRESH_MS window).
 *      - Updates background color + text depending on running state.

 *   2) Live UI Updates (Room LiveData)
 *      - Observes DailyCumulative → updates progress ring + “active time”.
 *      - Observes AdjustedDailyGoal → updates ring max.
 *      - Week logic:
 *          week 1 or ≥6 ⇒ hide ring (just-wear phase)
 *          weeks 2–5 → show active-minutes progress.

 *   3) Complications
 *      - On startup/resume: forces refresh of progress, wear time, and service-alive complications.

 *   4) Permissions & System Survival
 *      - Requests BODY_SENSORS + POST_NOTIFICATIONS.
 *      - Asks user to ignore battery optimizations (keeps FGS alive).
 *      - Schedules HeartbeatCheckWorker (periodic restart if FGS dies).

 *   5) Admin & Settings
 *      - Simple password gate → open SettingsActivity & handedness changes.
 *      - Hard-coded user setup (temporary for study).

 *   6) Extra
 *      - Manual DB reads on resume ensure instant UI correctness.
 *      - Hidden debug buttons for start/stop/data export.
 *
 * Essence:
 *   A small dashboard ensuring the sensor service runs reliably,
 *   reflects real-time movement progress, handles permissions/power rules,
 *   and keeps complications + UI synced.
 */

public class MainActivity extends AppCompatActivity {

    // Tag used for logging/debugging
    private static final String TAG = "Main_KurtosisStudy";

    // Foreground Service Status, HeartBeat to check if foreground is dead
    private static final long FRESH_MS  = 120_000L;

    // Variable to check whether the foreground service is running
    private boolean isServiceRunning = false;

    // Check which week we're in in case its just-wear week (no notifications) 1,6,7,8,9:
    private boolean isWeek16789 = true;

    // UI elements
    private Button stopButton, startButton, saveDataButton, settingsButton;
    private BoxInsetLayout layout;          // Background container
    private ProgressBar progressBar;        // Circular progress bar around the screen
    private TextView activeTimeText;        // Displays elapsed active time

    private final ActivityResultLauncher<Intent> settingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK &&
                        result.getData() != null &&
                        result.getData().getBooleanExtra("changed_user", false)) {
                    // Recreate to rerun onCreate() with new USER_ID / handedness
                    recreate();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG,"onCreate() called");

        // TODO - 2nd STUDY (comment next section to choose user from settings activity instead of manually)
        SharedPreferences prefs = getSharedPreferences(PrefsKeys.Settings.SETTINGS_PREFS, MODE_PRIVATE);
        prefs.edit()
                .putInt(PrefsKeys.Settings.USER_ID, 4321)
                .putString(PrefsKeys.Settings.HANDEDNESS, "left")
                .apply();
        Log.e(TAG , "User_id: " + prefs.getInt(PrefsKeys.Settings.USER_ID, 0) + " ; and hand: "+prefs.getString(PrefsKeys.Settings.HANDEDNESS, ""));


        ComputationManager.initFromPrefs(getApplicationContext());

        // Initialize db and handedness
        DataStorageManager.init(getApplicationContext());

        // Set the layout defined in activity_main_kurtosisstudy.xml
        setContentView(R.layout.activity_main_kurtosisstudy);

        // Initialize and link UI elements to XML views
        layout = findViewById(R.id.BoxInsetLayout_id);
        progressBar = findViewById(R.id.progressBar_id);
        activeTimeText = findViewById(R.id.activeTimeText_id);

        // Start and Stop buttons
        stopButton = findViewById(R.id.stopServiceButton_id);
        stopButton.setVisibility(View.GONE); // only show when debugging (to force stop the service)

        startButton = findViewById(R.id.startServiceButton_id);
        startButton.setVisibility(View.GONE); // only show when debugging (to force start the service)

        saveDataButton = findViewById(R.id.saveDataButton_id);
        // TODO - 2nd STUDY (comment next to show access to cloud activity)
        saveDataButton.setVisibility(View.GONE);

        settingsButton = findViewById(R.id.btnOpenSettings);
        // TODO - 2nd STUDY (comment next to show access to settings activity)
        settingsButton.setVisibility(View.GONE);

        // Set initial background color (gray = not collecting data)
        layout.setBackgroundColor(ContextCompat.getColor(this, R.color.ui_background_color_notworn));

        // Request runtime permissions (for sensors and notifications)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {checkPermissions();}

        // Ask user to disable battery optimizations so the service isn't killed in background
        requestIgnoreBatteryOptimizations(this);

        startButton.setOnClickListener(v -> {
            Log.d(TAG, "Start Button Clicked");
            startForegroundService();
            updateUIBackground(true); // Service is now started, update UI accordingly
        });

        stopButton.setOnClickListener(v -> {
            Log.d(TAG, "Stop Button Clicked");
            stopForegroundService();
            updateUIBackground(false); // Service is now stopped, update UI accordingly
        });

        saveDataButton.setOnClickListener(v -> {
            Intent i = new Intent(MainActivity.this, CloudStorageActivity.class);
            startActivity(i);
        });

        settingsButton.setOnClickListener(v -> showAdminGate());

        // Start service if not already running (idempotent - no duplicates if called again)
        startForegroundService();

        isServiceRunning = isServiceLikelyRunning(this);
        updateUIBackground(isServiceRunning);

        MainResultsDatabase mainResultsDb = DataStorageManager.getMainDatabase();
        DailyDatabase db = DataStorageManager.getDailyDatabase();

        // At startup, push an immediate complication refresh to the watchface
        MyComplicationProviderService.requestComplicationUpdate(getApplicationContext());
        MyWearTimeComplicationProviderService.requestComplicationUpdate(getApplicationContext());
        MyProgressComplicationProviderService.requestComplicationUpdate(getApplicationContext());
        MyServiceAliveCheckComplicationProviderService.requestComplicationUpdate(getApplicationContext());

        // Set up LiveData observer for UI updates to also see the progress inside the main activity:
        // Initializes the database and attaches a LiveData observer to automatically update
        // the progress bar and active minutes text when new cumulative results are saved.
        // This ensures the UI stays in sync with the latest sensor data in real time.
        mainResultsDb.dailyCumulativeDao()
                .getLastEntryForDayLive(DataStorageManager.getDayForDB())
                .observe(this, entry -> {
                    int cumulative = (entry != null) ? entry.cumulative : 0;

                    // Log the retrieved result
                    if (entry != null) {
                        LogSaver.saveLog(TAG,"d", "LiveData observer: Found cumulative for day " + entry.day + " with average = " + cumulative);
                    } else {
                        LogSaver.saveLog(TAG,"d", "LiveData observer: No entry found for today — defaulting to average = 0");
                    }

                    // Update UI
                    progressBar.setProgress(cumulative);

                    if (isWeek16789){
                        activeTimeText.setText("Watch is recording data");
                    }else{
                        activeTimeText.setText(formatActiveTime(cumulative) + " active");
                    }

                    // TODO - 2nd STUDY (uncomment next)
                    //activeTimeText.setText(formatActiveTime(cumulative) + " active");

                });

        // Get most recent goal
        db.adjustedDailyGoalDao()
                .getLastGoalLive()
                .observe(this, entry -> {

                        // Hide during week 1 (baseline), show otherwise
                        progressBar.setVisibility(isWeek16789 ? View.GONE : View.VISIBLE);

                        // Log the retrieved result
                        int adjustedDailyGoal = 480;
                        if (entry != null) {
                            adjustedDailyGoal = isWeek16789 ? 480 : entry.adjustedDailyGoal;
                            LogSaver.saveLog(TAG,"d", "LiveData observer: Found entry for week " + entry.week + " with goal = " + adjustedDailyGoal);
                        } else {
                            LogSaver.saveLog(TAG,"d", "LiveData observer: No entry found for today — defaulting to 480");
                        }

                        // Update goal UI max range
                        progressBar.setMax(adjustedDailyGoal);
                    });


        // A periodic worker calls the HeartBeatCheckWorker every 15' to try and restart the Service if not running
        // This schedules once and survives reboot; you do not need to re-enqueue later.
        HeartbeatCheckWorker.enqueuePeriodic(getApplicationContext());

        // Also run once right now so you can see it immediately:
        HeartbeatCheckWorker.enqueueNow(getApplicationContext());
    }


    // A helper to show the settings button and a password for the admin access
    private void showAdminGate() {
        // Root (vert) — centered with small padding
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * dp);
        box.setPadding(pad, pad+30, pad, pad); // increase top padding

        // Title
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("Admin password");
        title.setTextSize(16f);
        title.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        // Password field (no prefill)
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSingleLine(true);
        input.setGravity(android.view.Gravity.CENTER);
        input.setEms(6); // keeps it narrow on round screens
        android.widget.LinearLayout.LayoutParams inLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        inLp.topMargin = (int) (8 * dp);

        // Buttons row (horizontal)
        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(this);
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        btnRow.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        android.widget.LinearLayout.LayoutParams rowLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = (int) (12 * dp);

        android.widget.Button btnCancel = new android.widget.Button(this);
        btnCancel.setText("Cancel");

        android.widget.Button btnOk = new android.widget.Button(this);
        btnOk.setText("OK");

        // Small gap between buttons
        android.view.View spacer = new android.view.View(this);
        spacer.setLayoutParams(new android.widget.LinearLayout.LayoutParams((int) (12 * dp), 1));

        btnRow.addView(btnCancel);
        btnRow.addView(spacer);
        btnRow.addView(btnOk);

        // Assemble
        box.addView(title);
        box.addView(input, inLp);
        box.addView(btnRow, rowLp);

        // Dialog (no title/message chrome)
        final androidx.appcompat.app.AlertDialog dlg =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setView(box)
                        .setCancelable(true)
                        .create();
        dlg.show();

        // Actions
        btnCancel.setOnClickListener(v -> dlg.dismiss());
        btnOk.setOnClickListener(v -> {
            String pwd = input.getText() != null ? input.getText().toString() : "";
            if ("a".equals(pwd)) {
                Intent i = new Intent(this, SettingsActivity.class)
                        .putExtra(SettingsActivity.EXTRA_ADMIN_OK, true);
                settingsLauncher.launch(i);   // ← use launcher, not startActivity
                dlg.dismiss();
            } else {
                android.widget.Toast.makeText(this, "Wrong password", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        /*btnOk.setOnClickListener(v -> {
            String pwd = input.getText() != null ? input.getText().toString() : "";
            if ("a".equals(pwd)) {
                android.content.Intent i = new android.content.Intent(
                        this, SettingsActivity.class);
                i.putExtra(SettingsActivity.EXTRA_ADMIN_OK, true);
                startActivity(i);
                dlg.dismiss();
            } else {
                android.widget.Toast.makeText(this, "Wrong password", android.widget.Toast.LENGTH_SHORT).show();
            }
        });

         */

        // Let keyboard "Done" trigger OK
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean isEnter = event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER;
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || isEnter) {
                btnOk.performClick();
                return true;
            }
            return false;
        });
    }


    @Override
    protected void onResume() {
        super.onResume();

        // When returning to this screen, update the UI to reflect current service state
        isServiceRunning = isServiceLikelyRunning(this);
        LogSaver.saveLog(TAG,"d", "isServiceLikelyRunning: " + isServiceRunning);
        updateUIBackground(isServiceRunning);

        // Start service if not already running (idempotent - no duplicates if called again)
        startForegroundService();

        // Get in which week we're at (db must have been initialized previously)
        int weekId = DataStorageManager.getWeekFromPrefs();
        isWeek16789 = (weekId == 1 || weekId >= 6);
        LogSaver.saveLog(TAG,"d", "Week ID is: " + weekId + ", so progress visible: " + !isWeek16789);

        // Force-refresh cumulative value from DB
        Executors.newSingleThreadExecutor().execute(() -> {
            DailyCumulativeDao dao = DataStorageManager.getMainDatabase().dailyCumulativeDao();
            DailyCumulativeEntity entry = dao.getLastEntryForDay(DataStorageManager.getDayForDB());

            int cumulative = (entry != null) ? entry.cumulative : 0;
            runOnUiThread(() -> {
                progressBar.setProgress(cumulative);

                if (isWeek16789){
                    activeTimeText.setText("Watch is recording data");
                }else{
                    activeTimeText.setText(formatActiveTime(cumulative) + " active");
                }
                // TODO - 2nd STUDY (uncomment next)
                //activeTimeText.setText(formatActiveTime(cumulative) + " active");
                LogSaver.saveLog(TAG,"d", "MainActivity onResume() reopened,  manual DB read — UI updated with cumulative: " + cumulative);
            });
        });

        // Force-refresh adjusted goal value from DB
        Executors.newSingleThreadExecutor().execute(() -> {
            AdjustedDailyGoalDao dao = DataStorageManager.getDailyDatabase().adjustedDailyGoalDao();
            AdjustedDailyGoalEntity entry = dao.getLastGoal();

            runOnUiThread(() -> {
                progressBar.setVisibility(isWeek16789 ? View.GONE : View.VISIBLE);

                // Log the retrieved result
                int adjustedDailyGoal = 480;
                if (entry != null) {
                    adjustedDailyGoal = isWeek16789 ? 480 : entry.adjustedDailyGoal;
                    LogSaver.saveLog(TAG,"d", "LiveData observer: Found entry for week " + entry.week + " with goal = " + adjustedDailyGoal);
                } else {
                    LogSaver.saveLog(TAG,"d", "LiveData observer: No entry found for today — defaulting to 480");
                }
                // Update goal UI max range
                progressBar.setMax(adjustedDailyGoal);
            });
        });
    }

    // Service is considered to be running is the heartbeat is fresh.
    // Foreground Service sends a heartbeat while working, if it breaks the heartbeat is not sent
    private static boolean isServiceLikelyRunning(Context ctx) {
        long last = ctx.getSharedPreferences(PrefsKeys.HeartBeat.HEARTBEAT_PREFS, Context.MODE_PRIVATE)
                .getLong(PrefsKeys.HeartBeat.HEARTBEAT_TIME, 0L);
        return last > 0 && (System.currentTimeMillis() - last) < FRESH_MS;
    }

    // A helper method to format the time string
    private String formatActiveTime(int minutes) {
        int hours = minutes / 60 ;
        int mins = minutes % 60;
        if (hours > 0 && mins > 0) {
            return String.format("%dh%02d′", hours, mins); // e.g. 2h05′
        } else if (hours > 0) {
            return String.format("%dh", hours);           // e.g. 1h
        } else {
            return String.format("%d′", mins);            // e.g. 45′
        }
    }

    // Change the button text and background depending on whether the service is running
    private void updateUIBackground(boolean isRunning) {
        Log.d(TAG, "Updating UI: " + isRunning);
        if (isRunning) {
            layout.setBackgroundColor(ContextCompat.getColor(this, R.color.ui_background_color_worn));
            startButton.setVisibility(View.GONE);
            //stopButton.setVisibility(View.VISIBLE);
        } else {
            stopButton.setVisibility(View.GONE);
            startButton.setVisibility(View.VISIBLE);
            layout.setBackgroundColor(ContextCompat.getColor(this, R.color.ui_background_color_notworn));
            activeTimeText.setText("Not running, restart Kurtosis App");
        }
    }

    // Create an intent to start your data-collection service
    private void startForegroundService() {
        LogSaver.saveLog(TAG,"d", "startForegroundService() called");
        Intent serviceIntent = new Intent(this, ForegroundSensorService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    // Stop the running service
    private void stopForegroundService() {
        LogSaver.saveLog(TAG,"d", "stopForegroundService() called");
        Intent stopIntent = new Intent(this, ForegroundSensorService.class);
        stopService(stopIntent);  // This will trigger onDestroy() inside the foreground service
    }

    // Requests the system to ignore battery optimizations for this app (e.g., Doze mode).
    public static void requestIgnoreBatteryOptimizations(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        String packageName = context.getPackageName();
        if (pm == null) {
            LogSaver.saveLog(TAG,"w", "PowerManager is null — cannot check battery optimization state");
            return;
        }
        // Check if app is already ignoring battery optimizations
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            LogSaver.saveLog(TAG,"d", "App is already ignoring battery optimizations");
            return;
        }
        try {
            // Request system to exempt this app from Doze/battery restrictions
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + packageName));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            LogSaver.saveLog(TAG,"d", "Prompted user to disable battery optimizations");
        } catch (ActivityNotFoundException e) {
            // If the system doesn’t support this intent, fallback to app settings
            LogSaver.saveLog(TAG,"e", "\"ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS not supported: "+e.getMessage());
            Intent fallbackIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            fallbackIntent.setData(Uri.parse("package:" + packageName));
            fallbackIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(fallbackIntent);
        }
    }

    // Checks permissions and prompts the user to accept
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private void checkPermissions() {
        ArrayList<String> permissionsToRequest = new ArrayList<>();

        // Check BODY_SENSORS
        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            LogSaver.saveLog(TAG,"e", "Permissions BODY_SENSORS not granted");
            permissionsToRequest.add(Manifest.permission.BODY_SENSORS);
        } else {
            LogSaver.saveLog(TAG,"d", "Permissions BODY_SENSORS already granted");
        }

        // Check POST_NOTIFICATIONS (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                LogSaver.saveLog(TAG,"e", "Permissions POST_NOTIFICATIONS not granted");
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS);
            } else {
                LogSaver.saveLog(TAG,"d", "Permissions POST_NOTIFICATIONS already granted");
            }
        }

        // Request all missing permissions in one dialog
        if (!permissionsToRequest.isEmpty()) {
            LogSaver.saveLog(TAG,"i", "Requesting missing permissions...");
            requestPermissions(permissionsToRequest.toArray(new String[0]), 100);
        } else {
            LogSaver.saveLog(TAG,"i", "All required permissions are granted");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        LogSaver.saveLog(TAG,"d", "onPause() called");
    }

    @Override
    protected void onDestroy() {
        LogSaver.saveLog(TAG,"d", "onDestroy() called");
        super.onDestroy();
    }
}
