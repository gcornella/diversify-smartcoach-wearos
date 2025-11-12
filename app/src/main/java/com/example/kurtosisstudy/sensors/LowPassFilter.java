package com.example.kurtosisstudy.sensors;

/**
 * Second-order Butterworth low-pass filter.
 * Designed for 50Hz sampling rate and 2Hz cutoff.
 */
public class LowPassFilter {
    // Filter coefficients (precomputed for 50 Hz fs, 2 Hz cutoff)
    private final float b0 = 0.06745527f;
    private final float b1 = 0.13491054f;
    private final float b2 = 0.06745527f;
    private final float a1 = -1.1429805f;
    private final float a2 = 0.4128016f;

    // State (input and output history)
    private float x1 = 0, x2 = 0;
    private float y1 = 0, y2 = 0;

    /**
     * Call this method once per new input sample.
     * @param x0 the current raw input sample
     * @return the filtered output
     */
    public float filter(float x0) {
        float y0 = b0 * x0 + b1 * x1 + b2 * x2
                - a1 * y1 - a2 * y2;

        // Shift history
        x2 = x1;
        x1 = x0;
        y2 = y1;
        y1 = y0;

        return y0;
    }

    /** Optional: reset filter state (e.g., between sessions) */
    public void reset() {
        x1 = x2 = y1 = y2 = 0f;
    }
}
