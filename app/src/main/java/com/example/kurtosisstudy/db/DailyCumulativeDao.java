package com.example.kurtosisstudy.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

/*
DailyCumulativeDao.java — Data Access Object
Author: Guillem Cornella (@gcornella)

Description: Defines the contract for reading and writing daily cumulative scores.
*/

@Dao
public interface DailyCumulativeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(DailyCumulativeEntity dailyCumulative);

    // Gets the last daily cumulative row from a given day but in LiveData form for the MainActivity
    @Query("SELECT * FROM daily_cumulative_table WHERE day = :todayDate ORDER BY timestamp DESC LIMIT 1")
    LiveData<DailyCumulativeEntity> getLastEntryForDayLive(String todayDate);

    // Gets the last daily cumulative row from a given day
    @Query("SELECT * FROM daily_cumulative_table WHERE day = :day ORDER BY timestamp DESC LIMIT 1")
    DailyCumulativeEntity getLastEntryForDay(String day);

    // Gets the first cumulative value after a given timestamp (or last since it's in reverse order)
    // This tells use the cumulative value exactly at 30 minutes before the notification
    @Query("SELECT cumulative FROM daily_cumulative_table WHERE timestamp <= :targetTime ORDER BY timestamp DESC LIMIT 1")
    Integer getClosestEntryBefore(long targetTime);

    // Gets the closest daily cumulative row to a timestamp for a given day (it will be the row where the worn slope starts)
    @Query("SELECT * FROM daily_cumulative_table WHERE timestamp <= :targetTimestamp AND day = :day ORDER BY timestamp DESC LIMIT 1")
    DailyCumulativeEntity getClosestBefore(long targetTimestamp, String day);


}
