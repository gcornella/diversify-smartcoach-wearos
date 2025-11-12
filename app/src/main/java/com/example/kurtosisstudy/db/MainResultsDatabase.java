package com.example.kurtosisstudy.db;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(
    entities = {
            DailyCumulativeEntity.class,
            WeeklyAverageEntity.class,
            WeeklyRatioEntity.class,
            NotificationEntity.class,
            StudyMetaEntity.class,
            WearSessionEntity.class,
            DailyWearTimeEntity.class
    },
    version = 1,
        exportSchema = false
)
public abstract class MainResultsDatabase extends RoomDatabase {
    public abstract DailyCumulativeDao dailyCumulativeDao();
    public abstract WeeklyAverageDao weeklyAverageDao();
    public abstract WeeklyRatioDao weeklyRatioDao();
    public abstract NotificationDao notificationDao();
    public abstract StudyMetaDao studyMetaDao();
    public abstract WearSessionDao wearSessionDao();
    public abstract DailyWearTimeDao dailyWearTimeDao();
}
