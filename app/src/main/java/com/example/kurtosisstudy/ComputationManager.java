package com.example.kurtosisstudy;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.kurtosisstudy.sensors.ExponentialMovingAverage;
import com.example.kurtosisstudy.sensors.HighPassFilter;
import com.example.kurtosisstudy.sensors.LowPassFilter;
import com.example.kurtosisstudy.sensors.MovingAverageFilter;

/*
 * ComputationManager
 * ------------------
 * Purpose:
 *   - Central math/logic engine for GMAC (active movement) and ADEM (Active & Diverse Exploratory Movement).
 *   - Provides rolling mean/std/kurtosis and threshold-based decision rules for real-time flags.
 *
 * What it does:
 *   • Handedness:
 *       - initFromPrefs(context) reads HANDEDNESS ("left"/"right") from SETTINGS_PREFS.
 *       - AX_SIGN = +1 (left) or -1 (right) so inclination uses ±ax correctly.
 *   • ADEM / kurtosis:
 *       - computeKurtosis(...) maintains rolling mean, M2, M3, M4 over a 3000-sample window.
 *       - Guards against negative moments and numerical issues.
 *       - Uses computeKurtosisDecisionRule(...) to derive:
 *           · stdDeg (angle std in degrees)
 *           · u_kurtosis (ADEM flag from kurtosis + GMAC activity + std thresholds).
 *   • GMAC:
 *       - computeActivity(ax, ay, az):
 *           · High-pass filters XYZ to remove gravity.
 *           · Computes movement magnitude and smooths it via MovingAverageFilter.
 *           · Returns u_alpha (movement active flag) + filtered magnitude.
 *       - computeInclination(ax, ay, az):
 *           · Computes pitch angle (omega_gmac) using atan2(AX_SIGN*ax, sqrt(ay²+az²)).
 *           · Applies hysteresis around omega_th / incr_omega to produce u_omega.
 *       - computeGMAC(ax, ay, az):
 *           · Combines u_alpha and u_omega → final u_gmac (active movement flag).
 *           · Returns {u_gmac, movementMagnitude, inclinationAngleDeg}.
 *   • Rolling activity:
 *       - computeActivityMean(...) keeps a rolling mean of u_GMAC over the last minute
 *         (used as lambda_th in the ADEM decision rule).
 */

public class ComputationManager {

    private static final String TAG = "ComputationManager_KurtosisStudy";

    // Window size for the rolling buffer
    private static final int WINDOW_SIZE = 3000;

    // ADEM - Active & Diverse Exploratory Movement
    private static float lambda_th = 0.5f;  // gmac threshold, >50% of the last minute should be u_gmac=1
    private static int kappa_th = 2;        // kurtosis threshold
    private static int angl_th = 10;        // std threshold
    private static int u_lambda = 0;        // flag for gmac
    private static int u_kappa = 0;         // flag for kurtosis
    private static int u_angl = 0;          // flag for std
    private static int u_kurtosis = 0;      // flag for ADEM

    // GMAC - Movement related variables
    private static final HighPassFilter hpfilterX = new HighPassFilter();
    private static final HighPassFilter hpfilterY = new HighPassFilter();
    private static final HighPassFilter hpfilterZ = new HighPassFilter();
    private static final int N_am = 25;             // Window size of the moving average filter for "amount of forearm movement"
    private static final MovingAverageFilter maFilterMov = new MovingAverageFilter(N_am);
    private static final float alpha_th = 0.2f;     // amount of forearm movement magnitude threshold
    private static int u_alpha = 0;                 // flag for gmac activity

    // GMAC - Inclination related variables
    private static final int N_p = 1;               // Window size of the moving average filter for "orientation"
    //private static final MovingAverageFilter maFilterOrient = new MovingAverageFilter(N_p);
    private static final int omega_th = 20;         // Pitch angle threshold (deg)
    private static final int incr_omega = 40;       // Hysteresis size of the pitch angle (deg)
    private static int u_omega_prev = 0;
    private static int u_omega = 0;                 // flag for inclination angle
    private static int u_gmac = 0;                  // flag for GMAC

    // Read from prefs to get handedness and change inclination angle
    // +1 for left hand (use +ax), -1 for right hand (use -ax). Default to right for backward-compat.

    private static volatile int AX_SIGN = -1;

    /** Call this once at app start (e.g., Application.onCreate or MainActivity.onCreate). */
    public static void initFromPrefs(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PrefsKeys.Settings.SETTINGS_PREFS, Context.MODE_PRIVATE);
        String handed = prefs.getString(PrefsKeys.Settings.HANDEDNESS, "right");
        setHandedness(handed);
    }

    /** You can also call this if the user changes handedness at runtime. */
    public static void setHandedness(String handed) {
        if (handed != null && handed.equalsIgnoreCase("left")) {
            AX_SIGN = +1;   // left -> +ax
        } else {
            AX_SIGN = -1;   // right (default) -> -ax
        }
        Log.e(TAG , "Sign chosen is: " + AX_SIGN);
    }

    // Rolling Sample Kurtosis (RSK)
    public static float[] computeKurtosis(int iter, float _mean, float _M2, float _M3, float _M4, float poppedValue, float newValue, float _meanGMAC) {
        int n = iter - 1;

        // First sample
        if (iter == 1 && poppedValue == -1) {
            float newMean = newValue;
            float newM2 = 0f;
            float newM3 = 0f;
            float newM4 = 0f;
            float kurtosis = 0f;
            float stdDeg = 0f;

            return new float[]{newMean, newM2, newM3, newM4, kurtosis, u_kurtosis, stdDeg};
        }
        // Buffer filling (first 3000 samples)
        else if (poppedValue == -1) {
            int n1 = n;
            n = n + 1;
            float delta = newValue - _mean;
            float delta_n = delta / n;
            float delta_n2 = delta_n * delta_n;
            float term1 = delta * delta_n * n1;

            float newMean = _mean + delta_n;
            float newM2 = _M2 + (newValue - newMean) * (newValue - _mean);

            float newM3 = _M3 - 3 * (newMean - _mean) * _M2 + (newM2 - _M2) * (newValue - newMean - (newMean - _mean));
            float newM4 = _M4 + term1 * delta_n2 * (n * n - 3 * n + 3)
                    + 6 * delta_n2 * _M2 - 4 * delta_n * _M3;

            float kurtosis = n * newM4 / (newM2 * newM2) - 3;

            float[] kurtDecision = computeKurtosisDecisionRule(kurtosis, newM2, _meanGMAC, n);
            float stdDeg = kurtDecision[0];
            u_kurtosis = (int) kurtDecision[1];

            return new float[]{newMean, newM2, newM3, newM4, kurtosis, u_kurtosis, stdDeg};
        }
        // Buffer filled
        else {
            n = WINDOW_SIZE;
            float newMean = _mean + (newValue - poppedValue) / n;
            float newM2 = _M2 + (newValue - poppedValue) * (newValue + poppedValue - newMean - _mean);
            float newM3 = _M3 - 3 * (newMean - _mean) * _M2 +
                    (newValue - poppedValue) *
                            ((poppedValue - _mean) * (poppedValue - 2 * newMean + _mean) +
                                    (newValue - newMean) * (newValue + poppedValue - 2 * newMean));
            float a = newMean - _mean;
            float newM4 = _M4 - 4 * a * _M3 + 6 * a * a * _M2 +
                    (newValue - poppedValue) *
                            ((newMean - _mean) * (newMean - _mean) * (newMean - _mean)
                                    + (newValue - 2 * newMean + poppedValue) *
                                    ((newValue - newMean) * (newValue - newMean) +
                                            (poppedValue - newMean) * (poppedValue - newMean)));

            // Guards
            if (newM2 < 0f) newM2 = 0f;   // variance can't be negative
            if (newM4 < 0f) newM4 = 0f;   // 4th moment can't be negative

            // enforce μ4 ≥ μ2²  -> in sums form: M4 ≥ (M2^2)/n
            if (n > 0) {
                float minM4 = (newM2 * newM2) / n;
                if (newM4 < minM4) newM4 = minM4;
            }

            float kurtosis;
            if (n < 4 || newM2 <= 1e-12f) {
                kurtosis = 0f;           // flat/undefined
            } else {
                // do the division carefully; double helps but is optional
                double kd = ((double) n * (double) newM4) /
                        ((double) newM2 * (double) newM2) - 3.0;
                if (!Double.isFinite(kd)) kd = 0.0;
                if (kd < -2.0) kd = -2.0;          // theoretical lower bound (optional)
                if (kd > 100.0) kd = 100.0;
                kurtosis = (float) kd;
            }
            float[] kurtDecision = computeKurtosisDecisionRule(kurtosis, newM2, _meanGMAC, n);
            float stdDeg = kurtDecision[0];
            u_kurtosis = (int) kurtDecision[1];

            return new float[]{newMean, newM2, newM3, newM4, kurtosis, u_kurtosis, stdDeg};
        }
    }

    // Gets the std from the M2
    public static float stdFromM2(float M2, int n) {
        if (n <= 0) return Float.NaN;
        if (M2 < 0f) M2 = 0f; // safety clamp against tiny negative due to FP error
        return (float) Math.sqrt(M2 / n);
    }

    // computes the decision rule for the ADEM metric
    private static float[] computeKurtosisDecisionRule(float kurtosis, float M2, float meanGMAC, int n) {
        float stdRad = stdFromM2(M2, n);
        float stdDeg = (float) Math.toDegrees(stdRad);

        u_kappa = (kurtosis < kappa_th) ? 1 : 0;
        u_lambda = (meanGMAC > lambda_th) ? 1 : 0; // the previous minute was lambda_th% active
        u_angl = (stdDeg > angl_th) ? 1 : 0;

        // Return Final Decision Rule for Kurtosis
        //Log.d(TAG, "kurtosis: " + kurtosis + "; meanGMAC: " + meanGMAC + "; u_kappa: " + u_kappa + "; u_lambda: " + u_lambda);
        return new float[]{stdDeg, u_lambda * u_kappa * u_angl};
    }

    // Rolling Sample Mean (Compute mean of u_GMAC for the previous minute)
    public static float computeActivityMean(int iter, float _mean, float poppedValue, float newValue) {
        int n = iter - 1;
        if (iter == 1 && poppedValue == -1) {
            return newValue;
        } else if (poppedValue == -1) {
            n = n + 1;
            float delta = newValue - _mean;
            float delta_n = delta / n;
            return _mean + delta_n;
        } else {
            return _mean + (newValue - poppedValue) / WINDOW_SIZE;
        }
    }

    // Compute GMAC (Activity section)
    public static float[] computeActivity(float ax, float ay, float az) {
        // ** Movement Section of GMAC ** //
        // High pass filter to remove gravity
        float filteredX = hpfilterX.applyHighPassFilter(ax);
        float filteredY = hpfilterY.applyHighPassFilter(ay);
        float filteredZ = hpfilterZ.applyHighPassFilter(az);
        // Calculate the 2norm
        float movementMag = (float) Math.sqrt(filteredX*filteredX + filteredY*filteredY + filteredZ*filteredZ);
        // Moving Average Filter
        float alpha_gmac = maFilterMov.add(movementMag);
        // Decision rule
        int decision = alpha_gmac > alpha_th ? 1 : 0;

        return new float[]{decision, alpha_gmac};
    }

    // Compute GMAC (Inclination section)
    public static float[] computeInclination(float ax, float ay, float az){
        // ** Inclination Section of GMAC ** //
        float denominator = (float) Math.sqrt(ay * ay + az * az);
        // RIGHT HAND -ax, left hand +ax
        float omega_gmac = (float) Math.toDegrees(Math.atan2(AX_SIGN*ax, denominator));
        //Log.e("_kurtosisstudy: ", AX_SIGN + "....."+String.valueOf(omega_gmac));

        // Orientation decision rule
        int _u_omega;
        if (omega_gmac > omega_th){
            _u_omega = 1;
        } else if (omega_gmac < omega_th - incr_omega) {
            _u_omega = 0;
        } else{
            _u_omega = u_omega_prev;
        }
        u_omega_prev = _u_omega;

        return new float[] {omega_gmac, _u_omega};
    }

    // Compute GMAC decision rule
    public static float[] computeGMAC(float ax, float ay, float az) {
        float[] gmacResult = computeActivity(ax, ay, az);
        u_alpha = (int) gmacResult[0];
        float gmac_mag = gmacResult[1];

        //u_alpha = computeActivity(ax, ay, az);
        float[] angleRes = computeInclination(ax, ay, az);
        float inclAngle = angleRes[0];
        u_omega = (int) angleRes[1];

        // ** Final Decision Rule for GMAC ** //
        u_gmac =  u_alpha * u_omega;

        return new float[]{u_gmac, gmac_mag, inclAngle};
    }
}
