package com.adsb.enrichment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public final class LocalCsvEnrichmentSourceTest {

    @Test
    void nullDirReturnsEmpty() {
        LocalCsvEnrichmentSource s = new LocalCsvEnrichmentSource(null);
        assertEquals(0, s.load());
        assertTrue(s.lookup("3C6444").isEmpty());
    }

    @Test
    void missingDirReturnsEmpty(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist");
        LocalCsvEnrichmentSource s = new LocalCsvEnrichmentSource(missing);
        assertEquals(0, s.load());
        assertTrue(s.lookup("3C6444").isEmpty());
    }

    @Test
    void loadsSingleCsv(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("db.csv"),
                "icao24,registration,typecode,operator\n" +
                "3c6444,D-ABYT,B748,Lufthansa\n");
        LocalCsvEnrichmentSource s = new LocalCsvEnrichmentSource(tmp);
        assertEquals(1, s.load());
        assertEquals(1, s.rowCount());
        assertEquals(1, s.fileCount());
        Optional<Enrichment> hit = s.lookup("3C6444");
        assertTrue(hit.isPresent());
        assertEquals("D-ABYT", hit.get().registration());
    }

    @Test
    void lookupIsCaseInsensitiveOnIcao(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("db.csv"),
                "icao24,registration\n3c6444,D-ABYT\n");
        LocalCsvEnrichmentSource s = new LocalCsvEnrichmentSource(tmp);
        s.load();
        assertTrue(s.lookup("3c6444").isPresent());
        assertTrue(s.lookup("3C6444").isPresent());
        assertTrue(s.lookup("  3C6444  ").isEmpty(),   // no trim on the lookup path (source-of-truth is upper-hex)
                "lookup should not silently trim; caller normalises");
    }

    @Test
    void mergesMultipleCsvs(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("a.csv"),
                "icao24,registration\n3c6444,D-ABYT\n");
        Files.writeString(tmp.resolve("b.csv"),
                "icao24,registration\n400123,G-XLEA\n");
        LocalCsvEnrichmentSource s = new LocalCsvEnrichmentSource(tmp);
        assertEquals(2, s.load());
        assertEquals(2, s.fileCount());
        assertTrue(s.lookup("3C6444").isPresent());
        assertTrue(s.lookup("400123").isPresent());
    }

    @Test
    void ignoresNonCsvFiles(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("db.csv"),
                "icao24,registration\n3c6444,D-ABYT\n");
        Files.writeString(tmp.resolve("readme.txt"), "hello");
        Files.writeString(tmp.resolve("notes.md"),   "hello");
        LocalCsvEnrichmentSource s = new LocalCsvEnrichmentSource(tmp);
        assertEquals(1, s.load());
        assertEquals(1, s.fileCount());
    }

    @Test
    void skipsCorruptCsvContinuesWithOthers(@TempDir Path tmp) throws IOException {
        // Missing icao24 header -> parser throws -> file skipped.
        Files.writeString(tmp.resolve("bad.csv"),
                "registration,typecode\nX,Y\n");
        Files.writeString(tmp.resolve("good.csv"),
                "icao24,registration\n3c6444,D-ABYT\n");
        LocalCsvEnrichmentSource s = new LocalCsvEnrichmentSource(tmp);
        int rows = s.load();
        assertEquals(1, rows);
        assertTrue(s.lookup("3C6444").isPresent());
    }

    @Test
    void reloadRepublishesFreshTable(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("db.csv"),
                "icao24,registration\n3c6444,D-ABYT\n");
        LocalCsvEnrichmentSource s = new LocalCsvEnrichmentSource(tmp);
        s.load();
        assertTrue(s.lookup("3C6444").isPresent());

        // Replace the file, re-load, assert new content wins.
        Files.writeString(tmp.resolve("db.csv"),
                "icao24,registration\n400123,G-XLEA\n");
        s.reload();
        assertTrue(s.lookup("3C6444").isEmpty());
        assertTrue(s.lookup("400123").isPresent());
    }
}
