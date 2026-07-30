package com.adsb.enrichment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the resolver's chain-of-responsibility. Uses fake
 * in-memory sources instead of hitting the network.
 */
public final class EnrichmentResolverTest {

    /** In-memory EnrichmentSource for tests. */
    private static final class FakeSource implements EnrichmentSource {
        private final String name;
        private final java.util.Map<String, Enrichment> data;
        FakeSource(String n, java.util.Map<String, Enrichment> d) { this.name = n; this.data = d; }
        @Override public Optional<Enrichment> lookup(String icaoHex) {
            if (icaoHex == null) return Optional.empty();
            return Optional.ofNullable(data.get(icaoHex.toUpperCase()));
        }
        @Override public String name() { return name; }
    }

    @Test
    void cacheHitShortCircuits(@TempDir Path tmp) {
        EnrichmentCache cache = new EnrichmentCache(tmp.resolve("c.json"));
        cache.put(new Enrichment("3C6444", "D-ABYT", "B748", null, null, null, null));
        FakeSource api = new FakeSource("api", java.util.Map.of(
                "3C6444", new Enrichment("3C6444", "SHOULD-NOT-WIN", null, null, null, null, null)));
        EnrichmentResolver r = new EnrichmentResolver(cache, null, null, api);

        Optional<Enrichment> hit = r.lookup("3C6444");
        assertTrue(hit.isPresent());
        assertEquals("D-ABYT", hit.get().registration(),
                "cache should short-circuit before hitting the API");
        r.shutdown();
    }

    @Test
    void localDirBeatsBundleBeatsApi(@TempDir Path tmp) throws Exception {
        Path localDir  = Files.createDirectory(tmp.resolve("local"));
        Path bundleDir = Files.createDirectory(tmp.resolve("bundle"));
        Files.writeString(localDir.resolve("local.csv"),
                "icao24,registration\n3c6444,LOCAL-WINS\n");
        Files.writeString(bundleDir.resolve("bundle.csv"),
                "icao24,registration\n3c6444,BUNDLE-LOSES\n");

        LocalCsvEnrichmentSource local  = new LocalCsvEnrichmentSource(localDir);
        LocalCsvEnrichmentSource bundle = new LocalCsvEnrichmentSource(bundleDir);
        local.load(); bundle.load();

        FakeSource api = new FakeSource("api", java.util.Map.of(
                "3C6444", new Enrichment("3C6444", "API-LOSES", null, null, null, null, null)));

        EnrichmentCache cache = new EnrichmentCache(tmp.resolve("c.json"));
        EnrichmentResolver r = new EnrichmentResolver(cache, local, bundle, api);

        assertEquals("LOCAL-WINS", r.lookup("3C6444").get().registration());
        // Second lookup should also come from cache now.
        assertEquals("LOCAL-WINS", r.lookup("3C6444").get().registration());
        r.shutdown();
    }

    @Test
    void bundleUsedWhenLocalMisses(@TempDir Path tmp) throws Exception {
        Path bundleDir = Files.createDirectory(tmp.resolve("bundle"));
        Files.writeString(bundleDir.resolve("bundle.csv"),
                "icao24,registration\n400123,BUNDLE-WINS\n");

        LocalCsvEnrichmentSource local  = new LocalCsvEnrichmentSource(null);
        LocalCsvEnrichmentSource bundle = new LocalCsvEnrichmentSource(bundleDir);
        local.load(); bundle.load();

        EnrichmentResolver r = new EnrichmentResolver(
                new EnrichmentCache(tmp.resolve("c.json")), local, bundle, null);
        assertEquals("BUNDLE-WINS", r.lookup("400123").get().registration());
        r.shutdown();
    }

    @Test
    void apiMissDeliveredAsyncViaListener(@TempDir Path tmp) throws Exception {
        FakeSource api = new FakeSource("api", java.util.Map.of(
                "400123", new Enrichment("400123", "ASYNC-D-ABYT", "B738", null, null, null, null)));
        EnrichmentCache cache = new EnrichmentCache(tmp.resolve("c.json"));
        EnrichmentResolver r = new EnrichmentResolver(cache, null, null, api);

        AtomicReference<Enrichment> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        r.setListener(e -> { received.set(e); latch.countDown(); });

        // Sync lookup returns empty (nothing in cache/local/bundle).
        assertTrue(r.lookup("400123").isEmpty());

        // Async completion should fire the listener within 2 s.
        assertTrue(latch.await(2, TimeUnit.SECONDS),
                "listener should be invoked after async API completion");
        assertEquals("ASYNC-D-ABYT", received.get().registration());

        // Now a fresh lookup should hit the cache.
        Optional<Enrichment> hit = r.lookup("400123");
        assertTrue(hit.isPresent());
        assertEquals("ASYNC-D-ABYT", hit.get().registration());
        r.shutdown();
    }

    @Test
    void apiNegativeMissCachedAndNotRetried(@TempDir Path tmp) throws Exception {
        // FakeSource with no data -> every lookup returns empty.
        FakeSource api = new FakeSource("api", java.util.Map.of());
        EnrichmentCache cache = new EnrichmentCache(tmp.resolve("c.json"));
        EnrichmentResolver r = new EnrichmentResolver(cache, null, null, api);

        assertTrue(r.lookup("400999").isEmpty());
        // Give the async lookup a moment.
        Thread.sleep(150);
        // Second sync lookup should NOT trigger another API call --
        // negative cache should intercept it. We verify by checking
        // the cache has an empty entry now.
        assertEquals(1, cache.size(),
                "negative-cache entry should have been recorded");
        r.shutdown();
    }
}
