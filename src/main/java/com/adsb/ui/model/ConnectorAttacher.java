package com.adsb.ui.model;

import com.adsb.core.FrameForwarder;
import com.adsb.core.PayloadFormat;
import com.adsb.core.SinkRegistry;
import com.adsb.cot.CoTBuilder;
import com.adsb.model.AircraftStateStore;
import com.adsb.transport.MulticastForwarder;
import com.adsb.transport.TcpForwarder;
import com.adsb.transport.UdpForwarder;
import com.adsb.transport.ZenohForwarder;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Turns {@link Connector} configuration records into live
 * {@link SinkRegistry.AttachedSink} entries (and back). One shared
 * instance sits between the {@link ConnectorStore} (config) and the
 * running {@link com.adsb.core.AdsbReceiver} (wire).
 *
 * <p>Handles both flavours:
 * <ul>
 *   <li><b>Per-frame</b> ({@link PayloadFormat#AVR} / {@link PayloadFormat#JSON}):
 *       creates a {@link UdpForwarder}/{@link MulticastForwarder}/
 *       {@link TcpForwarder} and registers it in the {@link SinkRegistry}.
 *       The receiver dispatches per frame.</li>
 *   <li><b>Snapshot-driven</b> ({@link PayloadFormat#COT}): creates the
 *       same underlying transport, but attaches an {@link AircraftStateStore}
 *       listener that runs {@link CoTBuilder#build} on every snapshot
 *       update and forwards the resulting XML bytes. The registry entry
 *       is still created so removal by connector id detaches the
 *       listener too.</li>
 * </ul>
 *
 * <p>Failure policy: if a target string is malformed or the transport
 * can't bind, the attach throws and the caller (UI) surfaces it as a
 * dialog. The connector stays in the store with {@code enabled=false}
 * so the operator can fix the target and try again.
 */
public final class ConnectorAttacher {

    private final SinkRegistry   sinks;
    private final AircraftStateStore stateStore;
    private final AtomicReference<CoTBuilder> cotBuilder;

    /** CoT listeners are keyed by connector id so detach() can unregister exactly one. */
    private final Map<String, Consumer<com.adsb.model.AdsbTrack>> cotListeners = new ConcurrentHashMap<>();

    /**
     * @param cotBuilder atomic ref — so the UI can swap in a fresh builder
     *                   (e.g. when the settings panel changes affiliation)
     *                   without touching per-connector listeners. May be null
     *                   when no CoT sinks are envisaged.
     */
    public ConnectorAttacher(SinkRegistry sinks,
                             AircraftStateStore stateStore,
                             AtomicReference<CoTBuilder> cotBuilder) {
        this.sinks = sinks;
        this.stateStore = stateStore;
        this.cotBuilder = cotBuilder;
    }

    /**
     * Create the underlying transport for {@code c}, register it in the
     * {@link SinkRegistry}, and (for CoT) attach the state-store listener.
     *
     * @throws Exception any transport-construction failure. Caller
     *                   should show the message to the operator and
     *                   leave the connector disabled.
     */
    public void attach(Connector c) throws Exception {
        if (!c.enabled()) return;
        if (!c.type().isImplemented()) {
            throw new UnsupportedOperationException(
                    c.type().label() + " is not yet implemented (see issue #4)");
        }

        FrameForwarder fwd = openTransport(c);
        sinks.add(c.id(), c.payload(), fwd);

        if (c.payload() == PayloadFormat.COT) {
            if (stateStore == null || cotBuilder == null) {
                sinks.remove(c.id());
                throw new IllegalStateException(
                        "CoT sink attached without a state store / CoT builder");
            }
            Consumer<com.adsb.model.AdsbTrack> listener = snap -> {
                CoTBuilder b = cotBuilder.get();
                if (b == null) return;
                String xml = b.build(snap);
                if (xml == null) return;
                byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
                try { fwd.forward(bytes); }
                catch (Exception e) {
                    System.err.println("[WARN] CoT forward on " + c.id()
                            + ": " + e.getMessage());
                }
            };
            cotListeners.put(c.id(), listener);
            stateStore.addListener(listener);
        }
    }

    /**
     * Reverse of {@link #attach}: unregisters the state-store listener
     * (if any) and removes the sink from the registry (which closes
     * the underlying transport).
     */
    public void detach(String connectorId) {
        Consumer<com.adsb.model.AdsbTrack> l = cotListeners.remove(connectorId);
        if (l != null && stateStore != null) stateStore.removeListener(l);
        sinks.remove(connectorId);
    }

    /** Detach then re-attach with the new config (used on connector edit). */
    public void reattach(Connector c) throws Exception {
        detach(c.id());
        attach(c);
    }

    /** Open the transport implied by {@code c.type()} and {@code c.target()}. */
    private static FrameForwarder openTransport(Connector c) throws Exception {
        String target = c.target();
        switch (c.type()) {
            case UDP_UNICAST: {
                String[] hp = splitHostPort(target, "UDP unicast target");
                return new UdpForwarder(hp[0], Integer.parseInt(hp[1]));
            }
            case UDP_MULTICAST: {
                String[] gp = splitHostPort(target, "UDP multicast target");
                return new MulticastForwarder(gp[0], Integer.parseInt(gp[1]));
            }
            case TCP_SERVER: {
                int port = Integer.parseInt(target.trim());
                TcpForwarder t = new TcpForwarder(port);
                t.start();
                return t;
            }
            case ZENOH: {
                // Post-2026-07-29-refactor: every Zenoh field lives on
                // the Connector record as its own typed member
                // (transport, endpoint, org, keyExpr, TLS material).
                // ZenohForwarder consumes the whole record; no target-
                // string parsing here. The legacy 'endpoint;key-prefix'
                // target string is dead -- ConnectorStore drops rows
                // using that shape at load time (see Marty 2026-07-29
                // 14:01 UTC nuke-and-fresh-start policy).
                return new ZenohForwarder(c);
            }
            default:
                throw new IllegalArgumentException("unknown connector type: " + c.type());
        }
    }

    private static String[] splitHostPort(String s, String label) {
        int i = s.lastIndexOf(':');
        if (i <= 0 || i == s.length() - 1)
            throw new IllegalArgumentException(label + " must be host:port, got '" + s + "'");
        return new String[]{ s.substring(0, i).trim(), s.substring(i + 1).trim() };
    }

    /**
     * Split an {@code endpoint;key} connector target into its two halves.
     * Retained after the 2026-07-29 refactor for backward compatibility
     * with any external test still calling this helper; the internal
     * ZENOH attach path no longer uses it. Package-private for tests.
     *
     * @deprecated the new-schema Zenoh fields on {@link Connector} make
     *             this parser unnecessary; kept only until existing
     *             tests that call it are retired.
     */
    @Deprecated
    static String[] splitEndpointAndKey(String s, String label) {
        if (s == null) throw new IllegalArgumentException(label + " is null");
        int i = s.indexOf(';');
        if (i <= 0 || i == s.length() - 1)
            throw new IllegalArgumentException(
                    label + " must be 'endpoint;key-prefix', got '" + s + "'");
        String endpoint = s.substring(0, i).trim();
        String key      = s.substring(i + 1).trim();
        if (endpoint.isEmpty() || key.isEmpty())
            throw new IllegalArgumentException(
                    label + " endpoint and key-prefix must both be non-empty, got '" + s + "'");
        return new String[]{ endpoint, key };
    }
}
