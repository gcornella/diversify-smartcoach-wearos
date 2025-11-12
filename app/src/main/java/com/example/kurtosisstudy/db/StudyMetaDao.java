package com.example.kurtosisstudy.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

/*
StudyMetaDao.java — Data Access Object
Author: Guillem Cornella (@gcornella)

Description: Defines the contract for reading and writing meta data shared during all project.
*/

@Dao
public interface StudyMetaDao {

    // Insert only once — when starting the study
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(StudyMetaEntity studyMeta);

    // Update flags like baselineComputed
    @Update
    void update(StudyMetaEntity studyMeta);

    // Always retrieve the one row (id = 1)
    @Query("SELECT * FROM study_meta_table WHERE id = 1 LIMIT 1")
    StudyMetaEntity getMeta();

}