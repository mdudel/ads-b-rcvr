package com.adsb.cot;

/**
 * Turns {@code (icaoHex, do260bCategory)} into a MIL-STD-2525 / CoT
 * type string of the form {@code a-{attitude}-A-{affiliation}-{function}}.
 *
 * <p>Atoms:
 * <ul>
 *   <li>attitude: {@code f} friendly, {@code n} neutral, {@code h} hostile,
 *       {@code u} unknown, {@code p} pending</li>
 *   <li>battle-dim: constant {@code A} (Air) for this class</li>
 *   <li>affiliation: {@code C} civilian, {@code M} military</li>
 *   <li>function: {@code F} fixed-wing, {@code H} helicopter,
 *       {@code L} lighter-than-air, {@code F-F} high-performance fighter</li>
 * </ul>
 *
 * <p><b>Default</b> (per Marty's 2026-07-27 direction): neutral civilian
 * fixed-wing = {@code a-n-A-C-F}. This is the correct MIL-STD-2525 encoding
 * for a globally-registered civil airliner — "friendly" is reserved for
 * confirmed coalition members, which no civilian airline qualifies as.
 *
 * <p>CLI overrides via {@link Affiliation} and {@link Category} always
 * beat the built-in ICAO range table.
 *
 * <p>The built-in ICAO range table currently covers only US, UK and
 * German military allocations; it's intentionally small and additive.
 * Broader coverage lives in issue #11 (known-craft overlay).
 */
public final class IcaoAircraftClassifier {

    /** Perceived affiliation, override for the ICAO range table. */
    public enum Affiliation {
        FRIENDLY('f'), NEUTRAL('n'), HOSTILE('h'), UNKNOWN('u'), PENDING('p');
        final char code;
        Affiliation(char c) { this.code = c; }
    }

    /** Category, override for the ICAO range table. */
    public enum Category {
        CIVILIAN('C'), MILITARY('M');
        final char code;
        Category(char c) { this.code = c; }
    }

    private final Affiliation affilOverride;
    private final Category    catOverride;

    /**
     * @param affilOverride null → use ICAO range table then default NEUTRAL
     * @param catOverride   null → use ICAO range table then default CIVILIAN
     */
    public IcaoAircraftClassifier(Affiliation affilOverride, Category catOverride) {
        this.affilOverride = affilOverride;
        this.catOverride   = catOverride;
    }

    /** @return the CoT {@code type} string, e.g. {@code a-n-A-C-F}. */
    public String classify(String icaoHex, String do260Category) {
        // Strip any pseudo-ICAO prefix (ADSBExchange marks MLAT/FAA-rebroadcast
        // tracks with a leading '~') then parse.
        String cleanHex = icaoHex == null ? "" : icaoHex.replace("~", "").trim();

        // Range-table verdict (may be overridden below).
        boolean rangeSaysMilitary = isMilitaryIcaoRange(cleanHex);

        Affiliation affil = affilOverride != null
                ? affilOverride
                : (rangeSaysMilitary ? Affiliation.FRIENDLY : Affiliation.NEUTRAL);

        Category cat = catOverride != null
                ? catOverride
                : (rangeSaysMilitary ? Category.MILITARY : Category.CIVILIAN);

        String function = functionSuffix(do260Category, cat == Category.MILITARY);

        return "a-" + affil.code + "-A-" + cat.code + "-" + function;
    }

    /**
     * DO-260B emitter category → function suffix.
     * <ul>
     *   <li>A1..A5 → {@code F} (light..heavy fixed-wing)</li>
     *   <li>A6     → {@code F-F} (high-performance fighter — only meaningful for military)</li>
     *   <li>A7     → {@code H} (rotorcraft)</li>
     *   <li>B2     → {@code L} (lighter-than-air)</li>
     *   <li>anything else / null → {@code F} (default fixed-wing)</li>
     * </ul>
     */
    private static String functionSuffix(String cat, boolean isMilitary) {
        if (cat == null) return "F";
        return switch (cat) {
            case "A1", "A2", "A3", "A4", "A5" -> "F";
            case "A6" -> isMilitary ? "F-F" : "F"; // civil high-perf still emits plain F
            case "A7" -> "H";
            case "B2" -> "L";
            default   -> "F";
        };
    }

    /**
     * Known military ICAO 24-bit ranges (uppercase hex, no separators).
     * Deliberately conservative — false-military is worse than false-civilian
     * for tactical display.
     * <p>
     * Extend this table via issue #11 (known-craft overlay) rather than adding
     * one-off ranges here.
     */
    private static boolean isMilitaryIcaoRange(String hex) {
        if (hex == null || hex.length() != 6) return false;
        long v;
        try { v = Long.parseLong(hex, 16); }
        catch (NumberFormatException e) { return false; }
        // US-MIL block
        if (v >= 0xADF7C8L && v <= 0xAFFFFFL) return true;
        // UK-MIL
        if (v >= 0x43C000L && v <= 0x43CFFFL) return true;
        // Germany-MIL
        if (v >= 0x3F81C0L && v <= 0x3FFFFFL) return true;
        return false;
    }
}
