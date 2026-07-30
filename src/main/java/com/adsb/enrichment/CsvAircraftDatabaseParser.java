package com.adsb.enrichment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parse an OpenSky-flavour aircraft-database CSV into an in-memory
 * {@code Map<icao24_upper, Enrichment>}.
 *
 * <p>Reference schema (OpenSky publishes monthly at
 * {@code https://opensky-network.org/datasets/metadata/}):
 *
 * <pre>
 * icao24,registration,manufacturericao,manufacturername,model,typecode,
 * serialnumber,linenumber,icaoaircrafttype,operator,operatorcallsign,
 * operatoricao,operatoriata,owner,testreg,registered,reguntil,status,
 * built,firstflightdate,seatconfiguration,engines,modes,adsb,acars,notes,
 * categoryDescription
 * </pre>
 *
 * <p>We map:
 * <ul>
 *   <li>{@code icao24} -&gt; key (uppercased)</li>
 *   <li>{@code registration} -&gt; {@link Enrichment#registration()}</li>
 *   <li>{@code typecode} -&gt; {@link Enrichment#typeCode()}</li>
 *   <li>{@code manufacturername} -&gt; {@link Enrichment#manufacturer()}</li>
 *   <li>{@code model} -&gt; {@link Enrichment#model()}</li>
 *   <li>{@code operator} -&gt; {@link Enrichment#operator()}</li>
 *   <li>{@code operatoricao} -&gt; {@link Enrichment#operatorIcao()}</li>
 * </ul>
 *
 * <p><b>Column matching is by NAME, not position.</b> The OpenSky
 * schema has drifted between snapshots (new columns appended,
 * occasionally renamed). Column-name lookup on the header row keeps
 * us robust to those changes as long as the canonical names above
 * survive.
 *
 * <p><b>Quoting</b>: OpenSky uses RFC 4180 rules -- fields containing
 * commas / quotes / newlines are double-quoted, embedded quotes
 * doubled. This parser handles the common cases (quoted comma,
 * doubled quote) but does NOT handle embedded newlines inside
 * quoted fields -- OpenSky's operator names occasionally have them
 * (e.g. multi-line notes columns) and we skip such rows rather than
 * mis-parse. Rare enough not to matter.
 */
public final class CsvAircraftDatabaseParser {

    private CsvAircraftDatabaseParser() {}

    /**
     * Read the given CSV reader and return a case-insensitive map of
     * icao24 (uppercase) -&gt; enrichment. Blank ICAO rows are skipped.
     * Callers are responsible for closing the reader.
     *
     * @throws IOException if the header row is missing / unreadable
     */
    public static Map<String, Enrichment> parse(Reader reader) throws IOException {
        BufferedReader br = (reader instanceof BufferedReader b) ? b : new BufferedReader(reader);

        String headerLine = br.readLine();
        if (headerLine == null) {
            throw new IOException("CSV is empty (no header row)");
        }

        List<String> headers = splitCsv(headerLine);
        int icao24Idx        = indexOf(headers, "icao24");
        int registrationIdx  = indexOf(headers, "registration");
        int typeCodeIdx      = indexOf(headers, "typecode");
        int manufacturerIdx  = indexOf(headers, "manufacturername");
        int modelIdx         = indexOf(headers, "model");
        int operatorIdx      = indexOf(headers, "operator");
        int operatorIcaoIdx  = indexOf(headers, "operatoricao");

        if (icao24Idx < 0) {
            throw new IOException("CSV header missing required column 'icao24'");
        }

        Map<String, Enrichment> out = new HashMap<>();
        String line;
        long lineNum = 1;   // header was line 1
        while ((line = br.readLine()) != null) {
            lineNum++;
            if (line.isEmpty()) continue;
            List<String> fields;
            try {
                fields = splitCsv(line);
            } catch (RuntimeException e) {
                // Malformed row -- skip rather than abort the whole file.
                continue;
            }
            String icao24 = safeGet(fields, icao24Idx);
            if (icao24 == null || icao24.isBlank()) continue;
            String key = icao24.trim().toUpperCase();
            if (key.length() > 6) continue;   // schema violation, skip

            Enrichment e = new Enrichment(
                    key,
                    trimToNull(safeGet(fields, registrationIdx)),
                    trimToNull(safeGet(fields, typeCodeIdx)),
                    trimToNull(safeGet(fields, manufacturerIdx)),
                    trimToNull(safeGet(fields, modelIdx)),
                    trimToNull(safeGet(fields, operatorIdx)),
                    trimToNull(safeGet(fields, operatorIcaoIdx))
            );
            // Last-write wins if a CSV somehow has duplicate ICAOs.
            out.put(key, e);
        }
        return out;
    }

    /**
     * Case-insensitive header index lookup. Returns -1 if the column
     * is absent (all downstream lookups then get null values, which
     * the record accepts).
     */
    private static int indexOf(List<String> headers, String want) {
        for (int i = 0; i < headers.size(); i++) {
            if (want.equalsIgnoreCase(headers.get(i).trim())) return i;
        }
        return -1;
    }

    private static String safeGet(List<String> row, int idx) {
        if (idx < 0 || idx >= row.size()) return null;
        return row.get(idx);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Split one CSV line respecting double-quoted fields and doubled
     * embedded quotes. Package-private for tests.
     *
     * <p>Does NOT support embedded newlines inside quoted fields --
     * the caller reads line-at-a-time and such a row would be
     * split across two readLine() results. See class javadoc.
     */
    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');   // escaped quote
                        i += 2;
                        continue;
                    } else {
                        inQuotes = false;
                        i++;
                        continue;
                    }
                }
                cur.append(c);
                i++;
            } else {
                if (c == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                    i++;
                } else if (c == '"' && cur.length() == 0) {
                    inQuotes = true;
                    i++;
                } else {
                    cur.append(c);
                    i++;
                }
            }
        }
        out.add(cur.toString());
        return out;
    }
}
