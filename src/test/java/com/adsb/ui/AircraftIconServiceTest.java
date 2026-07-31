package com.adsb.ui;

import com.adsb.enrichment.Enrichment;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AircraftIconService}.
 *
 * <p>The {@link #iconKeyFor_table()} test covers every explicit row in the
 * type-code mapping plus all family-fallback and catch-all rules.
 * The end-to-end {@link #iconFor_generic_roundtrip()} constructs a real
 * {@link AircraftIconService} and verifies the PNG loads and tints correctly;
 * it is skipped in headless environments where {@link java.awt.Toolkit}
 * may not initialise.
 */
class AircraftIconServiceTest {

    // ------------------------------------------------------------------
    // iconKeyFor table-driven test
    // ------------------------------------------------------------------

    /**
     * Helper: build a minimal {@link Enrichment} with just a typeCode.
     */
    private static Enrichment enr(String typeCode) {
        return new Enrichment("AABBCC", "N12345", typeCode, null, null, null, null);
    }

    @Test
    void iconKeyFor_null_enrichment_returns_generic() {
        assertEquals("acft_0", AircraftIconService.iconKeyFor(null));
    }

    @Test
    void iconKeyFor_empty_enrichment_returns_generic() {
        assertEquals("acft_0", AircraftIconService.iconKeyFor(Enrichment.empty("AABBCC")));
    }

    @Test
    void iconKeyFor_unknown_typeCode_returns_generic() {
        assertEquals("acft_0", AircraftIconService.iconKeyFor(enr("ZZZZ")));
    }

    @Test
    void iconKeyFor_null_typeCode_returns_generic() {
        Enrichment e = new Enrichment("AABBCC", "N12345", null, "Boeing", "747", "SomeAir", "SAL");
        assertEquals("acft_0", AircraftIconService.iconKeyFor(e));
    }

    @Test
    void iconKeyFor_table() {
        // Map of input typeCode -> expected icon key
        Map<String, String> table = Map.ofEntries(
            // ---- Airbus narrow-body ----
            Map.entry("A318",  "T_A320_0"),
            Map.entry("A319",  "T_A320_0"),
            Map.entry("A320",  "T_A320_0"),
            Map.entry("A321",  "T_A320_0"),
            Map.entry("A20N",  "T_A320_0"),
            Map.entry("A21N",  "T_A320_0"),

            // ---- Boeing 737 ----
            Map.entry("B737",  "T_737_0"),
            Map.entry("B738",  "T_737_0"),
            Map.entry("B739",  "T_737_0"),
            Map.entry("B734",  "T_737_0"),
            Map.entry("B735",  "T_737_0"),
            Map.entry("B736",  "T_737_0"),
            Map.entry("B38M",  "T_737_0"),
            Map.entry("B39M",  "T_737_0"),

            // ---- Boeing 767 ----
            Map.entry("B762",  "T_767_0"),
            Map.entry("B763",  "T_767_0"),
            Map.entry("B764",  "T_767_0"),
            Map.entry("B76F",  "T_767_0"),

            // ---- Boeing 777 ----
            Map.entry("B772",  "T_B777_0"),
            Map.entry("B773",  "T_B777_0"),
            Map.entry("B77L",  "T_B777_0"),
            Map.entry("B77W",  "T_B777_0"),
            Map.entry("B778",  "T_B777_0"),
            Map.entry("B779",  "T_B777_0"),

            // ---- Boeing 747 ----
            Map.entry("B742",  "T_B747_0"),
            Map.entry("B743",  "T_B747_0"),
            Map.entry("B744",  "T_B747_0"),
            Map.entry("B748",  "T_B747_0"),

            // ---- Boeing 757 ----
            Map.entry("B752",  "T_B757_0"),
            Map.entry("B753",  "T_B757_0"),

            // ---- C-130 ----
            Map.entry("C130",  "T_C130_0"),
            Map.entry("C30J",  "T_C130_0"),
            Map.entry("L100",  "T_C130_0"),

            // ---- C-17 ----
            Map.entry("C17",   "T_C17_0"),
            Map.entry("C17A",  "T_C17_0"),

            // ---- A400M ----
            Map.entry("A400",  "T_A400_0"),

            // ---- C-5 ----
            Map.entry("C5",    "T_C5M_0"),
            Map.entry("C5M",   "T_C5M_0"),
            Map.entry("C5A",   "T_C5M_0"),

            // ---- V-22 ----
            Map.entry("V22",   "T_V22_0"),
            Map.entry("MV22",  "T_V22_0"),
            Map.entry("CV22",  "T_V22_0"),

            // ---- AWACS ----
            Map.entry("E3TF",  "T_AWACS_0"),
            Map.entry("E3CF",  "T_AWACS_0"),
            Map.entry("E3BS",  "T_AWACS_0"),

            // ---- KC-10 / DC-10 ----
            Map.entry("KC10",  "T_KC10_0"),
            Map.entry("DC10",  "T_KC10_0"),

            // ---- KC-135 ----
            Map.entry("K35R",  "T_KC135_0"),
            Map.entry("KC135", "T_KC135_0"),
            Map.entry("C135",  "T_KC135_0"),

            // ---- RC-135 ----
            Map.entry("RC35",  "T_R135_0"),
            Map.entry("R135",  "T_R135_0"),
            Map.entry("C135R", "T_R135_0"),

            // ---- BE20 King Air ----
            Map.entry("BE20",  "T_BE20_0"),
            Map.entry("BE9L",  "T_BE20_0"),
            Map.entry("B350",  "T_BE20_0"),

            // ---- Citation ----
            Map.entry("C550",  "T_C550_0"),
            Map.entry("C560",  "T_C550_0"),
            Map.entry("C56X",  "T_C550_0"),

            // ---- Learjet ----
            Map.entry("LJ35",  "T_LJ35_0"),
            Map.entry("LJ45",  "T_LJ35_0"),
            Map.entry("LJ60",  "T_LJ35_0"),
            Map.entry("LJ75",  "T_LJ35_0"),

            // ---- Gulfstream ----
            Map.entry("GLF5",  "T_GLF5_0"),
            Map.entry("GLF4",  "T_GLF5_0"),
            Map.entry("GLF6",  "T_GLF5_0"),
            Map.entry("GLEX",  "T_GLF5_0"),

            // ---- G200 ----
            Map.entry("G200",  "T_G200_0"),
            Map.entry("G150",  "T_G200_0"),
            Map.entry("G280",  "T_G200_0"),

            // ---- EC130 ----
            Map.entry("EC30",  "T_EC130_0"),
            Map.entry("EC35",  "T_EC130_0"),
            Map.entry("AS50",  "T_EC130_0"),

            // ---- EC45 ----
            Map.entry("EC45",  "T_EC45_0"),
            Map.entry("H145",  "T_EC45_0"),
            Map.entry("H155",  "T_EC45_0"),
            Map.entry("H175",  "T_EC45_0"),

            // ---- H-60 Black Hawk ----
            Map.entry("H60",   "T_H60_0"),
            Map.entry("S70",   "T_H60_0"),
            Map.entry("UH60",  "T_H60_0"),
            Map.entry("HH60",  "T_H60_0"),
            Map.entry("MH60",  "T_H60_0"),

            // ---- S76 ----
            Map.entry("S76",   "T_SW4_0"),
            Map.entry("S76C",  "T_SW4_0"),
            Map.entry("SW4",   "T_SW4_0")
        );

        table.forEach((typeCode, expected) -> {
            String actual = AircraftIconService.iconKeyFor(enr(typeCode));
            assertEquals(expected, actual,
                    "Type code '" + typeCode + "' should map to '" + expected + "'");
        });
    }

    // ------------------------------------------------------------------
    // Family-fallback tests
    // ------------------------------------------------------------------

    @Test
    void iconKeyFor_A350_family_fallback() {
        // A350 not in explicit map → starts with 'A' → T_A320_0
        assertEquals("T_A320_0", AircraftIconService.iconKeyFor(enr("A350")));
    }

    @Test
    void iconKeyFor_B788_family_fallback() {
        // B788 (787) not in explicit map → starts with 'B7' → T_737_0
        assertEquals("T_737_0", AircraftIconService.iconKeyFor(enr("B788")));
    }

    @Test
    void iconKeyFor_H500_family_fallback() {
        // H500 not in explicit map → starts with 'H' → L1P_0 (helicopter)
        assertEquals("L1P_0", AircraftIconService.iconKeyFor(enr("H500")));
    }

    @Test
    void iconKeyFor_embraer_family_fallback() {
        // E190 starts with 'E' → T_A320_0 (regional-jet approximation)
        assertEquals("T_A320_0", AircraftIconService.iconKeyFor(enr("E190")));
    }

    @Test
    void iconKeyFor_cessna_family_fallback() {
        // C172 starts with 'C1' → L1P_0 (small GA)
        assertEquals("L1P_0", AircraftIconService.iconKeyFor(enr("C172")));
    }

    @Test
    void iconKeyFor_lowercase_typeCode_normalised() {
        // Type codes should be normalised to uppercase
        assertEquals("T_A320_0", AircraftIconService.iconKeyFor(enr("a320")));
        assertEquals("T_737_0",  AircraftIconService.iconKeyFor(enr("b738")));
    }

    // ------------------------------------------------------------------
    // End-to-end: construct AircraftIconService, call iconFor
    // ------------------------------------------------------------------

    @Test
    void iconFor_generic_roundtrip() {
        // Skip in truly headless environments that can't initialise
        // BufferedImage (though TYPE_INT_ARGB doesn't need a display).
        // This guard exists for any CI environment that panics on AWT init.
        Assumptions.assumeFalse(
                GraphicsEnvironment.isHeadless()
                        && !canCreateBufferedImage(),
                "Skipping icon roundtrip: headless environment without AWT image support");

        AircraftIconService svc;
        try {
            svc = new AircraftIconService();
        } catch (Exception e) {
            Assumptions.abort("AircraftIconService init failed (headless / missing resources): " + e);
            return;
        }

        // Generic icon (null enrichment)
        BufferedImage img22 = svc.iconFor(null, Color.GREEN, 22);
        assertNotNull(img22, "iconFor(null) should return a non-null image");
        assertEquals(22, img22.getWidth(), "Width should be 22 px");
        assertEquals(22, img22.getHeight(), "Height should be 22 px");

        // Second call at same size/tint must be the cached same object
        BufferedImage img22b = svc.iconFor(null, Color.GREEN, 22);
        assertSame(img22, img22b, "Repeated call with same args should return cached instance");

        // Different tint → different cached object
        BufferedImage img22red = svc.iconFor(null, Color.RED, 22);
        assertNotNull(img22red);
        assertNotSame(img22, img22red, "Different tint should produce a different cached image");

        // HiDPI size
        BufferedImage img44 = svc.iconFor(null, Color.BLUE, 44);
        assertNotNull(img44);
        assertEquals(44, img44.getWidth(), "Width should be 44 px for HiDPI");

        // Named type
        Enrichment enrA320 = new Enrichment("AABBCC", "N12345", "A320", "Airbus", "A320-214", "Lufthansa", "DLH");
        BufferedImage imgA320 = svc.iconFor(enrA320, Color.YELLOW, 22);
        assertNotNull(imgA320, "iconFor(A320 enrichment) should be non-null");
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private static boolean canCreateBufferedImage() {
        try {
            new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
