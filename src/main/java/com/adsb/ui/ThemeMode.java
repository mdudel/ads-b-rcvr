package com.adsb.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Window;

/**
 * Two-state look-and-feel mode for the ads-b-rcvr Swing UI.
 * Modelled after tmsweb-client's {@code ThemeMode} (three-state DAY/
 * DARK/NIGHT for cockpit use) but simplified to the two modes Marty
 * asked for on 2026-07-27 14:28 UTC: light + dark. Cycle order:
 * LIGHT &rarr; DARK &rarr; LIGHT.
 *
 * <p>Base look-and-feel is FlatLaf on both. LIGHT uses
 * {@link FlatLightLaf}, DARK uses {@link FlatDarkLaf}. No custom
 * palette layered on top -- FlatLaf's defaults already match the
 * clean chrome tmsweb-client visual, and adding an aviation palette
 * without a real requirement would be over-engineering.
 *
 * <p>Persistence: canonical string form ({@code "light"} / {@code "dark"})
 * lives in {@code ~/.adsb-rcvr/adsb-rcvr.properties} under the key
 * {@code ui.themeMode}. Missing / unknown values default to LIGHT
 * (matches OS-default aesthetics on Windows 10/11).
 */
public enum ThemeMode {
    LIGHT,
    DARK;

    /**
     * Parse a persisted string form. Case-insensitive; unknown values
     * fall back to {@link #LIGHT}.
     */
    public static ThemeMode fromString(String s) {
        if (s == null) return LIGHT;
        return switch (s.trim().toLowerCase()) {
            case "dark"  -> DARK;
            case "light" -> LIGHT;
            default      -> LIGHT;
        };
    }

    /** Canonical lowercase string; round-trips with {@link #fromString}. */
    public String canonical() {
        return name().toLowerCase();
    }

    /** Next mode in the cycle: {@code LIGHT -> DARK -> LIGHT}. */
    public ThemeMode next() {
        return this == LIGHT ? DARK : LIGHT;
    }

    /**
     * Install the FlatLaf base for this mode and repaint every existing
     * top-level window so the change is visible immediately.
     *
     * <p>Safe to call from any thread; the actual UIManager mutation
     * and {@link SwingUtilities#updateComponentTreeUI} calls are
     * marshalled onto the EDT. Idempotent -- calling twice with the
     * same mode is a no-op-in-effect.
     *
     * <p>If FlatLaf's own setup throws (extremely rare; typically an
     * illegal-system-property environment), the exception is caught
     * and the JVM's previous L&F is preserved rather than leaving the
     * UI in a half-installed state.
     */
    public void apply() {
        Runnable applyOnEdt = () -> {
            try {
                switch (this) {
                    case LIGHT -> FlatLightLaf.setup();
                    case DARK  -> FlatDarkLaf.setup();
                }
            } catch (RuntimeException e) {
                System.err.println("[WARN] FlatLaf theme install failed: " + e);
                return;
            }
            for (Window w : Window.getWindows()) {
                SwingUtilities.updateComponentTreeUI(w);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) applyOnEdt.run();
        else                                       SwingUtilities.invokeLater(applyOnEdt);
    }
}
