package com.example.kurtosisstudy;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.kurtosisstudy.complications.MyComplicationProviderService;
import com.example.kurtosisstudy.complications.MyProgressComplicationProviderService;
import com.example.kurtosisstudy.complications.MyServiceAliveCheckComplicationProviderService;
import com.example.kurtosisstudy.complications.MyWearTimeComplicationProviderService;
import com.example.kurtosisstudy.sensors.WatchWearDetector;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity_KurtosisStudy";

    public static final String EXTRA_ADMIN_OK = "admin_ok";

    // Bounds for the horizontal stepper
    private static final int MIN_ID = 1;
    private static final int MAX_ID = 50;

    private WatchWearDetector wearDetector;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Simple gate: only proceed if MainActivity granted access
        if (!getIntent().getBooleanExtra(EXTRA_ADMIN_OK, false)) {
            Log.w(TAG, "Admin flag missing — finishing.");
            finish();
            return;
        }

        setContentView(R.layout.activity_settings);

        // --- Bind views (horizontal stepper instead of NumberPicker) ---
        TextView idValue = findViewById(R.id.textUserIdValue);
        Button btnMinus = findViewById(R.id.btnUserIdMinus);
        Button btnPlus = findViewById(R.id.btnUserIdPlus);
        RadioGroup group = findViewById(R.id.groupHand);
        RadioButton radioLeft = findViewById(R.id.radioLeft);
        RadioButton radioRight = findViewById(R.id.radioRight);
        Button btnSave = findViewById(R.id.btnSave);

        // --- Load current values from prefs ---
        SharedPreferences prefs = getSharedPreferences(PrefsKeys.Settings.SETTINGS_PREFS, MODE_PRIVATE);
        int currentId = prefs.getInt(PrefsKeys.Settings.USER_ID, MIN_ID);
        if (currentId < MIN_ID || currentId > MAX_ID) currentId = MIN_ID;
        idValue.setText(String.valueOf(currentId));

        String currentHand = prefs.getString(PrefsKeys.Settings.HANDEDNESS, "right");
        if ("left".equalsIgnoreCase(currentHand)) {
            radioLeft.setChecked(true);
        } else {
            radioRight.setChecked(true);
        }

        // --- Stepper behavior ---
        btnMinus.setOnClickListener(v -> {
            int vNow = safeInt(idValue.getText().toString(), MIN_ID);
            if (vNow > MIN_ID) {
                vNow--;
                idValue.setText(String.valueOf(vNow));
            }
        });

        btnPlus.setOnClickListener(v -> {
            int vNow = safeInt(idValue.getText().toString(), MIN_ID);
            if (vNow < MAX_ID) {
                vNow++;
                idValue.setText(String.valueOf(vNow));
            }
        });

        // --- Save ---
        btnSave.setOnClickListener(v -> {
            int chosenId = safeInt(idValue.getText().toString(), MIN_ID);
            if (chosenId < MIN_ID) chosenId = MIN_ID;
            if (chosenId > MAX_ID) chosenId = MAX_ID;

            String chosenHand =
                    (group.getCheckedRadioButtonId() == R.id.radioLeft) ? "left" : "right";

            prefs.edit()
                    .putInt(PrefsKeys.Settings.USER_ID, chosenId)
                    .putString(PrefsKeys.Settings.HANDEDNESS, chosenHand)
                    .apply();

            Log.d(TAG, "Saved settings: user_id=" + chosenId + ", handedness=" + chosenHand);
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();

            // Restart everything with these new settings
            stopService(new Intent(this, ForegroundSensorService.class));

            // after saving prefs and stopping the service if you want
            setResult(RESULT_OK, new Intent().putExtra("changed_user", true));
            finish(); // Close and go back to Main without creating a new activity
            //startActivity(new Intent(this, MainActivity.class));

        });
    }

    private int safeInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return fallback; }
    }
}
