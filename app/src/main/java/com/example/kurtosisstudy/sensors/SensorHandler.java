package com.example.kurtosisstudy.sensors;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;


import com.example.kurtosisstudy.ComputationManager;
import com.example.kurtosisstudy.DataStorageManager;
import com.example.kurtosisstudy.LogSaver;

import java.util.Arrays;

/*
 * SensorHandler
 * -------------
 * Purpose:
 *   - Runs the accelerometer at ~50 Hz on a background thread and feeds real-time GMAC/ADEM logic.
 *
 * What it does:
 *   • Starts a dedicated HandlerThread and registers the accelerometer with SENSOR_DELAY_FASTEST
 *     (20_000 µs ≈ 50 Hz), keeping callbacks off the main/UI thread.
 *   • For each sample:
 *       - Stores timestamp + raw XYZ into circular buffers (3000-sample rolling window).
 *       - Computes wrist orientation angle from gravity vector and saves it.
 *       - Calls ComputationManager.computeGMAC(...) to get u_GMAC, raw GMAC, and inclination.
 *       - Updates rolling mean GMAC via computeActivityMean(...).
 *       - Calls ComputationManager.computeKurtosis(...) to update rolling mean/std/kurtosis
 *         and binary u_kurtosis based on the window (using popped + new angle).
 *   • When the buffer wraps (window full), clones all buffers and sends them to
 *     DataStorageManager.snapshotAndSaveBuffers(...) for batched DB writes.
 *   • Monitors per-sample compute time and logs when processing exceeds 15 ms.
 *   • stop():
 *       - Unregisters the sensor and posts resetState() to clear buffers and rolling stats.
 *   • shutdown():
 *       - Calls stop(), then quits and joins the HandlerThread for a clean teardown.
 */

// Handles sensor registration and streams data at 50 Hz in background.
public class SensorHandler implements SensorEventListener {

    // Tag used for logging/debugging
    private static final String TAG = "SensorHandler_KurtosisStudy";

    // HandlerThread runs a background thread with its own Looper
    private final HandlerThread thread;

    // Create a sensorManager to handle accelerometer updates using SENSOR_DELAY_FASTEST (20000 µs = 50 Hz).
    private final SensorManager sensorManager;

    // Define the accelerometer sensor
    private final Sensor accelSensor;

    // Handler schedules sensor callbacks on that thread (instead of the main/UI thread).
    private final Handler handler;

    // Prevents multiple registrations if .start() is called again.
    private volatile boolean isRunning = false;

    private static final int WINDOW_SIZE = 3000;
    private final float[] circularBufferAngle = new float[WINDOW_SIZE];
    private final float[] circularBufferInclination = new float[WINDOW_SIZE];
    private final float[] circularBufferRawKurtosis = new float[WINDOW_SIZE];
    private final float[] circularBufferRawGMAC = new float[WINDOW_SIZE];
    private final int[] circularBufferKurtosis = new int[WINDOW_SIZE];
    private final int[] circularBufferGMAC = new int[WINDOW_SIZE];
    private final float[] circularBufferSTD = new float[WINDOW_SIZE];


    private int currentIndex = 0;
    public int iter = 0;

    // Other buffers to save
    private final long[] circularBufferTimestamps = new long[WINDOW_SIZE];
    private final float[] circularBufferX = new float[WINDOW_SIZE];
    private final float[] circularBufferY = new float[WINDOW_SIZE];
    private final float[] circularBufferZ = new float[WINDOW_SIZE];

    // Orientation variables
    public float _mean = 0.0000f;
    public float _M2 = 0.0000f;
    public float _stdDeg = 0.0000f;
    public float _M3 = 0.0000f;
    public float _M4 = 0.0000f;
    public float _kurtosis = 0.0000f;
    float poppedValueAngle = -1;
    int u_kurtosis = 0;

    // GMAC rolling algorithm for previous minute activity variables
    public float _meanGMAC = 0.0000f;
    float poppedValueGMAC = -1;
    int u_GMAC = 0;

    public SensorHandler(Context context) {
        thread = new HandlerThread("SensorThread");
        thread.start();
        handler = new Handler(thread.getLooper());
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        // Measures the acceleration force in m/s2 that is applied to a device on all three physical axes (x, y, and z), including the force of gravity.
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    public void start() {
        if (!isRunning && accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, 20000, handler);
            isRunning = true;
            LogSaver.saveLog(TAG,"d", "Accelerometer registered and started");
        }
    }

    public void stop() {
        if (isRunning) {
            sensorManager.unregisterListener(this);
            isRunning = false;
            LogSaver.saveLog(TAG,"d", "Accelerometer unregistered and stopped");
        }
        // Ensure no in-flight sensor callbacks are mutating state while we clear it
        handler.post(this::resetState);
    }

    private void resetState() {
        Arrays.fill(circularBufferAngle, 0f);
        Arrays.fill(circularBufferInclination, 0f);

        Arrays.fill(circularBufferRawKurtosis, 0f);
        Arrays.fill(circularBufferRawGMAC, 0f);
        Arrays.fill(circularBufferKurtosis, 0);
        Arrays.fill(circularBufferGMAC, 0);
        Arrays.fill(circularBufferSTD, 0f);

        Arrays.fill(circularBufferTimestamps, 0L);
        Arrays.fill(circularBufferX, 0f);
        Arrays.fill(circularBufferY, 0f);
        Arrays.fill(circularBufferZ, 0f);

        currentIndex = 0;
        iter = 0;

        // Rolling stats
        _mean = _M2 = _stdDeg = _M3 = _M4 = _kurtosis = 0f;
        _meanGMAC = 0f;
        poppedValueAngle = -1f;
        poppedValueGMAC = -1f;
        u_kurtosis = 0;
        u_GMAC = 0;
    }

    public void shutdown() {
        stop(); // Unregister sensor if running

        if (thread != null && thread.isAlive()) {
            thread.quitSafely(); // Graceful shutdown
            try {
                thread.join(); // Wait for thread to fully exit
                LogSaver.saveLog(TAG,"w", "SensorHandler thread shut down completely");
            } catch (InterruptedException e) {
                LogSaver.saveLog(TAG,"w", "SensorHandler thread shutdown interrupted"+e.getMessage());
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {

        if (!isRunning || event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        // Log.d(TAG, "------ onSensorChanged called");

        // Convert from nanoseconds to miliseconds
        long timestamp = System.currentTimeMillis(); // - (System.nanoTime() - event.timestamp) / 1_000_000L;
        circularBufferTimestamps[currentIndex] = timestamp;

        float[] accelValues = event.values.clone();     // Safely preserve a snapshot of the accelerometer data at the moment the event occurs.
        float ACCELX = accelValues[0];
        float ACCELY = accelValues[1];
        float ACCELZ = accelValues[2];
        circularBufferX[currentIndex] = ACCELX;
        circularBufferY[currentIndex] = ACCELY;
        circularBufferZ[currentIndex] = ACCELZ;

        float magnitude = (float) Math.sqrt(ACCELX * ACCELX + ACCELY * ACCELY + ACCELZ * ACCELZ);
        if (magnitude < 1e-6f) magnitude = 1e-6f;   // Avoid division by zero or very small values (small epsilon to avoid NaN)
        //Log.d(TAG, "Magnitude: " + magnitude);

        // Clamp ratio to [-1, 1] to prevent NaN in acos
        float ratio = ACCELZ / magnitude;
        ratio = Math.max(-1f, Math.min(1f, ratio));

        // Calculate the orientation angle
        float newOrientationAngle = (float) Math.acos(ratio);
        // Log.e(TAG, "newOrientationAngle: "+Math.toDegrees(newOrientationAngle));


        if (iter == WINDOW_SIZE) {
            poppedValueAngle = circularBufferAngle[currentIndex];
            poppedValueGMAC = circularBufferGMAC[currentIndex];
        }else{
            //Log.d(TAG, "Buffer not full yet, iter=" + iter);
        }

        circularBufferAngle[currentIndex] = newOrientationAngle;


        /// GMAC ///
        float[] GMACResult = ComputationManager.computeGMAC(ACCELX, ACCELY, ACCELZ);

        u_GMAC = (int) GMACResult[0];
        circularBufferGMAC[currentIndex] = u_GMAC;

        float rawGMAC = GMACResult[1];
        circularBufferRawGMAC[currentIndex] = rawGMAC;

        float inclAngle = GMACResult[2];
        circularBufferInclination[currentIndex] = inclAngle;

        _meanGMAC = ComputationManager.computeActivityMean(iter, _meanGMAC, poppedValueGMAC, u_GMAC);
        //Log.d(TAG, "u_GMAC is =" + u_GMAC);
        /// End GMAC ///


        /// Start Kurtosis ///
        float[] kurtosisResult = ComputationManager.computeKurtosis(
                iter, _mean, _M2, _M3, _M4, poppedValueAngle, newOrientationAngle, _meanGMAC
        );
        // Update internal state
        _mean = kurtosisResult[0];
        _M2 = kurtosisResult[1];
        _M3 = kurtosisResult[2];
        _M4 = kurtosisResult[3];

        _kurtosis = kurtosisResult[4];
        circularBufferRawKurtosis[currentIndex] = _kurtosis;

        u_kurtosis = (int) kurtosisResult[5];
        circularBufferKurtosis[currentIndex] = u_kurtosis;

        _stdDeg = kurtosisResult[6];
        circularBufferSTD[currentIndex] = _stdDeg;

        //Log.d(TAG, "Kurtosis is =" + _kurtosis + " ; std:" + _stdDeg + "; u_Kurtosis is =" + u_kurtosis );

        /// End Kurtosis ///

        // Save batch to db
        if (currentIndex == WINDOW_SIZE-1 && iter >= WINDOW_SIZE - 1) {
            // Clone first to avoid locking or long sync
            final long[] tsCopy = circularBufferTimestamps.clone();
            final float[] xCopy = circularBufferX.clone();
            final float[] yCopy = circularBufferY.clone();
            final float[] zCopy = circularBufferZ.clone();
            final float[] angleCopy = circularBufferAngle.clone();
            final float[] inclinationCopy = circularBufferInclination.clone();
            final float[] stdCopy = circularBufferSTD.clone();
            final float[] rawKurtosisCopy = circularBufferRawKurtosis.clone();
            final float[] rawGMACCopy = circularBufferRawGMAC.clone();
            final int[] kurtosisCopy = circularBufferKurtosis.clone();
            final int[] gmacCopy = circularBufferGMAC.clone();

            DataStorageManager.snapshotAndSaveBuffers(
                    tsCopy, xCopy, yCopy, zCopy, angleCopy, inclinationCopy, stdCopy, rawKurtosisCopy, rawGMACCopy, kurtosisCopy, gmacCopy, WINDOW_SIZE
            );
        }

        // Iterate to the next sensor value
        currentIndex = (currentIndex + 1) % WINDOW_SIZE;
        iter = Math.min(iter + 1, WINDOW_SIZE);

        long endTime = System.currentTimeMillis();
        long durationMs = (endTime - timestamp);
        if (durationMs>15){
            LogSaver.saveLog(TAG,"d", "Computation took >15ms: " + durationMs);
        }
    }


    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }
}
