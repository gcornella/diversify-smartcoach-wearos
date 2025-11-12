package com.example.kurtosisstudy.cloud;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.LifecycleOwner;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.example.kurtosisstudy.MainActivity;
import com.example.kurtosisstudy.PrefsKeys;
import com.example.kurtosisstudy.R;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.ListResult;
import com.google.firebase.storage.StorageException;
import com.google.firebase.storage.StorageReference;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Cloud upload UI:
 * - HOTSPOT & HOME WI-FI supported (no WorkManager network constraint; UI still requires Wi-Fi).
 * - Blocks button only when RUNNING; cancels ENQUEUED job and enqueues fresh.
 * - REPLACE policy + cancelUniqueWork() to avoid getting stuck.
 * - Expedited work start; progress shown; rows marked ✅ as items complete.
 */
public class CloudStorageActivity extends AppCompatActivity {

    private static final String TAG = "CloudStorage_KurtosisStudy";

    // ---------- Config ----------
    private static int USER_ID = 1;
    private static final String UNIQUE_WORK = "upload_main_and_daily_dbs";
    private static final int PREVIOUS_DAYS_WINDOW = 20; // today + 19 prior days

    // Cooldown (hide MAIN + TODAY briefly after success)
    private static final long COOLDOWN_MINS = 2L;
    private static final long COOLDOWN_MS = COOLDOWN_MINS * 60L * 1000L;

    // ---------- UI ----------
    private LinearLayout listContainer;
    private TextView textStatus;
    private Button btnUpload, btnRefresh, btnBack;

    // Row registry: "MAIN" or "yyyy_MM_dd" -> TextView
    private final Map<String, TextView> rowViews = new LinkedHashMap<>();

    // Distinguish RUNNING vs ENQUEUED
    private volatile boolean isUploadRunning = false;
    private volatile boolean isUploadEnqueued = false;

    // Session flags to suppress replayed toasts
    private boolean uploadTriggeredThisSession = false;
    private boolean seenRunningThisSession = false;
    private boolean offlineToastShownThisEntry = false;
    private boolean hasNavigatedAfterSuccess = false;
    @Nullable private java.util.UUID thisSessionWorkId = null;


    private static final SimpleDateFormat DAY_FMT = new SimpleDateFormat("yyyy_MM_dd", Locale.US);

    private final ActivityResultLauncher<Intent> wifiPanelLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (isWifiConnected()) {
                    Toast.makeText(this, "Wi-Fi connected. Checking status…", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Wi-Fi still not connected.", Toast.LENGTH_LONG).show();
                }
                refreshPendingList();
            });

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cloud_storage);

        listContainer = findViewById(R.id.listContainer);
        textStatus = findViewById(R.id.textStatus);
        btnUpload = findViewById(R.id.btnUploadDb);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnBack = findViewById(R.id.btnBack);

        // Get User ID from prefs
        SharedPreferences prefs = getSharedPreferences(PrefsKeys.Settings.SETTINGS_PREFS, Context.MODE_PRIVATE);
        USER_ID = prefs.getInt(PrefsKeys.Settings.USER_ID, 1);
        Log.d(TAG, "Selected Cloud User is: " + USER_ID);

        // Observe the unique work
        WorkManager.getInstance(this)
                .getWorkInfosForUniqueWorkLiveData(UNIQUE_WORK)
                .observe((LifecycleOwner) this, this::onWorkInfosChanged);

        btnRefresh.setOnClickListener(v -> refreshPendingList());
        btnUpload.setOnClickListener(v -> onUploadClicked());
        btnBack.setOnClickListener(v -> finish()); // startActivity(new Intent(this, MainActivity.class))

        refreshPendingList();
    }

    // ---------- Wi-Fi state specific UI helpers ----------

    private void setNoWifiUI() {
        textStatus.setText("No Wi-Fi. Connect to upload.");
        btnUpload.setEnabled(true);
        btnUpload.setText("Connect Wi-Fi");
        btnUpload.setOnClickListener(v -> openWifiSettings());
        btnRefresh.setEnabled(true);
        if (!offlineToastShownThisEntry) {
            offlineToastShownThisEntry = true;
            Toast.makeText(this, "No Wi-Fi. Please connect to upload.", Toast.LENGTH_SHORT).show();
        }
    }

    private void setWifiCheckingUI() {
        offlineToastShownThisEntry = false; // reset when we have Wi-Fi
        textStatus.setText("Checking cloud status…");
        btnUpload.setEnabled(false);
        btnUpload.setText("Upload");
        btnUpload.setOnClickListener(v -> onUploadClicked());
    }

    // -------------------- Work state observer --------------------

    private void onWorkInfosChanged(List<WorkInfo> infos) {
        isUploadRunning = false;
        isUploadEnqueued = false;

        if (infos != null) {
            for (WorkInfo wi : infos) {
                if (wi.getState() == WorkInfo.State.RUNNING) { isUploadRunning = true; break; }
            }
            if (!isUploadRunning) {
                for (WorkInfo wi : infos) {
                    if (wi.getState() == WorkInfo.State.ENQUEUED) { isUploadEnqueued = true; break; }
                }
            }
        }

        // Prefer RUNNING for UI feedback
        WorkInfo current = null;
        if (infos != null && !infos.isEmpty()) {
            for (WorkInfo wi : infos) if (wi.getState() == WorkInfo.State.RUNNING) { current = wi; break; }
            if (current == null) for (WorkInfo wi : infos) if (wi.getState() == WorkInfo.State.ENQUEUED) { current = wi; break; }
            if (current == null) current = infos.get(0);
            Log.d(TAG, "Observer => running=" + isUploadRunning + " enqueued=" + isUploadEnqueued +
                    " state=" + current.getState() + " id=" + current.getId());
        } else {
            Log.d(TAG, "Observer => no work infos");
            return;
        }

        if (current.getState() == WorkInfo.State.RUNNING) {
            seenRunningThisSession = true;
            String itemDone = current.getProgress().getString(DatabaseUploadWorker.PROG_ITEM_DONE);
            if (!TextUtils.isEmpty(itemDone)) markRowDone(itemDone);
            int pct = current.getProgress().getInt(DatabaseUploadWorker.PROG_PERCENT, -1);
            if (pct >= 0) textStatus.setText("Uploading… " + pct + "%");
            btnUpload.setEnabled(false);

        } else if (current.getState().isFinished()) {
            btnUpload.setEnabled(true);
            String msg = current.getOutputData().getString(DatabaseUploadWorker.OUT_MESSAGE);
            boolean shouldToast = (seenRunningThisSession || uploadTriggeredThisSession);

            if (current.getState() == WorkInfo.State.SUCCEEDED) {
                textStatus.setText((msg != null) ? msg : "Upload complete.");
                if (shouldToast) Toast.makeText(this, "All items uploaded ✅", Toast.LENGTH_SHORT).show();

                // Only navigate away if THIS visit kicked off the upload
                boolean thisSessionsJob =
                        uploadTriggeredThisSession &&
                                thisSessionWorkId != null &&
                                current.getId().equals(thisSessionWorkId);

                if (thisSessionsJob && !isFinishing() && !isDestroyed() && !hasNavigatedAfterSuccess) {
                    hasNavigatedAfterSuccess = true;
                    setResult(RESULT_OK);
                    finish();          // ← go back to Main only for this session's upload
                    return;
                }

                refreshPendingList();
            } else {
                textStatus.setText((msg != null) ? msg : "Upload failed. Tap Upload to retry.");
                if (shouldToast) Toast.makeText(this, "Upload failed. Please try again.", Toast.LENGTH_LONG).show();
            }

            seenRunningThisSession = false;
            uploadTriggeredThisSession = false;
        } else {
            // ENQUEUED (waiting). Show “Ready” to allow a click that cancels+restarts.
            textStatus.setText("Ready");
            btnUpload.setEnabled(true);
        }
    }

    // -------------------- Button handler --------------------

    private void onUploadClicked() {
        // Block only if actually RUNNING
        if (isUploadRunning) {
            Toast.makeText(this, "Upload already running…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isWifiConnected()) {
            openWifiSettings();
            return;
        }

        // If something is ENQUEUED (waiting), kill it so we can start fresh
        if (isUploadEnqueued) {
            WorkManager wm0 = WorkManager.getInstance(this);
            wm0.cancelUniqueWork(UNIQUE_WORK);
            wm0.pruneWork();
        }

        uploadTriggeredThisSession = true;

        UploadTargets targets = collectTargetsFromUI();
        if (!targets.includeMain && targets.dayKeys.isEmpty()) {
            Toast.makeText(this, "Nothing to upload. Try Refresh.", Toast.LENGTH_SHORT).show();
            uploadTriggeredThisSession = false;
            return;
        }

        // No WorkManager constraints (hotspots OK). UI already requires Wi-Fi.
        Constraints constraints = new Constraints.Builder().build();

        Data input = new Data.Builder()
                .putInt(PrefsKeys.Settings.USER_ID, USER_ID)
                .putBoolean(DatabaseUploadWorker.KEY_INCLUDE_MAIN, targets.includeMain)
                .putStringArray(DatabaseUploadWorker.KEY_DAY_KEYS, targets.dayKeys.toArray(new String[0]))
                .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(DatabaseUploadWorker.class)
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(input)
                .build();

        thisSessionWorkId = req.getId();
        uploadTriggeredThisSession = true;

        WorkManager wm = WorkManager.getInstance(this);
        // Ensure nothing stale remains, then enqueue fresh with REPLACE
        wm.cancelUniqueWork(UNIQUE_WORK);
        wm.pruneWork();
        wm.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, req);

        textStatus.setText("Starting upload…");
        btnUpload.setEnabled(false);
        Log.d(TAG, "Worker enqueued: includeMain=" + targets.includeMain + ", days=" + targets.dayKeys);
    }

    /** Include MAIN only if present (hidden during cooldown). Include all visible day keys. */
    private UploadTargets collectTargetsFromUI() {
        boolean includeMain = rowViews.containsKey("MAIN");
        List<String> dayKeys = new ArrayList<>();
        for (String key : rowViews.keySet()) if (!"MAIN".equals(key)) dayKeys.add(key);
        Log.d(TAG, "collectTargetsFromUI -> includeMain=" + includeMain + " days=" + dayKeys);
        return new UploadTargets(includeMain, dayKeys);
    }

    // -------------------- List building --------------------

    private void refreshPendingList() {
        final String TAGF = TAG + "/refresh";

        if (!isWifiConnected()) {
            runOnUiThread(() -> {
                rowViews.clear();
                listContainer.removeAllViews();
                setNoWifiUI();
            });
            return;
        }

        runOnUiThread(this::setWifiCheckingUI);

        rowViews.clear();
        listContainer.removeAllViews();

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                final String appId = getPackageName();
                final String todayKey = DAY_FMT.format(new Date());

                long lastUploadMs = getSharedPreferences(PrefsKeys.Data.PREFS, MODE_PRIVATE)
                        .getLong(PrefsKeys.Data.LAST_CLOUD_UPLOAD_TIME, 0L);
                boolean inCooldown = lastUploadMs > 0 && (System.currentTimeMillis() - lastUploadMs) < COOLDOWN_MS;
                Log.d(TAGF, "lastUploadMs=" + lastUploadMs + " inCooldown=" + inCooldown);

                final long studyStartMs = getSharedPreferences(PrefsKeys.Data.PREFS, MODE_PRIVATE)
                        .getLong(PrefsKeys.Data.STUDY_START_TIME, 0L);
                Log.d(TAGF, "studyStartMs=" + studyStartMs + (studyStartMs > 0 ? " (" + new Date(studyStartMs) + ")" : " (not set)"));

                final List<String> lastNDays = getLastNDaysKeysLimited(PREVIOUS_DAYS_WINDOW, studyStartMs);
                Log.d(TAGF, "Window (limited) days: " + lastNDays);

                final Set<String> remoteDays = fetchRemoteDays(USER_ID, appId);
                final boolean hasMainRemote = fetchHasMain(USER_ID, appId);
                Log.d(TAGF, "Remote days count=" + remoteDays.size() + " hasMain=" + hasMainRemote);

                final List<RowItem> items = new ArrayList<>();

                if (!inCooldown) {
                    items.add(new RowItem("MAIN", "Main database"));
                    items.add(new RowItem(todayKey, todayKey + " (today)"));
                } else {
                    Log.d(TAGF, "Cooldown active: hiding MAIN and TODAY from the list.");
                }

                for (String d : lastNDays) {
                    if (d.equals(todayKey)) continue;
                    if (remoteDays.contains(d)) continue;
                    if (!hasLocalDaily(USER_ID, d)) continue;
                    items.add(new RowItem(d, d));
                }

                runOnUiThread(() -> {
                    buildListUI(items);
                    if (items.isEmpty()) {
                        textStatus.setText(inCooldown ? "All caught up. Databases saved recently." : "All caught up.");
                    } else {
                        textStatus.setText("Ready");
                    }
                    btnUpload.setEnabled(true);
                    btnUpload.setText("Upload");
                });

            } catch (Exception e) {
                Log.e(TAGF, "refreshPendingList failed", e);
                runOnUiThread(() -> {
                    textStatus.setText("Cloud check failed. You can still upload.");
                    btnUpload.setEnabled(true);
                    btnUpload.setText("Upload");
                });
            }
        });
    }

    /** Render rows; initial state: all ⬜ pending. ✅ appears only on worker progress. */
    private void buildListUI(List<RowItem> items) {
        listContainer.removeAllViews();
        rowViews.clear();
        for (RowItem it : items) {
            TextView tv = new TextView(this);
            tv.setTextSize(16f);
            tv.setText("⬜ " + it.label);
            listContainer.addView(tv);
            rowViews.put(it.key, tv);
            Log.d(TAG, "Row added: key=" + it.key + " text='" + tv.getText() + "'");
        }
    }

    /** Flip a row to ✅ when the worker reports that item as done. */
    private void markRowDone(String key) {
        TextView tv = rowViews.get(key);
        if (tv == null) return;
        String s = tv.getText().toString();
        if (!s.startsWith("✅ ")) tv.setText(s.replaceFirst("^⬜ ", "✅ "));
        Log.d(TAG, "Row marked done: " + key + " -> " + tv.getText());
    }

    // -------------------- Cloud helpers --------------------

    /** apps/<appId>/users/<USER_ID>/days/*.zip -> set of day keys present */
    private Set<String> fetchRemoteDays(int userId, String appId) throws Exception {
        Set<String> out = new HashSet<>();
        FirebaseStorage storage = FirebaseStorage.getInstance();
        StorageReference daysRef = storage.getReference()
                .child("apps").child(appId)
                .child("users").child(Integer.toString(userId))
                .child("days");

        ListResult page = Tasks.await(daysRef.list(1000));
        while (true) {
            for (StorageReference item : page.getItems()) {
                String name = item.getName();
                if (!name.endsWith(".zip")) continue;
                String base = name.substring(0, name.length() - 4);
                String dayKey = base;
                String p1 = "User" + userId + "_";
                if (base.startsWith(p1)) dayKey = base.substring(p1.length());
                else {
                    String p2 = userId + "_";
                    if (base.startsWith(p2)) dayKey = base.substring(p2.length());
                }
                out.add(dayKey); // yyyy_MM_dd
            }
            if (page.getPageToken() == null) break;
            page = Tasks.await(daysRef.list(1000, page.getPageToken()));
        }
        Log.d(TAG, "fetchRemoteDays -> " + out.size() + " items");
        return out;
    }

    /** apps/<appId>/users/<USER_ID>/main/main_results_db_<USER_ID>.zip exists? */
    private boolean fetchHasMain(int userId, String appId) {
        FirebaseStorage storage = FirebaseStorage.getInstance();
        StorageReference ref = storage.getReference()
                .child("apps").child(appId)
                .child("users").child(Integer.toString(userId))
                .child("main").child("main_results_db_" + userId + ".zip");
        try {
            Tasks.await(ref.getMetadata());
            return true;
        } catch (Exception ex) {
            Throwable cause = (ex instanceof java.util.concurrent.ExecutionException) ? ex.getCause() : ex;
            boolean notFound = (cause instanceof StorageException)
                    && ((StorageException) cause).getErrorCode() == StorageException.ERROR_OBJECT_NOT_FOUND;
            if (!notFound) Log.w(TAG, "fetchHasMain unexpected error", ex);
            return false;
        }
    }

    // -------------------- Local & date helpers --------------------
    private boolean hasLocalDaily(int userId, String dayKey) {
        String name = "User" + userId + "_" + dayKey;
        File base = getDatabasePath(name);
        return base != null && base.exists();
    }

    private List<String> getLastNDaysKeysLimited(int n, long studyStartMs) {
        final String earliestKey = (studyStartMs > 0L) ? DAY_FMT.format(new Date(studyStartMs)) : null;
        List<String> tmp = new ArrayList<>(n);
        Calendar cal = Calendar.getInstance(); // today
        for (int i = 0; i < n; i++) {
            String key = DAY_FMT.format(cal.getTime());
            if (earliestKey != null && key.compareTo(earliestKey) < 0) break;
            tmp.add(key);
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }
        Collections.reverse(tmp); // oldest → newest
        Log.d(TAG, "getLastNDaysKeysLimited: earliestKey=" + earliestKey + " -> " + tmp);
        return tmp;
    }

    // -------------------- System helpers --------------------

    private void openWifiSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiPanelLauncher.launch(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
            } else {
                wifiPanelLauncher.launch(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
        } catch (ActivityNotFoundException e) {
            wifiPanelLauncher.launch(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        }
    }

    private boolean isWifiConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    // -------------------- Tiny structs --------------------

    private static class RowItem {
        final String key;   // "MAIN" or "yyyy_MM_dd"
        final String label;
        RowItem(String key, String label) { this.key = key; this.label = label; }
    }

    private static class UploadTargets {
        final boolean includeMain; final List<String> dayKeys;
        UploadTargets(boolean includeMain, List<String> dayKeys) {
            this.includeMain = includeMain; this.dayKeys = dayKeys;
        }
    }
}
