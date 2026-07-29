package com.adsb.core;

import com.adsb.model.AdsbFrame;

import org.opensky.libadsb.ModeSDecoder;
import org.opensky.libadsb.Position;
import org.opensky.libadsb.PositionDecoder;
import org.opensky.libadsb.exceptions.BadFormatException;
import org.opensky.libadsb.exceptions.UnspecifiedFormatError;
import org.opensky.libadsb.msgs.AirbornePositionV0Msg;
import org.opensky.libadsb.msgs.AllCallReply;
import org.opensky.libadsb.msgs.EmergencyOrPriorityStatusMsg;
import org.opensky.libadsb.msgs.IdentificationMsg;
import org.opensky.libadsb.msgs.IdentifyReply;
import org.opensky.libadsb.msgs.ModeSReply;
import org.opensky.libadsb.msgs.SurfacePositionV0Msg;
import org.opensky.libadsb.msgs.VelocityOverGroundMsg;
import org.opensky.libadsb.tools;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Bridges the vendored OpenSky {@code libadsb} decoder ({@link ModeSDecoder}
 * + {@link PositionDecoder}) into our sealed {@link AdsbFrame} hierarchy
 * consumed by {@link com.adsb.model.AircraftStateStore}.
 *
 * <p><b>Why:</b> the earlier hand-rolled decoder in {@link AdsbDecoder}
 * shipped with a naked single-frame CPR approximation that aliased 5000+ km
 * off. Rather than patch it and require {@code --rx-latlon} on every
 * invocation, we delegate all position work to OpenSky's
 * {@link PositionDecoder}. That class implements the full ICAO Doc 9871
 * App. C algorithm \u2014 global (even+odd pair, no reference required),
 * local (with a reference position, single-frame), and straddle-error
 * detection. First fix appears after a matching even+odd pair arrives
 * (typically \u22645 s; ADS-B airborne position broadcasts at ~2 Hz).
 *
 * <p><b>Statefulness:</b>
 * <ul>
 *   <li>{@link ModeSDecoder} is shared as a single instance \u2014 it tracks
 *       per-ICAO ADS-B version internally and its docs recommend one
 *       instance per receiver.</li>
 *   <li>{@link PositionDecoder} is <b>per-aircraft</b>: even/odd frames must
 *       be paired against the same aircraft, so we cache one instance
 *       per ICAO hex. Lookup is a {@link ConcurrentHashMap#computeIfAbsent}.
 *       Never evicted here \u2014 the {@link com.adsb.model.AircraftStateStore}
 *       already has TTL eviction and can drive a corresponding cleanup
 *       hook if aircraft-list growth becomes a concern.</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> {@link ModeSDecoder} and {@link PositionDecoder}
 * are both single-threaded by contract. Our production caller
 * ({@link AdsbReceiver}) reads one AVR line at a time from the
 * {@code rtl_adsb} stdout stream, so there is only ever one thread inside
 * this class. If that changes (e.g. a worker pool is added), synchronise
 * at the {@link ModeSDecoder} call and per-{@link PositionDecoder}.
 */
public final class OpenSkyFrameAdapter {

    private final ModeSDecoder modeSDecoder = new ModeSDecoder();
    private final ConcurrentMap<String, PositionDecoder> positionDecoders = new ConcurrentHashMap<>();

    /**
     * Per-ICAO record of the last position we accepted. Used by
     * {@link #sanityCheckPosition} to reject frames whose implied
     * speed since the last accepted position exceeds
     * {@link #MAX_PLAUSIBLE_SPEED_KTS}.
     *
     * <p>Written on ACCEPT only, never on reject -- rejected positions
     * leave the aircraft at its last known good location downstream
     * (map, table, CoT connectors) per Marty's 2026-07-27 15:35 UTC
     * direction. The next in-range frame will move it.
     */
    private final ConcurrentMap<String, LastGoodPosition> lastGoodPositions = new ConcurrentHashMap<>();

    /**
     * Max plausible aircraft ground speed, knots. OpenSky's own
     * {@code PositionDecoder.withinThreshold} uses 514.4 m/s * 2.5 =
     * 1286 m/s ≈ 2500 kts for airborne targets. We used to run at
     * 1200 kts, which caused mass false-rejects on Marty's 2026-07-29
     * WARN log: rtl_adsb buffers frames in bursts and inter-frame dt
     * can compress from a wire-real 5s down to 0.5s, so a real 500
     * kt jet appears as an implied 5000 kt jump. 2500 kts still
     * three-orders-of-magnitude-catches the real glitches
     * (85000/212000/296000 kts) reported in the same log.
     */
    static final double MAX_PLAUSIBLE_SPEED_KTS = 2500.0;

    /**
     * OpenSky-derived jitter tolerance: when two consecutive frames
     * arrive so close in time (< {@value #JITTER_DT_SECONDS} s) AND
     * so close in space (< {@value #JITTER_DISTANCE_NM} nm) that the
     * distance is realistic no matter what the implied speed says,
     * accept unconditionally. Mirrors
     * {@code PositionDecoder.withinThreshold} logic verbatim: 0.7 s
     * and 2000 m ({@code ≈} 1.08 nm). rtl_adsb over USB is bursty;
     * without this bypass, back-to-back frames of a real, stationary-
     * ish target look like impossible-speed jumps.
     */
    static final double JITTER_DT_SECONDS  = 0.7;
    static final double JITTER_DISTANCE_NM = 2000.0 / 1852.0;   // 1.0799 nm

    /**
     * Ignore the last-good check when the previous accepted frame is
     * older than this many seconds. An aircraft that drops out of
     * range for a minute may legitimately reappear 100 nm away -- we
     * can't distinguish that from a glitch, so let OpenSky's own
     * reasonableness check be the only gate at that point.
     */
    static final double LAST_GOOD_TTL_SECONDS = 60.0;

    // ------------------------------------------------------------------
    // Receiver-relative geofence (Marty 2026-07-29 07:37 UTC).
    //
    // The speed check catches jumps AWAY from a known good anchor,
    // but has three well-understood holes:
    //   1. First fix (last == null) always accepts.
    //   2. dt > LAST_GOOD_TTL_SECONDS bypasses the check.
    //   3. dt <= 0 (clock skew / same-ms arrival) bypasses.
    // The geofence closes ALL THREE holes by fixing an absolute box
    // around the receiver: anything decoded outside the box is
    // rejected no matter what the anchor state is.
    //
    // Two configuration modes:
    //   A. Explicit: caller supplies receiver lat/lon (via setReceiverPosition).
    //   B. Bootstrap: no reference provided; adapter learns the
    //      receiver's approximate location from the first
    //      BOOTSTRAP_SAMPLES accepted positions (centroid + observed
    //      radius + BOOTSTRAP_HALO_NM safety margin). Preserves the
    //      "runs anywhere" contract from the 2026-07-27 ed8f4ef
    //      commit while still catching wild-longitude aliasing.
    // ------------------------------------------------------------------

    /** Default outer envelope for the receiver-range check, in nm. Public so CLI help/log can reference it. */
    public static final double DEFAULT_MAX_RANGE_NM = 350.0;

    /** How many first accepted fixes to collect before enabling the bootstrap geofence. Public so CLI log can reference it. */
    public static final int BOOTSTRAP_SAMPLES = 20;

    /**
     * Safety halo added on top of the observed radius when the geofence
     * is derived statistically. Guards against the bootstrap set being
     * a tight cluster around one busy airway; a real target 100 nm
     * further out shouldn't be rejected just because the first 20
     * fixes happened to land inside 50 nm.
     */
    static final double BOOTSTRAP_HALO_NM = 50.0;

    /** Explicit receiver lat/lon, or null when running in bootstrap mode. Written once at boot. */
    private volatile Double explicitRxLat = null;
    private volatile Double explicitRxLon = null;

    /** Max-range budget (nm). Applies in both explicit and bootstrap modes. Written once at boot. */
    private volatile double maxRangeNm = DEFAULT_MAX_RANGE_NM;

    /** Bootstrap sample buffer. Holds accepted (lat, lon) pairs until BOOTSTRAP_SAMPLES land. */
    private final java.util.List<double[]> bootstrapSamples =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** Derived bootstrap geofence: null until BOOTSTRAP_SAMPLES accepted fixes have been seen. */
    private volatile double[] bootstrapCentroid = null;  // [lat, lon]
    private volatile double   bootstrapRadiusNm = 0.0;

    /**
     * Configure the geofence.
     *
     * @param rxLat receiver latitude, or {@code null} to run in
     *              statistical bootstrap mode
     * @param rxLon receiver longitude, or {@code null} to run in
     *              statistical bootstrap mode
     * @param maxRangeNm outer envelope in nm; pass {@code <= 0} to use
     *              {@link #DEFAULT_MAX_RANGE_NM}. Applies to both modes.
     *
     * <p>Must be called at boot before frames start flowing; changing
     * this at runtime is safe but the old envelope may have already
     * accepted a fix beyond the new one.
     */
    public void configureGeofence(Double rxLat, Double rxLon, double maxRangeNm) {
        if (rxLat != null && rxLon != null
                && !Double.isNaN(rxLat) && !Double.isNaN(rxLon)
                && rxLat >= -90.0 && rxLat <= 90.0
                && rxLon >= -180.0 && rxLon <= 180.0) {
            this.explicitRxLat = rxLat;
            this.explicitRxLon = rxLon;
        } else {
            this.explicitRxLat = null;
            this.explicitRxLon = null;
        }
        this.maxRangeNm = (maxRangeNm > 0.0) ? maxRangeNm : DEFAULT_MAX_RANGE_NM;
    }

    /** Test hook: read the currently-effective geofence centre, or null if bootstrap not yet ready. */
    double[] effectiveGeofenceCentre() {
        if (explicitRxLat != null && explicitRxLon != null) {
            return new double[] { explicitRxLat, explicitRxLon };
        }
        return bootstrapCentroid;
    }

    /** Test hook: read the currently-effective geofence radius (nm). Returns 0 when not yet armed. */
    double effectiveGeofenceRadiusNm() {
        if (explicitRxLat != null && explicitRxLon != null) return maxRangeNm;
        if (bootstrapCentroid == null) return 0.0;
        return Math.min(maxRangeNm, bootstrapRadiusNm + BOOTSTRAP_HALO_NM);
    }

    /** Per-ICAO rate-limit for the reject WARN so a glitchy aircraft doesn't spam stderr. */
    private static final long REJECT_WARN_INTERVAL_MS = 5_000L;
    private final ConcurrentMap<String, Long> lastRejectWarnMs = new ConcurrentHashMap<>();

    /** Last accepted position for one ICAO. Package-private for tests. */
    record LastGoodPosition(double lat, double lon, double timeSec) {}

    /**
     * Test-only hook: pre-seed the last-good record for an ICAO so a
     * unit test can exercise the speed-check path without having to
     * push a real ADS-B frame through the whole decode pipeline.
     * Production code should never call this.
     */
    void seedLastGoodForTest(String icaoHex, double lat, double lon, double timeSec) {
        lastGoodPositions.put(icaoHex.toUpperCase(),
                new LastGoodPosition(lat, lon, timeSec));
    }

    /**
     * Decode one AVR line into a typed {@link AdsbFrame}. Returns
     * {@code null} when the frame is malformed, unsupported, or (for
     * position frames) when the even+odd pair hasn't yet arrived so an
     * absolute position can't yet be computed.
     */
    public AdsbFrame decode(String avrLine) {
        byte[] raw = parseAvr(avrLine);
        if (raw == null) return null;

        ModeSReply reply;
        try {
            reply = modeSDecoder.decode(raw);
        } catch (BadFormatException | UnspecifiedFormatError | RuntimeException e) {
            // OpenSky can throw NumberFormatException / IllegalArgumentException
            // for garbage frames; treat as unparseable, don't propagate.
            return null;
        }
        if (reply == null) return null;

        String icao = tools.toHexString(reply.getIcao24()).toUpperCase();
        double timeSec = System.currentTimeMillis() / 1000.0;

        // Order matters: check the more-specific subtypes first because
        // several message classes extend AirbornePositionV0Msg /
        // VelocityOverGroundMsg etc. through the v1/v2 inheritance chain.
        if (reply instanceof IdentificationMsg id) {
            return toIdentification(icao, id);
        }
        if (reply instanceof AirbornePositionV0Msg pos) {
            return toAirbornePosition(icao, pos, timeSec);
        }
        if (reply instanceof SurfacePositionV0Msg spos) {
            return toSurfacePosition(icao, spos, timeSec);
        }
        if (reply instanceof VelocityOverGroundMsg vel) {
            return toVelocity(icao, vel);
        }
        if (reply instanceof EmergencyOrPriorityStatusMsg em) {
            return new AdsbFrame.AircraftStatus(icao, em.getEmergencyStateCode());
        }
        if (reply instanceof IdentifyReply ir) {
            // Short surveillance identity reply \u2014 carries squawk (Mode 3/A).
            return new AdsbFrame.SurveillanceIdentity(icao, ir.getIdentity());
        }
        if (reply instanceof AllCallReply) {
            return new AdsbFrame.AllCall(icao);
        }
        // Everything else (Comm-B replies, ACAS, altitude replies, TC 28/31
        // operational status \u2026) is real data but doesn't need to reach the
        // state store for CoT purposes yet.
        return null;
    }

    /**
     * Package-private hook for TTL eviction. Removes the per-aircraft
     * {@link PositionDecoder} for the given ICAO. Safe to call while
     * decode() is running on other threads (map is concurrent), though
     * currently only one decoder thread exists.
     */
    void evict(String icaoHex) {
        if (icaoHex != null) {
            String key = icaoHex.toUpperCase();
            positionDecoders.remove(key);
            lastGoodPositions.remove(key);
            lastRejectWarnMs.remove(key);
        }
    }

    /**
     * Validate lat/lon range + plausibility of implied speed since the
     * last accepted position. Package-private so tests can drive it
     * directly without going through the full decode pipeline.
     *
     * @return true if the position should be accepted, false to reject
     *         (and keep the previous good position downstream)
     */
    boolean sanityCheckPosition(String icao, double lat, double lon, double timeSec) {
        // Rule 1: range + NaN check.
        if (Double.isNaN(lat) || Double.isNaN(lon)
                || lat < -90.0 || lat > 90.0
                || lon < -180.0 || lon > 180.0) {
            warnReject(icao, "out-of-range lat=" + lat + " lon=" + lon);
            return false;
        }

        // Rule 2: receiver-relative geofence -- the catch-all that fires
        // on the FIRST bad frame regardless of anchor. Runs in one of
        // two modes; see the field-doc block on maxRangeNm above.
        if (!withinGeofence(lat, lon)) {
            double[] c = effectiveGeofenceCentre();
            double r  = effectiveGeofenceRadiusNm();
            warnReject(icao, String.format(
                    "outside %.0f nm geofence (centre %.4f,%.4f; dist %.1f nm)",
                    r, c[0], c[1], haversineNm(c[0], c[1], lat, lon)));
            return false;
        }

        // Rule 3: implied-speed check vs last accepted position.
        LastGoodPosition last = lastGoodPositions.get(icao);
        if (last == null) return true;                    // first fix; nothing to compare
        double dtSec = timeSec - last.timeSec();
        if (dtSec <= 0.0) return true;                    // out-of-order or same timestamp
        if (dtSec > LAST_GOOD_TTL_SECONDS) return true;   // stale last-good; skip check

        double distNm = haversineNm(last.lat(), last.lon(), lat, lon);

        // OpenSky-derived jitter bypass: back-to-back frames of a
        // near-stationary target shouldn't trip the speed check just
        // because rtl_adsb's stdout buffer emitted them 0.2s apart.
        // See JITTER_DT_SECONDS / JITTER_DISTANCE_NM javadoc for why.
        if (dtSec < JITTER_DT_SECONDS && distNm < JITTER_DISTANCE_NM) {
            return true;
        }

        double impliedKts = distNm / (dtSec / 3600.0);
        if (impliedKts > MAX_PLAUSIBLE_SPEED_KTS) {
            warnReject(icao, String.format(
                    "implied speed %.0f kts over %.1fs (%.1f nm) exceeds %.0f kts ceiling",
                    impliedKts, dtSec, distNm, MAX_PLAUSIBLE_SPEED_KTS));
            return false;
        }
        return true;
    }

    /**
     * True when the given position is inside the receiver-relative
     * geofence, OR when the geofence hasn't yet armed (bootstrap mode
     * with < BOOTSTRAP_SAMPLES accepted fixes). In the pre-arm window
     * we only rely on the speed check and OpenSky's own
     * isReasonable() -- that's a deliberate trade: without a reference,
     * we can't fence, so the first ~20 fixes are gated only by the
     * upstream OpenSky checks. In practice the first fixes ARE close
     * to the receiver because ADS-B LOS drops off sharply beyond
     * ~350 nm, so the bootstrap centroid settles quickly.
     */
    private boolean withinGeofence(double lat, double lon) {
        // Explicit mode: always active from the very first frame.
        if (explicitRxLat != null && explicitRxLon != null) {
            return haversineNm(explicitRxLat, explicitRxLon, lat, lon) <= maxRangeNm;
        }

        // Bootstrap mode: armed only after BOOTSTRAP_SAMPLES accepted fixes.
        double[] centre = bootstrapCentroid;
        if (centre == null) return true;   // not yet armed

        // Effective envelope = min(explicit ceiling, observed radius + halo).
        // The min() protects against a bootstrap that observed a tight
        // cluster: we never widen beyond maxRangeNm, even if the
        // observed radius + halo would.
        double envelope = Math.min(maxRangeNm, bootstrapRadiusNm + BOOTSTRAP_HALO_NM);
        return haversineNm(centre[0], centre[1], lat, lon) <= envelope;
    }

    /**
     * Feed one just-accepted position into the bootstrap sampler.
     * When the sample count reaches {@link #BOOTSTRAP_SAMPLES}, derive
     * the centroid + max observed radius and arm the geofence.
     * No-op when running in explicit mode.
     */
    private void feedBootstrap(double lat, double lon) {
        if (explicitRxLat != null && explicitRxLon != null) return;
        if (bootstrapCentroid != null) return; // already armed
        synchronized (bootstrapSamples) {
            bootstrapSamples.add(new double[] { lat, lon });
            if (bootstrapSamples.size() < BOOTSTRAP_SAMPLES) return;
            double sumLat = 0.0, sumLon = 0.0;
            for (double[] p : bootstrapSamples) { sumLat += p[0]; sumLon += p[1]; }
            double cLat = sumLat / bootstrapSamples.size();
            double cLon = sumLon / bootstrapSamples.size();
            double maxNm = 0.0;
            for (double[] p : bootstrapSamples) {
                double d = haversineNm(cLat, cLon, p[0], p[1]);
                if (d > maxNm) maxNm = d;
            }
            bootstrapCentroid = new double[] { cLat, cLon };
            bootstrapRadiusNm = maxNm;
            bootstrapSamples.clear();  // no longer needed; free memory
            System.err.printf(
                    "[INFO] Geofence armed (bootstrap): centre %.4f,%.4f, envelope %.1f nm%n",
                    cLat, cLon, Math.min(maxRangeNm, maxNm + BOOTSTRAP_HALO_NM));
        }
    }

    /** Great-circle distance in nautical miles. */
    static double haversineNm(double lat1, double lon1, double lat2, double lon2) {
        double R_NM = 3440.065; // mean Earth radius in nm
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLam = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                 + Math.cos(phi1) * Math.cos(phi2)
                 * Math.sin(dLam / 2) * Math.sin(dLam / 2);
        return 2 * R_NM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** Rate-limited per-ICAO WARN so glitchy frames don't spam stderr. */
    private void warnReject(String icao, String detail) {
        long now = System.currentTimeMillis();
        Long prev = lastRejectWarnMs.get(icao);
        if (prev != null && now - prev < REJECT_WARN_INTERVAL_MS) return;
        lastRejectWarnMs.put(icao, now);
        System.err.printf("[WARN] Rejected implausible position for %s: %s%n", icao, detail);
    }

    /** @return count of per-aircraft position decoders cached. Test hook. */
    int positionDecoderCount() {
        return positionDecoders.size();
    }

    // ------------------------------------------------------------------
    // per-message conversion
    // ------------------------------------------------------------------

    private static AdsbFrame toIdentification(String icao, IdentificationMsg id) {
        String callsign = new String(id.getIdentity()).trim();
        // Callsigns pad with '#' in the CoT-community-adopted convention;
        // OpenSky returns spaces \u2014 further clean any trailing junk.
        if (callsign.isEmpty()) callsign = null;

        // Emitter category: OpenSky exposes it as a raw byte (0..7) plus a
        // "form set" implied by the TC (1..4). Encode to the "A1".."B2"
        // strings CoTBuilder / IcaoAircraftClassifier expect.
        String categoryCode = emitterCategoryCode(id);

        return new AdsbFrame.Identification(icao, callsign, categoryCode);
    }

    private AdsbFrame toAirbornePosition(String icao, AirbornePositionV0Msg pos, double timeSec) {
        if (!pos.hasPosition()) return null;

        PositionDecoder pd = positionDecoders.computeIfAbsent(icao, k -> new PositionDecoder());
        Position p = pd.decodePosition(timeSec, pos);
        if (p == null || p.getLatitude() == null || p.getLongitude() == null) {
            // Even+odd pair not yet complete, or straddle-error detected
            // -- wait for next matching frame.
            return null;
        }
        if (!p.isReasonable()) {
            // OpenSky flags positions that failed its sanity envelope
            // (usually straddle-boundary or NIC-exceeds-range). Drop it
            // rather than push a suspect fix into TAK.
            return null;
        }

        // Second-layer sanity check (Marty 2026-07-27 15:35 UTC, SkyLord
        // screenshot showed jumps of ~500 nm between frames): OpenSky's
        // isReasonable() catches most bad frames but occasionally lets a
        // wild-longitude blip through. Reject positions that are out of
        // range OR would imply an aircraft moved faster than
        // MAX_PLAUSIBLE_SPEED_KTS since our last accepted position. On
        // reject we keep the previous good position downstream by
        // returning null (state store keeps whatever it had).
        if (!sanityCheckPosition(icao, p.getLatitude(), p.getLongitude(), timeSec)) {
            return null;
        }
        lastGoodPositions.put(icao,
                new LastGoodPosition(p.getLatitude(), p.getLongitude(), timeSec));
        feedBootstrap(p.getLatitude(), p.getLongitude());

        // OpenSky's AirbornePositionV0Msg.getAltitude() returns the
        // DECODED value from the 12-bit AC field of a DF17/18 TC 9-18
        // (or TC 20-22) frame -- units are FEET per DO-260B (Q=1 -> 25 ft
        // increments offset -1000 ft; Q=0 -> Gillham gray-code, 100/500 ft).
        // Do NOT convert with /0.3048 here; that would divide feet by
        // metres-per-foot and shrink every altitude by 3.281x (35000 ft
        // aircraft would show up as ~10668 ft in TAK, matching the
        // "altitude way off" field bug 2026-07-28 08:00 UTC).
        int altFt = Integer.MIN_VALUE;
        if (pos.hasAltitude()) {
            Integer altFtBoxed = pos.getAltitude();
            if (altFtBoxed != null) altFt = altFtBoxed;
        }
        // AirbornePositionV0Msg vs V2Msg: v2 messages report GNSS geometric
        // altitude when the frame's TC is 20-22. The base class exposes
        // hasAltitude() uniformly; we tag geometric based on the wrapper
        // subclass (v0 wrapper == baro; v1/v2 wrapper doesn't distinguish
        // here -- keep as baro, which matches current behaviour.
        // GNSS-geometric preference is tracked in issue #6.)
        boolean geometric = false;

        return new AdsbFrame.AirbornePosition(icao,
                p.getLatitude(), p.getLongitude(), altFt, geometric);
    }

    private AdsbFrame toSurfacePosition(String icao, SurfacePositionV0Msg spos, double timeSec) {
        // Surface positions need a reference to disambiguate (they cover a
        // ~7 km zone). Without a receiver reference we can't decode them
        // globally, so skip \u2014 surface tracks (taxi/pushback) aren't the
        // priority target here anyway.
        return null;
    }

    private static AdsbFrame toVelocity(String icao, VelocityOverGroundMsg vel) {
        if (!vel.hasVelocityInfo()) return null;
        Double speed = vel.getVelocity();      // knots
        Double heading = vel.getHeading();     // deg true
        Integer vrate = vel.hasVerticalRateInfo() ? vel.getVerticalRate() : null; // ft/min
        if (speed == null || heading == null) return null;
        return new AdsbFrame.AirborneVelocity(icao,
                speed, heading,
                vrate == null ? Integer.MIN_VALUE : vrate);
    }

    /**
     * Reconstruct the DO-260B emitter-category string ({@code "A1".."D7"}).
     * OpenSky exposes the raw subtype byte via
     * {@link IdentificationMsg#getEmitterCategory()} but not the set
     * letter; the set is derived from the frame's Type Code, exposed via
     * the base {@code ExtendedSquitter} format-type-code getter.
     */
    private static String emitterCategoryCode(IdentificationMsg id) {
        byte cat = id.getEmitterCategory();
        if (cat == 0) return null; // no category info

        int tc = id.getFormatTypeCode();
        // ADS-B ident TC-to-set-letter mapping (per DO-260B):
        //   TC 4 -> A
        //   TC 3 -> B
        //   TC 2 -> C
        //   TC 1 -> D
        char set;
        switch (tc) {
            case 4: set = 'A'; break;
            case 3: set = 'B'; break;
            case 2: set = 'C'; break;
            case 1: set = 'D'; break;
            default: return null;
        }
        return "" + set + cat;
    }

    /** Trim leading * and trailing ; then hex-decode. Null on any parse issue. */
    private static byte[] parseAvr(String avrLine) {
        if (avrLine == null) return null;
        String hex = avrLine.trim();
        if (hex.startsWith("*")) hex = hex.substring(1);
        if (hex.endsWith(";"))   hex = hex.substring(0, hex.length() - 1);
        hex = hex.trim();
        if (hex.isEmpty() || hex.length() < 2 || (hex.length() & 1) != 0) return null;
        try {
            int n = hex.length() / 2;
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) {
                int hi = Character.digit(hex.charAt(2*i),   16);
                int lo = Character.digit(hex.charAt(2*i+1), 16);
                if (hi < 0 || lo < 0) return null;
                out[i] = (byte) ((hi << 4) | lo);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
