package com.example.kurtosisstudy.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

/*
WeeklyRatioDao.java — Data Access Object
Author: Guillem Cornella (@gcornella)

Description: Defines the contract for reading and writing weekly ratios.
*/

@Dao
public interface WeeklyRatioDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrUpdate(WeeklyRatioEntity weeklyRatio);

    // Gets the goal ratio for a given week, to adjust the daily goal accordingly
    @Query("SELECT * FROM weekly_ratio_table WHERE weekId = :weekId LIMIT 1")
    WeeklyRatioEntity getRatioByWeek(int weekId);
}
