package com.example.kurtosisstudy.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;

/*
NotificationsDao.java — Data Access Object
Author: Guillem Cornella (@gcornella)

Description: Defines the contract for writing notifications.
*/

@Dao
public interface NotificationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(NotificationEntity notification);
}
