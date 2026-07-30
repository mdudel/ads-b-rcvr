package com.adsb.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kalman filter unit tests. All pure math; no I/O, no Swing.
 * Focus areas:
 *   * first-measurement bootstrap
 *   * straight-line convergence: filter tracks a noisy line
 *     with less variance than the raw signal
 *   * reset semantics after a long gap
 *   * out-of-order measurement rejection
 *   * velocity estimate convergence
 */
public final class TrackKalmanFilterTest {

    @Test
    void firstMeasurementReturnsRawAndInitialises() {
        TrackKalmanFilter f = new TrackKalmanFilter();
        assertFalse(f.isInitialised());
        assertNull(f.currentEstimate());

        double[] out = f.update(50.0, 8.5, Instant.parse("2026-07-30T15:00:00Z"));
        assertTrue(f.isInitialised());
        assertEquals(50.0, out[0], 1e-12);
        assertEquals(8.5,  out[1], 1e-12);
        assertNotNull(f.currentEstimate());
        assertNotNull(f.currentVelocity());
        // Velocity starts at zero on init.
        assertEquals(0.0, f.currentVelocity()[0], 1e-12);
        assertEquals(0.0, f.currentVelocity()[1], 1e-12);
    }

    @Test
    void straightLineNoiseIsSmoothed() {
        // Simulate an aircraft flying due east at 0.01 deg/sec longitude
        // for 30 s (~= ~800 kt at 45N -- unrealistic but keeps the math
        // clean). Add Gaussian noise to each measurement and verify the
        // filter output variance is smaller than the raw variance.
        TrackKalmanFilter f = new TrackKalmanFilter();
        Random rng = new Random(42);
        double lat0 = 50.0, lon0 = 8.0;
        double vLon = 0.01;
        double sigma = 1e-3;  // ~110 m -- pathological ADS-B, worst case
        Instant t = Instant.parse("2026-07-30T15:00:00Z");

        double rawResSumSq = 0.0, filResSumSq = 0.0;
        int n = 30;
        for (int i = 0; i < n; i++) {
            double trueLon = lon0 + vLon * i;
            double zLon    = trueLon + rng.nextGaussian() * sigma;
            double zLat    = lat0    + rng.nextGaussian() * sigma;
            double[] out = f.update(zLat, zLon, t.plusSeconds(i));
            // Skip the first few frames while the filter's covariance
            // shrinks; measure residuals only after convergence.
            if (i >= 5) {
                double rawRes = (zLon - trueLon);
                double filRes = (out[1] - trueLon);
                rawResSumSq += rawRes * rawRes;
                filResSumSq += filRes * filRes;
            }
        }
        // Filter output variance should be materially smaller than
        // raw measurement variance. Threshold 0.5x is loose enough
        // to survive different random seeds but tight enough that
        // a broken filter (returning raw input) would fail.
        assertTrue(filResSumSq < rawResSumSq * 0.5,
                "filter should reduce residual variance by >= 2x; raw=" + rawResSumSq
                        + " filt=" + filResSumSq);
    }

    @Test
    void velocityConvergesToTruth() {
        // With the display-tuned PROCESS_NOISE_SIGMA (favouring smooth
        // trails over aggressive response), velocity convergence takes
        // more updates. Feed 60 s of clean data and check that vlat is
        // still near zero and vlon is in the ballpark. Loose tolerance
        // because the filter is deliberately slow to react.
        TrackKalmanFilter f = new TrackKalmanFilter();
        double lat0 = 50.0, lon0 = 8.0;
        double vLon = 0.001;   // realistic ~60 kt-ish equivalent
        Instant t = Instant.parse("2026-07-30T15:00:00Z");
        for (int i = 0; i < 60; i++) {
            f.update(lat0, lon0 + vLon * i, t.plusSeconds(i));
        }
        double[] v = f.currentVelocity();
        assertEquals(0.0,  v[0], 5e-5, "vlat should be ~0 for eastbound-only motion");
        // vlon should be positive and within an order of magnitude of
        // the true value. Exact convergence needs a much longer time
        // series; we just want to show the filter is tracking motion.
        assertTrue(v[1] > 0.0 && v[1] < vLon * 2.0,
                "vlon should be positive and in the vicinity of true velocity; got " + v[1]);
    }

    @Test
    void longGapCausesReinitialisation() {
        TrackKalmanFilter f = new TrackKalmanFilter();
        Instant t0 = Instant.parse("2026-07-30T15:00:00Z");
        f.update(50.0, 8.0, t0);
        f.update(50.0, 8.001, t0.plusSeconds(1));
        // Aircraft disappears for 60 s (past MAX_DT_RESET_SEC = 30).
        double[] out = f.update(51.0, 9.0, t0.plusSeconds(61));
        // Should have snapped exactly to the new measurement (reinit),
        // not blended with the stale state.
        assertEquals(51.0, out[0], 1e-12);
        assertEquals(9.0,  out[1], 1e-12);
        // Velocity re-zeroed on reinit.
        assertEquals(0.0, f.currentVelocity()[0], 1e-12);
        assertEquals(0.0, f.currentVelocity()[1], 1e-12);
    }

    @Test
    void outOfOrderMeasurementIgnored() {
        TrackKalmanFilter f = new TrackKalmanFilter();
        Instant t = Instant.parse("2026-07-30T15:00:00Z");
        f.update(50.0, 8.0, t);
        f.update(50.0, 8.01, t.plusSeconds(5));
        double[] snapshot = f.currentEstimate();
        // Now feed a measurement 3 s BEFORE the last one -- should not
        // change the estimate.
        double[] out = f.update(60.0, 20.0, t.plusSeconds(2));
        assertArrayEquals(snapshot, out, 1e-12);
        assertArrayEquals(snapshot, f.currentEstimate(), 1e-12);
    }

    @Test
    void constantsHoldTheContract() {
        // Pin the tunables so a rename doesn't silently change the
        // filter's aggressiveness.
        assertEquals(30.0,     TrackKalmanFilter.MAX_DT_RESET_SEC);
        assertEquals(3.0e-5,   TrackKalmanFilter.PROCESS_NOISE_SIGMA);
        assertEquals(1.35e-4,  TrackKalmanFilter.MEASUREMENT_NOISE_SIGMA);
    }
}
