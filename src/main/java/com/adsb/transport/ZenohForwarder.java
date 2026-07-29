package com.adsb.transport;

import com.adsb.core.FrameForwarder;
import com.adsb.ui.model.Connector;
import com.adsb.ui.model.ZenohMode;
import com.adsb.ui.model.ZenohTransport;

import io.mdudel.zenoh.purejava.PureJavaZenohPublisher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Publishes each forwarded frame to a Zenoh router as a PUSH message,
 * on a key derived from the payload contents.
 *
 * <h2>Configuration (post-2026-07-29-refactor)</h2>
 *
 * <p>Constructed from a {@link Connector} record: every Zenoh field
 * (transport, endpoint, org, keyExpr, TLS material, verify-host,
 * key-layout mode) lives on the Connector so persistence and UI
 * editing are trivially first-class. The forwarder just reads those
 * fields at attach time and wires them to the pure-Java facade's
 * Builder.
 *
 * <h2>Key expression layout</h2>
 *
 * <p>Controlled by {@link Connector#zenohMode()}:
 *
 * <ul>
 *   <li>{@link ZenohMode#STREAM}: every frame publishes to the base
 *       {@code keyExpr} (prepended by {@code org}, if any). All payloads
 *       (CoT, JSON, AVR) land on one topic. Best for downstream consumers
 *       that want the whole feed as a single stream and will dispatch /
 *       filter themselves.</li>
 *   <li>{@link ZenohMode#PER_AIRCRAFT}: CoT XML with a
 *       {@code uid="ICAO-XXXXXX"} attribute publishes to
 *       {@code &lt;org&gt;/&lt;keyExpr&gt;/&lt;ICAO&gt;} so Zenoh
 *       subscribers can select a single aircraft with e.g.
 *       {@code goatnet/tracks/adsb/4CA1FA} or the whole fleet with
 *       {@code goatnet/tracks/adsb/**}. Non-CoT payloads (AVR / JSON /
 *       bytes without a recognisable {@code uid}) still land on the
 *       base key.</li>
 * </ul>
 *
 * <p>The {@code org} prefix is applied inside
 * {@link PureJavaZenohPublisher} via
 * {@link io.mdudel.zenoh.purejava.wire.KeyExpr#resolveKey(String, String)}
 * with slash normalisation -- callers do not have to pre-join it into
 * the keyExpr. Blank/null org means no prefix.
 *
 * <h2>Transport</h2>
 * <p>{@link Connector#zenohTransport()} owns the URI scheme (tcp / tls /
 * ws / wss) which is prepended to {@link Connector#zenohEndpoint()} at
 * connect time. TLS material ({@code zenohClientCertPath} /
 * {@code zenohClientKeyPath} / {@code zenohRootCaPath}) is passed to the
 * Builder only when the transport is TLS-flavoured -- the pure-Java
 * facade ignores the setters for TCP/WS.
 *
 * <h2>Lifecycle</h2>
 * <p>Constructor blocks on the full Zenoh handshake (InitSyn/Ack,
 * OpenSyn/Ack) so a failed router connection surfaces immediately at
 * attach time (matches how {@link UdpForwarder} etc. surface bind
 * failures). Not lazy on purpose: the
 * {@link com.adsb.ui.model.ConnectorAttacher} failure policy is
 * "throw at attach, leave the connector disabled" -- lazy connect
 * would defer the failure to first frame arrival, which happens on a
 * background thread and is easy to miss.
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
    private final String keyExpr;
    private final ZenohMode mode;
    private volatile boolean closed = false;

    /**
     * Post-refactor primary constructor: read every Zenoh field off the
     * {@link Connector} and wire it to the pure-Java facade Builder.
     *
     * @throws IOException if required fields are missing, the router is
     *                     unreachable, or the Zenoh handshake fails.
     */
    public ZenohForwarder(Connector c) throws IOException {
        if (c == null) throw new IOException("Connector is required");
        if (c.type() != Connector.Type.ZENOH)
            throw new IOException("ZenohForwarder needs a ZENOH connector, got " + c.type());

        ZenohTransport transport = c.zenohTransport();
        if (transport == null) transport = ZenohTransport.TCP;

        String endpoint = c.zenohEndpoint();
        if (endpoint == null || endpoint.isBlank())
            throw new IOException("Zenoh endpoint (host:port) is required");

        String keyExpr = c.zenohKeyExpr();
        if (keyExpr == null || keyExpr.isBlank())
            throw new IOException("Zenoh topic (keyExpr) is required");

        if (transport.isTls()) {
            requireNonBlank(c.zenohClientCertPath(), "Zenoh client cert path");
            requireNonBlank(c.zenohClientKeyPath(),  "Zenoh client key path");
            requireNonBlank(c.zenohRootCaPath(),     "Zenoh CA / truststore path");
        }

        this.keyExpr = trimSlashes(keyExpr);
        this.mode = (c.zenohMode() == null) ? ZenohMode.PER_AIRCRAFT : c.zenohMode();

        PureJavaZenohPublisher.Builder b = PureJavaZenohPublisher.builder()
                .connectEndpoint(transport.buildEndpoint(endpoint))
                .keyExpr(this.keyExpr)
                .org(c.zenohOrg())                  // null/blank -> no prefix (see KeyExpr.resolveKey)
                .verifyHostname(c.zenohVerifyHostname());
        if (transport.isTls()) {
            b.rootCaCertPath(c.zenohRootCaPath())
             .clientCertPath(c.zenohClientCertPath())
             .clientKeyPath (c.zenohClientKeyPath());
        }
        this.publisher = b.build();
        this.publisher.start();
    }

    /**
     * Legacy 3-arg ctor kept for external callers. Constructs a synthetic
     * ZENOH {@link Connector} from the (endpoint;keyPrefix, mode) triple
     * so the new primary ctor does all the actual work. Deprecated
     * because the rich-form Connector fields are strictly more expressive
     * (org prefix, TLS material, transport dropdown) and this shape can't
     * represent them.
     *
     * <p>Endpoint is passed through verbatim; it must include the URI
     * scheme (e.g. {@code tcp/localhost:7447}). Split on the first '/'
     * into scheme + host:port so we can populate the new
     * {@link ZenohTransport} field.
     *
     * @deprecated use {@link #ZenohForwarder(Connector)} instead
     */
    @Deprecated
    public ZenohForwarder(String endpoint, String keyPrefix, ZenohMode mode) throws IOException {
        this(fromLegacy(endpoint, keyPrefix, mode));
    }

    /**
     * Convenience overload defaulting to {@link ZenohMode#PER_AIRCRAFT}.
     *
     * @deprecated use {@link #ZenohForwarder(Connector)} instead
     */
    @Deprecated
    public ZenohForwarder(String endpoint, String keyPrefix) throws IOException {
        this(endpoint, keyPrefix, ZenohMode.PER_AIRCRAFT);
    }

    /**
     * Publish one frame. Sub-key selection follows the {@link ZenohMode}
     * chosen at construction:
     * <ul>
     *   <li>{@link ZenohMode#STREAM}: always publishes to the base key.</li>
     *   <li>{@link ZenohMode#PER_AIRCRAFT}: CoT XML with a
     *       {@code uid="ICAO-XXXXXX"} attribute publishes to
     *       {@code <base>/<ICAO>}; anything else publishes to the base.</li>
     * </ul>
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
     * Package-private for tests.
     */
    static String selectSubKey(byte[] frame, ZenohMode mode) {
        if (mode == ZenohMode.STREAM) return null;
        return extractIcaoHex(frame);
    }

    /**
     * Extract the ICAO24 hex from a CoT XML payload's {@code uid} attribute,
     * or {@code null} if the payload doesn't look like CoT. Package-private
     * for tests.
     */
    static String extractIcaoHex(byte[] frame) {
        int scanLen = Math.min(frame.length, 256);
        String head = new String(frame, 0, scanLen, StandardCharsets.UTF_8);
        Matcher m = UID_ICAO_PATTERN.matcher(head);
        if (!m.find()) return null;
        return m.group(1).toUpperCase();
    }

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

    /** @return the trimmed key expression this sink publishes under. Test hook. */
    String keyExpr() {
        return keyExpr;
    }

    /**
     * Retained pre-refactor accessor name so any existing tests / debug
     * code calling {@code keyPrefix()} keep compiling. New code should
     * prefer {@link #keyExpr()}.
     */
    String keyPrefix() {
        return keyExpr;
    }

    /** @return the key-layout mode this sink was constructed with. Test hook. */
    ZenohMode mode() {
        return mode;
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private static void requireNonBlank(String v, String label) throws IOException {
        if (v == null || v.isBlank()) throw new IOException(label + " is required");
    }

    /**
     * Build a synthetic ZENOH {@link Connector} from the legacy
     * {@code (schemedEndpoint, keyPrefix, mode)} triple so the deprecated
     * ctor can delegate. Splits {@code tcp/localhost:7447} into
     * {@code (ZenohTransport.TCP, "localhost:7447")}. Unknown schemes
     * fall back to TCP.
     */
    private static Connector fromLegacy(String schemedEndpoint, String keyPrefix, ZenohMode mode) {
        if (schemedEndpoint == null || schemedEndpoint.isBlank())
            throw new IllegalArgumentException("legacy ctor: endpoint required");
        if (keyPrefix == null || keyPrefix.isBlank())
            throw new IllegalArgumentException("legacy ctor: keyPrefix required");

        int slash = schemedEndpoint.indexOf('/');
        ZenohTransport t;
        String hostPort;
        if (slash <= 0) {
            t = ZenohTransport.TCP;
            hostPort = schemedEndpoint.trim();
        } else {
            String scheme = schemedEndpoint.substring(0, slash).trim();
            t = ZenohTransport.fromScheme(scheme);
            if (t == null) t = ZenohTransport.TCP;
            hostPort = schemedEndpoint.substring(slash + 1).trim();
        }
        return Connector.newZenoh("legacy",
                t, hostPort, "", keyPrefix,
                com.adsb.core.PayloadFormat.COT,
                mode == null ? ZenohMode.PER_AIRCRAFT : mode,
                null, null, null, false, true);
    }

    private static String trimSlashes(String s) {
        int start = 0, end = s.length();
        while (start < end && s.charAt(start) == '/') start++;
        while (end > start && s.charAt(end - 1) == '/') end--;
        return s.substring(start, end);
    }
}
