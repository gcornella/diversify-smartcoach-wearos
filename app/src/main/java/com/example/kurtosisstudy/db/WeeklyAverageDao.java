package com.example.kurtosisstudy.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/*
WeeklyAverageDao.java — Data Access Object
Author: Guillem Cornella (@gcornella)

Description: Defines the contract for reading and writing weekly averages.
*/

@Dao
public interface WeeklyAverageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrUpdate(WeeklyAverageEntity weeklyAverage);

    // Gets the weekly average ratio rows to calculate the weekly goal ratio
    @Query("SELECT * FROM weekly_average_table ORDER BY weekNumber ASC")
    List<WeeklyAverageEntity> getAllSortedByWeek();


}
