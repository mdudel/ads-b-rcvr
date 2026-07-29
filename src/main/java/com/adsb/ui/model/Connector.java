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
 *   <li>{@link Type#ZENOH}: {@code target} is unused (kept blank);
 *       the connector reads {@link #zenohTransport} / {@link #zenohEndpoint} /
 *       {@link #zenohOrg} / {@link #zenohKeyExpr} / TLS material instead.
 *       This split was landed 2026-07-29 (commit after 0d4cdd6) to let
 *       the UI expose a proper form with a dropdown for the transport,
 *       Browse buttons for the cert/key/CA files, and separate root /
 *       topic fields (previously all shoved into one semicolon-joined
 *       target string).</li>
 * </ul>
 *
 * <p><b>Zenoh field semantics</b> (meaningful only when
 * {@link #type} == {@link Type#ZENOH}, all others may be null on other types):
 * <ul>
 *   <li>{@link #zenohTransport}: wire transport (TCP / TLS / WS / WSS).</li>
 *   <li>{@link #zenohEndpoint}: {@code host:port} without the URI scheme
 *       (the scheme is prepended from {@code zenohTransport} at connect
 *       time). Example: {@code 100.64.165.203:7447}.</li>
 *   <li>{@link #zenohOrg}: root / vendor / tenant prefix. Prepended to
 *       {@code zenohKeyExpr} before publish via
 *       {@link io.mdudel.zenoh.purejava.wire.KeyExpr#resolveKey(String, String)}
 *       with slash normalisation. May be null or blank -- then the
 *       topic is published bare.</li>
 *   <li>{@link #zenohKeyExpr}: the topic to publish under, e.g.
 *       {@code tracks/adsb}. Combined with {@code zenohOrg} yields
 *       {@code <org>/<topic>} (or just {@code <topic>} if org is blank).</li>
 *   <li>{@link #zenohClientCertPath}, {@link #zenohClientKeyPath},
 *       {@link #zenohRootCaPath}: filesystem paths to PEM material.
 *       Required only when {@code zenohTransport.isTls()} is true.</li>
 *   <li>{@link #zenohVerifyHostname}: TLS hostname verification.
 *       Default false because IP endpoints (Tailscale/CGNAT) usually
 *       don't have the IP as a cert SAN. Set true when the endpoint is
 *       a real DNS name.</li>
 * </ul>
 *
 * <p>{@link #zenohMode} controls the per-frame sub-key layout
 * (STREAM: everything to one topic; PER_AIRCRAFT: append ICAO under
 * the topic). Meaningful only for {@link Type#ZENOH} but persisted on
 * every record for schema simplicity.
 */
public record Connector(
        /** Stable UUID. Never mutated across edits so persistence keys stay valid. */
        String id,

        /** Human label shown in the UI list, e.g. "Ops COP UDP". */
        String name,

        /** Wire type. See per-type target semantics in the class javadoc. */
        Type type,

        /** Target string; interpretation depends on {@link #type}. Unused for ZENOH. */
        String target,

        /** Payload format. Same enum the CLI --payload flag uses. */
        PayloadFormat payload,

        /**
         * Zenoh key-layout mode. Meaningful only for {@link Type#ZENOH};
         * ignored (but persisted) for other types. Never null -- the
         * canonical ctor coerces null to {@link ZenohMode#PER_AIRCRAFT}
         * to preserve the pre-{@code ZenohMode} shipping default.
         */
        ZenohMode zenohMode,

        /** {@code true} = attach at startup / on save; {@code false} = keep in the list but don't attach. */
        boolean enabled,

        /**
         * Zenoh wire transport. Nullable on non-Zenoh types; the
         * canonical ctor coerces null to {@link ZenohTransport#TCP}
         * on Zenoh types so the persistence layer never has to make
         * one up.
         */
        ZenohTransport zenohTransport,

        /** Zenoh endpoint {@code host:port} without URI scheme. Nullable on non-Zenoh types. */
        String zenohEndpoint,

        /** Zenoh root / vendor topic prefix. Nullable or blank means "no prefix". */
        String zenohOrg,

        /** Zenoh topic (key expression). Required on Zenoh types; nullable on others. */
        String zenohKeyExpr,

        /** TLS client-cert PEM path. Required when {@link #zenohTransport} is TLS/WSS. */
        String zenohClientCertPath,

        /** TLS client-key PEM path. Required when {@link #zenohTransport} is TLS/WSS. */
        String zenohClientKeyPath,

        /** TLS truststore (CA root) PEM path. Required when {@link #zenohTransport} is TLS/WSS. */
        String zenohRootCaPath,

        /** TLS hostname-verification. False by default (IP endpoints); set true for DNS endpoints. */
        boolean zenohVerifyHostname
) {
    public Connector {
        Objects.requireNonNull(id,      "id");
        Objects.requireNonNull(name,    "name");
        Objects.requireNonNull(type,    "type");
        Objects.requireNonNull(target,  "target");
        Objects.requireNonNull(payload, "payload");
        if (zenohMode == null) zenohMode = ZenohMode.PER_AIRCRAFT;
        if (type == Type.ZENOH && zenohTransport == null) zenohTransport = ZenohTransport.TCP;
    }

    /**
     * Legacy 7-arg convenience constructor for pre-2026-07-29-refactor
     * call sites (tests + the ConnectorsPanel shared-fields edit path).
     * Zenoh-specific fields default to null / TCP / false. Use the
     * canonical 15-arg record ctor or {@link #newZenoh} when populating
     * the Zenoh rich-form fields.
     */
    public Connector(String id, String name, Type type, String target,
                     PayloadFormat payload, ZenohMode zenohMode, boolean enabled) {
        this(id, name, type, target, payload, zenohMode, enabled,
                null, null, null, null, null, null, null, false);
    }

    /**
     * Convenience factory for a fresh non-Zenoh connector; uses
     * {@link ZenohMode#PER_AIRCRAFT} as the (unused) Zenoh mode default
     * so legacy call sites don't need to pass one explicitly. Zenoh
     * fields default to null and must not be used for non-Zenoh types.
     */
    public static Connector newInstance(String name, Type type, String target,
                                        PayloadFormat payload, boolean enabled) {
        return new Connector(UUID.randomUUID().toString(),
                name, type, target, payload, ZenohMode.PER_AIRCRAFT, enabled,
                null, null, null, null, null, null, null, false);
    }

    /** Full-arg factory: fresh UUID + operator-chosen Zenoh mode (non-Zenoh path). */
    public static Connector newInstance(String name, Type type, String target,
                                        PayloadFormat payload, ZenohMode zenohMode,
                                        boolean enabled) {
        return new Connector(UUID.randomUUID().toString(),
                name, type, target, payload, zenohMode, enabled,
                null, null, null, null, null, null, null, false);
    }

    /** Zenoh-specific factory. Endpoint should be {@code host:port} (no scheme). */
    public static Connector newZenoh(String name,
                                     ZenohTransport transport,
                                     String endpoint,
                                     String org,
                                     String keyExpr,
                                     PayloadFormat payload,
                                     ZenohMode zenohMode,
                                     String clientCertPath,
                                     String clientKeyPath,
                                     String rootCaPath,
                                     boolean verifyHostname,
                                     boolean enabled) {
        return new Connector(UUID.randomUUID().toString(),
                name, Type.ZENOH, "", payload, zenohMode, enabled,
                transport == null ? ZenohTransport.TCP : transport,
                endpoint, org, keyExpr,
                clientCertPath, clientKeyPath, rootCaPath, verifyHostname);
    }

    public Connector withEnabled(boolean e) {
        return new Connector(id, name, type, target, payload, zenohMode, e,
                zenohTransport, zenohEndpoint, zenohOrg, zenohKeyExpr,
                zenohClientCertPath, zenohClientKeyPath, zenohRootCaPath, zenohVerifyHostname);
    }

    /**
     * @return a new record with {@link #zenohMode} replaced; leaves
     *         every other field untouched (id stable, so the sink
     *         registry keeps its handle).
     */
    public Connector withZenohMode(ZenohMode m) {
        return new Connector(id, name, type, target, payload, m, enabled,
                zenohTransport, zenohEndpoint, zenohOrg, zenohKeyExpr,
                zenohClientCertPath, zenohClientKeyPath, zenohRootCaPath, zenohVerifyHostname);
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
