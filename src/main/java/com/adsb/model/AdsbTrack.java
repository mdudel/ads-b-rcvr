package com.adsb.model;

import java.time.Instant;

/**
 * Immutable snapshot of the aggregated state we know about one aircraft.
 * <p>
 * ADS-B is chatty and fragmented: a single aircraft's identification, position, and
 * velocity arrive in different frame types (TC 1-4, TC 9-18/20-22, TC 19 respectively),
 * often seconds apart. A stateless per-frame CoT emit would produce useless track
 * events with holes. This record is the merged state, keyed by ICAO 24-bit address.
 * <p>
 * Any field may be {@code null} (references) or NaN (doubles) or {@link Integer#MIN_VALUE}
 * (ints) meaning "not yet observed for this aircraft".
 * <p>
 * The class is a plain {@code record}: instances are cheap, thread-safe by
 * immutability, and can be safely handed to any number of listener sinks without
 * defensive copying.
 */
public record AdsbTrack(
        /** ICAO 24-bit address, uppercase hex, no separators. Never null; the map key. */
        String icaoHex,

        /** Flight callsign / call letters (from TC 1-4 identification). {@code null} if not yet seen. */
        String callsign,

        /** DO-260B emitter category (e.g. "A1".."A7", "B2"). {@code null} if not yet seen. */
        String emitterCategory,

        /** Mode-A squawk code (from DF5/DF21). {@code null} if not yet seen. */
        String squawk,

        /** WGS-84 latitude, degrees. {@link Double#NaN} if not yet seen. */
        double latitude,

        /** WGS-84 longitude, degrees. {@link Double#NaN} if not yet seen. */
        double longitude,

        /** Barometric altitude in feet. {@link Integer#MIN_VALUE} if not yet seen. */
        int altBaroFt,

        /** Geometric (GNSS/WGS-84 HAE) altitude in feet. {@link Integer#MIN_VALUE} if not yet seen. */
        int altGeomFt,

        /** Ground speed in knots. {@link Double#NaN} if not yet seen. */
        double groundSpeedKts,

        /** Track over ground, degrees true 0..360. {@link Double#NaN} if not yet seen. */
        double trackDeg,

        /** Vertical rate in feet per minute (positive = climb). {@link Integer#MIN_VALUE} if not yet seen. */
        int verticalRateFpm,

        /** True when the aircraft self-reports on-ground (surface position message). */
        boolean onGround,

        /** ADS-B emergency status (0=none, 1=general, 2=medical, 3=min-fuel, ...). {@link Integer#MIN_VALUE} if unknown. */
        int emergencyStatus,

        /** Wall-clock time this snapshot was assembled. Never null. */
        Instant lastSeen
) {
    /**
     * @return true when we have enough state to emit a meaningful position-carrying
     *         CoT event (lat + lon at minimum).
     */
    public boolean hasPosition() {
        return !Double.isNaN(latitude) && !Double.isNaN(longitude);
    }

    /**
     * @return preferred altitude in feet: geometric first (GNSS WGS-84 HAE), then
     *         barometric, then {@link Integer#MIN_VALUE} if neither is known.
     */
    public int preferredAltFt() {
        if (altGeomFt != Integer.MIN_VALUE) return altGeomFt;
        return altBaroFt;
    }

    /**
     * @return true when this snapshot has an emergency-eligible squawk (7500/7600/7700)
     *         or ADS-B emergency status &gt; 0.
     */
    public boolean isEmergency() {
        if ("7500".equals(squawk) || "7600".equals(squawk) || "7700".equals(squawk)) return true;
        return emergencyStatus > 0;
    }

    /** @return a fresh builder with no fields populated. */
    public static Builder builder(String icaoHex) {
        return new Builder(icaoHex);
    }

    /** @return a new builder pre-populated from this snapshot (for merge-and-emit workflows). */
    public Builder toBuilder() {
        return new Builder(icaoHex)
                .callsign(callsign)
                .emitterCategory(emitterCategory)
                .squawk(squawk)
                .latitude(latitude)
                .longitude(longitude)
                .altBaroFt(altBaroFt)
                .altGeomFt(altGeomFt)
                .groundSpeedKts(groundSpeedKts)
                .trackDeg(trackDeg)
                .verticalRateFpm(verticalRateFpm)
                .onGround(onGround)
                .emergencyStatus(emergencyStatus)
                .lastSeen(lastSeen);
    }

    /**
     * Fluent builder. Not thread-safe; use one per merge operation and publish
     * the resulting {@link AdsbTrack} atomically.
     */
    public static final class Builder {
        private final String icaoHex;
        private String  callsign;
        private String  emitterCategory;
        private String  squawk;
        private double  latitude        = Double.NaN;
        private double  longitude       = Double.NaN;
        private int     altBaroFt       = Integer.MIN_VALUE;
        private int     altGeomFt       = Integer.MIN_VALUE;
        private double  groundSpeedKts  = Double.NaN;
        private double  trackDeg        = Double.NaN;
        private int     verticalRateFpm = Integer.MIN_VALUE;
        private boolean onGround;
        private int     emergencyStatus = Integer.MIN_VALUE;
        private Instant lastSeen        = Instant.EPOCH;

        Builder(String icaoHex) {
            if (icaoHex == null || icaoHex.isBlank())
                throw new IllegalArgumentException("icaoHex is required");
            this.icaoHex = icaoHex.toUpperCase();
        }

        public Builder callsign(String v)         { this.callsign = v;         return this; }
        public Builder emitterCategory(String v)  { this.emitterCategory = v;  return this; }
        public Builder squawk(String v)           { this.squawk = v;           return this; }
        public Builder latitude(double v)         { this.latitude = v;         return this; }
        public Builder longitude(double v)        { this.longitude = v;        return this; }
        public Builder altBaroFt(int v)           { this.altBaroFt = v;        return this; }
        public Builder altGeomFt(int v)           { this.altGeomFt = v;        return this; }
        public Builder groundSpeedKts(double v)   { this.groundSpeedKts = v;   return this; }
        public Builder trackDeg(double v)         { this.trackDeg = v;         return this; }
        public Builder verticalRateFpm(int v)     { this.verticalRateFpm = v;  return this; }
        public Builder onGround(boolean v)        { this.onGround = v;         return this; }
        public Builder emergencyStatus(int v)     { this.emergencyStatus = v;  return this; }
        public Builder lastSeen(Instant v)        { this.lastSeen = v;         return this; }

        public AdsbTrack build() {
            return new AdsbTrack(icaoHex, callsign, emitterCategory, squawk,
                    latitude, longitude, altBaroFt, altGeomFt,
                    groundSpeedKts, trackDeg, verticalRateFpm,
                    onGround, emergencyStatus,
                    lastSeen == Instant.EPOCH ? Instant.now() : lastSeen);
        }
    }
}
