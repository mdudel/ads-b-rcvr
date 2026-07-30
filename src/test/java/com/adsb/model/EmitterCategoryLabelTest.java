package com.adsb.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public final class EmitterCategoryLabelTest {

    @Test
    void nullOrBlankReturnsNull() {
        assertNull(EmitterCategoryLabel.labelFor(null));
        assertNull(EmitterCategoryLabel.labelFor(""));
        assertNull(EmitterCategoryLabel.labelFor("   "));
    }

    @Test
    void setAcoversFixedWingSizes() {
        assertEquals("Light",       EmitterCategoryLabel.labelFor("A1"));
        assertEquals("Small",       EmitterCategoryLabel.labelFor("A2"));
        assertEquals("Large",       EmitterCategoryLabel.labelFor("A3"));
        assertEquals("High vortex", EmitterCategoryLabel.labelFor("A4"));
        assertEquals("Heavy",       EmitterCategoryLabel.labelFor("A5"));
        assertEquals("High perf",   EmitterCategoryLabel.labelFor("A6"));
        assertEquals("Rotorcraft",  EmitterCategoryLabel.labelFor("A7"));
    }

    @Test
    void setBcoversOtherAirborne() {
        assertEquals("Glider",           EmitterCategoryLabel.labelFor("B1"));
        assertEquals("Lighter-than-air", EmitterCategoryLabel.labelFor("B2"));
        assertEquals("Parachutist",      EmitterCategoryLabel.labelFor("B3"));
        assertEquals("Ultralight",       EmitterCategoryLabel.labelFor("B4"));
        assertEquals("UAV",              EmitterCategoryLabel.labelFor("B6"));
        assertEquals("Space vehicle",    EmitterCategoryLabel.labelFor("B7"));
    }

    @Test
    void setCcoversSurface() {
        assertEquals("Emergency vehicle", EmitterCategoryLabel.labelFor("C1"));
        assertEquals("Service vehicle",   EmitterCategoryLabel.labelFor("C2"));
        assertEquals("Fixed obstacle",    EmitterCategoryLabel.labelFor("C3"));
    }

    @Test
    void isCaseInsensitiveAndTrimsWhitespace() {
        assertEquals("Heavy", EmitterCategoryLabel.labelFor("a5"));
        assertEquals("Heavy", EmitterCategoryLabel.labelFor("  A5  "));
        assertEquals("Heavy", EmitterCategoryLabel.labelFor("a5\t"));
    }

    @Test
    void unmappedButValidCodeReturnsUnknown() {
        // "D0"..."D7" are reserved for future use in DO-260B; we return
        // "Unknown" so the operator sees SOMETHING in the column rather
        // than a blank cell that reads as 'no data yet' (nulls are for
        // that case).
        assertEquals("Unknown", EmitterCategoryLabel.labelFor("D0"));
        assertEquals("Unknown", EmitterCategoryLabel.labelFor("D5"));
        assertEquals("Unknown", EmitterCategoryLabel.labelFor("XX"));
    }

    @Test
    void noInfoSubcategoriesAreLabelled() {
        assertEquals("No info", EmitterCategoryLabel.labelFor("A0"));
        assertEquals("No info", EmitterCategoryLabel.labelFor("B0"));
        assertEquals("No info", EmitterCategoryLabel.labelFor("C0"));
    }
}
