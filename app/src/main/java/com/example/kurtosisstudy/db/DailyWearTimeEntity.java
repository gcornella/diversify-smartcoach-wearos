package com.example.kurtosisstudy.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "daily_wear_minutes_table")
public class DailyWearTimeEntity {
    @PrimaryKey @NonNull
    public String day; // e.g. "2025-08-01"
    public int week;
    public long timestamp; // time when saved (optional)
    public int wornMinutes;
    public int notWornMinutes;

    public DailyWearTimeEntity(long timestamp, int week, @NonNull String day, int wornMinutes, int notWornMinutes) {
        this.timestamp = timestamp;
        this.week = week;
        this.day = day;
        this.wornMinutes = wornMinutes;
        this.notWornMinutes = notWornMinutes;
    }
}
