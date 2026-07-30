package com.adsb.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Spot-check the ICAO 24-bit allocation table. Full coverage isn't
 * practical (~180 ranges); this pins the boundaries of the ranges
 * most likely to see traffic in Marty's Frankfurt setup plus a few
 * global sanity checks.
 */
public final class IcaoCountryRegistryTest {

    @Test
    void nullBlankOrInvalidReturnsNull() {
        assertNull(IcaoCountryRegistry.countryFor(null));
        assertNull(IcaoCountryRegistry.countryFor(""));
        assertNull(IcaoCountryRegistry.countryFor("  "));
        assertNull(IcaoCountryRegistry.countryFor("XYZ")); // non-hex
        assertNull(IcaoCountryRegistry.countryFor("1234567")); // > 6 chars
        assertNull(IcaoCountryRegistry.countryFor("000000")); // reserved
        assertNull(IcaoCountryRegistry.countryFor("FFFFFF")); // reserved
    }

    @Test
    void toleratesCasePrefixAndWhitespace() {
        assertEquals("Germany", IcaoCountryRegistry.countryFor("3c6444"));
        assertEquals("Germany", IcaoCountryRegistry.countryFor("3C6444"));
        assertEquals("Germany", IcaoCountryRegistry.countryFor("  3C6444  "));
        assertEquals("Germany", IcaoCountryRegistry.countryFor("0x3C6444"));
        assertEquals("Germany", IcaoCountryRegistry.countryFor("0X3C6444"));
    }

    @Test
    void germanyBoundaries() {
        // 0x3C0000..0x3FFFFF -- the big DE block. Marty's Frankfurt
        // setup will see hundreds of these per hour.
        assertEquals("Germany", IcaoCountryRegistry.countryFor("3C0000"));
        assertEquals("Germany", IcaoCountryRegistry.countryFor("3C7FFF"));
        assertEquals("Germany", IcaoCountryRegistry.countryFor("3FFFFF"));
        // One below and one above the range.
        assertEquals("France",         IcaoCountryRegistry.countryFor("3BFFFF"));
        assertEquals("United Kingdom", IcaoCountryRegistry.countryFor("400000"));
    }

    @Test
    void bigStatesResolve() {
        // Marty's live traffic + common global carriers.
        assertEquals("United States",  IcaoCountryRegistry.countryFor("A12345"));
        assertEquals("United Kingdom", IcaoCountryRegistry.countryFor("400123"));
        assertEquals("France",         IcaoCountryRegistry.countryFor("39ABCD"));
        assertEquals("Germany",        IcaoCountryRegistry.countryFor("3C6789"));
        assertEquals("Netherlands",    IcaoCountryRegistry.countryFor("484123"));
        assertEquals("Spain",          IcaoCountryRegistry.countryFor("345678"));
        assertEquals("Italy",          IcaoCountryRegistry.countryFor("321234"));
        assertEquals("Russia",         IcaoCountryRegistry.countryFor("15ABCD"));
        assertEquals("China",          IcaoCountryRegistry.countryFor("7BC000"));
        assertEquals("Japan",          IcaoCountryRegistry.countryFor("867890"));
        assertEquals("Canada",         IcaoCountryRegistry.countryFor("C01234"));
        assertEquals("Australia",      IcaoCountryRegistry.countryFor("7D0000"));
        assertEquals("Brazil",         IcaoCountryRegistry.countryFor("E4ABCD"));
    }

    @Test
    void smallStatesResolve() {
        // Small allocations = boundary bugs more likely.
        assertEquals("Luxembourg", IcaoCountryRegistry.countryFor("4D0000"));
        assertEquals("Luxembourg", IcaoCountryRegistry.countryFor("4D03FF"));
        assertNull(IcaoCountryRegistry.countryFor("4D0400")); // one past
        assertEquals("Monaco",     IcaoCountryRegistry.countryFor("4D4000"));
        assertEquals("Iceland",    IcaoCountryRegistry.countryFor("4CC123"));
        assertEquals("Ireland",    IcaoCountryRegistry.countryFor("4CA123"));
    }

    @Test
    void unallocatedRegionsReturnNull() {
        // Big gaps in the allocation table -- these should NOT be
        // mis-labelled as neighbouring states.
        assertNull(IcaoCountryRegistry.countryFor("200000"));
        assertNull(IcaoCountryRegistry.countryFor("600400"));
        assertNull(IcaoCountryRegistry.countryFor("D00000"));
        assertNull(IcaoCountryRegistry.countryFor("F80000"));
    }
}
