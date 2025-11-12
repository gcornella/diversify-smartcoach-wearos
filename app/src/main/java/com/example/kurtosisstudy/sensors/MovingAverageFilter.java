package com.example.kurtosisstudy.sensors;

public class MovingAverageFilter {
    private final float[] buffer;
    private int index = 0;
    private int count = 0;
    private float sum = 0;

    public MovingAverageFilter(int windowSize) {
        buffer = new float[windowSize];
    }

    public float add(float newValue) {
        sum -= buffer[index];
        buffer[index] = newValue;
        sum += newValue;
        index = (index + 1) % buffer.length;
        if (count < buffer.length) count++;
        float avg = sum / count;
        return avg;
    }
}
