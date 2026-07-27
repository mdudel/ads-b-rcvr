package com.adsb.core;

/**
 * On-the-wire payload the forwarders send downstream.
 * <p>
 * Selected globally via the {@code --payload} CLI flag; applies to every
 * enabled sink (UDP, multicast, TCP, verbose stdout) so a single receiver
 * process consistently produces one shape.
 *
 * <p>Rationale for a single global flag (vs per-sink flags like
 * {@code --cot-udp}): matches the cotproto {@code payload=PROTOBUF|XML}
 * pattern (proven UX), keeps the CLI surface small, avoids the
 * "which sink got which payload" bug class. If callers need parallel
 * emission (raw AND CoT), run two receiver processes.
 */
public enum PayloadFormat {

    /** Raw AVR frame from rtl_adsb: {@code *8D4B1A00EA2B5C...;} (one line per frame). */
    AVR,

    /**
     * JSON decoded from AVR by {@link AdsbDecoder#decode(String)}
     * — the pre-existing shape. This is the default so upgrading the
     * jar with no CLI change is a zero-behaviour-change deploy.
     */
    JSON,

    /**
     * CoT XML per {@link com.adsb.cot.CoTBuilder}: one {@code <event>}
     * document per aggregated {@link com.adsb.model.AdsbTrack} snapshot
     * update. Only positioned tracks are emitted (see
     * {@link com.adsb.cot.CoTBuilder#build}).
     */
    COT;

    /** Case-insensitive parse; accepts {@code raw} as a synonym for {@link #AVR}. */
    public static PayloadFormat parse(String s) {
        if (s == null) throw new IllegalArgumentException("payload format required");
        String t = s.trim().toLowerCase();
        return switch (t) {
            case "avr", "raw" -> AVR;
            case "json"       -> JSON;
            case "cot", "xml" -> COT;
            default -> throw new IllegalArgumentException(
                    "unknown --payload value '" + s + "' (expected avr|json|cot)");
        };
    }
}
