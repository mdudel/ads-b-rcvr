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
        if (icaoHex != null) positionDecoders.remove(icaoHex.toUpperCase());
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
            // Even+odd pair not yet complete, or straddle-error detected \u2014
            // wait for next matching frame.
            return null;
        }
        if (!p.isReasonable()) {
            // OpenSky flags positions that failed its sanity envelope
            // (usually straddle-boundary or NIC-exceeds-range). Drop it
            // rather than push a suspect fix into TAK.
            return null;
        }

        int altFt = Integer.MIN_VALUE;
        if (pos.hasAltitude()) {
            Integer altM = pos.getAltitude();
            if (altM != null) altFt = (int) Math.round(altM / 0.3048);
        }
        // AirbornePositionV0Msg vs V2Msg: v2 messages report GNSS geometric
        // altitude when the frame's TC is 20-22. The base class exposes
        // hasAltitude() uniformly; we tag geometric based on the wrapper
        // subclass (v0 wrapper == baro; v1/v2 wrapper doesn't distinguish
        // here \u2014 keep as baro, which matches Marty's current pom-independent
        // behaviour. GNSS-geometric preference is tracked in #6.).
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
