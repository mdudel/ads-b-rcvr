package com.adsb.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural pins for {@link OpenSkyFrameAdapter#sanityCheckPosition}.
 * <p>
 * Field bug (Marty 2026-07-27 15:35 UTC, SkyLord screenshot): an
 * aircraft over Rhineland-Pfalz showed a track streak spanning
 * Belgium-France-Germany, ~500 nm horizontal glitch between two
 * frames received seconds apart. OpenSky's own reasonableness check
 * missed it, hence this second layer.
 */
class OpenSkyFrameAdapterSanityTest {

    @Test
    void first_fix_is_always_accepted() {
        OpenSkyFrameAdapter a = new OpenSkyFrameAdapter();
        assertTrue(a.sanityCheckPosition("A1B2C3", 50.0, 9.0, 100.0),
                "first position seen for an ICAO must accept -- nothing to compare against");
    }

    @Test
    void position_within_speed_ceiling_accepted() {
        OpenSkyFrameAdapter a = new OpenSkyFrameAdapter();
        // First fix (accepted, seeds the last-good).
        assertTrue(a.sanityCheckPosition("A1B2C3", 50.0, 9.0, 100.0));
        acceptViaFrame(a, "A1B2C3", 50.0, 9.0, 100.0);
        // 1 s later, aircraft has moved ~0.15 nm north (implied ~540 kts).
        // Well under the 1200 kt ceiling.
        assertTrue(a.sanityCheckPosition("A1B2C3", 50.0025, 9.0, 101.0),
                "reasonable inter-frame movement must accept");
    }

    @Test
    void jump_exceeding_speed_ceiling_rejected_and_previous_kept() {
        OpenSkyFrameAdapter a = new OpenSkyFrameAdapter();
        acceptViaFrame(a, "A1B2C3", 50.0, 9.0, 100.0);
        // The exact Marty-report jump shape: ~5 degrees longitude
        // (~200 nm at 50N) in 1 s implies ~720,000 kts. Reject.
        assertFalse(a.sanityCheckPosition("A1B2C3", 50.0, 14.0, 101.0),
                "500+ kts-in-one-second longitude jump must reject");
        // The keep-previous contract is enforced by toAirbornePosition
        // returning null on reject (state store keeps whatever it had),
        // but we can pin it at the adapter layer by confirming the
        // last-good record was NOT updated to the bad position:
        // accept a real subsequent frame and verify the delta is
        // measured against the ORIGINAL last-good, not the rejected one.
        assertTrue(a.sanityCheckPosition("A1B2C3", 50.01, 9.0, 102.0),
                "post-reject the check is still relative to the last GOOD position, "
                + "so a normal follow-on frame nearby must accept");
    }

    @Test
    void bad_range_lat_or_lon_rejected() {
        OpenSkyFrameAdapter a = new OpenSkyFrameAdapter();
        assertFalse(a.sanityCheckPosition("A1B2C3",  100.0,    9.0, 100.0), "lat > 90 must reject");
        assertFalse(a.sanityCheckPosition("A1B2C3",  -95.0,    9.0, 100.0), "lat < -90 must reject");
        assertFalse(a.sanityCheckPosition("A1B2C3",   50.0,  200.0, 100.0), "lon > 180 must reject");
        assertFalse(a.sanityCheckPosition("A1B2C3",   50.0, -200.0, 100.0), "lon < -180 must reject");
        assertFalse(a.sanityCheckPosition("A1B2C3",   Double.NaN, 9.0, 100.0), "NaN lat must reject");
        assertFalse(a.sanityCheckPosition("A1B2C3",   50.0, Double.NaN, 100.0), "NaN lon must reject");
    }

    @Test
    void boundary_values_accepted() {
        OpenSkyFrameAdapter a = new OpenSkyFrameAdapter();
        // Exact corners of the valid domain should not trip the range check.
        assertTrue(a.sanityCheckPosition("A1B2C3",  90.0,  180.0, 100.0));
        assertTrue(a.sanityCheckPosition("A1B2C4", -90.0, -180.0, 100.0));
        assertTrue(a.sanityCheckPosition("A1B2C5",   0.0,    0.0, 100.0));
    }

    @Test
    void stale_last_good_does_not_gate_speed_check() {
        OpenSkyFrameAdapter a = new OpenSkyFrameAdapter();
        acceptViaFrame(a, "A1B2C3", 50.0, 9.0, 100.0);
        // 2 minutes later (> LAST_GOOD_TTL_SECONDS) an aircraft may
        // legitimately reappear anywhere. Skip the speed check.
        assertTrue(a.sanityCheckPosition("A1B2C3", 55.0, 20.0, 100.0 + 200.0),
                "with the last-good older than TTL, speed check is skipped");
    }

    @Test
    void out_of_order_frame_does_not_reject_on_speed() {
        OpenSkyFrameAdapter a = new OpenSkyFrameAdapter();
        acceptViaFrame(a, "A1B2C3", 50.0, 9.0, 100.0);
        // A frame with timestamp earlier than the last-good: dtSec <= 0,
        // speed check bypassed (we can't divide by non-positive dt).
        // Range check still applies, so a valid lat/lon accepts.
        assertTrue(a.sanityCheckPosition("A1B2C3", 50.5, 9.5, 99.0));
    }

    @Test
    void evict_clears_the_last_good_record() {
        OpenSkyFrameAdapter a = new OpenSkyFrameAdapter();
        acceptViaFrame(a, "A1B2C3", 50.0, 9.0, 100.0);
        a.evict("A1B2C3");
        // After evict, the next big jump should be treated as first-fix
        // (no previous to compare against) and accept.
        assertTrue(a.sanityCheckPosition("A1B2C3", 50.0, 14.0, 101.0),
                "post-evict there is no last-good, so no speed check applies");
    }

    @Test
    void haversine_frankfurt_to_paris_matches_known_distance() {
        // Frankfurt (50.03, 8.57) to Paris CDG (49.01, 2.55) = ~245 nm.
        double d = OpenSkyFrameAdapter.haversineNm(50.03, 8.57, 49.01, 2.55);
        assertEquals(240.0, d, 10.0,
                "Frankfurt-Paris great-circle should be ~245 nm; got " + d);
    }

    /**
     * Convenience: seed the last-good record via a real accept call, so
     * subsequent sanity checks have something to compare against. Uses
     * the public sanityCheckPosition + a manual lastGoodPositions write
     * would be nicer but that field is private for good reason; the
     * production path only writes it inside toAirbornePosition. Here
     * we exploit that a call that accepts DOES NOT seed last-good --
     * seeding happens in toAirbornePosition. So tests use a canned
     * check that leaves the store empty, then call the sanity check.
     *
     * <p>To actually seed via public API from a test we would need to
     * exercise the whole ModeSDecoder path. Instead: the sanity checker
     * itself accepts a first fix, and we use the enclosing test's flow
     * to model "one accepted -> one candidate". The trick is that
     * sanityCheckPosition ALONE does not write last-good; the enclosing
     * toAirbornePosition does. So this helper simulates that write by
     * driving both sides -- the check to accept, plus a public seed via
     * the package-private LastGoodPosition record.
     */
    private static void acceptViaFrame(OpenSkyFrameAdapter a,
                                        String icao,
                                        double lat, double lon, double timeSec) {
        // The sanity check itself does not seed last-good; that's the
        // job of toAirbornePosition. For tests we can't reach that
        // easily without spinning up the OpenSky decoder, so we call
        // the public-for-test seeder directly (adding one line to the
        // adapter would be needed; simpler: mimic the seed by calling
        // sanityCheckPosition + directly writing via the record).
        //
        // Since the adapter's lastGoodPositions is private, tests use
        // this reflective-free path: perform ONE first-fix accept then
        // simulate the write via the adapter's public seed hook.
        //
        // (Simpler alternative: expose seedForTest() on the adapter.
        //  That's what we do below.)
        a.seedLastGoodForTest(icao, lat, lon, timeSec);
    }
}
