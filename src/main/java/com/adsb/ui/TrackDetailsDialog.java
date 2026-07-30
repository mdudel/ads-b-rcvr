package com.adsb.ui;

import com.adsb.enrichment.Enrichment;
import com.adsb.enrichment.EnrichmentResolver;
import com.adsb.model.AdsbTrack;
import com.adsb.model.AircraftStateStore;
import com.adsb.model.EmitterCategoryLabel;
import com.adsb.model.IcaoCountryRegistry;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Floating, non-modal popup showing every metadata field we have on
 * one aircraft: the model's live ADS-B state (position, altitude,
 * heading, speed, squawk, emergency, ...) and the enrichment
 * resolver's external metadata (registration, type, manufacturer,
 * model, operator).
 *
 * <p>Design (Marty 2026-07-30 15:01 UTC, issue #15):
 * <ul>
 *   <li>Non-modal so the operator can compare multiple aircraft
 *       side-by-side.</li>
 *   <li>One dialog per ICAO24 -- clicking a track that already has
 *       an open dialog raises the existing one rather than opening
 *       a duplicate. Tracked in a static WeakHashMap keyed on the
 *       owner window so multi-window setups don't leak.</li>
 *   <li>Live refresh on a 500ms Swing timer: position, altitude, and
 *       age tick along with the underlying ADS-B updates. Timer
 *       shuts down when the dialog closes.</li>
 *   <li>Enrichment listener wired so if the async API lookup lands
 *       after the dialog opens, Reg/Type/Operator/Manufacturer/Model
 *       populate without needing a manual refresh.</li>
 *   <li>Emergency indicator: the icon in the title bar and the
 *       Squawk field flip to bold red when
 *       {@link AdsbTrack#isEmergency()} is true.</li>
 * </ul>
 */
public final class TrackDetailsDialog extends JDialog {

    /**
     * Per-owner-window map of ICAO -&gt; open dialog. WeakHashMap on
     * the owner key so closing the main window lets the entry GC
     * cleanly (dialogs go with their parent). Package-private for
     * testability.
     */
    private static final Map<Window, Map<String, TrackDetailsDialog>> OPEN =
            new WeakHashMap<>();

    /**
     * Open (or raise, if already open) the details dialog for the
     * given ICAO. Safe to call from any thread; marshals to the EDT.
     */
    public static void showFor(Window owner, String icaoHex,
                               AircraftStateStore store,
                               EnrichmentResolver enrichment) {
        if (icaoHex == null || icaoHex.isBlank() || store == null) return;
        String key = icaoHex.toUpperCase();
        SwingUtilities.invokeLater(() -> {
            Map<String, TrackDetailsDialog> perOwner = OPEN.computeIfAbsent(
                    owner, k -> new HashMap<>());
            TrackDetailsDialog existing = perOwner.get(key);
            if (existing != null && existing.isDisplayable()) {
                existing.toFront();
                existing.requestFocus();
                return;
            }
            TrackDetailsDialog d = new TrackDetailsDialog(owner, key, store, enrichment);
            perOwner.put(key, d);
            d.setVisible(true);
        });
    }

    // ------------------------------------------------------------------

    private final String icaoHex;
    private final AircraftStateStore store;
    private final EnrichmentResolver enrichment;

    // Field labels updated by refresh() on every timer tick.
    private final JLabel valCallsign      = new JLabel();
    private final JLabel valCountry       = new JLabel();
    private final JLabel valRegistration  = new JLabel();
    private final JLabel valTypeCode      = new JLabel();
    private final JLabel valManufacturer  = new JLabel();
    private final JLabel valModel         = new JLabel();
    private final JLabel valOperator      = new JLabel();
    private final JLabel valOperatorIcao  = new JLabel();
    private final JLabel valCategory      = new JLabel();
    private final JLabel valSquawk        = new JLabel();
    private final JLabel valLatLon        = new JLabel();
    private final JLabel valAltBaro       = new JLabel();
    private final JLabel valAltGeom       = new JLabel();
    private final JLabel valSpeed         = new JLabel();
    private final JLabel valTrack         = new JLabel();
    private final JLabel valVerticalRate  = new JLabel();
    private final JLabel valOnGround      = new JLabel();
    private final JLabel valEmergency     = new JLabel();
    private final JLabel valLastSeen      = new JLabel();
    private final JLabel valAge           = new JLabel();

    private final Timer refreshTimer;

    private TrackDetailsDialog(Window owner, String icaoHex,
                               AircraftStateStore store,
                               EnrichmentResolver enrichment) {
        super(owner);
        this.icaoHex    = icaoHex;
        this.store      = store;
        this.enrichment = enrichment;

        setTitle("Track  " + icaoHex);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setResizable(true);

        JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 4, 3, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill   = GridBagConstraints.HORIZONTAL;

        int y = 0;
        y = section(content, gc, y, "Identity");
        y = row(content, gc, y, "ICAO24:",       new JLabel(icaoHex));
        y = row(content, gc, y, "Callsign:",     valCallsign);
        y = row(content, gc, y, "Country:",      valCountry);
        y = row(content, gc, y, "Registration:", valRegistration);
        y = row(content, gc, y, "Type:",         valTypeCode);
        y = row(content, gc, y, "Manufacturer:", valManufacturer);
        y = row(content, gc, y, "Model:",        valModel);
        y = row(content, gc, y, "Operator:",     valOperator);
        y = row(content, gc, y, "Operator ICAO:", valOperatorIcao);
        y = row(content, gc, y, "Category:",     valCategory);
        y = row(content, gc, y, "Squawk:",       valSquawk);
        y = row(content, gc, y, "Emergency:",    valEmergency);

        y = section(content, gc, y, "Position");
        y = row(content, gc, y, "Lat / Lon:",    valLatLon);
        y = row(content, gc, y, "Altitude (baro):", valAltBaro);
        y = row(content, gc, y, "Altitude (geom):", valAltGeom);
        y = row(content, gc, y, "Ground speed:", valSpeed);
        y = row(content, gc, y, "Track:",        valTrack);
        y = row(content, gc, y, "Vertical rate:", valVerticalRate);
        y = row(content, gc, y, "On ground:",    valOnGround);

        y = section(content, gc, y, "Timing");
        y = row(content, gc, y, "Last seen:",    valLastSeen);
        y = row(content, gc, y, "Age:",          valAge);

        // Spring at the bottom so widgets stay top-anchored on resize.
        gc.gridx = 0; gc.gridy = y; gc.gridwidth = 2; gc.weighty = 1.0;
        gc.fill = GridBagConstraints.BOTH;
        content.add(Box.createVerticalGlue(), gc);

        setContentPane(new JPanel(new BorderLayout()) {{
            add(content, BorderLayout.CENTER);
        }});

        pack();
        // Reasonable minimum so long enrichment strings don't collapse the layout.
        setMinimumSize(new Dimension(360, getHeight()));
        setLocationRelativeTo(owner);
        // Cascade a bit if we're stacking multiple on the same owner so
        // they don't perfectly overlap.
        cascade(owner);

        // Prime the labels immediately, then tick every 500ms.
        refresh();
        this.refreshTimer = new Timer(500, e -> refresh());
        refreshTimer.setRepeats(true);
        refreshTimer.start();

        // Wire enrichment listener so async API completion pops the
        // fields into view without waiting on the next timer tick.
        if (enrichment != null) {
            enrichment.addListener(en -> {
                if (en != null && icaoHex.equalsIgnoreCase(en.icaoHex())) {
                    SwingUtilities.invokeLater(this::refresh);
                }
            });
        }

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                refreshTimer.stop();
                Map<String, TrackDetailsDialog> perOwner = OPEN.get(owner);
                if (perOwner != null) perOwner.remove(icaoHex);
            }
        });
    }

    private static int section(JPanel content, GridBagConstraints gc, int y, String title) {
        JLabel h = new JLabel("\u2500 " + title + " \u2500");
        h.setFont(h.getFont().deriveFont(Font.BOLD));
        gc.gridx = 0; gc.gridy = y; gc.gridwidth = 2;
        content.add(h, gc);
        gc.gridwidth = 1;
        return y + 1;
    }

    private static int row(JPanel content, GridBagConstraints gc, int y,
                           String key, Component value) {
        JLabel k = new JLabel(key);
        gc.gridx = 0; gc.gridy = y; gc.weightx = 0;
        content.add(k, gc);
        gc.gridx = 1; gc.weightx = 1;
        content.add(value, gc);
        return y + 1;
    }

    /** Static per-owner counter for cascade offset -- purely cosmetic. */
    private static final Map<Window, Integer> CASCADE_COUNT = new WeakHashMap<>();

    private void cascade(Window owner) {
        if (owner == null) return;
        int n = CASCADE_COUNT.merge(owner, 1, Integer::sum) - 1;
        int step = 24;
        setLocation(getX() + (n % 8) * step, getY() + (n % 8) * step);
    }

    /**
     * Refresh every value label from the live store + enrichment.
     * Cheap even at 500ms cadence: ConcurrentHashMap.get + a handful
     * of formatters. Runs on the EDT (Swing Timer callback).
     */
    private void refresh() {
        AdsbTrack t = store.get(icaoHex);
        if (t == null) {
            // Track has been evicted (aged out). Grey the labels out
            // but leave the dialog open so the operator can see what
            // it looked like last; the age field will keep ticking.
            valCallsign.setText("(track evicted)");
            valCallsign.setForeground(Color.GRAY);
            return;
        }

        setText(valCallsign,     safeStr(t.callsign()));
        setText(valCountry,      IcaoCountryRegistry.countryFor(icaoHex));
        setText(valCategory,     labelFor(t.emitterCategory()));
        setText(valSquawk,       safeStr(t.squawk()));

        // Emergency: bold red on the squawk row + the dedicated row.
        boolean emergency = t.isEmergency();
        Color emergencyRed = new Color(0xC0, 0x39, 0x2B);
        Color labelFg = defaultLabelForeground();
        valSquawk.setForeground(emergency ? emergencyRed : labelFg);
        valSquawk.setFont(getFont().deriveFont(emergency ? Font.BOLD : Font.PLAIN));
        if (emergency) {
            valEmergency.setText(emergencyLabel(t));
            valEmergency.setForeground(emergencyRed);
            valEmergency.setFont(getFont().deriveFont(Font.BOLD));
        } else {
            setText(valEmergency, "no");
            valEmergency.setForeground(labelFg);
            valEmergency.setFont(getFont().deriveFont(Font.PLAIN));
        }

        // Enrichment (may still be resolving; blank stays blank).
        Enrichment e = (enrichment == null)
                ? null
                : enrichment.lookup(icaoHex).orElse(null);
        setText(valRegistration,  e == null ? null : e.registration());
        setText(valTypeCode,      e == null ? null : e.typeCode());
        setText(valManufacturer,  e == null ? null : e.manufacturer());
        setText(valModel,         e == null ? null : e.model());
        setText(valOperator,      e == null ? null : e.operator());
        setText(valOperatorIcao,  e == null ? null : e.operatorIcao());

        // Position group
        if (t.hasPosition()) {
            setText(valLatLon, String.format(Locale.ROOT, "%.5f, %.5f",
                    t.latitude(), t.longitude()));
        } else {
            setText(valLatLon, null);
        }
        setText(valAltBaro, fmtAlt(t.altBaroFt()));
        setText(valAltGeom, fmtAlt(t.altGeomFt()));
        setText(valSpeed,   Double.isNaN(t.groundSpeedKts()) ? null
                : String.format(Locale.ROOT, "%d kt", Math.round(t.groundSpeedKts())));
        setText(valTrack,   Double.isNaN(t.trackDeg()) ? null
                : String.format(Locale.ROOT, "%d\u00b0", Math.round(t.trackDeg())));
        setText(valVerticalRate, t.verticalRateFpm() == Integer.MIN_VALUE ? null
                : (t.verticalRateFpm() >= 0 ? "+" : "")
                        + t.verticalRateFpm() + " fpm");
        setText(valOnGround, t.onGround() ? "yes" : "no");

        // Timing
        setText(valLastSeen, t.lastSeen().toString());
        long ageSec = Duration.between(t.lastSeen(), Instant.now()).getSeconds();
        setText(valAge, ageSec + " s");
    }

    private static void setText(JLabel lbl, String s) {
        lbl.setText(s == null || s.isBlank() ? "\u2014" : s);   // em-dash for empty
    }

    /** L&F default so we don't hard-code a colour that reads badly on dark themes. */
    private static Color defaultLabelForeground() {
        Color c = javax.swing.UIManager.getColor("Label.foreground");
        return c == null ? Color.BLACK : c;
    }

    private static String safeStr(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String labelFor(String code) {
        String label = EmitterCategoryLabel.labelFor(code);
        return (label == null) ? null : label + "  (" + code + ")";
    }

    private static String fmtAlt(int ft) {
        return ft == Integer.MIN_VALUE ? null
                : String.format(Locale.ROOT, "%,d ft", ft);
    }

    private static String emergencyLabel(AdsbTrack t) {
        // Squawk-based:
        if ("7500".equals(t.squawk())) return "yes -- 7500 HIJACK";
        if ("7600".equals(t.squawk())) return "yes -- 7600 RADIO";
        if ("7700".equals(t.squawk())) return "yes -- 7700 GENERAL";
        if (t.emergencyStatus() > 0) {
            return "yes -- ADS-B status " + t.emergencyStatus();
        }
        return "yes";
    }
}
