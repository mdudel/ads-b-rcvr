/* -----------------------------------------------------------------------------
 *       UNCLASSIFIED UNCLASSIFIED UNCLASSIFIED UNCLASSIFIED UNCLASSIFIED
 *                 (C) Copyright 2026, USAREUR-AF G6 E&I
 *                         ALL RIGHTS RESERVED
 *                 THIS NOTICE DOES NOT IMPLY PUBLICATION
 * -------------------------------------------------------------------------- */
package com.adsb.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Icon;

/**
 * Material Icons Regular glyph loader + {@link Icon} factory.
 * Bundles the Material Icons Regular TTF as a classpath resource
 * ({@code /fonts/MaterialIcons-Regular.ttf}, ~357 KB) and hands
 * back Swing {@link Icon} instances that render the requested
 * glyph at the requested pixel size in the current foreground
 * colour.
 *
 * <p>Font is registered once with the local
 * {@link GraphicsEnvironment} on the first {@link #of} call. If
 * the font resource is missing (e.g. someone stripped it during
 * a repackage) all subsequent calls fall back to a
 * {@link UnicodeFallbackIcon} that renders a Unicode-equivalent
 * glyph so the UI stays functional. Font load failure is logged
 * once at INFO -- never re-thrown out of the loader.</p>
 *
 * <p>Idiomatic use:</p>
 * <pre>{@code
 *   JButton settings = new JButton("Settings",
 *           MaterialIcon.of(MaterialIcon.Glyph.SETTINGS, 18));
 * }</pre>
 *
 * <p>Icon foreground is picked up from the host component's
 * foreground at paint time (Swing's standard Icon contract) so
 * icons automatically re-tint on theme switch without any per-
 * icon plumbing.</p>
 *
 * <p>Package + class refactor rationale: kept SEPARATE from
 * {@link MaterialFlat} (which is pure UIManager shape overrides)
 * so a future test that stubs the icon font can do so without
 * touching the shape layer. Also lets tests exercise the fallback
 * path deterministically by simulating a missing resource.</p>
 */
public final class MaterialIcon {

    private static final Logger LOG =
            Logger.getLogger(MaterialIcon.class.getName());

    /** Classpath location of the bundled TTF. */
    static final String FONT_RESOURCE = "/fonts/MaterialIcons-Regular.ttf";

    /**
     * Canonical Material Icons Regular code points we use in the
     * client. Extend as new icons are needed -- lookup is via the
     * enum, not raw ints, so a bad enum reference is a compile-time
     * error, not a runtime blank-glyph.
     *
     * <p>Code points sourced from
     * <a href="https://github.com/google/material-design-icons/raw/master/font/MaterialIcons-Regular.codepoints">
     * MaterialIcons-Regular.codepoints</a>.</p>
     */
    public enum Glyph {
        /** {@code build} \uE869 -- Tools toolbar button. */
        TOOLS       (0xE869, '\u2699'),   // fallback: gear glyph ⚙
        /** {@code layers} \uE53B -- Overlays toolbar button. */
        OVERLAYS    (0xE53B, '\u2630'),   // fallback: trigram ☰
        /** {@code settings} \uE8B8 -- Settings toolbar button. */
        SETTINGS    (0xE8B8, '\u2699'),   // fallback: gear glyph ⚙
        /** {@code security} \uE32A -- Authentication toolbar button (shield outline). */
        AUTH        (0xE32A, '\u26BF'),   // fallback: squared key ⚿
        /** {@code info} \uE88E -- About toolbar button. */
        ABOUT       (0xE88E, '\u2139'),   // fallback: info ℹ
        /** {@code my_location} \uE55C -- OverlayPanel target-icon button. */
        FLY_TO      (0xE55C, '\u2295'),   // fallback: circled plus ⊕
        /** {@code close} \uE5CD -- SideDock close-X. */
        CLOSE       (0xE5CD, '\u2715'),   // fallback: multiplication x ✕
        /** {@code keyboard_arrow_up} \uE316 -- MapNavPanel pan-north. */
        PAN_UP      (0xE316, '\u25B2'),   // fallback: black up-pointing triangle ▲
        /** {@code keyboard_arrow_down} \uE313 -- MapNavPanel pan-south. */
        PAN_DOWN    (0xE313, '\u25BC'),   // fallback: black down-pointing triangle ▼
        /** {@code keyboard_arrow_left} \uE314 -- MapNavPanel pan-west. */
        PAN_LEFT    (0xE314, '\u25C0'),   // fallback: black left-pointing triangle ◀
        /** {@code keyboard_arrow_right} \uE315 -- MapNavPanel pan-east. */
        PAN_RIGHT   (0xE315, '\u25B6'),   // fallback: black right-pointing triangle ▶
        /** {@code add} \uE145 -- MapNavPanel zoom-in. */
        ZOOM_IN     (0xE145, '+'),        // fallback: ASCII plus
        /** {@code remove} \uE15B -- MapNavPanel zoom-out. */
        ZOOM_OUT    (0xE15B, '\u2013');   // fallback: en-dash –

        final int codepoint;
        final char fallbackChar;

        Glyph(int codepoint, char fallback) {
            this.codepoint = codepoint;
            this.fallbackChar = fallback;
        }
    }

    /** One-time font-load state. */
    private static volatile boolean loadAttempted = false;
    private static volatile Font iconFont; // null when load failed / not attempted

    /** Per-size cache so we don't derive a new Font per paint. */
    private static final Map<Integer, Font> SIZED_FONTS = new HashMap<>();

    private MaterialIcon() { /* static-only */ }

    /**
     * Build an {@link Icon} for {@code glyph} at {@code sizePx}
     * pixels. Never null; falls back to a Unicode-equivalent glyph
     * icon if the font isn't loadable.
     *
     * @param glyph  which glyph to render
     * @param sizePx square icon side length in pixels. Clamped to
     *               [8, 128] so a nutty caller can't allocate a
     *               giant Font.
     */
    public static Icon of(Glyph glyph, int sizePx) {
        if (glyph == null) throw new IllegalArgumentException("glyph is null");
        int size = Math.max(8, Math.min(128, sizePx));
        Font font = getIconFont();
        if (font == null) return new UnicodeFallbackIcon(glyph.fallbackChar, size);
        Font sized = sizedFont(size);
        return new FontGlyphIcon(sized, glyph.codepoint, size);
    }

    /**
     * Test hook: reset the one-time load flag + cache so a test
     * can exercise the fallback path deterministically.
     */
    static synchronized void resetForTest() {
        loadAttempted = false;
        iconFont = null;
        SIZED_FONTS.clear();
    }

    /** True iff the TTF has been successfully loaded. */
    public static boolean isFontLoaded() {
        return iconFont != null;
    }

    // ------------------------------------------------------------------
    // Font loading
    // ------------------------------------------------------------------

    private static Font getIconFont() {
        if (loadAttempted) return iconFont;
        synchronized (MaterialIcon.class) {
            if (loadAttempted) return iconFont;
            iconFont = loadFontFromClasspath();
            loadAttempted = true;
            return iconFont;
        }
    }

    private static Font loadFontFromClasspath() {
        try (InputStream in = MaterialIcon.class.getResourceAsStream(FONT_RESOURCE)) {
            if (in == null) {
                LOG.log(Level.INFO,
                        "Material Icons font resource {0} not found on classpath; "
                      + "using Unicode fallback glyphs", FONT_RESOURCE);
                return null;
            }
            Font base = Font.createFont(Font.TRUETYPE_FONT, in);
            // Register so any component that names the family by string
            // (unlikely -- we always derive from the base font here) can
            // find it.
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .registerFont(base);
            LOG.log(Level.INFO, "Material Icons font loaded: family=\"{0}\"",
                    base.getFamily());
            return base;
        } catch (Exception e) {
            LOG.log(Level.INFO,
                    "Material Icons font load failed ({0}: {1}); using Unicode fallback",
                    new Object[]{e.getClass().getSimpleName(), e.getMessage()});
            return null;
        }
    }

    private static synchronized Font sizedFont(int size) {
        Font cached = SIZED_FONTS.get(size);
        if (cached != null) return cached;
        Font derived = iconFont.deriveFont(Font.PLAIN, (float) size);
        SIZED_FONTS.put(size, derived);
        return derived;
    }

    // ------------------------------------------------------------------
    // Icon impls
    // ------------------------------------------------------------------

    /**
     * Icon that paints a Material Icons glyph at its target size in
     * the host component's foreground colour. Anti-aliased.
     */
    static final class FontGlyphIcon implements Icon {
        private final Font font;
        private final int codepoint;
        private final int size;

        FontGlyphIcon(Font font, int codepoint, int size) {
            this.font = font;
            this.codepoint = codepoint;
            this.size = size;
        }

        @Override public int getIconWidth()  { return size; }
        @Override public int getIconHeight() { return size; }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                    RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(font);
                // Pick up host foreground so the icon re-tints with
                // the theme. Falls back to black if no host was given.
                Color fg = (c != null) ? c.getForeground() : Color.BLACK;
                if (fg != null) g2.setColor(fg);
                String glyph = new String(Character.toChars(codepoint));
                FontMetrics fm = g2.getFontMetrics(font);
                int gw = fm.charWidth(codepoint);
                int gh = fm.getAscent() - fm.getDescent();
                // Centre inside the icon's declared bounding box.
                int gx = x + (size - gw) / 2;
                int gy = y + (size + gh) / 2 - 1;
                g2.drawString(glyph, gx, gy);
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * Fallback icon that renders one JDK-built-in Unicode glyph.
     * Kept minimal + character-based so it works with whatever
     * font the JDK ships with -- no dependency on a specific
     * icon font.
     */
    static final class UnicodeFallbackIcon implements Icon {
        private final char glyph;
        private final int size;

        UnicodeFallbackIcon(char glyph, int size) {
            this.glyph = glyph;
            this.size = size;
        }

        @Override public int getIconWidth()  { return size; }
        @Override public int getIconHeight() { return size; }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                    RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                // Size the glyph to fit the icon box; SansSerif is
                // present on every JDK and has broad Unicode coverage
                // for the fallback characters we chose.
                Font f = new Font(Font.SANS_SERIF, Font.PLAIN, (int) (size * 0.9f));
                g2.setFont(f);
                Color fg = (c != null) ? c.getForeground() : Color.BLACK;
                if (fg != null) g2.setColor(fg);
                FontMetrics fm = g2.getFontMetrics(f);
                int gw = fm.charWidth(glyph);
                int gh = fm.getAscent() - fm.getDescent();
                int gx = x + (size - gw) / 2;
                int gy = y + (size + gh) / 2 - 1;
                g2.drawString(String.valueOf(glyph), gx, gy);
            } finally {
                g2.dispose();
            }
        }
    }
}
