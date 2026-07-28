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
 *       under. See {@link #zenohMode} for the per-frame key layout.</li>
 * </ul>
 *
 * <p>{@link #zenohMode} is meaningful only for {@link Type#ZENOH}
 * connectors but is persisted on every record for schema simplicity.
 * Other types read/write the field but ignore it at attach time.
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

        /**
         * Zenoh key-layout mode. Meaningful only for {@link Type#ZENOH};
         * ignored (but persisted) for other types. Never null — the
         * canonical ctor coerces null to {@link ZenohMode#PER_AIRCRAFT}
         * to preserve the pre-{@code ZenohMode} shipping default.
         */
        ZenohMode zenohMode,

        /** {@code true} = attach at startup / on save; {@code false} = keep in the list but don't attach. */
        boolean enabled
) {
    public Connector {
        Objects.requireNonNull(id,      "id");
        Objects.requireNonNull(name,    "name");
        Objects.requireNonNull(type,    "type");
        Objects.requireNonNull(target,  "target");
        Objects.requireNonNull(payload, "payload");
        if (zenohMode == null) zenohMode = ZenohMode.PER_AIRCRAFT;
    }

    /**
     * Convenience factory for a fresh connector; uses
     * {@link ZenohMode#PER_AIRCRAFT} as the Zenoh mode default so
     * legacy call sites don't need to pass one explicitly.
     */
    public static Connector newInstance(String name, Type type, String target,
                                        PayloadFormat payload, boolean enabled) {
        return newInstance(name, type, target, payload, ZenohMode.PER_AIRCRAFT, enabled);
    }

    /** Full-arg factory: fresh UUID + operator-chosen Zenoh mode. */
    public static Connector newInstance(String name, Type type, String target,
                                        PayloadFormat payload, ZenohMode zenohMode,
                                        boolean enabled) {
        return new Connector(UUID.randomUUID().toString(),
                name, type, target, payload, zenohMode, enabled);
    }

    public Connector withEnabled(boolean e) {
        return new Connector(id, name, type, target, payload, zenohMode, e);
    }

    /**
     * @return a new record with {@link #zenohMode} replaced; leaves
     *         every other field untouched (id stable, so the sink
     *         registry keeps its handle).
     */
    public Connector withZenohMode(ZenohMode m) {
        return new Connector(id, name, type, target, payload, m, enabled);
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
