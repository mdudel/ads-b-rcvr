package com.adsb.enrichment;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public final class CsvAircraftDatabaseParserTest {

    @Test
    void parsesMinimalOpenSkySchema() throws Exception {
        String csv = """
                icao24,registration,typecode,manufacturername,model,operator,operatoricao
                3c6444,D-ABYT,B748,Boeing,747-830,Lufthansa,DLH
                400123,G-XLEA,A388,Airbus,A380-841,British Airways,BAW
                """;
        Map<String, Enrichment> map = CsvAircraftDatabaseParser.parse(new StringReader(csv));
        assertEquals(2, map.size());
        Enrichment lh = map.get("3C6444");
        assertNotNull(lh);
        assertEquals("D-ABYT",     lh.registration());
        assertEquals("B748",       lh.typeCode());
        assertEquals("Boeing",     lh.manufacturer());
        assertEquals("747-830",    lh.model());
        assertEquals("Lufthansa",  lh.operator());
        assertEquals("DLH",        lh.operatorIcao());
    }

    @Test
    void keyIsUppercasedButValueFieldsPreserveCase() throws Exception {
        String csv = "icao24,registration\n3c6444,D-ABYT\n";
        Map<String, Enrichment> map = CsvAircraftDatabaseParser.parse(new StringReader(csv));
        assertTrue(map.containsKey("3C6444"));
        assertFalse(map.containsKey("3c6444"));
        assertEquals("D-ABYT", map.get("3C6444").registration());
    }

    @Test
    void tolerantOfMissingOptionalColumns() throws Exception {
        // Only icao24 + registration -- the rest should be null.
        String csv = "icao24,registration\n3c6444,D-ABYT\n";
        Map<String, Enrichment> map = CsvAircraftDatabaseParser.parse(new StringReader(csv));
        Enrichment e = map.get("3C6444");
        assertNotNull(e);
        assertEquals("D-ABYT", e.registration());
        assertNull(e.typeCode());
        assertNull(e.manufacturer());
        assertNull(e.model());
        assertNull(e.operator());
        assertNull(e.operatorIcao());
    }

    @Test
    void headerMatchingIsCaseInsensitive() throws Exception {
        String csv = "ICAO24,Registration,TypeCode\n3c6444,D-ABYT,B748\n";
        Map<String, Enrichment> map = CsvAircraftDatabaseParser.parse(new StringReader(csv));
        Enrichment e = map.get("3C6444");
        assertNotNull(e);
        assertEquals("D-ABYT", e.registration());
        assertEquals("B748",   e.typeCode());
    }

    @Test
    void missingIcao24ColumnThrows() {
        String csv = "registration,typecode\nD-ABYT,B748\n";
        assertThrows(java.io.IOException.class,
                () -> CsvAircraftDatabaseParser.parse(new StringReader(csv)));
    }

    @Test
    void blankIcaoRowsSkipped() throws Exception {
        String csv = """
                icao24,registration
                3c6444,D-ABYT
                ,SHOULD-BE-SKIPPED
                400123,G-XLEA
                """;
        Map<String, Enrichment> map = CsvAircraftDatabaseParser.parse(new StringReader(csv));
        assertEquals(2, map.size());
    }

    @Test
    void handlesQuotedFieldWithComma() {
        // OpenSky operator names occasionally contain commas.
        List<String> row = CsvAircraftDatabaseParser.splitCsv(
                "3c6444,D-ABYT,\"Boeing, Commercial Airplanes\",747-8");
        assertEquals(4, row.size());
        assertEquals("Boeing, Commercial Airplanes", row.get(2));
    }

    @Test
    void handlesDoubledEmbeddedQuote() {
        // "Foo ""bar"" baz" -> Foo "bar" baz
        List<String> row = CsvAircraftDatabaseParser.splitCsv(
                "a,\"Foo \"\"bar\"\" baz\",c");
        assertEquals(3, row.size());
        assertEquals("Foo \"bar\" baz", row.get(1));
    }

    @Test
    void emptyFieldsProduceEmptyStrings() {
        List<String> row = CsvAircraftDatabaseParser.splitCsv("a,,c,,");
        assertEquals(5, row.size());
        assertEquals("a", row.get(0));
        assertEquals("",  row.get(1));
        assertEquals("c", row.get(2));
        assertEquals("",  row.get(3));
        assertEquals("",  row.get(4));
    }

    @Test
    void oversizedIcaoIsSkipped() throws Exception {
        // > 6 hex chars can't be a valid ICAO24; skip that row.
        String csv = "icao24,registration\n3c6444,D-ABYT\ntoolong123,BOGUS\n";
        Map<String, Enrichment> map = CsvAircraftDatabaseParser.parse(new StringReader(csv));
        assertEquals(1, map.size());
        assertTrue(map.containsKey("3C6444"));
    }

    @Test
    void trailingWhitespaceInFieldsTrimmed() throws Exception {
        String csv = "icao24,registration\n 3c6444 , D-ABYT \n";
        Map<String, Enrichment> map = CsvAircraftDatabaseParser.parse(new StringReader(csv));
        Enrichment e = map.get("3C6444");
        assertNotNull(e);
        assertEquals("D-ABYT", e.registration());
    }
}
