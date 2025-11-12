package com.example.kurtosisstudy.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "daily_cumulative_table")
public class DailyCumulativeEntity {
    @PrimaryKey @NonNull
    public long timestamp;

    public String day;          // Format "yyyy_MM_dd"
    public int week;
    public int cumulative;      // Computed from minute averages
    public int secondaryCumulative;

    public DailyCumulativeEntity(@NonNull String day,
                                 long timestamp,
                                 int week,
                                 int cumulative,
                                 int secondaryCumulative
                            ) {
        this.day = day;
        this.timestamp = timestamp;
        this.week = week;
        this.cumulative = cumulative;
        this.secondaryCumulative = secondaryCumulative;
    }
}
