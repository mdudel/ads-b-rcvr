package com.adsb.core;

import com.adsb.model.AdsbFrame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link OpenSkyFrameAdapter}, the bridge from OpenSky's
 * {@code libadsb} to our sealed {@link AdsbFrame} hierarchy.
 *
 * <p>The critical behavioural pin is {@link
 * #global_cpr_pair_decodes_without_any_reference_position()}: feed
 * matched even+odd pairs (encoded from a known truth position) with
 * <b>no receiver reference</b>, confirm the adapter eventually returns
 * an {@link AdsbFrame.AirbornePosition} close to truth. This is the
 * whole reason we ripped out {@code --rx-latlon}: the receiver now
 * runs anywhere without knowledge of its own location.
 *
 * <p>Frames are constructed in-test via {@link #encodeAirbornePosition}
 * so the tests are hermetic \u2014 no external captured-frame fixtures
 * required, and the "expected" position is by construction whatever we
 * asked the encoder for.
 */
class OpenSkyFrameAdapterTest {

    @Test
    void global_cpr_pair_decodes_without_any_reference_position() {
        // Truth: mid-Germany, ICAO 40621D (arbitrary), TC 11 (airborne baro),
        // altitude 38000 ft. Encode alternating even+odd for a few frames
        // \u2014 OpenSky's PositionDecoder gates position emission behind a
        // 3-reasonable-message warmup (num_reasonable > 2), so a single
        // pair isn't enough.
        double truthLat = 49.98;
        double truthLon =  8.55;
        String icao = "40621D";

        OpenSkyFrameAdapter a = new OpenSkyFrameAdapter();

        List<String> frames = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            frames.add(encodeAirbornePosition(icao, truthLat, truthLon, /*F*/ 0));
            frames.add(encodeAirbornePosition(icao, truthLat, truthLon, /*F*/ 1));
        }

        AdsbFrame.AirbornePosition last = null;
        int posCount = 0;
        for (String f : frames) {
            AdsbFrame decoded = a.decode(f);
            if (decoded instanceof AdsbFrame.AirbornePosition p) {
                last = p;
                posCount++;
            }
        }

        assertNotNull(last, "at least one AirbornePosition must eventually emerge");
        assertTrue(posCount >= 1, "at least one position frame expected, saw " + posCount);
        assertEquals(icao, last.icaoHex());
        // Truth vs decoded \u2014 the whole point is to be near truth without any
        // reference. Round-trip accuracy of CPR is metres, not km.
        assertEquals(truthLat, last.latitude(),  0.001,
                "lat should decode within 0.001\u00b0 of truth, got " + last.latitude());
        assertEquals(truthLon, last.longitude(), 0.001,
                "lon should decode within 0.001\u00b0 of truth, got " + last.longitude());
    }

    @Test
    void identification_frame_produces_typed_identification_with_callsign() {
        OpenSkyFrameAdapter a = new OpenSkyFrameAdapter();
        // Real DF17 TC4 ident frame from OpenSky test suite: KLM1023, ICAO 4840D6.
        AdsbFrame f = a.decode("*8D4840D6202CC371C32CE0576098;");
        assertNotNull(f);
        assertTrue(f instanceof AdsbFrame.Identification,
                "expected Identification, got " + f.getClass().getSimpleName());
        AdsbFrame.Identification id = (AdsbFrame.Identification) f;
        assertEquals("4840D6", id.icaoHex());
        assertNotNull(id.callsign(), "callsign should decode");
        assertEquals("KLM1023", id.callsign().trim(),
                "expected KLM1023, got '" + id.callsign() + "'");
        // This particular frame has emitter category subtype 0 (unspecified),
        // which our adapter returns as null (correct \u2014 not an "A0" placeholder).
        assertNull(id.emitterCategory(),
                "category subtype 0 (unspecified) should map to null, got '"
                        + id.emitterCategory() + "'");
    }

    @Test
    void garbage_input_returns_null_not_throws() {
        OpenSkyFrameAdapter a = new OpenSkyFrameAdapter();
        assertNull(a.decode(null));
        assertNull(a.decode(""));
        assertNull(a.decode("*;"));
        assertNull(a.decode("*ZZZZ;"));          // non-hex
        assertNull(a.decode("*8D4CA1FA;"));      // too short
        assertNull(a.decode("random noise"));
    }

    @Test
    void per_aircraft_position_decoder_cache_is_populated_and_reused() {
        OpenSkyFrameAdapter a = new OpenSkyFrameAdapter();
        assertEquals(0, a.positionDecoderCount());

        // First position frame for aircraft #1 creates its decoder.
        a.decode(encodeAirbornePosition("40621D", 49.98, 8.55, 0));
        assertEquals(1, a.positionDecoderCount(),
                "one PositionDecoder should be cached per ICAO");

        // Same aircraft again \u2192 reuse, cache size unchanged.
        a.decode(encodeAirbornePosition("40621D", 49.98, 8.55, 1));
        assertEquals(1, a.positionDecoderCount(),
                "same-ICAO frame must reuse decoder");

        // Different aircraft \u2192 cache grows.
        a.decode(encodeAirbornePosition("AABBCC", 49.98, 8.55, 0));
        assertEquals(2, a.positionDecoderCount(),
                "different ICAO must get its own decoder");
    }

    @Test
    void airborne_position_altitude_round_trips_in_feet_not_metres() {
        // Regression pin for the 2026-07-28 08:00 UTC field bug:
        // OpenSky's AirbornePositionV0Msg.getAltitude() returns FEET
        // per DO-260B (25 ft or 100 ft increments). An earlier version
        // of this adapter named the return value "altM" and divided by
        // 0.3048, shrinking every altitude by ~3.281x -- a 38000 ft
        // airliner would land on the CoT wire as ~11582 ft, which
        // Marty saw in WinTAK as FL115 instead of FL380.
        //
        // Pin: encode a real Q=1 25-ft-increment altitude, decode
        // through the full adapter, assert we get 38000 ft back (not
        // ~11582 ft).
        OpenSkyFrameAdapter a = new OpenSkyFrameAdapter();
        int truthAltFt = 38000;
        double truthLat = 49.98;
        double truthLon =  8.55;
        String icao = "40621D";

        AdsbFrame.AirbornePosition last = null;
        for (int i = 0; i < 4; i++) {
            AdsbFrame f0 = a.decode(encodePosWithAlt(icao, truthLat, truthLon, 0, truthAltFt));
            AdsbFrame f1 = a.decode(encodePosWithAlt(icao, truthLat, truthLon, 1, truthAltFt));
            if (f0 instanceof AdsbFrame.AirbornePosition p) last = p;
            if (f1 instanceof AdsbFrame.AirbornePosition p) last = p;
        }
        assertNotNull(last, "an AirbornePosition should emerge from paired even+odd frames");
        // Q=1 25-ft steps: 38000 lands exactly on a step, so the round-trip
        // must be byte-exact -- no tolerance needed. Any tolerance here would
        // let the /0.3048 regression sneak back in (which returns ~11582).
        assertEquals(truthAltFt, last.altitudeFt(),
                "altitude must round-trip in FEET; if this reads ~11582 the"
                        + " adapter is dividing feet by 0.3048 again");
        // Also pin the geometric flag: TC 9-18 = barometric, so isGeometric
        // must be false today. Flip this assertion when TC 20-22 support
        // (issue #6) lands and the adapter starts distinguishing.
        assertFalse(last.isGeometric(),
                "TC 9-18 airborne position is barometric; adapter must not"
                        + " flag it as GNSS geometric until issue #6 lands");
    }

    @Test
    void airborne_position_altitude_various_25ft_values_all_round_trip() {
        // Sweep across the Q=1 25-ft-increment range to catch any
        // sign/off-by-one/mask bug in the encoder OR the decoder path.
        // Values chosen: sea level, GA cruise, jet cruise, RVSM top,
        // U-2 territory. All are exact multiples of 25 ft in the
        // (alt+1000)/25 encoding, so round-trip is byte-exact.
        int[] truthAltsFt = { 0, 2500, 12500, 35000, 41000, 50000 };
        for (int truthAltFt : truthAltsFt) {
            OpenSkyFrameAdapter a = new OpenSkyFrameAdapter();
            AdsbFrame.AirbornePosition last = null;
            for (int i = 0; i < 4; i++) {
                AdsbFrame f0 = a.decode(encodePosWithAlt("ABCDEF", 49.98, 8.55, 0, truthAltFt));
                AdsbFrame f1 = a.decode(encodePosWithAlt("ABCDEF", 49.98, 8.55, 1, truthAltFt));
                if (f0 instanceof AdsbFrame.AirbornePosition p) last = p;
                if (f1 instanceof AdsbFrame.AirbornePosition p) last = p;
            }
            assertNotNull(last,
                    "AirbornePosition must emerge for altitude " + truthAltFt + " ft");
            assertEquals(truthAltFt, last.altitudeFt(),
                    "altitude " + truthAltFt + " ft must round-trip exactly"
                            + " (got " + last.altitudeFt() + " -- likely /0.3048 regression)");
        }
    }

    @Test
    void evict_removes_position_decoder() {
        OpenSkyFrameAdapter a = new OpenSkyFrameAdapter();
        a.decode(encodeAirbornePosition("40621D", 49.98, 8.55, 0));
        assertEquals(1, a.positionDecoderCount());
        a.evict("40621D");
        assertEquals(0, a.positionDecoderCount());
        // Case-insensitive.
        a.decode(encodeAirbornePosition("40621D", 49.98, 8.55, 0));
        a.evict("40621d");
        assertEquals(0, a.positionDecoderCount());
    }

    // ------------------------------------------------------------------
    // AVR frame encoder \u2014 minimal DF17 TC 11 airborne baro position
    // encoder just for these tests. Not production code; only used to
    // synthesise known-truth frames for round-trip testing.
    // ------------------------------------------------------------------

    /** Encode a DF17 airborne baro-position frame (TC=11) with the AC field zeroed. */
    private static String encodeAirbornePosition(String icaoHex, double lat, double lon, int F) {
        return encodePosWithAlt(icaoHex, lat, lon, F, /* zero altcode */ Integer.MIN_VALUE);
    }

    /**
     * Encode a DF17 TC 11 airborne baro-position frame with an explicit
     * altitude in feet, using the DO-260B Q=1 25-ft-increment encoding.
     * Pass {@link Integer#MIN_VALUE} to leave the AC field zeroed (matches
     * the pre-existing tests that don't care about altitude).
     *
     * <p>Q=1 encoding per DO-260B / Doc 9871 App. C:
     * <pre>
     *   N        = (alt_ft + 1000) / 25
     *   AC[11:0] = N[10:5] Q N[4:0]     (Q=1 in bit 4)
     * </pre>
     * Altitudes outside a Q=1-representable range fall back to a zeroed
     * AC field so tests can still exercise the frame-shape paths.
     */
    private static String encodePosWithAlt(String icaoHex, double lat, double lon,
                                            int F, int altFt) {
        int tc = 11;

        double dLat = (F == 0) ? 360.0 / 60.0 : 360.0 / 59.0;
        int yz = (int) Math.floor(131072.0 * modPos(lat, dLat) / dLat + 0.5) & 0x1FFFF;

        int nl = cprNLTable(lat);
        int n  = Math.max(nl - F, 1);
        double dLon = 360.0 / n;
        int xz = (int) Math.floor(131072.0 * modPos(lon, dLon) / dLon + 0.5) & 0x1FFFF;

        // Q=1 AC field. Valid range: N in [1, 2047] -> alt in [-975, +50175] ft.
        int ac = 0;
        if (altFt != Integer.MIN_VALUE) {
            int nCode = (altFt + 1000) / 25;
            if (nCode < 1 || nCode > 2047 || (nCode * 25 - 1000) != altFt) {
                // Non-representable in Q=1 25-ft steps; encoder deliberately
                // does not support Q=0 gray-code -- pick a Q=1-friendly test value.
                throw new IllegalArgumentException(
                        "altitude " + altFt + " ft not representable in Q=1 25-ft encoding");
            }
            // OpenSky decodes with N = (AC & 0xF) | ((AC & 0xFE0) >>> 1),
            // which recovers an 11-bit N by concatenating AC[3:0] (low 4) with
            // AC[11:5] (top 7, shifted right by 1 to skip the Q bit at AC[4]).
            // Inverse: keep N[3:0] in AC[3:0], shift N[10:4] up by 1 to sit at
            // AC[11:5], and set Q=1 at AC[4]. Verified by round-trip for
            // 0/2500/5000/12500/35000/38000/41000/50000 ft against OpenSky's
            // exact decoder formula.
            int acLo = nCode & 0x00F;         // N[3:0] -> AC[3:0]
            int acHi = (nCode & 0x7F0) << 1;  // N[10:4] -> AC[11:5] (skip Q at bit 4)
            ac = (acHi | 0x10 | acLo) & 0xFFF;
        }

        // ME (56 bits) layout for airborne baro position (v0):
        //   [55:51] TC(5) [50:49] SS(2) [48] NIC-SB [47:36] AltCode(12) [35] T [34] F
        //   [33:17] CPRlat(17) [16:0] CPRlon(17)
        long me = 0L;
        me |= ((long)(tc & 0x1F)) << 51;
        me |= 0L << 49;                   // SS = 0
        me |= 0L << 48;                   // NIC-SB
        me |= ((long)(ac & 0xFFF)) << 36; // 12-bit AC field
        me |= 0L << 35;                   // T
        me |= ((long)(F & 1)) << 34;
        me |= ((long)(yz & 0x1FFFF)) << 17;
        me |= ((long)(xz & 0x1FFFF));

        // Full 88-bit non-CRC payload: DF(5) CA(3) ICAO(24) ME(56)
        int df = 17, ca = 5;
        long icao = Long.parseLong(icaoHex, 16);
        byte[] payload = new byte[11];
        payload[0] = (byte) (((df & 0x1F) << 3) | (ca & 0x7));
        payload[1] = (byte) ((icao >> 16) & 0xFF);
        payload[2] = (byte) ((icao >>  8) & 0xFF);
        payload[3] = (byte) ( icao        & 0xFF);
        for (int i = 0; i < 7; i++) {
            payload[4 + i] = (byte) ((me >> (48 - 8 * i)) & 0xFF);
        }
        int crc = modeSCrc(payload);
        StringBuilder sb = new StringBuilder(30).append('*');
        for (byte b : payload) sb.append(String.format("%02X", b & 0xFF));
        sb.append(String.format("%06X", crc));
        sb.append(';');
        return sb.toString();
    }

    /** Positive modulo. */
    private static double modPos(double a, double b) {
        return a - b * Math.floor(a / b);
    }

    /** Standard airborne CPR NL(lat) table. */
    private static int cprNLTable(double lat) {
        lat = Math.abs(lat);
        if (lat < 10.47047130) return 59;
        if (lat < 14.82817437) return 58;
        if (lat < 18.18626357) return 57;
        if (lat < 21.02939493) return 56;
        if (lat < 23.54504487) return 55;
        if (lat < 25.82924707) return 54;
        if (lat < 27.93898710) return 53;
        if (lat < 29.91135686) return 52;
        if (lat < 31.77209708) return 51;
        if (lat < 33.53993436) return 50;
        if (lat < 35.22899598) return 49;
        if (lat < 36.85025108) return 48;
        if (lat < 38.41241892) return 47;
        if (lat < 39.92256684) return 46;
        if (lat < 41.38651832) return 45;
        if (lat < 42.80914012) return 44;
        if (lat < 44.19454951) return 43;
        if (lat < 45.54626723) return 42;
        if (lat < 46.86733252) return 41;
        if (lat < 48.16039128) return 40;
        if (lat < 49.42776439) return 39;
        if (lat < 50.67150166) return 38;
        if (lat < 51.89342469) return 37;
        return 36;
    }

    /** Mode-S CRC-24, polynomial 0x1FFF409, over 88 non-CRC bits (11 bytes). */
    private static int modeSCrc(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc ^= (b & 0xFF) << 16;
            for (int k = 0; k < 8; k++) {
                crc = ((crc & 0x800000) != 0)
                        ? ((crc << 1) ^ 0xFFF409) & 0xFFFFFF
                        : ( crc << 1) & 0xFFFFFF;
            }
        }
        return crc;
    }
}
