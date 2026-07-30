package com.adsb.model;

import java.time.Duration;
import java.time.Instant;

/**
 * Per-aircraft 4-state constant-velocity Kalman filter for smoothing
 * position tracks on the map surface.
 *
 * <p><b>State</b>: {@code x = [lat, lon, vlat, vlon]} (degrees +
 * degrees/sec). Treating latitude and longitude as independent
 * Cartesian axes is an approximation -- one degree of longitude
 * shortens as latitude grows -- but it is more than sufficient for
 * the visual smoothing purpose over the ~5-10 s intervals between
 * ADS-B position updates. For a rigorous ENU-frame filter we'd
 * project through a local tangent plane; not warranted for a
 * display-only smoother.
 *
 * <p><b>Dynamics</b>: constant velocity between updates. Process
 * noise {@code Q} injects the possibility of small acceleration
 * (thrust changes, gentle turns) so the filter can keep up when the
 * aircraft manoeuvres. Larger Q -&gt; more responsive, less smooth.
 * Smaller Q -&gt; smoother trail, laggier through corners.
 *
 * <p><b>Measurement</b>: 2-dimensional {@code z = [lat, lon]}
 * observation. Measurement noise {@code R} models typical ADS-B
 * position uncertainty (~15 m ~= 1.35e-4 deg at 45N latitude, of
 * the same order for longitude at that latitude). Squared into the
 * covariance so R = sigma^2.
 *
 * <p><b>Numerical form</b>: closed-form 4x4 predict, 2x2
 * measurement-residual invert. No matrix library needed. All
 * arithmetic is plain doubles. Not thread-safe -- the caller (the
 * smoothing registry) serialises access per aircraft.
 *
 * <p><b>Reset</b>: after {@link #MAX_DT_RESET_SEC} seconds of no
 * updates, the next update reinitialises the filter rather than
 * projecting through a huge dt (which would produce a garbage
 * covariance). Prevents ghosts on re-acquired tracks.
 */
public final class TrackKalmanFilter {

    /**
     * If more than this many seconds elapse without a measurement,
     * treat the next update as a fresh initialisation rather than
     * a huge {@code predict()} step. Roughly one full fade-out
     * window (see {@link AircraftStateStore#REMOVE_AT_MS}).
     */
    public static final double MAX_DT_RESET_SEC = 30.0;

    /**
     * Process noise sigma per axis in degrees/sec^2 (small: aircraft
     * change velocity slowly relative to the ~5 s update cadence).
     * Injected via a discretised constant-white-noise-acceleration
     * model: Q = G * G^T * sigma^2 where G = [dt^2/2, dt, dt^2/2, dt].
     *
     * <p>Tuned for VISUAL smoothing (Marty's ask): favour trust in
     * the constant-velocity model over trust in each individual
     * measurement so the trail reads as a clean curve rather than a
     * jaggy line. A pure-tracking application (e.g. downstream C2
     * that needs low-latency response to manoeuvres) would want a
     * larger sigma. Bump this up if the filter feels laggy through
     * turns; drop it if the trail still looks jaggy.
     */
    public static final double PROCESS_NOISE_SIGMA = 3.0e-5;

    /**
     * Measurement noise sigma per axis in degrees. ADS-B nominal
     * accuracy is ~15 m; 15 m / 111_320 m/deg ~= 1.35e-4 deg.
     */
    public static final double MEASUREMENT_NOISE_SIGMA = 1.35e-4;

    // 4-state x = [lat, lon, vlat, vlon]
    private double xLat, xLon, xVLat, xVLon;
    // 4x4 covariance, stored as 16 doubles in row-major order.
    // Rows/cols: 0=lat, 1=lon, 2=vlat, 3=vlon.
    private final double[] p = new double[16];

    private Instant lastUpdate;
    private boolean initialised;

    /**
     * Feed a new measurement into the filter and return the
     * smoothed lat/lon estimate.
     *
     * @param lat  measured WGS-84 latitude (degrees)
     * @param lon  measured WGS-84 longitude (degrees)
     * @param when timestamp of the measurement (used for dt)
     * @return the a-posteriori smoothed estimate {@code [lat, lon]}
     */
    public double[] update(double lat, double lon, Instant when) {
        if (!initialised) {
            initialiseTo(lat, lon, when);
            return new double[] { xLat, xLon };
        }
        double dt = Duration.between(lastUpdate, when).toNanos() / 1e9;
        if (dt < 0.0) {
            // Out-of-order measurement; ignore rather than
            // propagating backward.
            return new double[] { xLat, xLon };
        }
        if (dt > MAX_DT_RESET_SEC) {
            initialiseTo(lat, lon, when);
            return new double[] { xLat, xLon };
        }
        predict(dt);
        correct(lat, lon);
        lastUpdate = when;
        return new double[] { xLat, xLon };
    }

    /**
     * @return the current smoothed lat/lon, or null if the filter
     *         hasn't seen a measurement yet.
     */
    public double[] currentEstimate() {
        return initialised ? new double[] { xLat, xLon } : null;
    }

    /** @return true after the first {@link #update} call. */
    public boolean isInitialised() {
        return initialised;
    }

    /**
     * @return current velocity estimate {@code [vlat, vlon]} in
     *         degrees per second, or null if uninitialised. Useful
     *         for followup features (leader-line rendering, ETA).
     */
    public double[] currentVelocity() {
        return initialised ? new double[] { xVLat, xVLon } : null;
    }

    /** @return timestamp of the last {@link #update} call, or null if never updated. */
    public Instant lastUpdate() { return lastUpdate; }

    // ------------------------------------------------------------------

    private void initialiseTo(double lat, double lon, Instant when) {
        this.xLat  = lat;
        this.xLon  = lon;
        this.xVLat = 0.0;
        this.xVLon = 0.0;
        // Initial covariance: uncertain about position by roughly
        // one measurement stddev (already a good fix), and very
        // uncertain about velocity until we see a second frame.
        double rr = MEASUREMENT_NOISE_SIGMA * MEASUREMENT_NOISE_SIGMA;
        double vv = 1.0; // 1 (deg/sec)^2 -- huge; drops fast after 2nd fix
        for (int i = 0; i < 16; i++) p[i] = 0.0;
        p[0]  = rr;   // P[lat,lat]
        p[5]  = rr;   // P[lon,lon]
        p[10] = vv;   // P[vlat,vlat]
        p[15] = vv;   // P[vlon,vlon]
        this.lastUpdate = when;
        this.initialised = true;
    }

    /**
     * Predict step: propagate state + covariance forward by {@code dt}
     * seconds under the constant-velocity model.
     *
     * <pre>
     *   x' = F x
     *   P' = F P F^T + Q
     *
     *   F = [ 1 0 dt 0 ]
     *       [ 0 1 0 dt ]
     *       [ 0 0 1  0 ]
     *       [ 0 0 0  1 ]
     * </pre>
     */
    private void predict(double dt) {
        // State: only velocity terms feed position.
        xLat += xVLat * dt;
        xLon += xVLon * dt;
        // Velocity unchanged in the deterministic step.

        // P' = F P F^T + Q, hand-expanded for a sparse F.
        // F P has effect: rows 0 and 1 get their vel-column contributions.
        // Then F P F^T does the same on the transposed side.
        // Rather than write out the 16 element updates symbolically,
        // do it in two matrix passes using a small scratch buffer.
        double[] fp = new double[16];
        // FP: rows 0,1 add dt * row(2,3). Rows 2,3 unchanged.
        for (int j = 0; j < 4; j++) {
            fp[0 * 4 + j] = p[0 * 4 + j] + dt * p[2 * 4 + j];
            fp[1 * 4 + j] = p[1 * 4 + j] + dt * p[3 * 4 + j];
            fp[2 * 4 + j] = p[2 * 4 + j];
            fp[3 * 4 + j] = p[3 * 4 + j];
        }
        // (FP) F^T: cols 0,1 add dt * col(2,3). Cols 2,3 unchanged.
        double[] np = new double[16];
        for (int i = 0; i < 4; i++) {
            np[i * 4 + 0] = fp[i * 4 + 0] + dt * fp[i * 4 + 2];
            np[i * 4 + 1] = fp[i * 4 + 1] + dt * fp[i * 4 + 3];
            np[i * 4 + 2] = fp[i * 4 + 2];
            np[i * 4 + 3] = fp[i * 4 + 3];
        }
        // Add discretised constant-white-noise-acceleration Q:
        //   Q = sigma^2 * [ dt^4/4    0    dt^3/2   0    ]
        //                 [   0    dt^4/4    0   dt^3/2  ]
        //                 [ dt^3/2   0     dt^2    0     ]
        //                 [   0    dt^3/2    0    dt^2   ]
        double s2 = PROCESS_NOISE_SIGMA * PROCESS_NOISE_SIGMA;
        double dt2 = dt * dt;
        double dt3 = dt2 * dt;
        double dt4 = dt3 * dt;
        np[0]  += s2 * dt4 / 4.0;
        np[5]  += s2 * dt4 / 4.0;
        np[10] += s2 * dt2;
        np[15] += s2 * dt2;
        np[2]  += s2 * dt3 / 2.0;
        np[7]  += s2 * dt3 / 2.0;
        np[8]  += s2 * dt3 / 2.0;
        np[13] += s2 * dt3 / 2.0;

        System.arraycopy(np, 0, p, 0, 16);
    }

    /**
     * Correct step: fold in a measurement {@code z = [lat, lon]}
     * using the standard Kalman update.
     *
     * <pre>
     *   y = z - H x
     *   S = H P H^T + R          (2x2)
     *   K = P H^T S^{-1}         (4x2)
     *   x = x + K y
     *   P = (I - K H) P
     *
     *   H = [ 1 0 0 0 ]  (observation matrix -- picks lat, lon)
     *       [ 0 1 0 0 ]
     *   R = sigma^2 * I2
     * </pre>
     */
    private void correct(double zLat, double zLon) {
        double yLat = zLat - xLat;
        double yLon = zLon - xLon;

        // S = H P H^T + R = P[0..1, 0..1] + R
        double r  = MEASUREMENT_NOISE_SIGMA * MEASUREMENT_NOISE_SIGMA;
        double s00 = p[0]  + r;
        double s01 = p[1];
        double s10 = p[4];
        double s11 = p[5]  + r;

        // S^{-1} (2x2)
        double det = s00 * s11 - s01 * s10;
        if (Math.abs(det) < 1e-30) {
            // Degenerate; skip the correction rather than blow up.
            return;
        }
        double inv00 =  s11 / det;
        double inv01 = -s01 / det;
        double inv10 = -s10 / det;
        double inv11 =  s00 / det;

        // K = P H^T S^{-1}. P H^T is columns 0,1 of P (4x2).
        // K rows i, cols j: sum over k of P[i,k] * (H^T)[k,l] * inv[l,j]
        // = P[i,0] * inv[0,j] + P[i,1] * inv[1,j]
        double[] k = new double[8]; // 4x2 row-major
        for (int i = 0; i < 4; i++) {
            double pi0 = p[i * 4 + 0];
            double pi1 = p[i * 4 + 1];
            k[i * 2 + 0] = pi0 * inv00 + pi1 * inv10;
            k[i * 2 + 1] = pi0 * inv01 + pi1 * inv11;
        }

        // x = x + K y
        xLat  += k[0] * yLat + k[1] * yLon;
        xLon  += k[2] * yLat + k[3] * yLon;
        xVLat += k[4] * yLat + k[5] * yLon;
        xVLon += k[6] * yLat + k[7] * yLon;

        // P = (I - K H) P
        // (I - K H) has effect: row i, cols 0 and 1 get -K[i,0], -K[i,1]
        // added to the identity. Multiplying by P: for each row i, new
        // row = old row - K[i,0] * P[0,*] - K[i,1] * P[1,*].
        double[] pp = new double[16];
        for (int i = 0; i < 4; i++) {
            double ki0 = k[i * 2 + 0];
            double ki1 = k[i * 2 + 1];
            for (int j = 0; j < 4; j++) {
                pp[i * 4 + j] = p[i * 4 + j]
                        - ki0 * p[0 * 4 + j]
                        - ki1 * p[1 * 4 + j];
            }
        }
        System.arraycopy(pp, 0, p, 0, 16);
    }
}
