package com.adsb.cot;

import com.adsb.cot.IcaoAircraftClassifier.Affiliation;
import com.adsb.cot.IcaoAircraftClassifier.Category;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IcaoAircraftClassifierTest {

    @Test
    void default_is_neutral_civilian_fixed_wing() {
        // Marty-blessed default (2026-07-27).
        assertEquals("a-n-A-C-F",
                new IcaoAircraftClassifier(null, null).classify("A1B2C3", "A3"));
    }

    @Test
    void unknown_category_falls_back_to_fixed_wing() {
        assertEquals("a-n-A-C-F",
                new IcaoAircraftClassifier(null, null).classify("A1B2C3", null));
    }

    @Test
    void category_A7_is_helicopter() {
        assertEquals("a-n-A-C-H",
                new IcaoAircraftClassifier(null, null).classify("A1B2C3", "A7"));
    }

    @Test
    void category_B2_is_lighter_than_air() {
        assertEquals("a-n-A-C-L",
                new IcaoAircraftClassifier(null, null).classify("A1B2C3", "B2"));
    }

    @Test
    void us_military_icao_range_flips_to_friendly_military() {
        // Somewhere inside 0xADF7C8..0xAFFFFF.
        assertEquals("a-f-A-M-F",
                new IcaoAircraftClassifier(null, null).classify("AE1234", "A3"));
    }

    @Test
    void a6_on_military_is_high_performance() {
        assertEquals("a-f-A-M-F-F",
                new IcaoAircraftClassifier(null, null).classify("AE1234", "A6"));
    }

    @Test
    void a6_on_civilian_stays_fixed_wing() {
        // civil A6 exists (e.g. business jets) but we don't emit the -F-F
        // military-fighter suffix for civil traffic.
        assertEquals("a-n-A-C-F",
                new IcaoAircraftClassifier(null, null).classify("A1B2C3", "A6"));
    }

    @Test
    void cli_override_always_wins_over_icao_range_table() {
        // AE1234 would be range-classified military; forcing neutral civilian
        // via CLI must override.
        assertEquals("a-n-A-C-F",
                new IcaoAircraftClassifier(Affiliation.NEUTRAL, Category.CIVILIAN)
                        .classify("AE1234", "A3"));
    }

    @Test
    void tilde_prefixed_pseudo_icao_is_stripped() {
        // ADSBExchange marks MLAT-only tracks with a leading ~; classifier
        // must not classify these as military just because ~AE1234 parses
        // differently.
        assertEquals("a-f-A-M-F",
                new IcaoAircraftClassifier(null, null).classify("~AE1234", "A3"));
    }

    @Test
    void garbage_hex_falls_through_to_civilian_default() {
        assertEquals("a-n-A-C-F",
                new IcaoAircraftClassifier(null, null).classify("XYZ!!!", "A3"));
    }
}
