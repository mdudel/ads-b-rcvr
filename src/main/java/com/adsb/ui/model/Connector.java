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
 *   <li>{@link Type#ZENOH}: {@code target} = {@code endpoint;key-prefix}
 *       (present in the type dropdown but disabled until issue #4 lands)</li>
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

    /** Types the UI offers. Zenoh is present but the UI disables it until #4 lands. */
    public enum Type {
        UDP_UNICAST("UDP unicast"),
        UDP_MULTICAST("UDP multicast"),
        TCP_SERVER("TCP server"),
        ZENOH("Zenoh (coming soon)");

        private final String label;
        Type(String label) { this.label = label; }
        public String label() { return label; }

        /** @return true if this type is implemented today (i.e. can be attached). */
        public boolean isImplemented() {
            return this != ZENOH;
        }
    }
}
