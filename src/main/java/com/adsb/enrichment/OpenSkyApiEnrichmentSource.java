package com.adsb.enrichment;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link EnrichmentSource} that hits the OpenSky Network aircraft
 * metadata endpoint:
 *
 * <pre>
 * GET https://opensky-network.org/api/metadata/aircraft/icao/{icao24}
 * </pre>
 *
 * <p>Unauthenticated. The endpoint returns JSON of the shape:
 *
 * <pre>{@code
 * {
 *   "icao24": "3c6444",
 *   "registration": "D-ABYT",
 *   "manufacturerName": "Boeing",
 *   "model": "747-830",
 *   "typecode": "B748",
 *   "operator": "Lufthansa",
 *   "operatorIcao": "DLH",
 *   ...
 * }
 * }</pre>
 *
 * <p><b>Never called on the EDT</b> -- the caller (typically
 * {@link EnrichmentResolver}) drives lookups from a background
 * executor. This class blocks the calling thread on the HTTP
 * exchange; a 5s connect + 10s response timeout keeps stalls
 * bounded.
 *
 * <p><b>Rate limit</b>: a {@link Semaphore} caps in-flight requests
 * to 1 to avoid the OpenSky WAF's 429 threshold. A minimum
 * inter-request delay (default 1 s) prevents burst spikes. Both are
 * tunable via the constructor.
 *
 * <p><b>Errors</b> (404, 429, 5xx, timeouts, DNS failure) return
 * {@link Optional#empty()} with a WARN to stderr. 404 is the common
 * case (aircraft not in OpenSky's DB) and is logged at DEBUG level
 * to avoid log spam; other errors log at WARN.
 *
 * <p><b>JSON parsing</b>: hand-rolled (no Jackson / Gson dep). The
 * endpoint shape is stable and tiny; a 30-line string extractor is
 * cheaper than pulling in a new dependency.
 */
public final class OpenSkyApiEnrichmentSource implements EnrichmentSource {

    private static final String DEFAULT_BASE_URL =
            "https://opensky-network.org/api/metadata/aircraft/icao/";
    private static final Duration DEFAULT_MIN_INTERVAL = Duration.ofMillis(1000);
    private static final Duration CONNECT_TIMEOUT     = Duration.ofSeconds(5);
    private static final Duration RESPONSE_TIMEOUT    = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final Semaphore inFlight = new Semaphore(1, /*fair*/ true);
    private final long minIntervalNanos;
    private final AtomicLong lastRequestNanos = new AtomicLong(0);

    public OpenSkyApiEnrichmentSource() {
        this(DEFAULT_BASE_URL, DEFAULT_MIN_INTERVAL);
    }

    public OpenSkyApiEnrichmentSource(String baseUrl, Duration minInterval) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.minIntervalNanos = minInterval.toNanos();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)   // safest against WAFs
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public Optional<Enrichment> lookup(String icaoHex) {
        if (icaoHex == null || icaoHex.isBlank()) return Optional.empty();
        String key = icaoHex.trim().toLowerCase();
        if (key.length() > 6) return Optional.empty();

        try {
            inFlight.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        try {
            // Simple pacing: sleep until minInterval has passed since
            // the last request. Cheap on wall-clock, no scheduler.
            long now = System.nanoTime();
            long earliest = lastRequestNanos.get() + minIntervalNanos;
            if (now < earliest) {
                long sleepNanos = earliest - now;
                try {
                    TimeUnit.NANOSECONDS.sleep(sleepNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
            lastRequestNanos.set(System.nanoTime());

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + key))
                    .timeout(RESPONSE_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("User-Agent", "ads-b-rcvr/1.0 (+github.com/mdudel/ads-b-rcvr)")
                    .GET()
                    .build();
            HttpResponse<String> resp;
            try {
                resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                System.err.printf("[enrichment/opensky] I/O for %s: %s%n", key, e.getMessage());
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            int sc = resp.statusCode();
            if (sc == 404) {
                // Common case: aircraft not in OpenSky DB. Don't log.
                return Optional.empty();
            }
            if (sc == 429) {
                System.err.printf("[enrichment/opensky] rate-limited on %s%n", key);
                return Optional.empty();
            }
            if (sc / 100 != 2) {
                System.err.printf("[enrichment/opensky] HTTP %d for %s%n", sc, key);
                return Optional.empty();
            }
            return Optional.of(parseJson(icaoHex.toUpperCase(), resp.body()));
        } finally {
            inFlight.release();
        }
    }

    @Override
    public String name() {
        return "opensky-api";
    }

    /**
     * Extract the six fields we care about from OpenSky's JSON
     * response. Package-private for tests. Handles missing fields
     * (returns null for absent keys) and the {@code null} literal.
     */
    static Enrichment parseJson(String icaoHexUpper, String body) {
        return new Enrichment(
                icaoHexUpper,
                extractString(body, "registration"),
                extractString(body, "typecode"),
                extractString(body, "manufacturerName"),
                extractString(body, "model"),
                extractString(body, "operator"),
                extractString(body, "operatorIcao")
        );
    }

    /**
     * Minimal JSON string extractor: find {@code "key" : "value"}
     * and return the value with escape sequences decoded. Returns
     * null when the key is absent, the value is the literal
     * {@code null}, or the value is an empty string.
     *
     * <p>Deliberately does NOT handle nested objects, arrays, or
     * numeric values -- OpenSky's aircraft-metadata endpoint has
     * only string / null fields at the top level.
     */
    private static String extractString(String body, String key) {
        String needle = "\"" + key + "\"";
        int k = body.indexOf(needle);
        if (k < 0) return null;
        int colon = body.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int i = colon + 1;
        // Skip whitespace
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        if (i >= body.length()) return null;
        if (body.charAt(i) == 'n') {
            // "null" literal
            return null;
        }
        if (body.charAt(i) != '"') {
            // Non-string value (shouldn't happen for this endpoint)
            return null;
        }
        // Read quoted string with basic \" and \\ handling
        StringBuilder out = new StringBuilder();
        i++;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char next = body.charAt(i + 1);
                switch (next) {
                    case '"'  -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/'  -> out.append('/');
                    case 'n'  -> out.append('\n');
                    case 't'  -> out.append('\t');
                    case 'r'  -> out.append('\r');
                    default   -> out.append(next);
                }
                i += 2;
            } else if (c == '"') {
                String v = out.toString().trim();
                return v.isEmpty() ? null : v;
            } else {
                out.append(c);
                i++;
            }
        }
        return null;   // unterminated string
    }
}
