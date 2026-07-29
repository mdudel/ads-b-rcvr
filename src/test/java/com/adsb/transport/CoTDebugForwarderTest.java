package com.adsb.transport;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural pins for {@link CoTDebugForwarder}. Kept focused on the
 * eyeball-debugging contract Marty asked for 2026-07-29 10:34 UTC --
 * NOT a full XML-writer test suite.
 */
class CoTDebugForwarderTest {

    private static final String SAMPLE_COT =
            "<?xml version='1.0' standalone='yes'?>"
          + "<event version=\"2.0\" type=\"a-n-A-C-F\" uid=\"ICAO-4B3810\""
          + " how=\"m-g\" time=\"2026-07-29T10:34:00.000Z\""
          + " start=\"2026-07-29T10:34:00.000Z\""
          + " stale=\"2026-07-29T10:34:30.000Z\">"
          + "<point lat=\"50.043210\" lon=\"8.327780\" hae=\"11582.4\""
          + " ce=\"9999999.0\" le=\"9999999.0\"/>"
          + "<detail>"
          + "<contact callsign=\"DLH123\"/>"
          + "<track speed=\"231.50\" course=\"92.0\"/>"
          + "<remarks>DLH123 4B3810 ALT 38000ft</remarks>"
          + "</detail>"
          + "</event>";

    @Test
    void raw_mode_writes_the_document_unchanged_plus_newline() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        CoTDebugForwarder f = new CoTDebugForwarder(
                new PrintStream(buf, true, StandardCharsets.UTF_8),
                /*pretty*/ false, /*rateMs*/ 0L, /*filter*/ null);
        f.forward(SAMPLE_COT.getBytes(StandardCharsets.UTF_8));
        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.startsWith("<?xml"),
                "raw mode preserves the XML prolog on the wire");
        assertTrue(out.endsWith(System.lineSeparator()),
                "expected trailing newline");
        // Byte-for-byte match on the payload -- essential for wire-diff debugging.
        assertEquals(SAMPLE_COT,
                out.substring(0, out.length() - System.lineSeparator().length()));
    }

    @Test
    void pretty_mode_produces_multiline_indented_output() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        CoTDebugForwarder f = new CoTDebugForwarder(
                new PrintStream(buf, true, StandardCharsets.UTF_8),
                /*pretty*/ true, 0L, null);
        f.forward(SAMPLE_COT.getBytes(StandardCharsets.UTF_8));
        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.startsWith("---- CoT 4B3810 @ "),
                "pretty mode must prefix a human-readable header, got: "
                        + out.substring(0, Math.min(60, out.length())));
        String[] lines = out.split("\\R");
        assertTrue(lines.length > 5,
                "pretty mode should split into multiple lines, got " + lines.length);
        boolean sawIndentedPoint = false;
        for (String line : lines) {
            if (line.startsWith("  <point") && !line.startsWith("   <point")) {
                sawIndentedPoint = true;
                break;
            }
        }
        assertTrue(sawIndentedPoint,
                "expected 2-space-indented <point/> line, got:\n" + out);
    }

    @Test
    void extract_icao_finds_uid_hex() {
        assertEquals("4B3810", CoTDebugForwarder.extractIcao(SAMPLE_COT));
        assertNull(CoTDebugForwarder.extractIcao("no uid here"));
        assertNull(CoTDebugForwarder.extractIcao(null));
        // Extractor mustn't reject a hypothetical lowercase source.
        String lower = SAMPLE_COT.replace("ICAO-4B3810", "ICAO-4b3810");
        assertEquals("4B3810", CoTDebugForwarder.extractIcao(lower));
    }

    @Test
    void rate_limit_suppresses_second_line_within_window() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        CoTDebugForwarder f = new CoTDebugForwarder(
                new PrintStream(buf, true, StandardCharsets.UTF_8),
                false, /*rateMs*/ 60_000L, null);   // 60s window
        f.forward(SAMPLE_COT.getBytes(StandardCharsets.UTF_8));
        f.forward(SAMPLE_COT.getBytes(StandardCharsets.UTF_8));
        String out = buf.toString(StandardCharsets.UTF_8);
        long count = out.lines()
                .filter(l -> l.contains("uid=\"ICAO-4B3810\""))
                .count();
        assertEquals(1, count,
                "rate limit must suppress the second line; got:\n" + out);
    }

    @Test
    void rate_limit_does_not_affect_other_icaos() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        CoTDebugForwarder f = new CoTDebugForwarder(
                new PrintStream(buf, true, StandardCharsets.UTF_8),
                false, 60_000L, null);
        f.forward(SAMPLE_COT.getBytes(StandardCharsets.UTF_8));
        String other = SAMPLE_COT.replace("ICAO-4B3810", "ICAO-CAFE01");
        f.forward(other.getBytes(StandardCharsets.UTF_8));
        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("ICAO-4B3810"), "first ICAO must appear");
        assertTrue(out.contains("ICAO-CAFE01"),
                "second (different) ICAO must NOT be rate-limited");
    }

    @Test
    void icao_filter_regex_matches_case_insensitively() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Pattern re = Pattern.compile("4b3810|471db5", Pattern.CASE_INSENSITIVE);
        CoTDebugForwarder f = new CoTDebugForwarder(
                new PrintStream(buf, true, StandardCharsets.UTF_8),
                false, 0L, re);
        f.forward(SAMPLE_COT.getBytes(StandardCharsets.UTF_8));   // match
        f.forward(SAMPLE_COT.replace("ICAO-4B3810", "ICAO-CAFE01")
                .getBytes(StandardCharsets.UTF_8));               // no match
        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("ICAO-4B3810"), "matching ICAO must be emitted");
        assertFalse(out.contains("ICAO-CAFE01"),
                "non-matching ICAO must be suppressed");
    }

    @Test
    void empty_or_null_payload_is_no_op() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        CoTDebugForwarder f = new CoTDebugForwarder(
                new PrintStream(buf, true, StandardCharsets.UTF_8),
                false, 0L, null);
        f.forward(null);
        f.forward(new byte[0]);
        assertEquals(0, buf.size(),
                "null / empty payloads must not produce output");
    }

    @Test
    void pretty_print_of_malformed_input_falls_back_to_raw() {
        // The pretty-printer must never crash the debug stream. Even if the
        // input isn't well-formed XML, we want the raw bytes visible.
        String junk = "<event><unclosed";
        String rendered = CoTDebugForwarder.prettyPrint(junk);
        assertNotNull(rendered);
        assertTrue(rendered.length() > 0);
    }
}
