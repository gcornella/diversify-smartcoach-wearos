package com.example.kurtosisstudy.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

/*
SensorSampleDao.java — Data Access Object
Author: Guillem Cornella (@gcornella)

Description: Defines the contract for reading and writing raw data and main outcomes of interest.
*/

@Dao
public interface SensorSampleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertAll(List<SensorSampleEntity> sensorSample);

    // Gets raw data rows between a given timestamp start and end, to calculate minute averages
    @Query("SELECT * FROM sensor_data_table WHERE timestamp > :start AND timestamp < :end")
    List<SensorSampleEntity> getkurtosisOrGMACValuesInRange(long start, long end);
}

