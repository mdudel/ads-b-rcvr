package com.adsb.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TrackMergerTest {

    @Test
    void identification_frame_populates_callsign_and_category() {
        AircraftStateStore store = new AircraftStateStore();
        AdsbTrack t = TrackMerger.merge(store,
                new AdsbFrame.Identification("A1B2C3", "UAL123", "A3"));
        assertEquals("UAL123", t.callsign());
        assertEquals("A3", t.emitterCategory());
        assertFalse(t.hasPosition());
    }

    @Test
    void position_frame_populates_lat_lon_and_prefers_geom_altitude() {
        AircraftStateStore store = new AircraftStateStore();

        TrackMerger.merge(store,
                new AdsbFrame.AirbornePosition("A1B2C3", 48.0, 11.0, 34800, /*geom*/ false));
        AdsbTrack after1 = store.get("A1B2C3");
        assertEquals(34800, after1.altBaroFt());
        assertEquals(Integer.MIN_VALUE, after1.altGeomFt());
        assertEquals(34800, after1.preferredAltFt());

        TrackMerger.merge(store,
                new AdsbFrame.AirbornePosition("A1B2C3", 48.0, 11.0, 35000, /*geom*/ true));
        AdsbTrack after2 = store.get("A1B2C3");
        assertEquals(35000, after2.altGeomFt());
        assertEquals(34800, after2.altBaroFt(), "baro must not be clobbered by a geom-altitude frame");
        assertEquals(35000, after2.preferredAltFt(), "preferred alt must switch to geom once known");
    }

    @Test
    void velocity_frame_populates_speed_course_and_vertical_rate() {
        AircraftStateStore store = new AircraftStateStore();
        AdsbTrack t = TrackMerger.merge(store,
                new AdsbFrame.AirborneVelocity("A1B2C3", 450.5, 92.0, -1024));
        assertEquals(450.5, t.groundSpeedKts());
        assertEquals(92.0,  t.trackDeg());
        assertEquals(-1024, t.verticalRateFpm());
    }

    @Test
    void surveillance_identity_populates_squawk() {
        AircraftStateStore store = new AircraftStateStore();
        AdsbTrack t = TrackMerger.merge(store,
                new AdsbFrame.SurveillanceIdentity("A1B2C3", "7700"));
        assertEquals("7700", t.squawk());
        assertTrue(t.isEmergency(), "squawk 7700 must flip emergency flag");
    }

    @Test
    void aircraft_status_populates_emergency() {
        AircraftStateStore store = new AircraftStateStore();
        AdsbTrack t = TrackMerger.merge(store,
                new AdsbFrame.AircraftStatus("A1B2C3", 1));
        assertEquals(1, t.emergencyStatus());
        assertTrue(t.isEmergency());
    }

    @Test
    void listener_fires_once_per_update_in_registration_order() {
        AircraftStateStore store = new AircraftStateStore();
        List<String> events = new ArrayList<>();
        AtomicInteger counter = new AtomicInteger();
        store.addListener(t -> events.add("a:" + counter.incrementAndGet() + ":" + t.icaoHex()));
        store.addListener(t -> events.add("b:" + counter.incrementAndGet() + ":" + t.icaoHex()));

        TrackMerger.merge(store, new AdsbFrame.Identification("A1B2C3", "UAL1", "A3"));
        TrackMerger.merge(store, new AdsbFrame.Identification("DEF456", "UAL2", "A3"));

        assertEquals(List.of(
                "a:1:A1B2C3", "b:2:A1B2C3",
                "a:3:DEF456", "b:4:DEF456"), events);
    }

    @Test
    void listener_exception_does_not_break_the_pipeline() {
        AircraftStateStore store = new AircraftStateStore();
        AtomicInteger reachedSecond = new AtomicInteger(0);
        store.addListener(t -> { throw new RuntimeException("boom"); });
        store.addListener(t -> reachedSecond.incrementAndGet());
        TrackMerger.merge(store, new AdsbFrame.Identification("A1B2C3", "UAL1", "A3"));
        assertEquals(1, reachedSecond.get(),
                "second listener must run even after first threw");
    }

    @Test
    void derived_velocity_on_position_frame_populates_track_when_no_reported() {
        // 2026-07-29 CoT enrichment: when the adapter attaches derived
        // (speed, heading) to an AirbornePosition frame AND no reported
        // velocity has been merged yet, TrackMerger must promote them
        // into the track so CoTBuilder emits a non-empty <track> element.
        AircraftStateStore store = new AircraftStateStore();
        TrackMerger.merge(store, new AdsbFrame.AirbornePosition(
                "A1B2C3", 48.0, 11.0, 34800, /*geom*/ false,
                /*derivedSpd*/ 420.0, /*derivedHdg*/ 87.5));
        AdsbTrack t = store.get("A1B2C3");
        assertEquals(420.0, t.groundSpeedKts(),
                "derived speed must populate when no reported speed exists");
        assertEquals(87.5, t.trackDeg(),
                "derived heading must populate when no reported heading exists");
    }

    @Test
    void reported_velocity_wins_over_derived_from_later_position_frame() {
        // Reported (TC 19) arrives first; a subsequent position frame with
        // a derived-velocity payload must NOT overwrite the reported value.
        AircraftStateStore store = new AircraftStateStore();
        TrackMerger.merge(store,
                new AdsbFrame.AirborneVelocity("A1B2C3", 450.0, 90.0, 0));
        TrackMerger.merge(store, new AdsbFrame.AirbornePosition(
                "A1B2C3", 48.0, 11.0, 34800, false,
                /*derived*/ 999.0, /*derived*/ 180.0));
        AdsbTrack t = store.get("A1B2C3");
        assertEquals(450.0, t.groundSpeedKts(),
                "reported speed must not be overwritten by derived on later position frame");
        assertEquals(90.0, t.trackDeg(),
                "reported heading must not be overwritten by derived on later position frame");
    }

    @Test
    void icao_normalises_to_uppercase() {
        AircraftStateStore store = new AircraftStateStore();
        TrackMerger.merge(store, new AdsbFrame.Identification("a1b2c3", "UAL1", "A3"));
        assertNotNull(store.get("A1B2C3"));
        assertNotNull(store.get("a1b2c3"), "lookup must also be case-insensitive");
    }
}
