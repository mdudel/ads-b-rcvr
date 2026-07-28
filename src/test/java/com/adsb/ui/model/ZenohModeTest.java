package com.adsb.ui.model;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the {@link ZenohMode} enum's parse-with-default contract, which
 * is the load-side of ConnectorStore backward-compat. The default
 * matters: it's what pre-mode saves get on load, and if it flips to
 * STREAM by accident every already-configured Zenoh sink would silently
 * change wire behaviour.
 */
class ZenohModeTest {

    @Test
    void parseOrDefault_returns_exact_match() {
        assertEquals(ZenohMode.STREAM,       ZenohMode.parseOrDefault("STREAM"));
        assertEquals(ZenohMode.PER_AIRCRAFT, ZenohMode.parseOrDefault("PER_AIRCRAFT"));
    }

    @Test
    void parseOrDefault_trims_whitespace() {
        assertEquals(ZenohMode.STREAM, ZenohMode.parseOrDefault("  STREAM  "),
                "leading/trailing whitespace from hand-edited files must not break parse");
    }

    @Test
    void parseOrDefault_defaults_to_per_aircraft_on_null_blank_or_unknown() {
        // This is the backward-compat contract for pre-mode saves.
        // If this default ever flips, every already-configured Zenoh
        // sink written by commit 8e4aca2 changes wire behaviour on
        // load. Do not flip lightly.
        assertEquals(ZenohMode.PER_AIRCRAFT, ZenohMode.parseOrDefault(null));
        assertEquals(ZenohMode.PER_AIRCRAFT, ZenohMode.parseOrDefault(""));
        assertEquals(ZenohMode.PER_AIRCRAFT, ZenohMode.parseOrDefault("   "));
        assertEquals(ZenohMode.PER_AIRCRAFT, ZenohMode.parseOrDefault("NOT_A_MODE"));
        assertEquals(ZenohMode.PER_AIRCRAFT, ZenohMode.parseOrDefault("stream"),
                "lower-case is unknown (enum names are upper-case); must fall back "
                        + "-- if you want case-insensitive parse, change parseOrDefault "
                        + "AND flip this test to assert STREAM");
    }

    @Test
    void every_mode_has_a_non_blank_label_and_is_distinct() {
        // Label uniqueness matters for the UI dropdown -- two modes with the
        // same label would confuse operators. Pin so no future add breaks it.
        Set<String> labels = new HashSet<>();
        for (ZenohMode m : ZenohMode.values()) {
            assertNotNull(m.label(), "mode " + m + " must have a label");
            assertFalse(m.label().isBlank(), "mode " + m + " label must not be blank");
            assertTrue(labels.add(m.label()),
                    "duplicate label for mode " + m + ": " + m.label());
        }
    }
}
