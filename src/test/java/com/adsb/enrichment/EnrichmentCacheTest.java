package com.adsb.enrichment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public final class EnrichmentCacheTest {

    @Test
    void positiveHitRoundTripsThroughDisk(@TempDir Path tmp) {
        Path store = tmp.resolve("cache.json");
        EnrichmentCache c = new EnrichmentCache(store);
        c.load();  // no file yet -> empty
        c.put(new Enrichment("3C6444", "D-ABYT", "B748", "Boeing", "747-830",
                "Lufthansa", "DLH"));
        c.flush();
        assertTrue(Files.exists(store));

        EnrichmentCache c2 = new EnrichmentCache(store);
        c2.load();
        Optional<Enrichment> hit = c2.get("3C6444");
        assertTrue(hit.isPresent());
        assertEquals("D-ABYT",    hit.get().registration());
        assertEquals("B748",      hit.get().typeCode());
        assertEquals("Lufthansa", hit.get().operator());
    }

    @Test
    void missingCacheFileLoadsEmpty(@TempDir Path tmp) {
        EnrichmentCache c = new EnrichmentCache(tmp.resolve("nope.json"));
        c.load();
        assertEquals(0, c.size());
        assertTrue(c.get("3C6444").isEmpty());
    }

    @Test
    void negativeHitStoredButRetriedWhenStale(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("cache.json");
        EnrichmentCache c = new EnrichmentCache(store);
        c.put(Enrichment.empty("3C6444"));
        c.flush();

        // Fresh negative-hit: cache RETURNS the marker so the
        // resolver can distinguish 'we asked recently, don't re-ask'
        // from 'never asked'. The resolver checks .isEmpty() on the
        // returned enrichment; we replicate that contract here.
        Optional<Enrichment> fresh = c.get("3C6444");
        assertTrue(fresh.isPresent(), "fresh negative hit must return the marker");
        assertTrue(fresh.get().isEmpty(), "marker must be an empty enrichment");

        // Rewrite the on-disk record with a fetchedAt 25h in the past.
        String tampered = "{\"icao24\":\"3C6444\",\"registration\":null,"
                + "\"typecode\":null,\"manufacturer\":null,\"model\":null,"
                + "\"operator\":null,\"operatorIcao\":null,"
                + "\"fetchedAt\":\"" + java.time.Instant.now().minusSeconds(90_000).toString() + "\"}\n";
        Files.writeString(store, tampered);

        EnrichmentCache c2 = new EnrichmentCache(store);
        c2.load();
        // TTL expired -> cache returns absent so the resolver falls
        // through to a fresh API query.
        assertTrue(c2.get("3C6444").isEmpty());
    }

    @Test
    void jsonEscapingSurvivesQuotesAndBackslashes(@TempDir Path tmp) {
        Path store = tmp.resolve("cache.json");
        EnrichmentCache c = new EnrichmentCache(store);
        c.put(new Enrichment("3C6444", "D-ABYT", "B748",
                "Boeing \"Commercial\"", "747-8 \\ variant",
                "AirCo\tX", "AIRX"));
        c.flush();

        EnrichmentCache c2 = new EnrichmentCache(store);
        c2.load();
        Enrichment e = c2.get("3C6444").orElseThrow();
        assertEquals("Boeing \"Commercial\"", e.manufacturer());
        assertEquals("747-8 \\ variant",      e.model());
        assertEquals("AirCo\tX",              e.operator());
    }

    @Test
    void corruptLineSkippedRestLoads(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("cache.json");
        String content = "{ this is not JSON at all }\n"
                + "{\"icao24\":\"3C6444\",\"registration\":\"D-ABYT\","
                + "\"typecode\":\"B748\",\"manufacturer\":\"Boeing\","
                + "\"model\":\"747-830\",\"operator\":\"Lufthansa\","
                + "\"operatorIcao\":\"DLH\",\"fetchedAt\":\""
                + java.time.Instant.now().toString() + "\"}\n";
        Files.writeString(store, content);

        EnrichmentCache c = new EnrichmentCache(store);
        c.load();
        // Corrupt line is skipped (decodeLine returns null on missing
        // icao24). Second line loads normally.
        assertTrue(c.get("3C6444").isPresent());
    }

    @Test
    void nullInputsAreSafe(@TempDir Path tmp) {
        EnrichmentCache c = new EnrichmentCache(tmp.resolve("cache.json"));
        c.put(null);
        assertTrue(c.get(null).isEmpty());
        assertEquals(0, c.size());
    }
}
