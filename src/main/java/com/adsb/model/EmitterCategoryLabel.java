package com.adsb.model;

/**
 * DO-260B emitter-category code -&gt; human-readable label.
 *
 * <p>Codes are two characters: set letter (A/B/C/D) + subcategory
 * digit (1..7). Set A is the fixed-wing family, B is rotorcraft +
 * gliders + UAVs, C is surface vehicles + obstacles, D is reserved
 * for future use.
 *
 * <p>Reference: RTCA DO-260B Table 2-72 (Emitter Category). Labels
 * are the short forms most ADS-B decoders / flight trackers use so
 * an operator familiar with FlightAware / FR24 sees the same names.
 *
 * <p>Package-visible pure static class; no instances.
 */
public final class EmitterCategoryLabel {

    private EmitterCategoryLabel() {}

    /**
     * @param code raw DO-260B category code (e.g. {@code "A3"}, {@code "B2"}),
     *             any case; leading/trailing whitespace tolerated.
     * @return short human label (e.g. {@code "Large"}, {@code "Rotorcraft"});
     *         {@code null} when {@code code} is null/blank; {@code "Unknown"}
     *         for a syntactically-valid but unmapped code so the operator
     *         still sees SOMETHING in the column rather than a blank.
     */
    public static String labelFor(String code) {
        if (code == null) return null;
        String c = code.trim().toUpperCase();
        if (c.isEmpty()) return null;
        return switch (c) {
            // Set A: powered fixed-wing
            case "A0" -> "No info";
            case "A1" -> "Light";           // < 15,500 lb
            case "A2" -> "Small";           // 15,500 - 75,000 lb
            case "A3" -> "Large";           // 75,000 - 300,000 lb
            case "A4" -> "High vortex";     // B757 class
            case "A5" -> "Heavy";           // > 300,000 lb
            case "A6" -> "High perf";       // > 5 g and > 400 kt
            case "A7" -> "Rotorcraft";

            // Set B: other airborne
            case "B0" -> "No info";
            case "B1" -> "Glider";
            case "B2" -> "Lighter-than-air";
            case "B3" -> "Parachutist";
            case "B4" -> "Ultralight";
            case "B5" -> "Reserved";
            case "B6" -> "UAV";
            case "B7" -> "Space vehicle";

            // Set C: surface
            case "C0" -> "No info";
            case "C1" -> "Emergency vehicle";
            case "C2" -> "Service vehicle";
            case "C3" -> "Fixed obstacle";
            case "C4" -> "Cluster obstacle";
            case "C5" -> "Line obstacle";
            case "C6", "C7" -> "Reserved";

            default    -> "Unknown";
        };
    }
}
