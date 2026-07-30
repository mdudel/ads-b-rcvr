package com.adsb.enrichment;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * {@link EnrichmentSource} backed by one directory of OpenSky-format
 * CSV files. Every {@code *.csv} in the directory is parsed and
 * merged into a single in-memory map at load time; lookups are then
 * O(1).
 *
 * <p><b>Load model</b>: eager on {@link #load()} and again on
 * {@link #reload()}. No incremental / watched-directory magic --
 * the operator drops a new CSV in and clicks the reload button (or
 * relaunches). Keeping it simple is worth more than filesystem
 * watchers here.
 *
 * <p><b>Thread safety</b>: the merged map is held in an
 * {@link AtomicReference}, so {@link #lookup} sees a consistent
 * snapshot even while {@link #reload} is running on another thread.
 * Lookups never block on I/O -- everything is in memory once loaded.
 */
public final class LocalCsvEnrichmentSource implements EnrichmentSource {

    private final Path dir;
    private final AtomicReference<Map<String, Enrichment>> table =
            new AtomicReference<>(Collections.emptyMap());
    private volatile int lastLoadedRowCount = 0;
    private volatile int lastLoadedFileCount = 0;

    public LocalCsvEnrichmentSource(Path dir) {
        this.dir = dir;
    }

    /**
     * Scan the directory, parse every {@code *.csv}, merge into a
     * fresh table, and publish it atomically. Safe to call at any
     * time. No-op (empty table) when the directory is null or
     * doesn't exist.
     *
     * @return number of rows loaded (0 when the directory is empty
     *         or non-existent)
     */
    public int load() {
        if (dir == null || !Files.isDirectory(dir)) {
            table.set(Collections.emptyMap());
            lastLoadedRowCount = 0;
            lastLoadedFileCount = 0;
            return 0;
        }
        Map<String, Enrichment> merged = new HashMap<>();
        int files = 0;
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString();
                if (!name.toLowerCase().endsWith(".csv")) continue;
                try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    Map<String, Enrichment> one = CsvAircraftDatabaseParser.parse(r);
                    // Later-parsed file wins on duplicates; we don't
                    // define load order across CSVs beyond
                    // Files.list()'s (system-dependent) enumeration.
                    // Duplicates are rare in practice.
                    merged.putAll(one);
                    files++;
                } catch (IOException e) {
                    System.err.printf(
                            "[enrichment/local-csv] skip %s: %s%n",
                            p.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.printf(
                    "[enrichment/local-csv] failed to list %s: %s%n",
                    dir, e.getMessage());
        }
        table.set(merged);
        lastLoadedRowCount = merged.size();
        lastLoadedFileCount = files;
        return merged.size();
    }

    /** Alias for {@link #load} to make the operator intent explicit at call sites. */
    public int reload() { return load(); }

    @Override
    public Optional<Enrichment> lookup(String icaoHex) {
        if (icaoHex == null) return Optional.empty();
        Enrichment e = table.get().get(icaoHex.toUpperCase());
        return Optional.ofNullable(e);
    }

    @Override
    public String name() {
        return "local-csv:" + (dir == null ? "(unset)" : dir);
    }

    /** @return number of rows in the currently-loaded table. */
    public int rowCount() { return lastLoadedRowCount; }

    /** @return number of CSV files parsed on the last load. */
    public int fileCount() { return lastLoadedFileCount; }

    /** @return the directory this source watches (may be null). */
    public Path directory() { return dir; }
}
