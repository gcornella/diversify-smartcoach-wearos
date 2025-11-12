package com.example.kurtosisstudy.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;

/*
LogsDao.java — Data Access Object
Author: Guillem Cornella (@gcornella)

Description: Defines the contract for writing logs for debugging.
*/

@Dao
public interface LogsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(LogsEntity log);
}