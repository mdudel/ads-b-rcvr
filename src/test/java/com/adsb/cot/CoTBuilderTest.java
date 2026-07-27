package com.adsb.cot;

import com.adsb.model.AdsbTrack;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Byte-shape pins on the CoT XML emitted by {@link CoTBuilder}. These are the
 * fence tests that keep future refactors honest \u2014 the shape has to survive
 * across the wire to WinTAK/ATAK/GCCS-J COP, so if these break, real receivers
 * break too.
 */
class CoTBuilderTest {

    /** Fixed timestamp so goldens are deterministic. */
    private static final Instant T = Instant.parse("2026-07-27T09:12:34.567Z");

    private static CoTBuilder newBuilder() {
        return new CoTBuilder(
                new IcaoAircraftClassifier(null, null), 30, 120);
    }

    @Test
    void full_featured_track_produces_expected_xml_bytes() {
        AdsbTrack t = AdsbTrack.builder("A1B2C3")
                .callsign("UAL123")
                .emitterCategory("A3")
                .squawk("1234")
                .latitude(48.123456)
                .longitude(11.654321)
                .altGeomFt(35000)
                .altBaroFt(34800)
                .groundSpeedKts(500.0)
                .trackDeg(45.0)
                .verticalRateFpm(1024)
                .onGround(false)
                .emergencyStatus(0)
                .lastSeen(T)
                .build();

        String actual = newBuilder().build(t);

        String expected =
                "<?xml version='1.0' standalone='yes'?>"
              + "<event version=\"2.0\" type=\"a-n-A-C-F\" uid=\"ICAO-A1B2C3\" how=\"m-g\""
              + " time=\"2026-07-27T09:12:34.567Z\""
              + " start=\"2026-07-27T09:12:34.567Z\""
              + " stale=\"2026-07-27T09:13:04.567Z\">"
              + "<point lat=\"48.123456\" lon=\"11.654321\" hae=\"10668.0\""
              + " ce=\"9999999.0\" le=\"9999999.0\"/>"
              + "<detail>"
              + "<contact callsign=\"UAL123\"/>"
              + "<track speed=\"257.22\" course=\"45.0\"/>"
              + "<remarks>UAL123 A1B2C3 SQUAWK 1234 CAT A3 ALT 35000ft</remarks>"
              + "</detail>"
              + "</event>";
        assertEquals(expected, actual);
    }

    @Test
    void position_only_track_omits_track_element() {
        AdsbTrack t = AdsbTrack.builder("ABCDEF")
                .latitude(0.0)
                .longitude(0.0)
                .altBaroFt(10000)
                .lastSeen(T)
                .build();

        String xml = newBuilder().build(t);
        assertNotNull(xml);
        assertFalse(xml.contains("<track "), "track element must be omitted without velocity: " + xml);
        assertTrue (xml.contains("callsign=\"ICAO-ABCDEF\""), "callsign falls back to ICAO-<hex>: " + xml);
    }

    @Test
    void ground_track_uses_ground_stale_offset() {
        AdsbTrack t = AdsbTrack.builder("A1B2C3")
                .latitude(48.0).longitude(11.0)
                .altBaroFt(0)
                .onGround(true)
                .lastSeen(T)
                .build();
        String xml = newBuilder().build(t);
        // 120s -> 09:14:34.567
        assertTrue(xml.contains("stale=\"2026-07-27T09:14:34.567Z\""),
                "ground stale must be +120s: " + xml);
    }

    @Test
    void airborne_track_uses_air_stale_offset() {
        AdsbTrack t = AdsbTrack.builder("A1B2C3")
                .latitude(48.0).longitude(11.0)
                .altBaroFt(35000)
                .onGround(false)
                .lastSeen(T)
                .build();
        String xml = newBuilder().build(t);
        // 30s -> 09:13:04.567
        assertTrue(xml.contains("stale=\"2026-07-27T09:13:04.567Z\""),
                "air stale must be +30s: " + xml);
    }

    @Test
    void positionless_track_returns_null() {
        AdsbTrack t = AdsbTrack.builder("A1B2C3").callsign("UAL123").lastSeen(T).build();
        assertNull(newBuilder().build(t));
    }

    @Test
    void locale_independence_de_DE() {
        // Marty's cotproto #77 lesson: de-DE JVMs default to comma decimal
        // separator; ROOT locale on every %f is the only reason the wire bytes
        // stay parseable by receivers.
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("de", "DE"));
            AdsbTrack t = AdsbTrack.builder("A1B2C3")
                    .latitude(48.123456)
                    .longitude(11.654321)
                    .altBaroFt(35000)
                    .groundSpeedKts(500.0)
                    .trackDeg(45.0)
                    .lastSeen(T)
                    .build();
            String xml = newBuilder().build(t);
            // Zero commas anywhere in a numeric attribute.
            assertFalse(xml.contains("48,123456"), "must use dot separator: " + xml);
            assertFalse(xml.contains("257,22"), "must use dot separator: " + xml);
            assertTrue (xml.contains("48.123456"));
            assertTrue (xml.contains("257.22"));
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void xml_attribute_escape_covers_all_five_entities() {
        assertEquals("A&amp;B", CoTBuilder.xmlAttrEscape("A&B"));
        assertEquals("A&lt;B",  CoTBuilder.xmlAttrEscape("A<B"));
        assertEquals("A&gt;B",  CoTBuilder.xmlAttrEscape("A>B"));
        assertEquals("A&quot;B",CoTBuilder.xmlAttrEscape("A\"B"));
        assertEquals("A&apos;B",CoTBuilder.xmlAttrEscape("A'B"));
        assertEquals("&amp;&lt;&gt;&quot;&apos;",
                CoTBuilder.xmlAttrEscape("&<>\"'"));
        assertEquals("", CoTBuilder.xmlAttrEscape(null));
        assertEquals("", CoTBuilder.xmlAttrEscape(""));
    }

    @Test
    void xml_attribute_escape_fast_path_returns_input_unchanged() {
        String clean = "UAL123 A1B2C3";
        String out = CoTBuilder.xmlAttrEscape(clean);
        assertSame(clean, out, "fast path must return the same reference when no escaping needed");
    }

    @Test
    void icao_hex_normalises_to_uppercase_in_uid() {
        AdsbTrack t = AdsbTrack.builder("a1b2c3") // lowercase input
                .latitude(0.0).longitude(0.0).lastSeen(T).build();
        String xml = newBuilder().build(t);
        assertTrue(xml.contains("uid=\"ICAO-A1B2C3\""),
                "UID must be uppercase for cross-receiver dedup: " + xml);
    }
}
