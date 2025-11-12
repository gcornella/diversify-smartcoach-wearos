package com.example.kurtosisstudy.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "adjusted_daily_goal_table")
public class AdjustedDailyGoalEntity {
    @PrimaryKey
    public long timestamp;          
    public int week;
    public String day;
    public int adjustedDailyGoal;

    public AdjustedDailyGoalEntity(long timestamp, int week, String day, int adjustedDailyGoal) {
        this.timestamp = timestamp;
        this.week = week;
        this.day = day;
        this.adjustedDailyGoal = adjustedDailyGoal;
    }
}