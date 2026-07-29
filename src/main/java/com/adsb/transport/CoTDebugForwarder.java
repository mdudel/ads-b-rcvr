package com.adsb.transport;

import com.adsb.core.FrameForwarder;

import java.io.PrintStream;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Debug sink that writes every emitted CoT XML document to a
 * {@link PrintStream} (stdout by default). Attached like any other
 * {@link com.adsb.core.PayloadFormat#COT} sink so it sees the exact
 * bytes that go on the wire to UDP/multicast/TCP transports.
 *
 * <p><b>Why this exists (Marty 2026-07-29 10:34 UTC):</b> after
 * three iterations of anti-jump filters (84a93c9 speed-only,
 * f9af106 geofence, 755c2c7 kinematic) SkyLord was still showing
 * jumping tracks. Rule 2 from MEMORY.md L807-864 (LOB-legend
 * disaster): two failed fixes = revert and rethink. Before writing
 * fix #4, prove the CoT going out actually matches what SkyLord
 * displays. This sink is the evidence source.
 *
 * <p><b>Features:</b>
 * <ul>
 *   <li>Optional {@link #pretty} mode: reformat single-line CoT XML
 *       into indented multi-line output so a terminal can read it.
 *       Off by default (single-line matches what really goes on the
 *       wire and preserves ATAK/GCCS-J-compatible byte-for-byte
 *       comparison).</li>
 *   <li>Optional {@link #rateLimitMs}: emit at most one document per
 *       ICAO per this interval. Zero disables. A chatty aircraft can
 *       emit 2 Hz position + 1 Hz velocity = ~3 CoT/s, and with 40
 *       aircraft airborne the terminal drowns; rate-limit at 5 s per
 *       ICAO by default when enabled.</li>
 *   <li>Optional {@link #icaoFilter}: regex; when non-null, only
 *       emit for aircraft whose ICAO matches. Handy for tailing one
 *       specific glitchy target (e.g. {@code 4B3810|471DB5}).</li>
 * </ul>
 *
 * <p><b>Non-features (deliberately kept out):</b>
 * <ul>
 *   <li>No timestamp on the emitted line -- the CoT document already
 *       has one in the {@code time=""} attribute. Adding a wall-clock
 *       prefix would break byte-diffing against the wire.</li>
 *   <li>No colour codes / ANSI. Terminals that don't render them
 *       show garbage; the operator can pipe through {@code grep --color}.</li>
 *   <li>No file rotation / append mode. If someone wants persistent
 *       logs, they can shell-redirect stdout.</li>
 * </ul>
 *
 * <p>Thread-safe: {@link PrintStream#println} is synchronised.
 */
public final class CoTDebugForwarder implements FrameForwarder {

    private final PrintStream out;
    private final boolean pretty;
    private final long rateLimitMs;
    private final Pattern icaoFilter;

    /** Per-ICAO last-emit timestamp for rate-limiting. Package-private for tests. */
    final ConcurrentMap<String, Long> lastEmitMs = new ConcurrentHashMap<>();

    /**
     * Extract the {@code uid="ICAO-XXXXXX"} attribute so we can rate-limit
     * per aircraft without parsing the full XML. Matches {@link com.adsb.cot.CoTBuilder}'s
     * emission shape verbatim: uid is {@code "ICAO-" + track.icaoHex()},
     * uppercase, no separators.
     */
    private static final Pattern UID_PATTERN =
            Pattern.compile("uid=\"ICAO-([0-9A-Fa-f]+)\"");

    /**
     * @param out          where to write (usually {@link System#out})
     * @param pretty       reformat XML into indented lines (single-line otherwise)
     * @param rateLimitMs  min ms between consecutive lines for the same ICAO;
     *                     0 to disable
     * @param icaoFilter   regex; only ICAOs matching are emitted. {@code null} = all.
     */
    public CoTDebugForwarder(PrintStream out, boolean pretty,
                             long rateLimitMs, Pattern icaoFilter) {
        if (out == null) throw new IllegalArgumentException("out");
        if (rateLimitMs < 0) throw new IllegalArgumentException("rateLimitMs");
        this.out         = out;
        this.pretty      = pretty;
        this.rateLimitMs = rateLimitMs;
        this.icaoFilter  = icaoFilter;
    }

    /** Convenience: stdout, single-line, no rate limit, no filter. */
    public static CoTDebugForwarder stdoutRaw() {
        return new CoTDebugForwarder(System.out, false, 0L, null);
    }

    @Override
    public void forward(byte[] frame) {
        if (frame == null || frame.length == 0) return;
        String xml = new String(frame, java.nio.charset.StandardCharsets.UTF_8);

        String icao = extractIcao(xml);
        if (icaoFilter != null && (icao == null || !icaoFilter.matcher(icao).matches())) {
            return;
        }
        if (rateLimitMs > 0 && icao != null) {
            long now = System.currentTimeMillis();
            Long prev = lastEmitMs.get(icao);
            if (prev != null && (now - prev) < rateLimitMs) return;
            lastEmitMs.put(icao, now);
        }

        if (pretty) {
            out.println("---- CoT " + (icao == null ? "?" : icao) + " @ "
                    + Instant.now() + " ----");
            out.println(prettyPrint(xml));
        } else {
            out.println(xml);
        }
        out.flush();
    }

    @Override
    public void close() { /* stdout stays open */ }

    // ------------------------------------------------------------------

    /** Extract ICAO hex from a CoT event {@code uid="ICAO-XXXXXX"}; null on miss. */
    static String extractIcao(String xml) {
        if (xml == null) return null;
        Matcher m = UID_PATTERN.matcher(xml);
        if (m.find()) return m.group(1).toUpperCase();
        return null;
    }

    /**
     * Reformat CoT XML into readable multi-line form. NOT a full XML
     * pretty-printer -- CoT documents have a known shape:
     * {@code <?xml ...?><event ...><point .../><detail>...</detail></event>}
     * and we can split predictably on element boundaries. Preserves
     * attribute order and text content; adds two-space indentation.
     * Safe fallback: if the XML doesn't match the expected shape, return
     * the raw input (better raw than crashing the debug stream).
     */
    static String prettyPrint(String xml) {
        if (xml == null) return "";
        try {
            String s = xml.trim();
            StringBuilder sb = new StringBuilder(s.length() + 64);
            int depth = 0;
            int i = 0;
            while (i < s.length()) {
                if (s.charAt(i) != '<') {
                    // stray text between elements -- rare in CoT, but preserve
                    int nextLt = s.indexOf('<', i);
                    if (nextLt < 0) { sb.append(s.substring(i)); break; }
                    String txt = s.substring(i, nextLt).trim();
                    if (!txt.isEmpty()) sb.append(indent(depth)).append(txt).append('\n');
                    i = nextLt;
                    continue;
                }
                int end = s.indexOf('>', i);
                if (end < 0) { sb.append(s.substring(i)); break; }
                String tag = s.substring(i, end + 1);
                boolean isClose      = tag.startsWith("</");
                boolean isSelfClosing = tag.endsWith("/>") || tag.startsWith("<?");
                if (isClose) depth = Math.max(0, depth - 1);
                sb.append(indent(depth)).append(tag).append('\n');
                if (!isClose && !isSelfClosing) depth++;
                i = end + 1;
            }
            return sb.toString();
        } catch (Exception e) {
            return xml;
        }
    }

    private static String indent(int depth) {
        if (depth <= 0) return "";
        StringBuilder sb = new StringBuilder(depth * 2);
        for (int i = 0; i < depth; i++) sb.append("  ");
        return sb.toString();
    }
}
