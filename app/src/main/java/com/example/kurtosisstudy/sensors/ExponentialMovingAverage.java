package com.example.kurtosisstudy.sensors;

/**
 * Efficient Exponential Moving Average filter.
 * Use to smooth sensor signals like acceleration magnitude.
 */
public class ExponentialMovingAverage {
    private final float alpha;      // Smoothing factor (0 < alpha ≤ 1)
    private float average = 0f;     // Smoothed output
    private boolean initialized = false;

    /**
     * Create an EMA filter.
     * @param alpha Smoothing factor (e.g., 0.1f = smooth; 0.5f = reactive)
     */
    public ExponentialMovingAverage(float alpha) {
        if (alpha <= 0 || alpha > 1) {
            throw new IllegalArgumentException("Alpha must be in (0, 1]");
        }
        this.alpha = alpha;
    }

    /**
     * Add a new value and update the moving average.
     * @param newValue New sensor input
     * @return Updated smoothed value
     */
    public float add(float newValue) {
        if (!initialized) {
            average = newValue;
            initialized = true;
        } else {
            average = alpha * newValue + (1 - alpha) * average;
        }
        return average;
    }

}
