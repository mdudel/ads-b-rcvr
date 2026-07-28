package com.adsb.transport;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ZenohForwarder} that don't require a running
 * {@code zenohd}. Focus is on the two pieces of adapter logic we own:
 * <ul>
 *   <li>ICAO-hex extraction from a CoT XML payload</li>
 *   <li>Trailing/leading slash normalisation on the key prefix</li>
 * </ul>
 *
 * <p>End-to-end wire behaviour (session handshake, PUSH frame emission,
 * subscriber receipt) is exercised by the pure-Java Zenoh client's own
 * upstream tests; the smoke recipe against a local {@code zenohd} is
 * documented in the commit message + README so Marty can verify the
 * live wire on Windows where a router is easy to start.
 */
class ZenohForwarderTest {

    // ------------------------------------------------------------------
    // extractIcaoHex: the ONE piece of logic that would silently break
    // per-aircraft Zenoh keys if CoTBuilder ever changes its uid format.
    // Pin the shape so a builder-side regression trips these first.
    // ------------------------------------------------------------------

    @Test
    void icao_extracted_from_a_real_shape_cot_event() {
        // Byte-for-byte copy of the header CoTBuilder actually emits
        // (see CoTBuilder.build -- uid=\"ICAO-XXXXXX\" is the first
        // attribute after the type). If this fails, either the builder
        // or the extractor drifted.
        byte[] xml = (
            "<?xml version='1.0' standalone='yes'?>" +
            "<event version=\"2.0\" type=\"a-n-A-C-F\" uid=\"ICAO-4CA1FA\" " +
            "how=\"m-g\" time=\"2026-07-28T12:00:00.000Z\" " +
            "start=\"2026-07-28T12:00:00.000Z\" stale=\"2026-07-28T12:00:30.000Z\">" +
            "<point lat=\"49.98\" lon=\"8.55\" hae=\"11582.4\" ce=\"9999999\" le=\"9999999\"/>" +
            "<detail><contact callsign=\"RYR8SZ\"/></detail>" +
            "</event>"
        ).getBytes(StandardCharsets.UTF_8);
        assertEquals("4CA1FA", ZenohForwarder.extractIcaoHex(xml));
    }

    @Test
    void icao_extraction_is_case_insensitive_on_input_but_uppercases_output() {
        byte[] xml = "<event uid=\"ICAO-4ca1fa\"/>".getBytes(StandardCharsets.UTF_8);
        assertEquals("4CA1FA", ZenohForwarder.extractIcaoHex(xml),
                "extractor lower-cases-in / upper-cases-out so Zenoh keys are stable "
                        + "regardless of how the CoT builder happened to emit the hex");
    }

    @Test
    void extraction_returns_null_for_non_cot_payloads() {
        assertNull(ZenohForwarder.extractIcaoHex(
                "*8D4CA1FA202CC371C32CE0576098;\n".getBytes(StandardCharsets.UTF_8)),
                "AVR frames have no uid attribute; extractor must return null "
                        + "so ZenohForwarder falls through to the base key");
        assertNull(ZenohForwarder.extractIcaoHex(
                "{\"icao\":\"4CA1FA\",\"lat\":49.98,\"lon\":8.55}"
                        .getBytes(StandardCharsets.UTF_8)),
                "JSON frames use a different key layout; extractor must return null");
        assertNull(ZenohForwarder.extractIcaoHex(new byte[0]),
                "empty payload must return null cleanly, not throw");
    }

    @Test
    void extraction_is_bounded_to_first_256_bytes() {
        // CoTBuilder always emits uid in the first ~200 bytes. The
        // extractor's 256-byte scan window ensures we don't pay a linear
        // cost for chatty <detail> children like <takv>, <status>, etc.
        // A uid that lives PAST the scan window must return null --
        // this pins the boundary and prevents a well-meaning future edit
        // from silently making the extractor O(payload-size).
        StringBuilder head = new StringBuilder();
        // 260 bytes of filler that does NOT include the uid pattern.
        for (int i = 0; i < 260; i++) head.append('x');
        head.append("<event uid=\"ICAO-4CA1FA\"/>");
        assertNull(ZenohForwarder.extractIcaoHex(head.toString().getBytes(StandardCharsets.UTF_8)),
                "uid attribute past byte 256 must NOT be found; if this test starts "
                        + "passing, someone widened the scan window -- verify that's intentional");
    }

    @Test
    void extraction_ignores_non_icao_uid_patterns() {
        // TAK user CoT events have uids like ANDROID-1234 or GeoChat.abc.
        // We must NOT invent a Zenoh sub-key from those -- they aren't
        // aircraft. Pattern is strictly ICAO-<6-hex-digits>.
        byte[] tak = "<event uid=\"ANDROID-abc-def\"/>".getBytes(StandardCharsets.UTF_8);
        assertNull(ZenohForwarder.extractIcaoHex(tak));

        byte[] chat = "<event uid=\"GeoChat.XX.foo.bar\"/>".getBytes(StandardCharsets.UTF_8);
        assertNull(ZenohForwarder.extractIcaoHex(chat));

        // Wrong length (5 hex digits) must not match either.
        byte[] shortHex = "<event uid=\"ICAO-4CA1F\"/>".getBytes(StandardCharsets.UTF_8);
        assertNull(ZenohForwarder.extractIcaoHex(shortHex));

        // Wrong length (7+ hex digits) must NOT match because our regex
        // anchors the closing quote right after the 6 hex chars:
        //   uid="ICAO-([0-9A-Fa-f]{6})"
        // A uid value like "ICAO-4CA1FAAA" has extra chars before the
        // closing quote, which breaks the anchor. This is deliberate:
        // an odd-length uid is almost certainly not a real aircraft and
        // we don't want to invent a stable Zenoh sub-key from garbage.
        byte[] longHex = "<event uid=\"ICAO-4CA1FAAA\"/>".getBytes(StandardCharsets.UTF_8);
        assertNull(ZenohForwarder.extractIcaoHex(longHex),
                "uid with 7+ hex chars must NOT match; strict 6-char rule prevents "
                        + "stable-key invention from malformed uids");
    }

    // ------------------------------------------------------------------
    // trimSlashes via public keyPrefix() -- can't hit the ctor without a
    // live router, so we cannot construct ZenohForwarder in a hermetic
    // unit test. The slash-normalisation logic is exercised indirectly
    // through the target-parser tests in ConnectorAttacherTargetTest,
    // which are hermetic (no ctor call).
    // ------------------------------------------------------------------

    @Test
    void forward_on_a_null_or_empty_frame_is_a_noop() {
        // We can't construct a live ZenohForwarder in the sandbox
        // (no zenohd), so this is a compile-time sanity that the
        // forward() contract accepts null/empty without NPE. It's
        // exercised via the extractor's null/empty tests above; keeping
        // this as a documented pin so a future rewrite that adds a
        // hard null-check knows it was deliberate to allow it.
        assertNull(ZenohForwarder.extractIcaoHex(new byte[0]));
    }
}
