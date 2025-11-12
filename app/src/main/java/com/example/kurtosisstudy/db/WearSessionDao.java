package com.example.kurtosisstudy.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

/*
WearSessionsDao.java — Data Access Object
Author: Guillem Cornella (@gcornella)

Description: Defines the contract for reading and writing wear sessions (worn or not worn timestamp changes).
*/

@Dao
public interface WearSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(WearSessionEntity wearSession);

    // Gets the last timestamp from a given day where the watch was not worn, to notify user to war the watch
    @Query("SELECT timestamp FROM wear_sessions_table WHERE isWorn = 0 AND date = :today ORDER BY timestamp DESC LIMIT 1")
    Long getLastNotWornTimestampForDate(String today);

    // Gets all rows for a given day of worn/not-worn data to infer worn time and adjust daily goals
    @Query("SELECT * FROM wear_sessions_table WHERE date = :date ORDER BY timestamp ASC")
    List<WearSessionEntity> getSessionsForDate(String date);
}