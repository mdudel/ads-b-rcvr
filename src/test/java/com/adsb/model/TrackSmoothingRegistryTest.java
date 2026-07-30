package com.adsb.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public final class TrackSmoothingRegistryTest {

    @Test
    void disabledPassesThrough() {
        TrackSmoothingRegistry r = new TrackSmoothingRegistry(false);
        double[] out = r.smooth("3C6444", 50.0, 8.0, Instant.now());
        assertEquals(50.0, out[0], 1e-12);
        assertEquals(8.0,  out[1], 1e-12);
        // No filter should have been created since we short-circuit.
        assertEquals(0, r.size());
    }

    @Test
    void enabledCreatesPerIcaoFilter() {
        TrackSmoothingRegistry r = new TrackSmoothingRegistry(true);
        Instant t = Instant.parse("2026-07-30T15:00:00Z");
        r.smooth("3C6444", 50.0, 8.0, t);
        r.smooth("400123", 51.5, -0.1, t);
        assertEquals(2, r.size());
        assertNotNull(r.currentEstimate("3C6444"));
        assertNotNull(r.currentEstimate("400123"));
    }

    @Test
    void icaoLookupIsCaseInsensitive() {
        TrackSmoothingRegistry r = new TrackSmoothingRegistry(true);
        Instant t = Instant.parse("2026-07-30T15:00:00Z");
        r.smooth("3c6444", 50.0, 8.0, t);
        // Lowercase and uppercase should hit the same filter.
        assertEquals(1, r.size());
        assertNotNull(r.currentEstimate("3C6444"));
        assertNotNull(r.currentEstimate("3c6444"));
    }

    @Test
    void nullIcaoOrDisabledReturnsRaw() {
        TrackSmoothingRegistry r = new TrackSmoothingRegistry(true);
        double[] out = r.smooth(null, 50.0, 8.0, Instant.now());
        assertEquals(50.0, out[0]);
        assertEquals(8.0,  out[1]);
        assertEquals(0, r.size());
    }

    @Test
    void toggleOffRetainsStateForReEnable() {
        TrackSmoothingRegistry r = new TrackSmoothingRegistry(true);
        Instant t = Instant.parse("2026-07-30T15:00:00Z");
        r.smooth("3C6444", 50.0, 8.0, t);
        r.smooth("3C6444", 50.0, 8.001, t.plusSeconds(1));
        assertEquals(1, r.size());
        r.setEnabled(false);
        // Filter retained; disabled just skips consulting it.
        assertEquals(1, r.size());
        r.setEnabled(true);
        assertNotNull(r.currentEstimate("3C6444"));
    }

    @Test
    void clearDropsEveryFilter() {
        TrackSmoothingRegistry r = new TrackSmoothingRegistry(true);
        Instant t = Instant.parse("2026-07-30T15:00:00Z");
        r.smooth("3C6444", 50.0, 8.0, t);
        r.smooth("400123", 51.5, -0.1, t);
        assertEquals(2, r.size());
        r.clear();
        assertEquals(0, r.size());
        assertNull(r.currentEstimate("3C6444"));
    }

    @Test
    void evictOlderThanDropsStale() {
        TrackSmoothingRegistry r = new TrackSmoothingRegistry(true);
        Instant t0 = Instant.parse("2026-07-30T15:00:00Z");
        r.smooth("3C6444", 50.0, 8.0, t0);
        r.smooth("400123", 51.5, -0.1, t0.plusSeconds(600));
        assertEquals(2, r.size());
        // "Now" is 610 s after t0; 3C6444 is 610 s stale, 400123 is 10 s.
        // EVICT_STALE_SEC = 300, so 3C6444 goes, 400123 stays.
        int dropped = r.evictOlderThan(t0.plusSeconds(610));
        assertEquals(1, dropped);
        assertEquals(1, r.size());
        assertNotNull(r.currentEstimate("400123"));
        assertNull(r.currentEstimate("3C6444"));
    }

    @Test
    void removeIcaoDropsOne() {
        TrackSmoothingRegistry r = new TrackSmoothingRegistry(true);
        Instant t = Instant.parse("2026-07-30T15:00:00Z");
        r.smooth("3C6444", 50.0, 8.0, t);
        r.smooth("400123", 51.5, -0.1, t);
        r.removeIcao("3C6444");
        assertEquals(1, r.size());
        assertNull(r.currentEstimate("3C6444"));
        assertNotNull(r.currentEstimate("400123"));
    }
}
