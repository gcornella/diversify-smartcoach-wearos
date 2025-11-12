package com.example.kurtosisstudy.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "sensor_data_table")
public class SensorSampleEntity {
    @PrimaryKey
    public long timestamp;
    public float accelX;
    public float accelY;
    public float accelZ;
    public float angle;
    public float inclination;
    public float std;
    public float rawKurtosis;
    public float rawGMAC;
    public int kurtosis;
    public int activity;

    public SensorSampleEntity(long timestamp, float accelX, float accelY, float accelZ, float angle, float inclination, float std, float rawKurtosis, float rawGMAC, int kurtosis, int activity) {
        this.timestamp = timestamp;
        this.accelX = accelX;
        this.accelY = accelY;
        this.accelZ = accelZ;
        this.angle = angle;
        this.inclination = inclination;
        this.std = std;
        this.rawKurtosis = rawKurtosis;
        this.rawGMAC = rawGMAC;
        this.kurtosis = kurtosis;
        this.activity = activity;
    }
}