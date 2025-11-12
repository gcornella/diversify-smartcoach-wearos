package com.example.kurtosisstudy.db;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(
    entities = {
            SensorSampleEntity.class,
            MinuteAverageEntity.class,
            AdjustedDailyGoalEntity.class,
            LogsEntity.class,
    },
    version = 1,
    exportSchema = false
)
public abstract class DailyDatabase extends RoomDatabase {
    public abstract SensorSampleDao sensorSampleDao();
    public abstract MinuteAverageDao minuteAverageDao();
    public abstract AdjustedDailyGoalDao adjustedDailyGoalDao();
    public abstract LogsDao logsDao();
}

