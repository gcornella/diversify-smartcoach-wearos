package com.example.kurtosisstudy.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

/*
DailyWearTimeDao.java — Data Access Object
Author: Guillem Cornella (@gcornella)

Description: Defines the contract for reading and writing daily wear time.
*/

@Dao
public interface DailyWearTimeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrUpdate(DailyWearTimeEntity dailyWearTime);

    // Gets the last row from the wear minutes table for a given day. How many minutes has the watch been worn today until now.
    @Query("SELECT * FROM daily_wear_minutes_table WHERE day = :day ORDER BY timestamp DESC LIMIT 1")
    DailyWearTimeEntity getLastEntryForDay(String day);

    // Gets all entries for a given week, to calculate the sum of worn time for a week.
    @Query("SELECT * FROM daily_wear_minutes_table WHERE week = :week ORDER BY day ASC")
    List<DailyWearTimeEntity> getEntriesForWeek(int week);
}
