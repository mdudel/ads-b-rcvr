package com.adsb.core;

/**
 * Which position-plausibility filter runs inside
 * {@link OpenSkyFrameAdapter#sanityCheckPosition(String, double, double, double)}.
 *
 * <p>Introduced 2026-07-29 after Marty's SkyLord jumping-tracks complaint.
 * The earlier work landed a receiver-relative geofence that was rejected
 * as "hokey" -- it draws a static box on the map without regard for the
 * physics of the specific track. This enum lets the operator pick.
 *
 * <p><b>Modes:</b>
 * <ul>
 *   <li>{@link #KINEMATIC} (default) -- per-track physics gate: budget =
 *       last-known speed (reported OR derived-from-positions) * 3 + 200
 *       kt headroom, hard-capped at 2500 kts. Rejects frames whose great-
 *       circle distance from the last accepted position exceeds
 *       budget * dt. Uses OpenSky's jitter bypass (dt&lt;0.7s AND
 *       d&lt;1.08 nm) as a short-circuit accept for bursty rtl_adsb
 *       stdout timing compression.</li>
 *   <li>{@link #GEOFENCE} -- receiver-relative envelope. Requires
 *       {@code --rx-latlon} for explicit setup, else falls back to the
 *       statistical bootstrap that arms after ~20 accepted fixes.</li>
 *   <li>{@link #BOTH} -- kinematic gate first, then geofence. Belt and
 *       braces; rejects the union of both.</li>
 *   <li>{@link #OFF} -- accept every position OpenSky's own
 *       {@code isReasonable()} accepts. Debug escape hatch; also useful
 *       to isolate a suspected filter false-positive by turning it off
 *       and observing raw behaviour.</li>
 * </ul>
 */
public enum FilterMode {
    KINEMATIC,
    GEOFENCE,
    BOTH,
    OFF;

    /** Canonical lowercase name for CLI + properties file. */
    public String canonical() { return name().toLowerCase(java.util.Locale.ROOT); }

    /**
     * Parse a canonical name (case-insensitive). Returns {@link #KINEMATIC}
     * on null / unknown so a corrupted properties file can't stop the
     * receiver from starting.
     */
    public static FilterMode fromString(String s) {
        if (s == null) return KINEMATIC;
        return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "kinematic" -> KINEMATIC;
            case "geofence"  -> GEOFENCE;
            case "both"      -> BOTH;
            case "off"       -> OFF;
            default          -> KINEMATIC;
        };
    }
}
