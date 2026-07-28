package com.adsb.ui.model;

/**
 * How a {@link Connector.Type#ZENOH} sink lays out its emitted Zenoh
 * keys. Ignored (but persisted) for every other connector type; this
 * enum only affects wire behaviour on Zenoh sinks.
 *
 * <p><b>Why this exists:</b> operators need to choose between a single
 * firehose topic (easy to subscribe to, easy to log, "everything on
 * one wire") and per-entity topics (idiomatic Zenoh, cheap
 * subscriber-side filtering, natural mapping to per-aircraft consumers
 * downstream). The right choice depends on the downstream
 * subscriber's design, not something we should decide for them at
 * build time.
 *
 * <p><b>Backward compat:</b> the enum was added on 2026-07-28 after
 * the initial Zenoh sink (commit {@code 8e4aca2}) hardcoded
 * per-aircraft-for-CoT behaviour. Persisted connector files written
 * before this exists have no {@code mode} property; the loader treats
 * missing as {@link #PER_AIRCRAFT} so already-configured sinks keep
 * their prior wire shape without operator action.
 */
public enum ZenohMode {

    /**
     * Every frame is published to the base key prefix as-is.
     *
     * <p>Effective key: {@code &lt;keyPrefix&gt;}
     *
     * <p>All payloads (CoT, JSON, AVR) land on the same one topic.
     * Best for downstream consumers that just want the whole feed as
     * one stream and will filter / dispatch themselves.
     */
    STREAM("Stream (one topic)"),

    /**
     * CoT frames are fanned out to a per-aircraft sub-key derived
     * from the {@code uid="ICAO-XXXXXX"} attribute. Non-CoT payloads
     * (JSON / AVR / anything without a recognisable aircraft uid)
     * still land on the base key because there's no reliable
     * per-entity key to derive.
     *
     * <p>Effective key for CoT with matching uid:
     * {@code &lt;keyPrefix&gt;/&lt;ICAO24&gt;}
     *
     * <p>Effective key for everything else: {@code &lt;keyPrefix&gt;}
     *
     * <p>Best for Zenoh-idiomatic consumers that want to subscribe
     * per aircraft ({@code adsb/cot/4CA1FA}) or per-fleet with a
     * wildcard ({@code adsb/cot/**}).
     */
    PER_AIRCRAFT("Per aircraft (fan out)");

    private final String label;

    ZenohMode(String label) { this.label = label; }

    /** Human-readable label for the UI dropdown. */
    public String label() { return label; }

    /**
     * @param s persisted enum name, possibly null or unrecognised
     *          (missing property from a pre-{@code ZenohMode} save)
     * @return the parsed enum value, or {@link #PER_AIRCRAFT} when
     *         {@code s} is null / blank / unrecognised. Matches the
     *         pre-existing hardcoded behaviour before the mode
     *         concept was added; documented in the class javadoc.
     */
    public static ZenohMode parseOrDefault(String s) {
        if (s == null || s.isBlank()) return PER_AIRCRAFT;
        try { return valueOf(s.trim()); }
        catch (IllegalArgumentException e) { return PER_AIRCRAFT; }
    }
}
