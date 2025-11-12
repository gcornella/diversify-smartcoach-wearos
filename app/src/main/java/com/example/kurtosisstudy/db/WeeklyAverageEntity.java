package com.example.kurtosisstudy.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "weekly_average_table")
public class WeeklyAverageEntity {

    @PrimaryKey
    public int weekNumber;
    public long timestamp;
    public float goalAchieved; // Whether the participant met the goal

    public WeeklyAverageEntity(long timestamp, int weekNumber, float goalAchieved) {
        this.timestamp = timestamp;
        this.weekNumber = weekNumber;
        this.goalAchieved = goalAchieved;
    }
}
