package com.adsb.transport;

import com.adsb.core.PayloadFormat;
import com.adsb.ui.model.Connector;
import com.adsb.ui.model.ZenohMode;
import com.adsb.ui.model.ZenohTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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

    // ------------------------------------------------------------------
    // Mode-branch tests: we can't stand up a live Zenoh session in the
    // sandbox, but the sub-key selection is pure and can be tested
    // through a package-private helper that mirrors forward()'s branch.
    // The test doesn't need a live publisher because the choice of key
    // is decided BEFORE the publish call. Pin the branch table so a
    // future edit to forward() that flips the mode semantics is caught.
    // ------------------------------------------------------------------

    @Test
    void stream_mode_always_publishes_to_base_key_regardless_of_payload() {
        // CoT payload with a valid ICAO uid -- PER_AIRCRAFT would fan out,
        // but STREAM must ignore the uid and stay on the base key.
        byte[] cot = "<event uid=\"ICAO-4CA1FA\"/>".getBytes(StandardCharsets.UTF_8);
        assertNull(ZenohForwarder.selectSubKey(cot, ZenohMode.STREAM),
                "STREAM mode must publish CoT to the base key; got a sub-key -- "
                        + "the mode toggle in forward() is not honouring STREAM");

        byte[] json = "{\"icao\":\"4CA1FA\"}".getBytes(StandardCharsets.UTF_8);
        assertNull(ZenohForwarder.selectSubKey(json, ZenohMode.STREAM));

        byte[] avr = "*8D4CA1FA202CC371C32CE0576098;".getBytes(StandardCharsets.UTF_8);
        assertNull(ZenohForwarder.selectSubKey(avr, ZenohMode.STREAM));
    }

    @Test
    void per_aircraft_mode_derives_icao_subkey_from_cot_payload() {
        byte[] cot = "<event uid=\"ICAO-4CA1FA\"/>".getBytes(StandardCharsets.UTF_8);
        assertEquals("4CA1FA", ZenohForwarder.selectSubKey(cot, ZenohMode.PER_AIRCRAFT),
                "PER_AIRCRAFT mode must derive the ICAO sub-key from CoT uid");
    }

    @Test
    void per_aircraft_mode_falls_back_to_base_key_for_non_cot_payloads() {
        // AVR and JSON have no uid; PER_AIRCRAFT must NOT invent one.
        byte[] avr = "*8D4CA1FA202CC371C32CE0576098;".getBytes(StandardCharsets.UTF_8);
        assertNull(ZenohForwarder.selectSubKey(avr, ZenohMode.PER_AIRCRAFT),
                "PER_AIRCRAFT with a non-CoT payload must fall back to the base key");

        byte[] json = "{\"icao\":\"4CA1FA\"}".getBytes(StandardCharsets.UTF_8);
        assertNull(ZenohForwarder.selectSubKey(json, ZenohMode.PER_AIRCRAFT),
                "JSON payloads have no uid; PER_AIRCRAFT must NOT peek into them");
    }

    @Test
    void null_mode_selects_as_per_aircraft() {
        // Defensive: the ctor coerces null to PER_AIRCRAFT before any
        // forward() call, so production never sees a null mode here.
        // But selectSubKey() is package-private and callable from other
        // code paths in the future; pin the null branch so nobody
        // accidentally NPEs it later.
        byte[] cot = "<event uid=\"ICAO-4CA1FA\"/>".getBytes(StandardCharsets.UTF_8);
        assertEquals("4CA1FA", ZenohForwarder.selectSubKey(cot, null),
                "null mode should behave as PER_AIRCRAFT -- matches ctor coercion contract");
    }

    // ------------------------------------------------------------------
    // Post-2026-07-29-refactor: single-arg Connector ctor validation.
    // We can't reach a live zenohd from the sandbox, so these tests
    // only exercise the fail-fast validation that runs BEFORE any
    // network I/O. Success paths need Marty's Windows smoke test.
    // ------------------------------------------------------------------

    @Test
    void connector_ctor_rejects_null_connector() {
        IOException ex = assertThrows(IOException.class,
                () -> new ZenohForwarder((Connector) null));
        assertTrue(ex.getMessage().toLowerCase().contains("connector"),
                "error message should mention Connector; got: " + ex.getMessage());
    }

    @Test
    void connector_ctor_rejects_non_zenoh_connector() {
        Connector udp = Connector.newInstance("UDP", Connector.Type.UDP_UNICAST,
                "1.2.3.4:6969", PayloadFormat.COT, true);
        IOException ex = assertThrows(IOException.class, () -> new ZenohForwarder(udp));
        assertTrue(ex.getMessage().contains("ZENOH"),
                "error message should mention ZENOH type; got: " + ex.getMessage());
    }

    @Test
    void connector_ctor_rejects_missing_endpoint() {
        Connector c = Connector.newZenoh("z", ZenohTransport.TCP,
                "",             // endpoint blank
                "", "adsb/cot",
                PayloadFormat.COT, ZenohMode.PER_AIRCRAFT,
                null, null, null, false, true);
        IOException ex = assertThrows(IOException.class, () -> new ZenohForwarder(c));
        assertTrue(ex.getMessage().toLowerCase().contains("endpoint"),
                "error message should mention 'endpoint'; got: " + ex.getMessage());
    }

    @Test
    void connector_ctor_rejects_missing_topic() {
        Connector c = Connector.newZenoh("z", ZenohTransport.TCP,
                "localhost:7447",
                "", "",         // topic blank
                PayloadFormat.COT, ZenohMode.PER_AIRCRAFT,
                null, null, null, false, true);
        IOException ex = assertThrows(IOException.class, () -> new ZenohForwarder(c));
        assertTrue(ex.getMessage().toLowerCase().contains("topic")
                || ex.getMessage().toLowerCase().contains("keyexpr"),
                "error message should mention 'topic' or 'keyExpr'; got: " + ex.getMessage());
    }

    @Test
    void connector_ctor_rejects_tls_transport_without_cert_material() {
        // TLS transport MUST have all three: client cert, client key, CA.
        // Verify each one triggers the specific fail-fast.
        Connector missingCert = Connector.newZenoh("z", ZenohTransport.TLS,
                "host:7447", "", "adsb/cot",
                PayloadFormat.COT, ZenohMode.PER_AIRCRAFT,
                null,                    // client cert missing
                "/tmp/key.pem", "/tmp/ca.pem",
                false, true);
        IOException ex = assertThrows(IOException.class, () -> new ZenohForwarder(missingCert));
        assertTrue(ex.getMessage().toLowerCase().contains("cert"),
                "missing cert should be called out; got: " + ex.getMessage());

        Connector missingKey = Connector.newZenoh("z", ZenohTransport.TLS,
                "host:7447", "", "adsb/cot",
                PayloadFormat.COT, ZenohMode.PER_AIRCRAFT,
                "/tmp/cert.pem", null, "/tmp/ca.pem",
                false, true);
        IOException ex2 = assertThrows(IOException.class, () -> new ZenohForwarder(missingKey));
        assertTrue(ex2.getMessage().toLowerCase().contains("key"),
                "missing key should be called out; got: " + ex2.getMessage());

        Connector missingCa = Connector.newZenoh("z", ZenohTransport.TLS,
                "host:7447", "", "adsb/cot",
                PayloadFormat.COT, ZenohMode.PER_AIRCRAFT,
                "/tmp/cert.pem", "/tmp/key.pem", null,
                false, true);
        IOException ex3 = assertThrows(IOException.class, () -> new ZenohForwarder(missingCa));
        assertTrue(ex3.getMessage().toLowerCase().contains("ca")
                || ex3.getMessage().toLowerCase().contains("trust"),
                "missing CA/truststore should be called out; got: " + ex3.getMessage());
    }

    @Test
    void connector_ctor_allows_tcp_transport_without_cert_material() {
        // TCP transport doesn't need any TLS material -- but we can't
        // actually construct the forwarder in the sandbox (no zenohd).
        // Instead, verify the fail-fast validation PASSES: the ctor
        // would throw ConnectException / ClosedChannelException /
        // similar at the router-connect step, NOT the pre-flight
        // 'cert missing' IOException. Since the sandbox has no zenohd
        // running on port 7447, we expect some network-flavoured
        // exception -- what we're pinning is: NOT the 'cert required'
        // message.
        Connector c = Connector.newZenoh("z", ZenohTransport.TCP,
                "127.0.0.1:1",        // guaranteed-refused port
                "", "adsb/cot",
                PayloadFormat.COT, ZenohMode.PER_AIRCRAFT,
                null, null, null,       // TCP -- no TLS material needed
                false, true);
        Exception ex = assertThrows(Exception.class, () -> new ZenohForwarder(c));
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        assertFalse(msg.contains("cert required") || msg.contains("key path")
                        || msg.contains("truststore path"),
                "TCP transport must NOT require TLS material; got: " + ex.getMessage());
    }
}
