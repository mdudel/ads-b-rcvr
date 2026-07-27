package com.adsb.model;

/**
 * Fold-once bridge: apply an {@link AdsbFrame} to an {@link AircraftStateStore}
 * so listeners see a fresh {@link AdsbTrack} snapshot on every update.
 *
 * <p>Kept as a static utility (not tied to the store) so the merge rules are
 * unit-testable in isolation from ConcurrentHashMap plumbing.
 */
public final class TrackMerger {

    private TrackMerger() {}

    /** Apply one typed frame; returns the newly-installed snapshot. */
    public static AdsbTrack merge(AircraftStateStore store, AdsbFrame frame) {
        return store.update(frame.icaoHex(), b -> apply(b, frame));
    }

    /**
     * Package-private for tests.
     * <p>
     * Written as an {@code instanceof} chain rather than JDK 21 pattern-matching
     * {@code switch} so this compiles on JDK 17 (project target). The sealed
     * hierarchy still gives us a compile-time hint if a new frame variant
     * is added — the exhaustiveness check moves to the reader, but adding a
     * new {@code permits} entry without touching this file is intended to be
     * noticed in code review.
     */
    static void apply(AdsbTrack.Builder b, AdsbFrame frame) {
        if (frame instanceof AdsbFrame.Identification f) {
            if (f.callsign() != null && !f.callsign().isBlank()) b.callsign(f.callsign());
            if (f.emitterCategory() != null) b.emitterCategory(f.emitterCategory());
        } else if (frame instanceof AdsbFrame.AirbornePosition f) {
            b.latitude(f.latitude()).longitude(f.longitude());
            if (f.isGeometric()) b.altGeomFt(f.altitudeFt());
            else                 b.altBaroFt(f.altitudeFt());
            b.onGround(false);
        } else if (frame instanceof AdsbFrame.AirborneVelocity f) {
            b.groundSpeedKts(f.groundSpeedKts())
             .trackDeg(f.trackDeg())
             .verticalRateFpm(f.verticalRateFpm());
        } else if (frame instanceof AdsbFrame.SurveillanceAltitude f) {
            b.altBaroFt(f.altitudeFt());
        } else if (frame instanceof AdsbFrame.SurveillanceIdentity f) {
            b.squawk(f.squawk());
        } else if (frame instanceof AdsbFrame.AircraftStatus f) {
            b.emergencyStatus(f.emergencyStatus());
        }
        // AllCall carries presence only; no fields to merge.
    }
}
