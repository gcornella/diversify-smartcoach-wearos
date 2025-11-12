package com.example.kurtosisstudy.cloud;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.content.pm.ServiceInfo;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.kurtosisstudy.PrefsKeys;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageException;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uploads main + daily DBs; robust to missing days; foreground + expedited friendly.
 *  - Main: always overwrite.
 *  - Daily: idempotent via SHA; skip identical.
 *  - Safe metadata probes (404 -> absent, no throw).
 *  - Per-day try/catch so one bad day doesn't fail the run.
 *  - Prunes local daily DBs >14 days old only if cloud has the zip.
 */
public class DatabaseUploadWorker extends Worker {

    private static final String TAG = "CloudStorage_KurtosisStudy";

    // ---- Inputs from enqueue() ----
    public static final String KEY_INCLUDE_MAIN = "include_main";    // boolean
    public static final String KEY_DAY_KEYS = "day_keys";        // String[] of yyyy_MM_dd

    // ---- Progress keys ----
    public static final String PROG_ITEM_DONE = "progress_item_done"; // "MAIN" or yyyy_MM_dd
    public static final String PROG_PERCENT = "progress_percent";   // 0..100

    // ---- Output keys ----
    public static final String OUT_MESSAGE = "out_message";

    // ---- Formats ----
    private static final SimpleDateFormat TS_FMT  = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    private static final SimpleDateFormat DAY_FMT = new SimpleDateFormat("yyyy_MM_dd", Locale.US);

    // ---- Pruning policy ----
    private static final int AGE_DAYS = 14;

    // Pattern: User{digits}_{yyyy_MM_dd}
    private static final Pattern USER_DAY_PATTERN =
            Pattern.compile("^User(\\d+)_([0-9]{4}_[0-9]{2}_[0-9]{2})$");

    // Foreground notification
    private static final int NOTIF_ID = 8412;
    private static final String NOTIF_CHANNEL = "uploads";

    public DatabaseUploadWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull @Override
    public Result doWork() {
        try {
            setForegroundAsync(makeForegroundInfo("Uploading databases", "Preparing…"));

            final int userIdInt = getInputData().getInt(PrefsKeys.Settings.USER_ID, 1);
            final String userId = Integer.toString(userIdInt);
            final boolean includeMain = getInputData().getBoolean(KEY_INCLUDE_MAIN, true);
            final String[] dayKeysArr = getInputData().getStringArray(KEY_DAY_KEYS);
            final java.util.List<String> dayKeys =
                    (dayKeysArr != null) ? Arrays.asList(dayKeysArr) : java.util.Collections.emptyList();

            Log.d(TAG, "Starting upload. userId=" + userId + " includeMain=" + includeMain + " days=" + dayKeys);

            // Ensure Firebase auth
            if (FirebaseAuth.getInstance().getCurrentUser() == null) {
                Tasks.await(FirebaseAuth.getInstance().signInAnonymously());
                Log.d(TAG, "Signed in anonymously to Firebase.");
            }

            final String appId = getApplicationContext().getPackageName();
            final String deviceId = Settings.Secure.getString(
                    getApplicationContext().getContentResolver(), Settings.Secure.ANDROID_ID);
            final FirebaseStorage storage = FirebaseStorage.getInstance();
            final StorageReference rootRef = storage.getReference();

            final int total = (includeMain ? 1 : 0) + dayKeys.size();
            int done = 0;

            // ------------------ MAIN (always overwrite) ------------------
            if (includeMain) {
                final String mainDbName = "main_results_db_" + userId;
                final File mainDb = getApplicationContext().getDatabasePath(mainDbName);
                final File wal = new File(mainDb.getPath() + "-wal");
                final File shm = new File(mainDb.getPath() + "-shm");

                if (mainDb.exists()) {
                    final String ts = TS_FMT.format(new Date());
                    final File meta = writeMetaJson(ts, deviceId, mainDbName, "MAIN", userId, appId, true);
                    final File zip = new File(getApplicationContext().getCacheDir(),
                            "main_backup_" + userId + "_" + ts + ".zip");

                    zipFiles(zip,
                            new ZipSource(mainDb, "db/" + mainDb.getName()),
                            wal.exists() ? new ZipSource(wal, "db/" + wal.getName()) : null,
                            shm.exists() ? new ZipSource(shm, "db/" + shm.getName()) : null,
                            new ZipSource(meta, "meta.json")
                    );

                    final String objectPath = "apps/" + appId + "/users/" + userId + "/main/" + mainDbName + ".zip";
                    Log.d(TAG, "MAIN objectPath: " + objectPath);
                    final StorageReference ref = rootRef.child(objectPath);
                    final StorageMetadata metaUp = new StorageMetadata.Builder()
                            .setCustomMetadata("note", "ALWAYS_OVERWRITE")
                            .setCustomMetadata("dbName", mainDbName)
                            .setCustomMetadata("isFinal", "true")
                            .setCustomMetadata("deviceId", deviceId)
                            .setCustomMetadata("timestamp", ts)
                            .build();

                    setForegroundAsync(makeForegroundInfo("Uploading databases", "Uploading main…"));
                    UploadTask ut = ref.putFile(Uri.fromFile(zip), metaUp);
                    Tasks.await(ut);
                    Log.d(TAG, "MAIN uploaded.");
                } else {
                    Log.w(TAG, "MAIN DB missing locally: " + mainDbName);
                }

                done++;
                setProgressAsync(new Data.Builder()
                        .putString(PROG_ITEM_DONE, "MAIN")
                        .putInt(PROG_PERCENT, percent(done, total))
                        .build());
            }

            // ------------------ DAILY items (idempotent by SHA) ------------------
            for (String dayKey : dayKeys) {
                try {
                    final String dailyDbName = "User" + userId + "_" + dayKey;
                    final File db = getApplicationContext().getDatabasePath(dailyDbName);
                    final File wal = new File(db.getPath() + "-wal");
                    final File shm = new File(db.getPath() + "-shm");

                    if (!db.exists()) {
                        Log.w(TAG, "Daily DB missing locally, skip: " + dailyDbName);
                        continue;
                    }

                    final String ts = TS_FMT.format(new Date());
                    final boolean isFinal = true; // explicit user press = snapshot intent
                    final File meta = writeMetaJson(ts, deviceId, dailyDbName, dayKey, userId, appId, isFinal);
                    final File zip = new File(getApplicationContext().getCacheDir(),
                            "room_backup_" + userId + "_" + dayKey + "_" + ts + ".zip");

                    zipFiles(zip,
                            new ZipSource(db, "db/" + db.getName()),
                            wal.exists() ? new ZipSource(wal, "db/" + wal.getName()) : null,
                            shm.exists() ? new ZipSource(shm, "db/" + shm.getName()) : null,
                            new ZipSource(meta, "meta.json")
                    );

                    final String sha256 = sha256(zip);
                    final String objectPath = "apps/" + appId + "/users/" + userId + "/days/" + dailyDbName + ".zip";
                    Log.d(TAG, "DAILY objectPath: " + objectPath);
                    final StorageReference ref = rootRef.child(objectPath);

                    StorageMetadata rmeta = getMetadataSafe(ref); // 404 -> null
                    String remoteSha = (rmeta != null) ? rmeta.getCustomMetadata("contentSha256") : null;

                    if (!sha256.equals(remoteSha)) {
                        final StorageMetadata up = new StorageMetadata.Builder()
                                .setCustomMetadata("contentSha256", sha256)
                                .setCustomMetadata("isFinal", String.valueOf(isFinal))
                                .setCustomMetadata("deviceId", deviceId)
                                .setCustomMetadata("dbName", dailyDbName)
                                .setCustomMetadata("dayKey", dayKey)
                                .setCustomMetadata("userId", userId)
                                .setCustomMetadata("filename", dailyDbName + ".zip")
                                .setCustomMetadata("timestamp", ts)
                                .build();

                        setForegroundAsync(makeForegroundInfo("Uploading databases", "Uploading " + dayKey + "…"));
                        UploadTask ut = ref.putFile(Uri.fromFile(zip), up);
                        Tasks.await(ut);
                        Log.d(TAG, "Daily uploaded: " + dayKey);
                    } else {
                        Log.d(TAG, "Skip identical daily: " + dayKey);
                    }
                } catch (Exception dayEx) {
                    Log.e(TAG, "Daily upload failed for " + dayKey + " (skipping)", dayEx);
                } finally {
                    done++;
                    setProgressAsync(new Data.Builder()
                            .putString(PROG_ITEM_DONE, dayKey)
                            .putInt(PROG_PERCENT, percent(done, total))
                            .build());
                }
            }

            // Record last successful upload time
            getApplicationContext()
                    .getSharedPreferences(PrefsKeys.Data.PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(PrefsKeys.Data.LAST_CLOUD_UPLOAD_TIME, System.currentTimeMillis())
                    .apply();

            // ------------------ GLOBAL PRUNE ------------------
            try {
                pruneLocalDailyDatabasesOlderThanAgeCloudSafe(
                        getApplicationContext(),
                        storage,
                        appId,
                        AGE_DAYS
                );
            } catch (Exception e) {
                Log.e(TAG, "Pruning step failed (skipped): ", e);
            }

            return Result.success(new Data.Builder()
                    .putString(OUT_MESSAGE, "Upload complete. All items saved to cloud ✅")
                    .build());

        } catch (Exception e) {
            Log.e(TAG, "Upload failed", e);
            return Result.failure(new Data.Builder()
                    .putString(OUT_MESSAGE, "Upload failed: " + e.getMessage())
                    .build());
        }
    }

    // ================== PRUNING (age-based across ALL users, cloud-safe) ==================

    private void pruneLocalDailyDatabasesOlderThanAgeCloudSafe(
            Context ctx,
            FirebaseStorage storage,
            String appId,
            int ageDays
    ) throws Exception {

        File dbDir = ctx.getDatabasePath("dummy").getParentFile();
        if (dbDir == null || !dbDir.exists()) return;

        File[] entries = dbDir.listFiles((dir, name) ->
                name.startsWith("User") && !name.endsWith("-wal") && !name.endsWith("-shm")
        );
        if (entries == null || entries.length == 0) return;

        Calendar today = Calendar.getInstance();
        zeroTime(today);
        Calendar cutoff = (Calendar) today.clone();
        cutoff.add(Calendar.DAY_OF_YEAR, -(ageDays - 1)); // keep today..today-(ageDays-1)

        for (File db : entries) {
            final String fileName = db.getName(); // e.g., "User7_2025_09_05"
            Matcher m = USER_DAY_PATTERN.matcher(fileName);
            if (!m.matches()) continue;

            final String uid = m.group(1);      // "7"
            final String dayKey = m.group(2);   // "2025_09_05"
            Date dayDate = parseDay(dayKey);
            if (dayDate == null) {
                Log.w(TAG, "Skip prune (unparseable dayKey): " + fileName);
                continue;
            }

            Calendar dayCal = Calendar.getInstance();
            dayCal.setTime(dayDate);
            zeroTime(dayCal);

            if (dayCal.before(cutoff)) {
                final String objectPath = "apps/" + appId + "/users/" + uid + "/days/" + fileName + ".zip";
                Log.d(TAG, "PRUNE check objectPath: " + objectPath);
                StorageReference ref = storage.getReference().child(objectPath);
                boolean existsInCloud = objectExists(ref); // 404 -> false

                if (!existsInCloud) {
                    Log.w(TAG, "Skip delete (not found in cloud): " + fileName);
                    continue;
                }

                // Safe deletes (missing files OK)
                File wal = new File(db.getPath() + "-wal");
                File shm = new File(db.getPath() + "-shm");

                boolean okDb  = !db.exists()  || db.delete();
                boolean okWal = !wal.exists() || wal.delete();
                boolean okShm = !shm.exists() || shm.delete();

                Log.i(TAG, "Pruned local daily DB: " + fileName +
                        " (db=" + okDb + ", wal=" + okWal + ", shm=" + okShm + ")");
            }
        }
    }

    private static void zeroTime(Calendar cal) {
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
    }

    private static Date parseDay(String dayKey) {
        if (dayKey == null) return null;
        try { return DAY_FMT.parse(dayKey); }
        catch (ParseException e) { return null; }
    }

    // ================== Helpers ==================

    private static int percent(int done, int total) {
        if (total <= 0) return 100;
        return Math.min(100, Math.max(0, (int)Math.round(100.0 * done / total)));
    }

    private File writeMetaJson(String ts, String deviceId, String dbName,
                               String dayKey, String userId, String appId, boolean isFinal) throws Exception {
        JSONObject meta = new JSONObject();
        meta.put("timestamp", ts);
        meta.put("deviceId", deviceId);
        meta.put("dbName", dbName);
        meta.put("dayKey", dayKey);
        meta.put("userId", userId);
        meta.put("appId", appId);
        meta.put("isFinal", isFinal);

        File f = new File(getApplicationContext().getCacheDir(),
                "meta_" + userId + "_" + dayKey + "_" + ts + ".json");
        try (FileWriter fw = new FileWriter(f)) { fw.write(meta.toString()); }
        return f;
    }

    private void zipFiles(File outZip, ZipSource... sources) throws Exception {
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(outZip)))) {
            byte[] buf = new byte[8192];
            for (ZipSource src : sources) {
                if (src == null || src.file == null || !src.file.exists()) continue;
                try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(src.file))) {
                    java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(src.entryName);
                    entry.setTime(src.file.lastModified());
                    zos.putNextEntry(entry);
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        zos.write(buf, 0, read);
                    }
                    zos.closeEntry();
                }
            }
        }
    }

    private static class ZipSource {
        final File file; final String entryName;
        ZipSource(File f, String name) { this.file = f; this.entryName = name; }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) md.update(buf, 0, n);
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Safe metadata getter: returns null on 404 (object not found). */
    private StorageMetadata getMetadataSafe(StorageReference ref) {
        try {
            return Tasks.await(ref.getMetadata());
        } catch (Exception ex) {
            Throwable c = (ex instanceof java.util.concurrent.ExecutionException) ? ex.getCause() : ex;
            if (c instanceof StorageException &&
                    ((StorageException) c).getErrorCode() == StorageException.ERROR_OBJECT_NOT_FOUND) {
                return null; // missing is normal
            }
            Log.e(TAG, "getMetadataSafe unexpected", ex);
            return null;
        }
    }

    private boolean objectExists(StorageReference ref) {
        return getMetadataSafe(ref) != null;
    }



    private ForegroundInfo makeForegroundInfo(String title, String text) {
        Context ctx = getApplicationContext();

        // Channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    NOTIF_CHANNEL, "Cloud uploads", NotificationManager.IMPORTANCE_LOW
            );
            ch.enableLights(false);
            ch.enableVibration(false);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }

        Notification notif = new NotificationCompat.Builder(ctx, NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .build();

        // IMPORTANT: include a foreground service TYPE (data sync suits uploads)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            );
        } else {
            return new ForegroundInfo(NOTIF_ID, notif);
        }
    }



}
