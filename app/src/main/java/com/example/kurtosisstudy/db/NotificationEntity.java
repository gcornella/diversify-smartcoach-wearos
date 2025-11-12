package com.example.kurtosisstudy.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "notifications_table")
public class NotificationEntity {

    @PrimaryKey
    public long timestamp;
    public int level;
    public String message;

    public NotificationEntity(long timestamp, int level, String message) {
        this.timestamp = timestamp;
        this.level = level;
        this.message = message;
    }
}
