package com.example.kurtosisstudy.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

/*
MinuteAverageDao.java — Data Access Object
Author: Guillem Cornella (@gcornella)

Description: Defines the contract for reading and writing the averages (in seconds) per every minute.
For instance, minute 08:31 am can have an average of 41 seconds of active time
*/

@Dao
public interface MinuteAverageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrUpdate(MinuteAverageEntity minuteAverage);

    // Gets all the minute averages rows to calculate the cumulative (in minutes)
    @Query("SELECT * FROM minute_averages_table")
    List<MinuteAverageEntity> getAllMinuteAverages();

    // Gets all the minute averages (in seconds) to calculate the cumulative (in minutes) from the previous 30 minutes to detect active or not active window
    @Query("SELECT average FROM minute_averages_table WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    List<Integer> getMinuteAveragesSince(long startTime);
}

