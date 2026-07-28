package com.adsb.transport;

import com.adsb.core.FrameForwarder;
import com.adsb.ui.model.ZenohMode;

import io.mdudel.zenoh.purejava.PureJavaZenohPublisher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Publishes each forwarded frame to a Zenoh router as a PUSH message,
 * on a key derived from the payload contents.
 *
 * <h2>Key expression layout</h2>
 *
 * <p>Controlled by the {@link ZenohMode} passed at construction:
 *
 * <ul>
 *   <li>{@link ZenohMode#STREAM}: every frame publishes to the base
 *       {@code keyPrefix} as-is. All payloads (CoT, JSON, AVR) land on
 *       one topic. Best for downstream consumers that want the whole
 *       feed as a single stream and will dispatch / filter themselves.</li>
 *   <li>{@link ZenohMode#PER_AIRCRAFT}: CoT XML with a
 *       {@code uid="ICAO-XXXXXX"} attribute publishes to
 *       {@code &lt;keyPrefix&gt;/&lt;ICAO&gt;} so Zenoh subscribers can
 *       select a single aircraft with {@code adsb/cot/4CA1FA} or the
 *       whole fleet with {@code adsb/cot/**}. Non-CoT payloads (AVR /
 *       JSON / bytes without a recognisable {@code uid}) still land on
 *       the base key because there's no reliable per-entity key to
 *       derive.</li>
 * </ul>
 *
 * <p>The per-aircraft split for CoT is idiomatic Zenoh — one topic per
 * addressable thing — and matches the pattern established by the reference
 * MQTT/CoT bridges (adsbcot, Flinterpop/ADSBtoCOT). It costs nothing at the
 * publisher side (single {@code IndexOf}/regex per frame) and enables cheap
 * subscriber-side filtering by tail-key. But it's not always what an
 * operator wants — hence the mode toggle exposed on every Zenoh connector.
 *
 * <h2>Endpoint</h2>
 * <p>Passed straight through to
 * {@link PureJavaZenohPublisher.Builder#connectEndpoint(String)}. Any
 * scheme the pure-Java facade supports works — {@code tcp/host:port},
 * {@code tls/host:port}, {@code ws/host:port}, {@code wss/host:port}.
 * TLS material (client cert / key / CA) is out of scope for this first
 * cut; use plain TCP or unauthenticated WS to a local {@code zenohd} for
 * the first smoke test. If Marty needs mTLS to a hardened router, add a
 * {@code tls-} prefix on the connector target and thread through to
 * the facade's {@code rootCaCertPath()} / {@code clientCertPath()} /
 * {@code clientKeyPath()} setters — filed as a follow-up.
 *
 * <h2>Lifecycle</h2>
 * <p>Constructor blocks on the full Zenoh handshake (InitSyn/Ack,
 * OpenSyn/Ack) so a failed router connection surfaces immediately at
 * attach time (matches how {@link UdpForwarder} etc. surface bind
 * failures). Not lazy on purpose: the {@link com.adsb.ui.model.ConnectorAttacher}
 * failure policy is "throw at attach, leave the connector disabled" —
 * lazy connect would defer the failure to first frame arrival, which
 * happens on a background thread and is easy to miss.
 *
 * <h2>Thread safety</h2>
 * <p>{@link PureJavaZenohPublisher#publish(String, byte[])} is safe to
 * call from any thread (session I/O runs on its own thread). Multiple
 * receiver-side dispatch threads are not a concern today (there is only
 * one), but the sink is written to remain correct if one is added later.
 *
 * <h2>Error handling</h2>
 * <p>Publish failures propagate as {@link Exception} to the receiver's
 * per-sink dispatch, which logs a WARN and continues (same policy as
 * {@link UdpForwarder}). {@link #close()} is idempotent and best-effort:
 * a router that has already dropped the session shouldn't wedge shutdown.
 */
public final class ZenohForwarder implements FrameForwarder {

    /**
     * Matches the {@code uid="ICAO-XXXXXX"} attribute {@link com.adsb.cot.CoTBuilder}
     * emits on every event's root element. 6 hex digits, upper-case per the
     * builder's contract (see {@code CoTBuilder.uid(...)}). Case-insensitive
     * anyway so we don't break if someone re-cases the input downstream.
     * Static so compile happens once per JVM, not per frame.
     */
    private static final Pattern UID_ICAO_PATTERN =
            Pattern.compile("uid=\"ICAO-([0-9A-Fa-f]{6})\"");

    private final PureJavaZenohPublisher publisher;
    private final String keyPrefix;
    private final ZenohMode mode;
    private volatile boolean closed = false;

    /**
     * Open a session against {@code endpoint} and prepare to publish
     * under {@code keyPrefix}. Blocks until the router handshake completes.
     *
     * @param endpoint   Zenoh connect endpoint, e.g. {@code tcp/localhost:7447}.
     * @param keyPrefix  Base key expression, e.g. {@code adsb/cot}. Leading
     *                   and trailing slashes are trimmed to keep the emitted
     *                   keys well-formed regardless of operator input.
     * @throws IOException if the endpoint is malformed, the router is
     *                     unreachable, or the Zenoh handshake fails.
     */
    public ZenohForwarder(String endpoint, String keyPrefix, ZenohMode mode) throws IOException {
        if (endpoint == null || endpoint.isBlank())
            throw new IOException("Zenoh endpoint is required");
        if (keyPrefix == null || keyPrefix.isBlank())
            throw new IOException("Zenoh key prefix is required");
        this.keyPrefix = trimSlashes(keyPrefix);
        this.mode = (mode == null) ? ZenohMode.PER_AIRCRAFT : mode;
        // Bind the base key at build time; per-frame sub-keys are appended
        // via publish(subKey, bytes). The facade concatenates
        // effectiveKeyExpr + "/" + subKey internally.
        this.publisher = PureJavaZenohPublisher.builder()
                .connectEndpoint(endpoint)
                .keyExpr(this.keyPrefix)
                .build();
        this.publisher.start();
    }

    /**
     * Convenience overload defaulting to {@link ZenohMode#PER_AIRCRAFT} —
     * matches the pre-mode shipping behaviour (commit {@code 8e4aca2}).
     * Preserved so any downstream caller wired against the 2-arg ctor
     * before the mode field landed keeps compiling. Intentionally does
     * NOT auto-detect stream vs per-aircraft — the choice is an
     * operator decision, not a heuristic.
     */
    public ZenohForwarder(String endpoint, String keyPrefix) throws IOException {
        this(endpoint, keyPrefix, ZenohMode.PER_AIRCRAFT);
    }

    /**
     * Publish one frame. Sub-key selection follows the {@link ZenohMode}
     * chosen at construction:
     * <ul>
     *   <li>{@link ZenohMode#STREAM}: always publishes to the base
     *       {@code keyPrefix}, regardless of payload contents.</li>
     *   <li>{@link ZenohMode#PER_AIRCRAFT}: CoT XML with a
     *       {@code uid="ICAO-XXXXXX"} attribute publishes to
     *       {@code &lt;keyPrefix&gt;/&lt;ICAO&gt;}; anything else publishes
     *       to the base {@code keyPrefix}.</li>
     * </ul>
     *
     * <p>Payload bytes are published verbatim; no re-encoding. Zenoh
     * consumers see exactly the CoT XML / AVR line / JSON blob the
     * receiver produced.
     */
    @Override
    public void forward(byte[] frame) throws Exception {
        if (closed) throw new IOException("ZenohForwarder is closed");
        if (frame == null || frame.length == 0) return;
        String subKey = selectSubKey(frame, mode);
        publisher.publish(subKey, frame);
    }

    /**
     * Compute the Zenoh sub-key for one frame under the given mode.
     * Pulled out of {@link #forward(byte[])} so tests can pin the
     * decision table hermetically (a live Zenoh router isn't available
     * in the sandbox but the sub-key choice is pure data).
     *
     * <ul>
     *   <li>{@link ZenohMode#STREAM}: always {@code null} (publish to
     *       the base key).</li>
     *   <li>{@link ZenohMode#PER_AIRCRAFT} or {@code null}: delegate to
     *       {@link #extractIcaoHex} and let it decide (returns ICAO for
     *       CoT, {@code null} for anything else).</li>
     * </ul>
     *
     * <p>A {@code null} mode is treated as PER_AIRCRAFT to match the
     * ctor coercion contract; the ctor itself already normalises before
     * any {@link #forward} call, so production never actually exercises
     * the null branch, but the invariant is pinned in tests so nobody
     * accidentally makes this method NPE later.
     */
    static String selectSubKey(byte[] frame, ZenohMode mode) {
        if (mode == ZenohMode.STREAM) return null;
        return extractIcaoHex(frame);
    }

    /**
     * Extract the ICAO24 hex from a CoT XML payload's {@code uid} attribute,
     * or {@code null} if the payload doesn't look like CoT.
     *
     * <p>Bounded search: we only scan the first 256 bytes to keep this fast
     * even for chatty payloads. {@link com.adsb.cot.CoTBuilder} emits the
     * {@code uid} inside the {@code <event ...>} root element, which is
     * always in the first ~200 bytes of every message. Package-private for
     * tests.
     */
    static String extractIcaoHex(byte[] frame) {
        int scanLen = Math.min(frame.length, 256);
        String head = new String(frame, 0, scanLen, StandardCharsets.UTF_8);
        Matcher m = UID_ICAO_PATTERN.matcher(head);
        if (!m.find()) return null;
        return m.group(1).toUpperCase();
    }

    /**
     * Idempotent close. Best-effort — a router that already dropped the
     * session shouldn't wedge JVM shutdown. Logs any close-time exception
     * to stderr in the same style as {@link SinkRegistry#remove(String)}.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            publisher.close();
        } catch (RuntimeException e) {
            System.err.println("[zenoh-forwarder] close error: " + e.getMessage());
        }
    }

    /** @return the trimmed key prefix this sink publishes under. Test hook. */
    String keyPrefix() {
        return keyPrefix;
    }

    /** @return the key-layout mode this sink was constructed with. Test hook. */
    ZenohMode mode() {
        return mode;
    }

    /**
     * Strip leading and trailing slashes to keep the emitted key
     * expression well-formed regardless of whether the operator typed
     * {@code adsb/cot}, {@code /adsb/cot}, {@code adsb/cot/}, or
     * {@code /adsb/cot/}. Internal slashes are preserved.
     */
    private static String trimSlashes(String s) {
        int start = 0, end = s.length();
        while (start < end && s.charAt(start) == '/') start++;
        while (end > start && s.charAt(end - 1) == '/') end--;
        return s.substring(start, end);
    }
}
