package com.adsb.ui.model;

import com.adsb.core.PayloadFormat;

import java.util.Objects;
import java.util.UUID;

/**
 * A user-defined output destination for the receiver: one row in the
 * ConnectorsPanel, one live {@link com.adsb.core.FrameForwarder} in the
 * running process (when enabled). Immutable value-record; edit by
 * building a new one.
 *
 * <p>Serialisation: {@link ConnectorStore} reads/writes these to
 * {@code ~/.adsb-rcvr/adsb-rcvr.properties} using a
 * {@code connector.<id>.<field>=<value>} shape so multiple connectors
 * can live side-by-side in one flat properties file.
 *
 * <p>Field semantics per {@link Type}:
 * <ul>
 *   <li>{@link Type#UDP_UNICAST}: {@code target} = {@code host:port}</li>
 *   <li>{@link Type#UDP_MULTICAST}: {@code target} = {@code group:port}</li>
 *   <li>{@link Type#TCP_SERVER}: {@code target} = {@code port} (server side)</li>
 *   <li>{@link Type#ZENOH}: {@code target} = {@code endpoint;key-prefix},
 *       e.g. {@code tcp/localhost:7447;adsb/cot}. Endpoint is any scheme
 *       the pure-Java Zenoh facade accepts (tcp / tls / ws / wss);
 *       key-prefix is the base Zenoh key expression the sink publishes
 *       under. Per-frame keys are derived per-payload by
 *       {@link com.adsb.transport.ZenohForwarder} — CoT XML gets a
 *       per-aircraft sub-key of ICAO24, AVR/JSON go to the base key.</li>
 * </ul>
 */
public record Connector(
        /** Stable UUID. Never mutated across edits so persistence keys stay valid. */
        String id,

        /** Human label shown in the UI list, e.g. "Ops COP UDP". */
        String name,

        /** Wire type. See per-type target semantics in the class javadoc. */
        Type type,

        /** Target string; interpretation depends on {@link #type}. */
        String target,

        /** Payload format. Same enum the CLI --payload flag uses. */
        PayloadFormat payload,

        /** {@code true} = attach at startup / on save; {@code false} = keep in the list but don't attach. */
        boolean enabled
) {
    public Connector {
        Objects.requireNonNull(id,      "id");
        Objects.requireNonNull(name,    "name");
        Objects.requireNonNull(type,    "type");
        Objects.requireNonNull(target,  "target");
        Objects.requireNonNull(payload, "payload");
    }

    /** @return a fresh connector with a new UUID id. */
    public static Connector newInstance(String name, Type type, String target,
                                        PayloadFormat payload, boolean enabled) {
        return new Connector(UUID.randomUUID().toString(),
                name, type, target, payload, enabled);
    }

    public Connector withEnabled(boolean e) {
        return new Connector(id, name, type, target, payload, e);
    }

    /** Types the UI offers. All types are wire-implemented as of the Zenoh landing under #4. */
    public enum Type {
        UDP_UNICAST("UDP unicast"),
        UDP_MULTICAST("UDP multicast"),
        TCP_SERVER("TCP server"),
        ZENOH("Zenoh");

        private final String label;
        Type(String label) { this.label = label; }
        public String label() { return label; }

        /**
         * @return true if this type is implemented today (i.e. can be attached).
         *     Kept as a per-type predicate rather than an assertion at attach
         *     time so a future scaffolded-but-unwired type can rejoin the
         *     dropdown grayed-out without another round of surgery here.
         */
        public boolean isImplemented() {
            return true;
        }
    }
}
