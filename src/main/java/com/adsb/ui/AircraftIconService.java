package com.adsb.ui;

import com.adsb.enrichment.Enrichment;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads per-aircraft-type icons (pre-rasterised PNGs on the classpath) and
 * returns tinted, cached {@link BufferedImage} instances for painting on the
 * map.
 *
 * <h2>No-Batik design</h2>
 * Apache Batik (the conventional SVG-in-Java choice) is not available in the
 * local Maven cache and cannot be downloaded from the network in this
 * environment.  Instead, all 28 SVGs were pre-rasterised offline with
 * ImageMagick at 22 px and 44 px (HiDPI) and committed to
 * {@code src/main/resources/icons/} as {@code <key>_22.png} /
 * {@code <key>_44.png}.  This class loads those PNGs via
 * {@link ImageIO#read(InputStream)} — zero extra dependencies.
 *
 * <h2>Tinting</h2>
 * Every icon SVG uses white fill ({@code #ffffff}) with a black outline.
 * At load time we recolour each white or near-white pixel to a neutral
 * mid-grey placeholder.  At draw time {@link #iconFor} tints on-the-fly:
 * for every pixel we substitute the requested altitude {@link Color} while
 * preserving the black outline and alpha channel.  The result is cached by
 * {@code (iconKey, tintRGB, sizePx)} so repeated calls for the same colour
 * are O(1).
 *
 * <h2>Rotation</h2>
 * Rotation is NOT baked in.  The caller applies {@link Graphics2D#rotate}
 * at draw time.  This keeps the cache size bounded to
 * {@code N_icons × N_colours × N_sizes} rather than × 360.
 *
 * <h2>Extending</h2>
 * To add a new aircraft type:
 * <ol>
 *   <li>Drop a new SVG in {@code ICONS/} (Marty's staging area).</li>
 *   <li>Pre-rasterise at 22 and 44 px:
 *       {@code magick -background none NewType_0.svg -resize 22x22 -define png:color-type=6 src/main/resources/icons/NewType_0_22.png}
 *       (repeat for 44px).</li>
 *   <li>Add the type-code → key mapping in {@link #buildTypeMap()}.</li>
 * </ol>
 */
public final class AircraftIconService {

    /** Key used when no enrichment is available or enrichment is empty. */
    private static final String GENERIC_KEY = "acft_0";

    /**
     * Maps ICAO type-designator (uppercase) → icon key.
     * Icon key matches the {@code <key>_22.png} / {@code <key>_44.png}
     * filenames in {@code src/main/resources/icons/}.
     * Built once in a static initialiser and never mutated.
     */
    private static final Map<String, String> TYPE_TO_ICON = buildTypeMap();

    /**
     * Raw (white-fill) images indexed by {@code "<key>_<sizePx>"}.
     * Populated eagerly in the constructor.
     */
    private final Map<String, BufferedImage> rawImages = new ConcurrentHashMap<>();

    /**
     * Tinted, ready-to-draw images indexed by {@code "<key>_<sizePx>_<tintRGB>"}.
     * Populated lazily on first use of each (key, tint, size) triple.
     */
    private final Map<String, BufferedImage> tintedCache = new ConcurrentHashMap<>();

    /**
     * Construct the service and eagerly load all PNGs from the classpath.
     *
     * @throws IllegalStateException if the mandatory generic icon
     *         ({@code acft_0_22.png}) cannot be found on the classpath.
     */
    public AircraftIconService() {
        // Load every icon key we know about at both sizes.
        // Missing type-specific icons are silently skipped; a missing
        // generic is a hard fail.
        for (String key : allKnownKeys()) {
            for (int size : new int[]{22, 44}) {
                String resourceName = "/icons/" + key + "_" + size + ".png";
                try (InputStream is = AircraftIconService.class.getResourceAsStream(resourceName)) {
                    if (is == null) {
                        if (GENERIC_KEY.equals(key)) {
                            throw new IllegalStateException(
                                    "[AircraftIconService] FATAL: generic icon not found on classpath: "
                                            + resourceName);
                        }
                        // type-specific icon missing — tolerated
                        continue;
                    }
                    BufferedImage img = ImageIO.read(is);
                    if (img == null) {
                        System.err.println("[AircraftIconService] WARN: could not decode " + resourceName);
                        continue;
                    }
                    rawImages.put(key + "_" + size, ensureARGB(img));
                } catch (IOException e) {
                    if (GENERIC_KEY.equals(key)) {
                        throw new IllegalStateException(
                                "[AircraftIconService] FATAL: error reading generic icon: " + e, e);
                    }
                    System.err.println("[AircraftIconService] WARN: error reading " + resourceName + ": " + e);
                }
            }
        }
        System.out.println("[AircraftIconService] loaded " + (rawImages.size() / 2) + " icon(s) at 2 sizes");
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Return a cached, tinted {@link BufferedImage} for the given enrichment
     * and altitude colour.
     *
     * @param e       enrichment (may be {@code null} — uses generic icon)
     * @param tint    altitude-derived colour; used to fill the white parts
     *                of the icon shape
     * @param sizePx  desired icon size in pixels; 22 or 44 (HiDPI).
     *                Values other than 44 use the 22 px source.
     * @return        non-null {@link BufferedImage} sized approximately
     *                {@code sizePx × sizePx}; falls back to the generic
     *                icon when the type-specific one is missing.
     */
    public BufferedImage iconFor(Enrichment e, Color tint, int sizePx) {
        String key = iconKeyFor(e);
        int size = (sizePx >= 40) ? 44 : 22;
        String cacheKey = key + "_" + size + "_" + tint.getRGB();

        return tintedCache.computeIfAbsent(cacheKey, k -> {
            BufferedImage raw = rawImages.get(key + "_" + size);
            if (raw == null) {
                // Fall back to generic
                raw = rawImages.get(GENERIC_KEY + "_" + size);
            }
            if (raw == null) {
                // Should never happen — constructor guards this
                return createFallbackImage(size, tint);
            }
            return applyTint(raw, tint);
        });
    }

    // ------------------------------------------------------------------
    // Type-code → icon-key resolution (package-visible for unit tests)
    // ------------------------------------------------------------------

    /**
     * Resolve an enrichment to an icon key.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Null or all-null enrichment → {@code "acft_0"}.</li>
     *   <li>Exact match on {@code typeCode} (uppercased) in {@link #TYPE_TO_ICON}.</li>
     *   <li>Family fallback by first 1–2 characters of {@code typeCode}.</li>
     *   <li>Catch-all → {@code "acft_0"}.</li>
     * </ol>
     *
     * @param e enrichment (may be null)
     * @return icon key (never null, always a key that has a PNG on classpath)
     */
    static String iconKeyFor(Enrichment e) {
        if (e == null || e.isEmpty()) return GENERIC_KEY;
        String tc = e.typeCode();
        if (tc == null || tc.isBlank()) return GENERIC_KEY;

        tc = tc.trim().toUpperCase();

        // 1. Exact match
        String mapped = TYPE_TO_ICON.get(tc);
        if (mapped != null) return mapped;

        // 2. Family fallbacks
        if (tc.startsWith("H") || tc.startsWith("EC2") || tc.startsWith("EC3")
                || tc.startsWith("EC5") || tc.startsWith("EC7")) {
            // Unmatched type starting with H → assume helicopter
            return "L1P_0";
        }
        if (tc.startsWith("A")) {
            // Unmatched Airbus or other type starting with A
            return "T_A320_0";
        }
        if (tc.startsWith("B7")) {
            // Unmatched Boeing 7xx series
            return "T_737_0";
        }
        if (tc.startsWith("E")) {
            // Embraer (E170, E190, ERJ etc.) — approximate with narrow-body jet
            return "T_A320_0";
        }
        if (tc.startsWith("C1") || tc.startsWith("C2") || tc.startsWith("C4")) {
            // Small Cessna singles/light twins
            return "L1P_0";
        }

        // 3. Catch-all
        return GENERIC_KEY;
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    /**
     * Build the immutable type-code → icon-key table.
     * Groups are commented by aircraft family for easy extension.
     */
    private static Map<String, String> buildTypeMap() {
        Map<String, String> m = new java.util.HashMap<>(256);

        // ---- Airbus narrow-body family ----
        m.put("A318", "T_A320_0");
        m.put("A319", "T_A320_0");
        m.put("A320", "T_A320_0");
        m.put("A321", "T_A320_0");
        m.put("A20N", "T_A320_0");   // A320neo ICAO designator
        m.put("A21N", "T_A320_0");   // A321neo
        m.put("A19N", "T_A320_0");   // A319neo

        // ---- Boeing 737 family ----
        m.put("B737", "T_737_0");
        m.put("B738", "T_737_0");
        m.put("B739", "T_737_0");
        m.put("B734", "T_737_0");
        m.put("B735", "T_737_0");
        m.put("B736", "T_737_0");
        m.put("B38M", "T_737_0");   // 737 MAX 8
        m.put("B39M", "T_737_0");   // 737 MAX 9
        m.put("B37M", "T_737_0");   // 737 MAX 7

        // ---- Boeing 767 family ----
        m.put("B762", "T_767_0");
        m.put("B763", "T_767_0");
        m.put("B764", "T_767_0");
        m.put("B76F", "T_767_0");   // 767 freighter

        // ---- Boeing 777 family ----
        m.put("B772", "T_B777_0");
        m.put("B773", "T_B777_0");
        m.put("B77L", "T_B777_0");
        m.put("B77W", "T_B777_0");
        m.put("B778", "T_B777_0");
        m.put("B779", "T_B777_0");
        m.put("B77F", "T_B777_0");

        // ---- Boeing 747 family ----
        m.put("B742", "T_B747_0");
        m.put("B743", "T_B747_0");
        m.put("B744", "T_B747_0");
        m.put("B748", "T_B747_0");
        m.put("B74F", "T_B747_0");

        // ---- Boeing 757 family ----
        m.put("B752", "T_B757_0");
        m.put("B753", "T_B757_0");

        // ---- C-130 Hercules family (military transport) ----
        m.put("C130", "T_C130_0");
        m.put("C30J", "T_C130_0");   // C-130J Super Hercules ICAO
        m.put("L100", "T_C130_0");   // Lockheed L-100 Hercules civilian

        // ---- C-17 Globemaster (military transport) ----
        m.put("C17",  "T_C17_0");
        m.put("C17A", "T_C17_0");

        // ---- A400M Atlas (military transport) ----
        m.put("A400", "T_A400_0");
        m.put("A40M", "T_A400_0");

        // ---- C-5 Galaxy (military heavy transport) ----
        m.put("C5",   "T_C5M_0");
        m.put("C5M",  "T_C5M_0");
        m.put("C5A",  "T_C5M_0");
        m.put("C5B",  "T_C5M_0");

        // ---- V-22 Osprey tiltrotor ----
        m.put("V22",  "T_V22_0");
        m.put("MV22", "T_V22_0");
        m.put("CV22", "T_V22_0");

        // ---- E-3 Sentry (AWACS) ----
        m.put("E3TF", "T_AWACS_0");
        m.put("E3CF", "T_AWACS_0");
        m.put("E3BS", "T_AWACS_0");
        m.put("E3A",  "T_AWACS_0");

        // ---- KC-10 Extender / DC-10 (airframe sibling, close enough for a glyph) ----
        m.put("KC10", "T_KC10_0");
        m.put("DC10", "T_KC10_0");

        // ---- KC-135 / C-135 Stratotanker family ----
        m.put("K35R",  "T_KC135_0");
        m.put("KC135", "T_KC135_0");
        m.put("C135",  "T_KC135_0");
        m.put("E135",  "T_KC135_0");   // E-8 / E-135 variants

        // ---- RC-135 Rivet Joint ----
        m.put("RC35",  "T_R135_0");
        m.put("R135",  "T_R135_0");
        m.put("C135R", "T_R135_0");

        // ---- Beechcraft King Air 200 / 350 family (turboprop) ----
        m.put("BE20", "T_BE20_0");
        m.put("BE9L", "T_BE20_0");
        m.put("BE10", "T_BE20_0");
        m.put("B350", "T_BE20_0");
        m.put("B300", "T_BE20_0");   // King Air 300

        // ---- Cessna Citation II / III family (biz jets) ----
        m.put("C550", "T_C550_0");
        m.put("C551", "T_C550_0");
        m.put("C560", "T_C550_0");
        m.put("C56X", "T_C550_0");
        m.put("C525", "T_C550_0");   // Citation CJ1

        // ---- Learjet family ----
        m.put("LJ35", "T_LJ35_0");
        m.put("LJ31", "T_LJ35_0");
        m.put("LJ40", "T_LJ35_0");
        m.put("LJ45", "T_LJ35_0");
        m.put("LJ55", "T_LJ35_0");
        m.put("LJ60", "T_LJ35_0");
        m.put("LJ70", "T_LJ35_0");
        m.put("LJ75", "T_LJ35_0");

        // ---- Gulfstream family ----
        m.put("GLF5", "T_GLF5_0");
        m.put("GLF4", "T_GLF5_0");
        m.put("GLF6", "T_GLF5_0");
        m.put("GLF3", "T_GLF5_0");
        m.put("GLEX", "T_GLF5_0");   // Bombardier Global Express (close enough)
        m.put("G650", "T_GLF5_0");
        m.put("G550", "T_GLF5_0");
        m.put("G500", "T_GLF5_0");

        // ---- Gulfstream G200 / G280 family (mid-size) ----
        m.put("G200", "T_G200_0");
        m.put("G150", "T_G200_0");
        m.put("G280", "T_G200_0");

        // ---- Eurocopter / Airbus Helicopters EC130 family ----
        m.put("EC30", "T_EC130_0");
        m.put("EC35", "T_EC130_0");
        m.put("EC20", "T_EC130_0");
        m.put("AS50", "T_EC130_0");
        m.put("AS55", "T_EC130_0");
        m.put("H130", "T_EC130_0");   // H130 (EC130 successor designation)

        // ---- Eurocopter EC145 / H145 family ----
        m.put("EC45", "T_EC45_0");
        m.put("EC55", "T_EC45_0");
        m.put("EC75", "T_EC45_0");
        m.put("H145", "T_EC45_0");
        m.put("H155", "T_EC45_0");
        m.put("H175", "T_EC45_0");

        // ---- Sikorsky UH-60 Black Hawk family ----
        m.put("H60",  "T_H60_0");
        m.put("S70",  "T_H60_0");
        m.put("H60L", "T_H60_0");
        m.put("UH60", "T_H60_0");
        m.put("S70A", "T_H60_0");
        m.put("S70I", "T_H60_0");
        m.put("HH60", "T_H60_0");   // HH-60 Pave Hawk
        m.put("MH60", "T_H60_0");   // MH-60

        // ---- Sikorsky S-76 / SW4 family ----
        m.put("S76",  "T_SW4_0");
        m.put("S76B", "T_SW4_0");
        m.put("S76C", "T_SW4_0");
        m.put("S76D", "T_SW4_0");
        m.put("SW4",  "T_SW4_0");

        return Map.copyOf(m);
    }

    /**
     * All icon keys that should be pre-loaded. Generic first so we
     * can fail-fast on a missing classpath entry.
     */
    private static String[] allKnownKeys() {
        return new String[]{
            GENERIC_KEY,
            "L1P_0",
            "T_737_0", "T_767_0", "T_A320_0", "T_A400_0",
            "T_AWACS_0", "T_B747_0", "T_B757_0", "T_B777_0",
            "T_BE20_0", "T_C130_0", "T_C17_0", "T_C550_0", "T_C5M_0",
            "T_EC130_0", "T_EC45_0", "T_G200_0", "T_GLF5_0",
            "T_H60_0", "T_KC10_0", "T_KC135_0", "T_LJ35_0",
            "T_R135_0", "T_SERVICE_VEHICLE_0", "T_SW4_0", "T_V22_0",
            "a320_0"
        };
    }

    /**
     * Ensure the image is in {@link BufferedImage#TYPE_INT_ARGB} format
     * so pixel manipulation is fast and consistent.
     */
    private static BufferedImage ensureARGB(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage dest = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dest.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return dest;
    }

    /**
     * Apply a tint colour to a pre-loaded (white-fill, black-outline) icon.
     *
     * <p>For each pixel:
     * <ul>
     *   <li>Fully transparent → keep transparent.</li>
     *   <li>Very dark (all channels ≤ 80) → keep black outline.</li>
     *   <li>Everything else (the white fill) → replace with {@code tint},
     *       preserving the original alpha.</li>
     * </ul>
     *
     * @param src  source ARGB image (unmodified)
     * @param tint the altitude-derived colour
     * @return     a new {@link BufferedImage} with the tint applied
     */
    private static BufferedImage applyTint(BufferedImage src, Color tint) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int tr = tint.getRed();
        int tg = tint.getGreen();
        int tb = tint.getBlue();

        int[] pixels = src.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < pixels.length; i++) {
            int argb = pixels[i];
            int a = (argb >> 24) & 0xff;
            if (a == 0) {
                pixels[i] = 0;  // keep transparent
                continue;
            }
            int r = (argb >> 16) & 0xff;
            int g = (argb >>  8) & 0xff;
            int b = argb         & 0xff;
            // Dark pixels → keep as-is (outline)
            if (r <= 80 && g <= 80 && b <= 80) {
                continue;
            }
            // White / mid-tone fill → tint
            pixels[i] = (a << 24) | (tr << 16) | (tg << 8) | tb;
        }
        out.setRGB(0, 0, w, h, pixels, 0, w);
        return out;
    }

    /**
     * Create a minimal programmatic fallback image (small tinted square)
     * in the unlikely event that even the generic classpath PNG is missing.
     * This is a last-resort guard; normal operation should never reach it.
     */
    private static BufferedImage createFallbackImage(int size, Color tint) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(tint);
            int[] xs = {size / 2, size - 2, size / 2, 2};
            int[] ys = {2, size - 2, size - 6, size - 2};
            g.fillPolygon(xs, ys, 4);
            g.setColor(Color.BLACK);
            g.drawPolygon(xs, ys, 4);
        } finally {
            g.dispose();
        }
        return img;
    }
}
