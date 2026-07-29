package com.adsb.ui.model;

/**
 * Zenoh wire transport selectable on a {@link Connector.Type#ZENOH}
 * connector. Owns the URI scheme that is prepended to
 * {@code host:port} to form the {@code connectEndpoint} argument the
 * pure-Java Zenoh facade expects.
 *
 * <p>Split from the endpoint field so the operator picks it from a
 * dropdown instead of typing {@code tls/...} into a text box (harder
 * to get wrong, and lets the UI grey out the TLS material rows when
 * a non-TLS transport is chosen).
 *
 * <p>Ordering matches what the pure-Java facade accepts:
 * {@code tcp}, {@code tls}, {@code ws}, {@code wss}. See
 * {@link io.mdudel.zenoh.purejava.PureJavaZenohPublisher.Builder#connectEndpoint(String)}.
 */
public enum ZenohTransport {
    TCP("tcp", false, "Plain TCP (no encryption)"),
    TLS("tls", true,  "TLS with mutual authentication"),
    WS ("ws",  false, "WebSocket (no encryption)"),
    WSS("wss", true,  "Secure WebSocket (mutual TLS)");

    private final String scheme;
    private final boolean tls;
    private final String description;

    ZenohTransport(String scheme, boolean tls, String description) {
        this.scheme = scheme;
        this.tls = tls;
        this.description = description;
    }

    /** @return URI scheme, e.g. {@code "tcp"}, {@code "tls"}. */
    public String scheme() { return scheme; }

    /** @return true if the transport uses TLS (needs cert/key/CA material). */
    public boolean isTls() { return tls; }

    /** @return short human description shown in tooltips. */
    public String description() { return description; }

    /**
     * Build a Zenoh {@code connectEndpoint} string by prepending the
     * scheme + "/" to {@code hostPort}. Null-safe on hostPort; returns
     * null if hostPort is null so the caller sees the misconfiguration
     * cleanly instead of a bogus half-built URI.
     */
    public String buildEndpoint(String hostPort) {
        if (hostPort == null) return null;
        return scheme + "/" + hostPort.trim();
    }

    /**
     * Parse a scheme string (case-insensitive), returning null if it
     * doesn't match any known transport. Used by {@link ConnectorStore}
     * on load; malformed values are logged and skipped.
     */
    public static ZenohTransport fromScheme(String s) {
        if (s == null) return null;
        String norm = s.trim().toLowerCase();
        for (ZenohTransport t : values()) {
            if (t.scheme.equals(norm)) return t;
        }
        return null;
    }

    /**
     * Parse a name string (case-insensitive), returning {@code fallback}
     * if it doesn't match. Preferred over {@link #valueOf} at the
     * properties-file boundary because it's null-safe and doesn't
     * throw on bogus input.
     */
    public static ZenohTransport parseOrDefault(String s, ZenohTransport fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return ZenohTransport.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return fallback; }
    }
}
