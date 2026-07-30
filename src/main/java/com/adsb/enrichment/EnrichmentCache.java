package com.adsb.enrichment;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent per-ICAO enrichment cache. Holds both positive hits
 * ("this aircraft is D-ABYT, a Boeing 748") and negative hits
 * ("we asked and nothing came back, don't ask again for 24 h").
 *
 * <p>On-disk format: JSONL (one record per line) at
 * {@code ~/.adsb-rcvr/enrichment-cache.json}. Chosen over sqlite so
 * we don't pull a native dep for what is fundamentally a keyed store
 * with a few thousand entries. Atomic tmp+rename writes; corrupt
 * lines are skipped with a WARN so a partial write can't wedge us.
 *
 * <p><b>Negative-cache TTL</b>: 24 h. Rationale: OpenSky updates
 * their DB monthly; a 24 h TTL means we retry stubborn misses at
 * most once per day, which is polite but recovers new registrations
 * within a day of them appearing upstream.
 */
public final class EnrichmentCache {

    /** How long negative-cache entries survive before being retried. */
    public static final Duration NEGATIVE_TTL = Duration.ofHours(24);

    private record Entry(Enrichment e, Instant fetchedAt) {}

    private final Path storePath;
    private final ConcurrentMap<String, Entry> table = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public EnrichmentCache(Path storePath) {
        this.storePath = storePath;
    }

    /**
     * Load the cache from disk. Missing file / corrupt lines are
     * tolerated (empty in-memory table on missing; skip on corrupt).
     */
    public synchronized void load() {
        table.clear();
        if (storePath == null || !Files.exists(storePath)) return;
        try (BufferedReader r = Files.newBufferedReader(storePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    Entry e = decodeLine(line);
                    if (e != null && e.e().icaoHex() != null) {
                        table.put(e.e().icaoHex(), e);
                    }
                } catch (RuntimeException re) {
                    // Skip garbled line.
                }
            }
        } catch (IOException e) {
            System.err.println("[enrichment/cache] load failed: " + e.getMessage());
        }
    }

    /** Persist the cache to disk. No-op when nothing has changed. */
    public synchronized void flush() {
        if (!dirty.get() || storePath == null) return;
        try {
            Files.createDirectories(storePath.getParent());
            Path tmp = storePath.resolveSibling(storePath.getFileName().toString() + ".tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (Entry e : table.values()) {
                    w.write(encodeLine(e));
                    w.newLine();
                }
            }
            Files.move(tmp, storePath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            dirty.set(false);
        } catch (IOException e) {
            System.err.println("[enrichment/cache] flush failed: " + e.getMessage());
        }
    }

    /**
     * @return the cached enrichment for this ICAO, or empty when
     *         absent OR when a negative-cache entry has aged past
     *         {@link #NEGATIVE_TTL}. Positive hits never expire
     *         from cache (aircraft metadata rarely changes).
     */
    public Optional<Enrichment> get(String icaoHex) {
        if (icaoHex == null) return Optional.empty();
        Entry e = table.get(icaoHex.toUpperCase());
        if (e == null) return Optional.empty();
        if (e.e().isEmpty()) {
            // Negative cache -- check TTL
            Duration age = Duration.between(e.fetchedAt(), Instant.now());
            if (age.compareTo(NEGATIVE_TTL) > 0) {
                return Optional.empty();
            }
        }
        return Optional.of(e.e());
    }

    /** Store a positive or negative hit; marks the cache dirty. */
    public void put(Enrichment enrichment) {
        if (enrichment == null || enrichment.icaoHex() == null) return;
        table.put(enrichment.icaoHex(),
                new Entry(enrichment, Instant.now()));
        dirty.set(true);
    }

    /** @return total number of entries currently held. */
    public int size() {
        return table.size();
    }

    // ---- JSON line encoding (hand-rolled, same reason as OpenSkyApiEnrichmentSource) ----

    static String encodeLine(Entry e) {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{');
        appendKv(sb, "icao24",       e.e().icaoHex(),       true);
        appendKv(sb, "registration", e.e().registration(),  false);
        appendKv(sb, "typecode",     e.e().typeCode(),      false);
        appendKv(sb, "manufacturer", e.e().manufacturer(),  false);
        appendKv(sb, "model",        e.e().model(),         false);
        appendKv(sb, "operator",     e.e().operator(),      false);
        appendKv(sb, "operatorIcao", e.e().operatorIcao(),  false);
        appendKv(sb, "fetchedAt",    e.fetchedAt().toString(), false);
        sb.append('}');
        return sb.toString();
    }

    private static void appendKv(StringBuilder sb, String k, String v, boolean first) {
        if (!first) sb.append(',');
        sb.append('"').append(k).append("\":");
        if (v == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escape(v)).append('"');
        }
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default   -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Decode one JSONL line to an entry. Package-private for tests. */
    static Entry decodeLine(String line) {
        String icao       = fieldOrNull(line, "icao24");
        if (icao == null) return null;
        String reg        = fieldOrNull(line, "registration");
        String type       = fieldOrNull(line, "typecode");
        String manuf      = fieldOrNull(line, "manufacturer");
        String model      = fieldOrNull(line, "model");
        String op         = fieldOrNull(line, "operator");
        String opIcao     = fieldOrNull(line, "operatorIcao");
        String fetchedAt  = fieldOrNull(line, "fetchedAt");
        Instant when;
        try {
            when = fetchedAt == null ? Instant.EPOCH : Instant.parse(fetchedAt);
        } catch (RuntimeException e) {
            when = Instant.EPOCH;
        }
        return new Entry(
                new Enrichment(icao, reg, type, manuf, model, op, opIcao),
                when);
    }

    private static String fieldOrNull(String body, String key) {
        String needle = "\"" + key + "\":";
        int k = body.indexOf(needle);
        if (k < 0) return null;
        int i = k + needle.length();
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        if (i >= body.length()) return null;
        char c = body.charAt(i);
        if (c == 'n') return null;
        if (c != '"') return null;
        i++;
        StringBuilder out = new StringBuilder();
        while (i < body.length()) {
            char ch = body.charAt(i);
            if (ch == '\\' && i + 1 < body.length()) {
                char nx = body.charAt(i + 1);
                switch (nx) {
                    case '"'  -> out.append('"');
                    case '\\' -> out.append('\\');
                    case 'n'  -> out.append('\n');
                    case 't'  -> out.append('\t');
                    case 'r'  -> out.append('\r');
                    default   -> out.append(nx);
                }
                i += 2;
            } else if (ch == '"') {
                return out.toString();
            } else {
                out.append(ch);
                i++;
            }
        }
        return null;
    }
}
