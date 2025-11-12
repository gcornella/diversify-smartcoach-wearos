package com.example.kurtosisstudy.sensors;

public class HighPassFilter {

    // Coefficients generated from scipy.signal.butter(2, 0.1 / 25, btype='high')
    final float[] b = { 0.9911536f, -1.98230719f, 0.9911536f };
    final float[] a = { 1.0f, -1.98222893f, 0.98238545f };

    // Coefficients generated from scipy.signal.butter(2, 0.3 / 25, btype='high')
    //final float[] b = { 0.9705452f, -1.9410904f, 0.9705452f };
    //final float[] a = { 1.0f,       -1.9402046f, 0.9431226f };

    // cutoff 0.5:
    //final float[] b = { 0.9565432f, -1.9130864f, 0.9565432f };
    //final float[] a = { 1.0f, -1.9111971f, 0.9149758f };

    // History buffers (initialize once in your class)
    float[] xBuffer = new float[3];  // input history
    float[] yBuffer = new float[3];  // output history

    public float applyHighPassFilter(float input) {
        // Shift history
        xBuffer[2] = xBuffer[1];
        xBuffer[1] = xBuffer[0];
        xBuffer[0] = input;

        yBuffer[2] = yBuffer[1];
        yBuffer[1] = yBuffer[0];

        // Apply difference equation
        yBuffer[0] = b[0]*xBuffer[0] + b[1]*xBuffer[1] + b[2]*xBuffer[2]
                - a[1]*yBuffer[1] - a[2]*yBuffer[2];

        return yBuffer[0];
    }
}
