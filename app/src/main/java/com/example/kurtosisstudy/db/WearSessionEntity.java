package com.example.kurtosisstudy.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "wear_sessions_table")
public class WearSessionEntity {
    @PrimaryKey
    public long timestamp;  // when the transition happened (ms)
    public String date;     // e.g., "2025-08-01" to help with day-wise queries
    public boolean isWorn;  // true = worn, false = removed

    public WearSessionEntity(long timestamp, String date, boolean isWorn) {
        this.timestamp = timestamp;
        this.date = date;
        this.isWorn = isWorn;
    }
}

