package com.example.kurtosisstudy.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "study_meta_table")
public class StudyMetaEntity {
    @PrimaryKey
    public int id = 1; // Always just one row

    public long startOfStudyTimestamp;
    public String startOfStudyDate;
    public boolean baselineComputed;

    public StudyMetaEntity(long startOfStudyTimestamp, String startOfStudyDate, boolean baselineComputed) {
        this.startOfStudyTimestamp = startOfStudyTimestamp;
        this.startOfStudyDate = startOfStudyDate;
        this.baselineComputed = baselineComputed;
    }
}
