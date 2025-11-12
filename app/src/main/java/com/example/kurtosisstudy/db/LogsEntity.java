package com.example.kurtosisstudy.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "logs_table")
public class LogsEntity {
    @PrimaryKey
    public long timestamp;
    public String tag;
    public String log;

    public LogsEntity(long timestamp, String tag, String log) {
        this.timestamp = timestamp;
        this.tag = tag;
        this.log = log;
    }
}