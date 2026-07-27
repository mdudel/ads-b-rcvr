package com.adsb.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThemeModeTest {

    @Test
    void from_string_parses_canonical_names_case_insensitively() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromString("light"));
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromString("LIGHT"));
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromString("  Light  "));
        assertEquals(ThemeMode.DARK,  ThemeMode.fromString("dark"));
        assertEquals(ThemeMode.DARK,  ThemeMode.fromString("Dark"));
    }

    @Test
    void from_string_unknown_or_null_returns_light() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromString(null));
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromString(""));
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromString("night"));
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromString("hi-vis"));
    }

    @Test
    void canonical_round_trips_with_from_string() {
        for (ThemeMode m : ThemeMode.values()) {
            assertEquals(m, ThemeMode.fromString(m.canonical()),
                    "round trip failed for " + m);
            assertEquals(m.name().toLowerCase(), m.canonical());
        }
    }

    @Test
    void next_cycles_two_states() {
        assertEquals(ThemeMode.DARK,  ThemeMode.LIGHT.next());
        assertEquals(ThemeMode.LIGHT, ThemeMode.DARK.next());
        assertEquals(ThemeMode.LIGHT, ThemeMode.LIGHT.next().next(),
                "next().next() should return to the starting state");
    }
}
