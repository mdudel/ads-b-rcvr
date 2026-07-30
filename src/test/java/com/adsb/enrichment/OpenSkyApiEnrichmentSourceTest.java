package com.adsb.enrichment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-parse tests for the JSON extractor. Live-HTTP tests aren't
 * appropriate for CI: the OpenSky endpoint is external, rate-limited,
 * and the values change over time.
 */
public final class OpenSkyApiEnrichmentSourceTest {

    @Test
    void parsesFullResponse() {
        String body = "{"
                + "\"icao24\":\"3c6444\","
                + "\"registration\":\"D-ABYT\","
                + "\"typecode\":\"B748\","
                + "\"manufacturerName\":\"Boeing\","
                + "\"model\":\"747-830\","
                + "\"operator\":\"Lufthansa\","
                + "\"operatorIcao\":\"DLH\","
                + "\"built\":\"2013\""
                + "}";
        Enrichment e = OpenSkyApiEnrichmentSource.parseJson("3C6444", body);
        assertEquals("3C6444",     e.icaoHex());
        assertEquals("D-ABYT",     e.registration());
        assertEquals("B748",       e.typeCode());
        assertEquals("Boeing",     e.manufacturer());
        assertEquals("747-830",    e.model());
        assertEquals("Lufthansa",  e.operator());
        assertEquals("DLH",        e.operatorIcao());
    }

    @Test
    void missingFieldsBecomeNull() {
        String body = "{\"icao24\":\"3c6444\",\"registration\":\"D-ABYT\"}";
        Enrichment e = OpenSkyApiEnrichmentSource.parseJson("3C6444", body);
        assertEquals("D-ABYT", e.registration());
        assertNull(e.typeCode());
        assertNull(e.manufacturer());
        assertNull(e.model());
        assertNull(e.operator());
    }

    @Test
    void nullLiteralBecomesNull() {
        String body = "{\"registration\":null,\"typecode\":\"B738\"}";
        Enrichment e = OpenSkyApiEnrichmentSource.parseJson("3C6444", body);
        assertNull(e.registration());
        assertEquals("B738", e.typeCode());
    }

    @Test
    void emptyStringBecomesNull() {
        // OpenSky sometimes returns "" instead of null for absent operators.
        String body = "{\"operator\":\"\",\"typecode\":\"B738\"}";
        Enrichment e = OpenSkyApiEnrichmentSource.parseJson("3C6444", body);
        assertNull(e.operator());
        assertEquals("B738", e.typeCode());
    }

    @Test
    void handlesEscapedQuotes() {
        String body = "{\"operator\":\"Air \\\"Nostrum\\\"\"}";
        Enrichment e = OpenSkyApiEnrichmentSource.parseJson("3C6444", body);
        assertEquals("Air \"Nostrum\"", e.operator());
    }

    @Test
    void handlesEscapedBackslash() {
        String body = "{\"model\":\"747\\\\special\"}";
        Enrichment e = OpenSkyApiEnrichmentSource.parseJson("3C6444", body);
        assertEquals("747\\special", e.model());
    }

    @Test
    void whitespaceAroundColonAndValues() {
        String body = "{ \"registration\" : \"D-ABYT\" , \"typecode\":  \"B748\"}";
        Enrichment e = OpenSkyApiEnrichmentSource.parseJson("3C6444", body);
        assertEquals("D-ABYT", e.registration());
        assertEquals("B748",   e.typeCode());
    }

    @Test
    void emptyBodyGivesAllNulls() {
        Enrichment e = OpenSkyApiEnrichmentSource.parseJson("3C6444", "{}");
        assertEquals("3C6444", e.icaoHex());
        assertNull(e.registration());
        assertNull(e.typeCode());
        assertNull(e.operator());
    }
}
