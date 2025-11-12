package com.example.kurtosisstudy.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

/*
AdjustedDailyGoalDao.java — Data Access Object
Author: Guillem Cornella (@gcornella)

Description: Defines the contract for reading and writing adjusted daily goal data.
*/

@Dao
public interface AdjustedDailyGoalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(AdjustedDailyGoalEntity adjustedDailyGoal);

    // Gets the last adjusted daily goal row from the table
    @Query("SELECT * FROM adjusted_daily_goal_table ORDER BY timestamp DESC LIMIT 1")
    AdjustedDailyGoalEntity getLastGoal();

    // Gets the last adjusted daily goal row from the table but in LiveData form for the MainActivity
    @Query("SELECT * FROM adjusted_daily_goal_table ORDER BY timestamp DESC LIMIT 1")
    LiveData<AdjustedDailyGoalEntity> getLastGoalLive();
}

