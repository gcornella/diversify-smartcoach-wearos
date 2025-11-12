package com.example.kurtosisstudy;

import android.util.Log;

import com.example.kurtosisstudy.db.DailyDatabase;
import com.example.kurtosisstudy.db.LogsDao;
import com.example.kurtosisstudy.db.LogsEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LogSaver {
    private static final ExecutorService logExecutor = Executors.newSingleThreadExecutor();
    private static final List<LogsEntity> buffer = new ArrayList<>();
    private static boolean isFlushing = false;

    private static final String TAG = "LogSaver_KurtosisStudy";

    public static void saveLog(String tag, String level, String message) {
        if (level == null) level = "d";
        switch (level) {
            case "e": Log.e(tag, message); break;
            case "w": Log.w(tag, message); break;
            case "i": Log.i(tag, message); break;
            default:  Log.d(tag, message); break;
        }

        logExecutor.execute(() -> {
            DailyDatabase db = DataStorageManager.getDailyDatabase();
            LogsEntity entry = new LogsEntity(System.currentTimeMillis(), tag, message);

            if (db == null || !db.isOpen()) {
                // Buffer the log
                synchronized (buffer) {
                    buffer.add(entry);
                }
                return;
            }

            try {
                flushBufferedLogs(db);
                db.logsDao().insert(entry);
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to save log: " + e.getMessage(), e);
            }
        });
    }

    private static void flushBufferedLogs(DailyDatabase db) {
        if (isFlushing) return;
        isFlushing = true;
        try {
            synchronized (buffer) {
                if (!buffer.isEmpty()) {
                    LogsDao dao = db.logsDao();
                    for (LogsEntity entry : buffer) {
                        dao.insert(entry);
                    }
                    buffer.clear();
                    Log.d(TAG, "✅ Flushed buffered logs.");
                }
            }
        } finally {
            isFlushing = false;
        }
    }
}
