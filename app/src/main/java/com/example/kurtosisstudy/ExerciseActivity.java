package com.example.kurtosisstudy;

/*
 * ExerciseActivity
 * ----------------
 * Purpose:
 *   - Shows a randomized exercise GIF with a "Done" button for quick, repeatable exercises.
 *
 * What it does:
 *   • Picks a random exercise (GIF) and avoids immediate repeats across launches.
 *   • Enforces a COOLDOWN between automatic exercise changes using SharedPreferences:
 *       - Uses wall-clock timestamps (System.currentTimeMillis()).
 *       - Will not auto-change within MIN_SWITCH_INTERVAL_MS.
 *          (MIN_SWITCH_INTERVAL_MS is the cooldown window that prevents the app from auto-picking
 *          a different exercise when you reopen/relauch the Activity too soon.)
 *       - User can ALWAYS advance immediately by pressing the "Done" button (bypasses cooldown).
 *   • Logs launch source (notification/complication/unknown) and all key actions with timestamps.
 *   • Keeps the screen awake for a SINGLE, NON-RESETTABLE window starting the first time
 *     the Activity appears (configured by KEEP_SCREEN_ON_MS; currently 3 minute).
 *       - Pressing the button or any interaction does NOT extend this window.
 *       - After the window ends, the watch may go back to the inactive watch face if idle.
 *
 * How to configure:
 *   • Change MIN_SWITCH_INTERVAL_MS to adjust the cooldown (e.g., 60_000L = 60s).
 *   • Change KEEP_SCREEN_ON_MS to control the single keep-awake duration
 *       (e.g., 5 * 60_000L for 5 minutes).
 *
 * Requirements:
 *   • Layout must include: ImageView @id/gifView and Button @id/btnDone.
 *   • Glide is used to render animated GIFs.
 *
 * Notes:
 *   • Uses FLAG_KEEP_SCREEN_ON (no wake locks).
 *   • Battery impact scales with KEEP_SCREEN_ON_MS; keep it reasonable.
 */

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;

import java.util.Random;

public class ExerciseActivity extends AppCompatActivity {
    private static final String TAG = "ExerciseActivity_KurtosisStudy";

    // Prefs & cooldown (wall-clock based)
    private static final long   MIN_SWITCH_INTERVAL_MS = 60_000L; // 60 seconds

    // Keep the screen on for a single, non-resettable 3-minute window
    private static final long KEEP_SCREEN_ON_MS = 3 * 60_000L;
    private CountDownTimer screenOnTimer;
    private boolean keepScreenOnStarted = false; // do not restart once started

    // Source logging
    public static final String EXTRA_LAUNCH_SOURCE = "launch_source";
    public static final String SOURCE_NOTIFICATION = "notification_action";
    public static final String SOURCE_COMPLICATION = "complication_tap";

    // UI
    private ImageView gifView;
    private Button btnDone;

    // State
    private int selectedIndex = -1;

    // Your drawable list
    /*private final int[] GIFS = new int[] {
            R.drawable.d_ex2, R.drawable.d_ex3, R.drawable.d_ex4,
            R.drawable.d_ex5, R.drawable.d_ex6, R.drawable.d_ex7, R.drawable.d_ex8,
            R.drawable.d_ex9, R.drawable.d_ex11
    };
    */


    private final int[] GIFS = new int[] {
            R.drawable.a_ex1, R.drawable.a_ex2, R.drawable.a_ex3, R.drawable.a_ex4,
            R.drawable.a_ex5, R.drawable.a_ex6, R.drawable.a_ex7, R.drawable.a_ex8
    };


    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_exercise);

        gifView = findViewById(R.id.gifView);
        btnDone = findViewById(R.id.btnDone);

        if (savedInstanceState != null) {
            selectedIndex = savedInstanceState.getInt("selected_index", -1);
        }

        btnDone.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            LogSaver.saveLog(TAG, "i",
                    "Exercise " + selectedIndex + " done");
            // User wants another exercise immediately: bypass cooldown.
            // NOTE: This does NOT restart the 5-minute keep-awake window.
            showNextExerciseNow();
        });

        logSource(getIntent(), "onCreate");
        maybeShowExercise();          // time-gated pick or keep
        startKeepScreenOnOnce();      // single 5-minute window from first appearance
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        logSource(intent, "onNewIntent");
        maybeShowExercise();          // still time-gated; won’t switch within cooldown
        startKeepScreenOnOnce();      // no-op if already started
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("selected_index", selectedIndex);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopKeepScreenOnTimer();
        setKeepScreenOn(false);
    }

    // ---------- Logging & source ----------

    private void logSource(Intent intent, String hook) {
        String src = intent != null ? intent.getStringExtra(EXTRA_LAUNCH_SOURCE) : null;
        int compId = (intent != null && intent.hasExtra("complication_id"))
                ? intent.getIntExtra("complication_id", -1) : -1;
        if (src == null) src = "unknown";
        LogSaver.saveLog(TAG, "i",
                "Entered via=" + src + ", compId=" + compId + " (" + hook + ")");
    }

    // ---------- Exercise selection (wall-clock timestamps) ----------
    private void maybeShowExercise() {
        SharedPreferences sp = getSharedPreferences(PrefsKeys.Exercise.EXERCISE_PREFS, MODE_PRIVATE);
        int lastIndex = sp.getInt(PrefsKeys.Exercise.LAST_INDEX, -1);
        long lastSwitch = sp.getLong(PrefsKeys.Exercise.LAST_SWITCH_TIME, 0L);
        long now = System.currentTimeMillis();

        if (lastIndex == -1) {
            selectedIndex = pickRandomIndexAvoiding(-1);
            persistSelection(sp, selectedIndex, now);
            LogSaver.saveLog(TAG, "i", "Initial pick index=" + selectedIndex);
        } else if (now - lastSwitch >= MIN_SWITCH_INTERVAL_MS) {
            selectedIndex = pickRandomIndexAvoiding(lastIndex);
            persistSelection(sp, selectedIndex, now);
            LogSaver.saveLog(TAG, "i", "Switched to index=" + selectedIndex +
                    " after " + (now - lastSwitch) + "ms (cooldown met)");
        } else {
            selectedIndex = lastIndex;
            LogSaver.saveLog(TAG, "i", "Keeping index=" + selectedIndex +
                    " (" + (MIN_SWITCH_INTERVAL_MS - (now - lastSwitch)) + "ms left");
        }

        Glide.with(this).asGif().load(GIFS[selectedIndex]).into(gifView);
    }

    /** Bypass cooldown and immediately switch to a different exercise (no timer reset). */
    private void showNextExerciseNow() {
        SharedPreferences sp = getSharedPreferences(PrefsKeys.Exercise.EXERCISE_PREFS, MODE_PRIVATE);
        int avoid = (selectedIndex >= 0) ? selectedIndex : sp.getInt(PrefsKeys.Exercise.LAST_INDEX, -1);

        selectedIndex = pickRandomIndexAvoiding(avoid);

        long now = System.currentTimeMillis();
        persistSelection(sp, selectedIndex, now);

        Glide.with(this).asGif().load(GIFS[selectedIndex]).into(gifView);
        LogSaver.saveLog(TAG, "i", "User requested next -> index=" + selectedIndex);
    }

    private void persistSelection(SharedPreferences sp, int idx, long nowMs) {
        sp.edit()
                .putInt(PrefsKeys.Exercise.LAST_INDEX, idx)
                .putLong(PrefsKeys.Exercise.LAST_SWITCH_TIME, nowMs)  // wall clock
                .apply();
    }

    private int pickRandomIndexAvoiding(int avoid) {
        int n = GIFS.length;
        if (n <= 1) return 0;
        int idx; Random r = new Random();
        do { idx = r.nextInt(n); } while (idx == avoid);
        return idx;
    }

    // ---------- Keep screen on for a single 5-minute window ----------

    /** Start the 5-minute keep-awake window exactly once per Activity lifetime. */
    private void startKeepScreenOnOnce() {
        if (keepScreenOnStarted) return;
        keepScreenOnStarted = true;

        setKeepScreenOn(true);
        if (screenOnTimer != null) screenOnTimer.cancel();
        screenOnTimer = new CountDownTimer(KEEP_SCREEN_ON_MS, 1000) {
            public void onTick(long ms) { /* no-op */ }
            public void onFinish() { setKeepScreenOn(false); }
        }.start();
    }

    private void stopKeepScreenOnTimer() {
        if (screenOnTimer != null) {
            screenOnTimer.cancel();
            screenOnTimer = null;
        }
    }

    private void setKeepScreenOn(boolean keep) {
        Window w = getWindow();
        if (keep) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
