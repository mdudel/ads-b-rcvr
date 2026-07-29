package com.adsb.model;

/**
 * Typed sum-type for parsed ADS-B frames.
 *
 * <p>This is the merge-friendly counterpart to the JSON-string output of
 * {@link com.adsb.core.AdsbDecoder#decode(String)}. The decoder can now
 * produce either shape (JSON for the current forwarder path, typed for
 * the {@link AircraftStateStore}) without duplicating parse work.
 *
 * <p>Sealed so pattern matching in {@code TrackMerger} is exhaustive by
 * construction — adding a frame variant forces the merger to grow with it.
 */
public sealed interface AdsbFrame
        permits AdsbFrame.Identification,
                AdsbFrame.AirbornePosition,
                AdsbFrame.AirborneVelocity,
                AdsbFrame.SurveillanceAltitude,
                AdsbFrame.SurveillanceIdentity,
                AdsbFrame.AircraftStatus,
                AdsbFrame.AllCall {

    /** ICAO 24-bit address, uppercase hex, no separators. Never null. */
    String icaoHex();

    /** Identification (callsign) — DF17/18 TC 1-4. */
    record Identification(String icaoHex, String callsign, String emitterCategory) implements AdsbFrame {}

    /**
     * Airborne position — DF17/18 TC 9-18 (baro) or TC 20-22 (geometric).
     *
     * <p><b>Derived fields (added 2026-07-29 for CoT enrichment):</b> when
     * the aircraft has never emitted a TC 19 velocity frame, the
     * {@link com.adsb.core.OpenSkyFrameAdapter} computes an implied
     * ground speed + track heading from the last two accepted positions
     * and attaches them here. {@link com.adsb.model.TrackMerger} promotes
     * these into the {@link AdsbTrack} <b>only if no reported</b> speed/
     * heading has yet been observed for the ICAO. Values are {@link Double#NaN}
     * when the adapter had no derived estimate to attach (single-fix aircraft
     * or every prior frame was rejected).
     */
    record AirbornePosition(String icaoHex, double latitude, double longitude,
                            int altitudeFt, boolean isGeometric,
                            double derivedGroundSpeedKts, double derivedTrackDeg)
            implements AdsbFrame {
        /** Back-compat constructor: no derived velocity attached. */
        public AirbornePosition(String icaoHex, double latitude, double longitude,
                                int altitudeFt, boolean isGeometric) {
            this(icaoHex, latitude, longitude, altitudeFt, isGeometric,
                    Double.NaN, Double.NaN);
        }
    }

    /** Airborne velocity — DF17/18 TC 19. */
    record AirborneVelocity(String icaoHex, double groundSpeedKts, double trackDeg,
                            int verticalRateFpm) implements AdsbFrame {}

    /** Short surveillance altitude reply — DF0/DF4/DF20. */
    record SurveillanceAltitude(String icaoHex, int altitudeFt) implements AdsbFrame {}

    /** Short surveillance identity reply — DF5/DF21. Carries squawk. */
    record SurveillanceIdentity(String icaoHex, String squawk) implements AdsbFrame {}

    /** Aircraft status — DF17/18 TC 28 (emergency + squawk if present). */
    record AircraftStatus(String icaoHex, int emergencyStatus) implements AdsbFrame {}

    /** All-call reply — DF11 (ICAO only, useful for presence). */
    record AllCall(String icaoHex) implements AdsbFrame {}
}
