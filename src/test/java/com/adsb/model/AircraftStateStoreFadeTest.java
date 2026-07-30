package com.adsb.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pin the fade / eviction math added 2026-07-30 (12:47 UTC ask from
 * Marty: tracks silent for 120 s begin a 30 s fade, then evict at
 * 150 s).
 *
 * <p>Pure-function tests -- no JXMapViewer, no Swing, no clock skew.
 * The 30 s fade is a linear ramp so we can pin every 5 s tick.
 */
public final class AircraftStateStoreFadeTest {

    @Test
    void freshTrackIsFullyOpaque() {
        assertEquals(1.0f, AircraftStateStore.fadeAlphaForAgeMs(0L));
        assertEquals(1.0f, AircraftStateStore.fadeAlphaForAgeMs(60_000L));
        assertEquals(1.0f, AircraftStateStore.fadeAlphaForAgeMs(119_999L));
    }

    @Test
    void fadeStartsAtExactly120Seconds() {
        // At the boundary the alpha is still 1.0 -- the ramp hasn't
        // moved yet. First millisecond of fade knocks it below 1.0.
        assertEquals(1.0f, AircraftStateStore.fadeAlphaForAgeMs(120_000L));
        assertEquals(1.0f - (1.0f / 30_000f),
                AircraftStateStore.fadeAlphaForAgeMs(120_001L), 1e-6);
    }

    @Test
    void fadeIsLinearOverThe30SecondWindow() {
        // At 135 s (halfway through the 30 s ramp) alpha should be ~0.5.
        assertEquals(0.5f, AircraftStateStore.fadeAlphaForAgeMs(135_000L), 1e-6);
        // At 125 s alpha should be ~5/6.
        assertEquals(1.0f - (5_000f / 30_000f),
                AircraftStateStore.fadeAlphaForAgeMs(125_000L), 1e-6);
        // At 145 s alpha should be ~1/6.
        assertEquals(1.0f - (25_000f / 30_000f),
                AircraftStateStore.fadeAlphaForAgeMs(145_000L), 1e-6);
    }

    @Test
    void fadeCompletesAt150Seconds() {
        // At the boundary the ramp has reached 0 and the track is due
        // for eviction. Anything beyond stays clamped at 0.
        assertEquals(0.0f, AircraftStateStore.fadeAlphaForAgeMs(150_000L));
        assertEquals(0.0f, AircraftStateStore.fadeAlphaForAgeMs(151_000L));
        assertEquals(0.0f, AircraftStateStore.fadeAlphaForAgeMs(999_999_999L));
    }

    @Test
    void nullOrEpochTrackDoesNotThrow() {
        // Null-track / null-lastSeen guards: never throw NPE from the
        // paint pass; treat as fully opaque so a mid-construction track
        // still renders normally.
        assertEquals(1.0f, AircraftStateStore.fadeAlphaFor(null, Instant.now()));
    }

    @Test
    void aliveTrackViaConvenienceOverload() {
        // Build a genuine snapshot via the store and check the
        // convenience overload agrees with the raw ageMs form.
        AircraftStateStore store = new AircraftStateStore();
        AdsbTrack t = store.update("A1B2C3", b -> b.callsign("TEST"));
        assertNotNull(t);
        // lastSeen is 'now' so alpha should be 1.0.
        assertEquals(1.0f, AircraftStateStore.fadeAlphaFor(t, Instant.now()));
        // 130 s in the future -> mid-fade.
        Instant future = Instant.now().plusSeconds(130);
        float alpha = AircraftStateStore.fadeAlphaFor(t, future);
        // Allow a small window because Instant.now() moved between the
        // store.update() call and here.
        assertEquals(1.0f - (10_000f / 30_000f), alpha, 0.02f);
    }

    @Test
    void constantsHoldTheContract() {
        // If someone tweaks these, the tests above still guard the
        // behaviour, but pin the numbers explicitly so a rename doesn't
        // silently change the fade window.
        assertEquals(120_000L, AircraftStateStore.FADE_START_MS);
        assertEquals( 30_000L, AircraftStateStore.FADE_DURATION_MS);
        assertEquals(150_000L, AircraftStateStore.REMOVE_AT_MS);
    }
}
