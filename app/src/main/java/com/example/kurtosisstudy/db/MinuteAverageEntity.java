package com.example.kurtosisstudy.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "minute_averages_table")
public class MinuteAverageEntity {
    @PrimaryKey @NonNull
    public String alignedMinute;    // Start of minute containing data from the following minute (e.g., 13:14:00 contains data from 13:14:01 to 13:14:59)
    public long timestamp;          // Timestamp when a new row is added to the DB
    public int average;
    public int secondary;

    public MinuteAverageEntity(@NonNull String alignedMinute, long timestamp, int average, int secondary) {
        this.alignedMinute = alignedMinute;
        this.timestamp = timestamp;
        this.average = average;
        this.secondary = secondary;
    }
}