package com.adsb.enrichment;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

/**
 * Top-level facade: given an ICAO24, resolve enrichment metadata
 * from the configured chain of sources, caching results locally.
 *
 * <p><b>Resolution order</b> (Marty 2026-07-30 14:05 UTC):
 * <ol>
 *   <li>Positive cache hit -&gt; return immediately.</li>
 *   <li>Fresh negative cache hit (age &le; 24 h) -&gt; return empty.</li>
 *   <li>Local CSV directory -&gt; return + cache the hit.</li>
 *   <li>Downloaded bundle CSV -&gt; return + cache the hit.</li>
 *   <li>OpenSky API -&gt; async on the worker executor; on completion,
 *       cache + notify the listener so the UI can refresh the row.</li>
 * </ol>
 *
 * <p>The synchronous {@link #lookup} returns whatever's available
 * NOW (cache / local / bundle) so the UI paints without waiting on
 * the network. Anything not found synchronously is scheduled on the
 * background executor and delivered via {@link #addListener}.
 *
 * <p>In-flight tracking prevents the same ICAO from spawning
 * multiple concurrent API lookups when the UI paints faster than
 * the network can respond.
 */
public final class EnrichmentResolver {

    private final EnrichmentCache cache;
    private final LocalCsvEnrichmentSource localDir;
    private final LocalCsvEnrichmentSource bundle;
    private final EnrichmentSource api;
    private final ExecutorService worker;
    private final ConcurrentHashMap<String, Boolean> inflight = new ConcurrentHashMap<>();
    private volatile Consumer<Enrichment> listener;

    /**
     * @param cache     positive/negative cache; may be null (in-memory only)
     * @param localDir  user-configured local CSV directory source; may be null
     * @param bundle    downloaded OpenSky bundle CSV source; may be null
     * @param api       live API source; may be null (offline mode)
     */
    public EnrichmentResolver(EnrichmentCache cache,
                              LocalCsvEnrichmentSource localDir,
                              LocalCsvEnrichmentSource bundle,
                              EnrichmentSource api) {
        this.cache    = cache;
        this.localDir = localDir;
        this.bundle   = bundle;
        this.api      = api;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "adsb-enrichment");
            t.setDaemon(true);
            return t;
        };
        // Single worker: OpenSky's polite ceiling is ~1 req/sec anyway,
        // and serialising means we never accidentally overload it.
        this.worker = Executors.newSingleThreadExecutor(tf);
    }

    /**
     * @param listener called whenever an async lookup completes and
     *                 produces new metadata (positive or negative).
     *                 The UI wires this to a table-refresh so the row
     *                 populates without a full reload. Only one
     *                 listener supported; latest call wins.
     */
    public void setListener(Consumer<Enrichment> listener) {
        this.listener = listener;
    }

    /**
     * Register a listener without dropping any previous one --
     * wraps them in a fan-out so multiple UI components (table +
     * details popup) can subscribe. Simple, no CopyOnWriteArrayList
     * needed at this scale.
     */
    public synchronized void addListener(Consumer<Enrichment> next) {
        Consumer<Enrichment> prev = this.listener;
        this.listener = (prev == null)
                ? next
                : e -> { safe(prev, e); safe(next, e); };
    }

    private static void safe(Consumer<Enrichment> c, Enrichment e) {
        try { c.accept(e); }
        catch (RuntimeException ex) {
            System.err.println("[enrichment] listener error: " + ex);
        }
    }

    /**
     * Synchronous fast path. Never blocks on the network. Returns:
     * <ul>
     *   <li>positive cache hit -&gt; the enrichment</li>
     *   <li>fresh negative hit -&gt; {@link Optional#empty()}</li>
     *   <li>local dir hit -&gt; the enrichment (also caches)</li>
     *   <li>bundle hit -&gt; the enrichment (also caches)</li>
     *   <li>otherwise -&gt; {@link Optional#empty()} AND spawns an
     *       async API lookup whose result is delivered via the
     *       listener.</li>
     * </ul>
     */
    public Optional<Enrichment> lookup(String icaoHex) {
        if (icaoHex == null) return Optional.empty();
        String key = icaoHex.toUpperCase();

        // 1) cache
        if (cache != null) {
            Optional<Enrichment> hit = cache.get(key);
            if (hit.isPresent()) {
                Enrichment e = hit.get();
                return e.isEmpty() ? Optional.empty() : hit;
            }
        }

        // 2) local dir
        Optional<Enrichment> hit = safeLookup(localDir, key);
        if (hit.isPresent()) {
            if (cache != null) cache.put(hit.get());
            return hit;
        }

        // 3) bundle
        hit = safeLookup(bundle, key);
        if (hit.isPresent()) {
            if (cache != null) cache.put(hit.get());
            return hit;
        }

        // 4) API (async, non-blocking)
        // Skip the async spawn entirely when the API source has
        // tripped its circuit breaker -- otherwise we'd queue a
        // worker task that immediately no-ops, wasting the slot.
        if (api != null && !apiIsDisabled(api)
                && inflight.putIfAbsent(key, Boolean.TRUE) == null) {
            worker.submit(() -> {
                try {
                    Enrichment result = api.lookup(key).orElseGet(() -> Enrichment.empty(key));
                    if (cache != null) cache.put(result);
                    Consumer<Enrichment> l = listener;
                    if (l != null && !result.isEmpty()) safe(l, result);
                } finally {
                    inflight.remove(key);
                }
            });
        }
        return Optional.empty();
    }

    /**
     * Duck-type check for a source-level circuit breaker. Currently
     * only {@link OpenSkyApiEnrichmentSource} exposes one; other
     * sources return false. Kept as an instanceof rather than adding
     * an isDisabled() method to the SPI so the interface stays lean
     * for third-party sources that don't need the concept.
     */
    private static boolean apiIsDisabled(EnrichmentSource src) {
        return (src instanceof OpenSkyApiEnrichmentSource api) && api.isDisabled();
    }

    private static Optional<Enrichment> safeLookup(EnrichmentSource src, String key) {
        if (src == null) return Optional.empty();
        try {
            return src.lookup(key);
        } catch (RuntimeException e) {
            System.err.println("[enrichment/" + src.name() + "] error: " + e);
            return Optional.empty();
        }
    }

    /** Shut the worker down cleanly. */
    public void shutdown() {
        worker.shutdownNow();
        if (cache != null) cache.flush();
    }

    /** Force-flush the cache to disk (e.g. periodic housekeeping). */
    public void flushCache() {
        if (cache != null) cache.flush();
    }

    /** @return a short human-readable status line for a Settings panel display. */
    public String statusLine() {
        StringBuilder sb = new StringBuilder();
        if (localDir != null && localDir.directory() != null) {
            sb.append("local: ").append(localDir.rowCount()).append(" rows (")
              .append(localDir.fileCount()).append(" files)");
        } else {
            sb.append("local: unset");
        }
        sb.append(" | ");
        if (bundle != null && bundle.directory() != null) {
            sb.append("bundle: ").append(bundle.rowCount()).append(" rows");
        } else {
            sb.append("bundle: none");
        }
        sb.append(" | api: ").append(api == null ? "off" : "on");
        sb.append(" | cache: ").append(cache == null ? "off" : cache.size() + " entries");
        return sb.toString();
    }

    /**
     * Fetch the OpenSky aircraft-database snapshot to the given
     * directory and reload the bundle source. Blocks the calling
     * thread; the UI is expected to spin this off a background
     * worker so the EDT doesn't stall.
     *
     * <p>Default URL is the stable public snapshot at
     * {@code https://s3.opensky-network.org/data-samples/metadata/aircraftDatabase.csv}.
     * The caller may pass a list of candidate URLs to try in order
     * (empty list -&gt; use the default); the first URL that returns
     * 2xx wins.
     *
     * @return true on successful download + bundle reload; false on
     *         any network / disk error (details go to stderr)
     */
    public boolean downloadBundle(java.nio.file.Path targetDir, List<String> urls) {
        if (targetDir == null) return false;
        List<String> tryUrls = (urls == null || urls.isEmpty())
                ? List.of("https://s3.opensky-network.org/data-samples/metadata/aircraftDatabase.csv")
                : urls;
        try {
            java.nio.file.Files.createDirectories(targetDir);
        } catch (java.io.IOException e) {
            System.err.println("[enrichment/bundle] mkdir failed: " + e.getMessage());
            return false;
        }
        java.nio.file.Path target = targetDir.resolve("aircraftDatabase.csv");
        java.nio.file.Path tmp    = targetDir.resolve("aircraftDatabase.csv.tmp");

        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
        for (String url : tryUrls) {
            try {
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url))
                        .timeout(java.time.Duration.ofMinutes(5))
                        .header("User-Agent", "ads-b-rcvr/1.0 (+github.com/mdudel/ads-b-rcvr)")
                        .GET()
                        .build();
                java.net.http.HttpResponse<java.nio.file.Path> resp = client.send(req,
                        java.net.http.HttpResponse.BodyHandlers.ofFile(tmp));
                if (resp.statusCode() / 100 != 2) {
                    System.err.printf("[enrichment/bundle] %s -> HTTP %d%n", url, resp.statusCode());
                    try { java.nio.file.Files.deleteIfExists(tmp); } catch (Exception ignored) {}
                    continue;
                }
                java.nio.file.Files.move(tmp, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                System.out.printf("[enrichment/bundle] downloaded %s -> %s (%d bytes)%n",
                        url, target, java.nio.file.Files.size(target));
                if (bundle != null) bundle.reload();
                return true;
            } catch (java.io.IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                System.err.printf("[enrichment/bundle] %s failed: %s%n", url, e.getMessage());
                try { java.nio.file.Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        }
        return false;
    }

    /** @return underlying local-dir source (may be null). */
    public LocalCsvEnrichmentSource localDir() { return localDir; }

    /** @return underlying bundle source (may be null). */
    public LocalCsvEnrichmentSource bundle() { return bundle; }
}
