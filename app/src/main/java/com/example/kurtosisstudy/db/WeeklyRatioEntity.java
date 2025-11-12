package com.example.kurtosisstudy.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "weekly_ratio_table")
public class WeeklyRatioEntity {

    @PrimaryKey
    public int weekId;          // Week ID from 1 to 9
    public long timestamp;
    public float ratioValue;     // Integer goal value for the week

    public WeeklyRatioEntity(long timestamp, int weekId, float ratioValue) {
        this.timestamp = timestamp;
        this.weekId = weekId;
        this.ratioValue = ratioValue;
    }
}
