package com.adsb.ui;

import com.adsb.model.AdsbTrack;
import com.adsb.model.AircraftStateStore;
import com.adsb.model.EmitterCategoryLabel;
import com.adsb.model.IcaoCountryRegistry;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tracks tab: a scrollable {@link JTable} of every aircraft currently
 * held by the {@link AircraftStateStore}. Columns: ICAO, callsign,
 * lat, lon, altitude (ft), speed (kt), heading (deg), age (s since last-seen).
 *
 * <p>Selecting a row invokes an optional {@link Consumer} -- the shell
 * wires this to {@link MapPanel#centerOn(AdsbTrack)}.
 *
 * <p>Refresh strategy: the model rebuilds its row list from the store
 * on a fixed 500 ms coalescing timer. Simpler than diff-based row
 * updates, cheap for the expected size (&lt; 200 aircraft in view).
 */
public final class TracksPanel extends JPanel {

    private final AircraftStateStore store;
    private final Model              model;
    private final JTable             table;

    public TracksPanel(AircraftStateStore store, Consumer<AdsbTrack> onRowSelected) {
        super(new BorderLayout());
        this.store = store;
        this.model = new Model();
        this.table = new JTable(model);
        table.setRowSorter(new TableRowSorter<>(model));
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setAutoCreateRowSorter(false); // we set our own above

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int viewRow = table.getSelectedRow();
            if (viewRow < 0) return;
            int modelRow = table.convertRowIndexToModel(viewRow);
            AdsbTrack t = model.rowAt(modelRow);
            if (t != null && onRowSelected != null) onRowSelected.accept(t);
        });

        // Fade + metadata renderer: mirror the map's per-track alpha
        // in the table by blending the row's foreground toward the
        // table background, AND highlight emergency squawks in red
        // bold. Rows past REMOVE_AT are filtered in reload() so they
        // never reach the renderer. (Marty 2026-07-30 12:47 UTC fade;
        // 13:10 UTC metadata columns.)
        DefaultTableCellRenderer fadeRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(
                        tbl, value, isSelected, hasFocus, row, column);
                int modelRow = tbl.convertRowIndexToModel(row);
                AdsbTrack t = model.rowAt(modelRow);
                if (c instanceof JLabel lbl) {
                    // Reset to defaults so a previous emergency-row's
                    // bold/red styling doesn't leak into a normal row
                    // reused by the same renderer instance.
                    lbl.setFont(tbl.getFont());
                    Color baseFg = isSelected ? tbl.getSelectionForeground() : tbl.getForeground();
                    lbl.setForeground(baseFg);

                    if (t != null) {
                        // Emergency: bold red foreground for the whole
                        // row so a distressed aircraft is unmissable.
                        // Applies to ALL cells in the row so scanning
                        // works regardless of which column the eye
                        // lands on first.
                        if (t.isEmergency() && !isSelected) {
                            lbl.setForeground(new Color(0xC0, 0x39, 0x2B));
                            lbl.setFont(tbl.getFont().deriveFont(java.awt.Font.BOLD));
                        }

                        // Fade: blend row fg toward table bg at
                        // (1 - alpha). Skip on selection so a
                        // clicked faded row stays readable.
                        if (!isSelected) {
                            float alpha = AircraftStateStore.fadeAlphaFor(t, Instant.now());
                            if (alpha < 1.0f) {
                                Color bg = tbl.getBackground();
                                lbl.setForeground(blend(lbl.getForeground(), bg, alpha));
                            }
                        }
                    }
                }
                return c;
            }
        };
        // Install the fade renderer for every column type currently in
        // the model. New column types must be added here too.
        table.setDefaultRenderer(Object.class,  fadeRenderer);
        table.setDefaultRenderer(String.class,  fadeRenderer);
        table.setDefaultRenderer(Double.class,  fadeRenderer);
        table.setDefaultRenderer(Integer.class, fadeRenderer);
        table.setDefaultRenderer(Long.class,    fadeRenderer);

        add(new JScrollPane(table), BorderLayout.CENTER);

        // 500 ms refresh: rebuilds the row list from the store snapshot.
        Timer refresh = new Timer(500, e -> reload());
        refresh.setRepeats(true);
        refresh.start();
        reload();
    }

    private void reload() {
        // Filter out rows past the eviction threshold so the table
        // doesn't briefly show a fully-faded ghost row before the
        // periodic eviction sweep drops it from the store.
        Instant now = Instant.now();
        List<AdsbTrack> snap = new ArrayList<>();
        for (AdsbTrack t : store.allSnapshots()) {
            long age = Duration.between(t.lastSeen(), now).toMillis();
            if (age < AircraftStateStore.REMOVE_AT_MS) snap.add(t);
        }
        SwingUtilities.invokeLater(() -> model.setRows(snap));
    }

    /**
     * Blend {@code fg} toward {@code bg} by (1 - alpha). alpha=1 returns
     * fg unchanged; alpha=0 returns bg. Used to fade table row text
     * toward the table background as tracks age out.
     */
    private static Color blend(Color fg, Color bg, float alpha) {
        float inv = 1.0f - alpha;
        int r = (int) (fg.getRed()   * alpha + bg.getRed()   * inv);
        int g = (int) (fg.getGreen() * alpha + bg.getGreen() * inv);
        int b = (int) (fg.getBlue()  * alpha + bg.getBlue()  * inv);
        return new Color(
                Math.max(0, Math.min(255, r)),
                Math.max(0, Math.min(255, g)),
                Math.max(0, Math.min(255, b)));
    }

    // ------------------------------------------------------------------
    // model
    // ------------------------------------------------------------------

    private static final class Model extends AbstractTableModel {
        // Column layout is hand-kept in sync between COLS, TYPES, and
        // the switch in getValueAt. Add a column: update ALL three.
        // 2026-07-30 13:10 UTC: added Country, Category, Squawk, V/S
        // between Callsign and Lat. Order chosen so the identity
        // group (ICAO/Callsign/Country/Category/Squawk) sits together
        // on the left, then geometry (Lat/Lon), then altitude/motion.
        private static final String[] COLS = {
                "ICAO", "Callsign", "Country", "Category", "Squawk",
                "Lat", "Lon", "Alt (ft)", "V/S (fpm)",
                "Speed (kt)", "Track", "Age (s)"
        };
        private static final Class<?>[] TYPES = {
                String.class, String.class, String.class, String.class, String.class,
                Double.class, Double.class, String.class, Integer.class,
                Integer.class, Integer.class, Long.class
        };

        private List<AdsbTrack> rows = new ArrayList<>();

        void setRows(List<AdsbTrack> r) {
            this.rows = r;
            fireTableDataChanged();
        }

        AdsbTrack rowAt(int i) {
            return (i >= 0 && i < rows.size()) ? rows.get(i) : null;
        }

        @Override public int getRowCount()               { return rows.size(); }
        @Override public int getColumnCount()            { return COLS.length; }
        @Override public String getColumnName(int c)     { return COLS[c]; }
        @Override public Class<?> getColumnClass(int c)  { return TYPES[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            AdsbTrack t = rows.get(r);
            switch (c) {
                case 0: return t.icaoHex();
                case 1:
                    return (t.callsign() != null && !t.callsign().isBlank())
                            ? t.callsign().trim() : "";
                case 2:
                    // Country of registration (pure algorithmic lookup
                    // via IcaoCountryRegistry; no network). Null when
                    // the ICAO24 falls in an unallocated / reserved
                    // range -- render as empty cell.
                    return IcaoCountryRegistry.countryFor(t.icaoHex());
                case 3:
                    // Emitter category as a human label. Null before
                    // the first TC 1-4 identification message arrives.
                    return EmitterCategoryLabel.labelFor(t.emitterCategory());
                case 4:
                    // Squawk code (Mode-A). Empty when not yet seen.
                    return (t.squawk() != null && !t.squawk().isBlank())
                            ? t.squawk().trim() : "";
                case 5:
                    // Lat as Double so the JTable's default sort is numeric,
                    // not lexicographic. Rounded to 4 decimal places -- narrow
                    // column while preserving ~11 m at the equator.
                    return t.hasPosition()
                            ? Double.valueOf(round4(t.latitude())) : null;
                case 6:
                    return t.hasPosition()
                            ? Double.valueOf(round4(t.longitude())) : null;
                case 7:
                    // Altitude: prepend "GND" when the aircraft self-
                    // reports on-ground (TC 5-8 surface position); makes
                    // taxiing traffic obvious in a scan. Column stays
                    // String-typed so we can carry the marker; sort will
                    // be lexicographic but that's acceptable given the
                    // typical value range.
                    if (t.onGround()) {
                        int alt = t.preferredAltFt();
                        return alt != Integer.MIN_VALUE
                                ? ("GND " + alt) : "GND";
                    }
                    return (t.preferredAltFt() != Integer.MIN_VALUE)
                            ? Integer.toString(t.preferredAltFt()) : null;
                case 8:
                    // Vertical speed (fpm). Positive = climb, negative =
                    // descent, 0 = level. Empty before any TC 19 frame.
                    return (t.verticalRateFpm() != Integer.MIN_VALUE)
                            ? Integer.valueOf(t.verticalRateFpm()) : null;
                case 9:
                    return Double.isNaN(t.groundSpeedKts())
                            ? null : Integer.valueOf((int) Math.round(t.groundSpeedKts()));
                case 10:
                    return Double.isNaN(t.trackDeg())
                            ? null : Integer.valueOf((int) Math.round(t.trackDeg()));
                case 11:
                    return Long.valueOf(
                            Duration.between(t.lastSeen(), Instant.now()).getSeconds());
                default: return null;
            }
        }

        /** Round to 4 dp (~11 m). Doubles carry through the JTable's
         *  own renderer, which respects the JVM locale for display. */
        private static double round4(double v) {
            return Math.round(v * 10_000.0) / 10_000.0;
        }
    }
}
