package com.adsb.cot;

import com.adsb.model.AdsbTrack;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Builds an ATAK / GCCS-J CoT XML document for a single {@link AdsbTrack} snapshot.
 *
 * <p>Structural template lifted from the {@code cotproto/CotXmlBuilder} in the
 * {@code tmsweb3190} monorepo — that shape is wire-tested against ATAK,
 * WinTAK, and GCCS-J COP CoT ingest, so we're not re-inventing the escape
 * or ROOT-locale numeric formatting logic that took two rounds of bug-hunts
 * in {@code cotproto} to settle.
 *
 * <p>Divergence from the ground-vehicle template:
 * <ul>
 *   <li>{@code type} comes from {@link IcaoAircraftClassifier} — default
 *       {@code a-n-A-C-F} (neutral civilian fixed-wing).</li>
 *   <li>{@code uid} is {@code ICAO-<hex_upper>} so multiple receivers seeing
 *       the same aircraft converge on one track in TAK Server.</li>
 *   <li>No {@code <__group>} element — that's exclusive to TAK team members;
 *       an aircraft is not a "Blue / Team Member".</li>
 *   <li>{@code stale} defaults to 30s airborne / 120s ground — 120s airborne
 *       would represent ~30 nm of extrapolated position at 500 kt, which is
 *       tactically nonsensical.</li>
 *   <li>{@code <point>}: {@code hae} in metres from feet, prefers geometric
 *       altitude when available. {@code ce}/{@code le} = 9999999.0 sentinel
 *       for now (real NACp lookup lands in issue #6).</li>
 *   <li>{@code <track>} element is omitted (not emitted with zeros) when
 *       velocity is not yet known — the receiver keeps whatever it had.</li>
 * </ul>
 *
 * <p>The builder is stateless and thread-safe.
 */
public final class CoTBuilder {

    /** CoT schema version pinned in {@code <event version="...">}. */
    private static final String COT_VERSION = "2.0";

    /** ADS-B is self-reported GPS-derived position relayed via RF. */
    private static final String COT_HOW = "m-g";

    /** Sentinel used for unknown altitude and unknown accuracy. */
    private static final double UNKNOWN_9S = 9_999_999.0;

    /** Feet → metres. */
    private static final double FEET_TO_METRES = 0.3048;

    /** Knots → metres/second. */
    private static final double KTS_TO_MPS = 0.514444;

    /** ISO-8601 UTC millisecond formatter, matches ATAK / TAK-server / tmsweb parsers. */
    private static final DateTimeFormatter ISO_MS_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final IcaoAircraftClassifier classifier;
    private final int staleAirSeconds;
    private final int staleGroundSeconds;

    /**
     * @param classifier          type-string producer
     * @param staleAirSeconds     stale offset for airborne tracks (typical 30)
     * @param staleGroundSeconds  stale offset for on-ground tracks (typical 120)
     */
    public CoTBuilder(IcaoAircraftClassifier classifier,
                      int staleAirSeconds, int staleGroundSeconds) {
        if (classifier == null) throw new IllegalArgumentException("classifier");
        if (staleAirSeconds    <= 0) throw new IllegalArgumentException("staleAirSeconds");
        if (staleGroundSeconds <= 0) throw new IllegalArgumentException("staleGroundSeconds");
        this.classifier         = classifier;
        this.staleAirSeconds    = staleAirSeconds;
        this.staleGroundSeconds = staleGroundSeconds;
    }

    /**
     * Convenience: 30s airborne / 120s ground, default classifier
     * (neutral civilian, no CLI overrides).
     */
    public static CoTBuilder defaults() {
        return new CoTBuilder(
                new IcaoAircraftClassifier(null, null),
                30, 120);
    }

    /**
     * @param track snapshot; must be non-null. If {@link AdsbTrack#hasPosition()}
     *              is false this method returns null — a positionless CoT event
     *              is useless to a TAK receiver and would just clutter the feed.
     * @return complete single-line CoT XML document, or {@code null} when the
     *         snapshot has no lat/lon yet.
     */
    public String build(AdsbTrack track) {
        if (track == null) throw new IllegalArgumentException("track");
        if (!track.hasPosition()) return null;

        Instant now   = track.lastSeen();
        int staleSec  = track.onGround() ? staleGroundSeconds : staleAirSeconds;
        String tNow   = ISO_MS_UTC.format(now);
        String tStale = ISO_MS_UTC.format(now.plusSeconds(staleSec));

        String type  = classifier.classify(track.icaoHex(), track.emitterCategory());
        String uid   = "ICAO-" + track.icaoHex();

        double haeM  = (track.preferredAltFt() == Integer.MIN_VALUE)
                ? UNKNOWN_9S
                : track.preferredAltFt() * FEET_TO_METRES;

        StringBuilder xml = new StringBuilder(512);
        xml.append("<?xml version='1.0' standalone='yes'?>")
           .append("<event version=\"").append(COT_VERSION).append("\"")
           .append(" type=\"").append(type).append("\"")
           .append(" uid=\"").append(xmlAttrEscape(uid)).append("\"")
           .append(" how=\"").append(COT_HOW).append("\"")
           .append(" time=\"").append(tNow).append("\"")
           .append(" start=\"").append(tNow).append("\"")
           .append(" stale=\"").append(tStale).append("\">");

        xml.append("<point")
           .append(" lat=\"").append(fmt6(track.latitude())).append("\"")
           .append(" lon=\"").append(fmt6(track.longitude())).append("\"")
           .append(" hae=\"").append(fmt1(haeM)).append("\"")
           .append(" ce=\"").append(fmt1(UNKNOWN_9S)).append("\"")
           .append(" le=\"").append(fmt1(UNKNOWN_9S)).append("\"")
           .append("/>");

        xml.append("<detail>");

        // <contact> — mandatory in practice (label in TAK).
        String cs = callsignFor(track);
        xml.append("<contact callsign=\"").append(xmlAttrEscape(cs)).append("\"/>");

        // <track> — only when we actually know speed AND heading; TAK ignores
        // element when absent and keeps its last-known value.
        if (!Double.isNaN(track.groundSpeedKts()) && !Double.isNaN(track.trackDeg())) {
            double speedMps = track.groundSpeedKts() * KTS_TO_MPS;
            xml.append("<track")
               .append(" speed=\"").append(fmt2(speedMps)).append("\"")
               .append(" course=\"").append(fmt1(normalizeCourse(track.trackDeg()))).append("\"")
               .append("/>");
        }

        // <remarks> — freeform; useful for humans reading raw CoT.
        xml.append("<remarks>").append(xmlAttrEscape(remarksFor(track))).append("</remarks>");

        xml.append("</detail>");
        xml.append("</event>");
        return xml.toString();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Fallback chain: flight callsign → registration (not yet plumbed) → {@code ICAO-<hex>}. */
    private static String callsignFor(AdsbTrack t) {
        if (t.callsign() != null && !t.callsign().isBlank()) return t.callsign().trim();
        return "ICAO-" + t.icaoHex();
    }

    private static String remarksFor(AdsbTrack t) {
        StringBuilder r = new StringBuilder(64);
        if (t.callsign() != null && !t.callsign().isBlank()) r.append(t.callsign().trim()).append(' ');
        r.append(t.icaoHex());
        if (t.squawk() != null) r.append(" SQUAWK ").append(t.squawk());
        if (t.emitterCategory() != null) r.append(" CAT ").append(t.emitterCategory());
        if (t.preferredAltFt() != Integer.MIN_VALUE) r.append(" ALT ").append(t.preferredAltFt()).append("ft");
        if (t.isEmergency()) r.append(" EMERGENCY");
        return r.toString();
    }

    private static double normalizeCourse(double deg) {
        // CoT expects 0..360; ADS-B track can occasionally arrive slightly negative
        // from float rounding on the atan2 path.
        double d = deg % 360.0;
        return d < 0 ? d + 360.0 : d;
    }

    private static String fmt6(double v) { return String.format(Locale.ROOT, "%.6f", v); }
    private static String fmt2(double v) { return String.format(Locale.ROOT, "%.2f", v); }
    private static String fmt1(double v) { return String.format(Locale.ROOT, "%.1f", v); }

    /**
     * Escapes the five XML predefined entities so user-supplied text is safe
     * to embed in a double-quoted attribute value or in element content.
     * Fast-path: scans first; returns the input unchanged when no escape needed.
     * Package-private so tests can pin this behaviour without going through
     * the whole document builder (matches cotproto convention).
     */
    static String xmlAttrEscape(String in) {
        if (in == null || in.isEmpty()) return "";
        boolean needs = false;
        for (int i = 0, n = in.length(); i < n; i++) {
            char c = in.charAt(i);
            if (c == '&' || c == '<' || c == '>' || c == '"' || c == '\'') { needs = true; break; }
        }
        if (!needs) return in;

        StringBuilder sb = new StringBuilder(in.length() + 16);
        for (int i = 0, n = in.length(); i < n; i++) {
            char c = in.charAt(i);
            switch (c) {
                case '&':  sb.append("&amp;");  break;
                case '<':  sb.append("&lt;");   break;
                case '>':  sb.append("&gt;");   break;
                case '"':  sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }
}
