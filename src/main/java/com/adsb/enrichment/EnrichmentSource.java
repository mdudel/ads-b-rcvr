package com.adsb.enrichment;

import java.util.Optional;

/**
 * SPI: one source of {@link Enrichment} data, keyed by ICAO 24-bit
 * address in uppercase hex.
 *
 * <p>Implementations should be thread-safe (the resolver may hit
 * multiple sources in parallel) and should return synchronously.
 * Sources that need I/O (API calls, disk reads) should perform the
 * blocking work themselves; the composite {@link EnrichmentResolver}
 * is responsible for keeping expensive lookups off the EDT via its
 * own executor.
 *
 * <p>Absent lookups return {@link Optional#empty()} so the resolver
 * can fall through to the next source in the chain. Errors are
 * signalled by returning empty (with an internal WARN log) rather
 * than throwing, to keep the resolver simple.
 */
public interface EnrichmentSource {

    /**
     * @param icaoHex ICAO 24-bit address in uppercase hex, no separators.
     * @return the enrichment for this aircraft, or {@link Optional#empty()}
     *         when this source does not know about it (or an I/O error
     *         occurred; check logs).
     */
    Optional<Enrichment> lookup(String icaoHex);

    /**
     * Short human name for logging / status display, e.g.
     * {@code "local-csv:/data/ads-b/"}.
     */
    String name();
}
