package com.adsb.enrichment;

/**
 * External metadata for one aircraft, keyed by 24-bit ICAO address.
 *
 * <p>Values come from either a user-provided local CSV (dropped into
 * the configured enrichment directory), the bundled OpenSky
 * aircraft-database CSV downloaded on demand, or a live REST call
 * against the OpenSky metadata endpoint. All three feed the same
 * record so downstream consumers (tracks table, popup, exports) don't
 * care about origin.
 *
 * <p>Any field may be {@code null} -- CSVs and API responses are
 * incomplete in practice (a lot of general-aviation aircraft have
 * only a registration, no operator; military and privately-owned
 * jets are usually redacted entirely).
 *
 * <p>An "empty" enrichment (all fields null) is treated as a
 * NEGATIVE cache marker in {@link EnrichmentCache}: it means "we
 * asked, nothing came back, don't ask again for 24h". Use
 * {@link #isEmpty()} to check.
 */
public record Enrichment(
        /** ICAO 24-bit address, uppercase hex, no separators. */
        String icaoHex,
        /** Civil registration / tail number (e.g. {@code "D-ABYT"}). */
        String registration,
        /** ICAO aircraft type designator (e.g. {@code "B738"}, {@code "A320"}). */
        String typeCode,
        /** Manufacturer name (e.g. {@code "Boeing"}, {@code "Airbus"}). */
        String manufacturer,
        /** Aircraft model (e.g. {@code "737-800"}, {@code "A320-214"}). */
        String model,
        /** Operator name (e.g. {@code "Lufthansa"}, {@code "DHL Air"}). */
        String operator,
        /** IATA/ICAO operator code (e.g. {@code "DLH"}, {@code "BAW"}). */
        String operatorIcao
) {
    /**
     * Factory for a negative-cache marker. Only the ICAO key is
     * populated so downstream code can still tell WHICH ICAO the
     * miss was recorded for.
     */
    public static Enrichment empty(String icaoHex) {
        return new Enrichment(
                icaoHex == null ? null : icaoHex.toUpperCase(),
                null, null, null, null, null, null);
    }

    /** @return true when every metadata field is null (negative-cache marker). */
    public boolean isEmpty() {
        return registration == null && typeCode == null && manufacturer == null
                && model == null && operator == null && operatorIcao == null;
    }
}
