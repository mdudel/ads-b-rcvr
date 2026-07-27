package com.adsb.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PayloadFormatTest {

    @Test
    void parses_canonical_names_case_insensitively() {
        assertEquals(PayloadFormat.AVR,  PayloadFormat.parse("avr"));
        assertEquals(PayloadFormat.AVR,  PayloadFormat.parse("AVR"));
        assertEquals(PayloadFormat.JSON, PayloadFormat.parse("json"));
        assertEquals(PayloadFormat.JSON, PayloadFormat.parse("JSON"));
        assertEquals(PayloadFormat.COT,  PayloadFormat.parse("cot"));
        assertEquals(PayloadFormat.COT,  PayloadFormat.parse("COT"));
    }

    @Test
    void raw_is_synonym_for_avr_and_xml_for_cot() {
        assertEquals(PayloadFormat.AVR, PayloadFormat.parse("raw"));
        assertEquals(PayloadFormat.COT, PayloadFormat.parse("xml"));
    }

    @Test
    void unknown_value_throws_with_helpful_message() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PayloadFormat.parse("beast"));
        assertTrue(ex.getMessage().contains("beast"));
        assertTrue(ex.getMessage().contains("avr|json|cot"));
    }

    @Test
    void null_throws() {
        assertThrows(IllegalArgumentException.class, () -> PayloadFormat.parse(null));
    }
}
