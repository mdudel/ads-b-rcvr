package com.adsb.ui;

import com.adsb.model.AdsbTrack;
import com.adsb.model.AircraftStateStore;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
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

        add(new JScrollPane(table), BorderLayout.CENTER);

        // 500 ms refresh: rebuilds the row list from the store snapshot.
        Timer refresh = new Timer(500, e -> reload());
        refresh.setRepeats(true);
        refresh.start();
        reload();
    }

    private void reload() {
        List<AdsbTrack> snap = store.allSnapshots();
        SwingUtilities.invokeLater(() -> model.setRows(snap));
    }

    // ------------------------------------------------------------------
    // model
    // ------------------------------------------------------------------

    private static final class Model extends AbstractTableModel {
        // Column layout is hand-kept in sync between COLS, TYPES, and
        // the switch in getValueAt. Add a column: update all three.
        private static final String[] COLS = {
                "ICAO", "Callsign", "Lat", "Lon",
                "Alt (ft)", "Speed (kt)", "Track", "Age (s)"
        };
        private static final Class<?>[] TYPES = {
                String.class, String.class, Double.class, Double.class,
                Integer.class, Integer.class, Integer.class, Long.class
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
                    // Lat as Double so the JTable's default sort is numeric,
                    // not lexicographic. Rounded to 4 decimal places -- narrow
                    // column while preserving ~11 m at the equator.
                    return t.hasPosition()
                            ? Double.valueOf(round4(t.latitude())) : null;
                case 3:
                    return t.hasPosition()
                            ? Double.valueOf(round4(t.longitude())) : null;
                case 4:
                    return (t.preferredAltFt() != Integer.MIN_VALUE)
                            ? Integer.valueOf(t.preferredAltFt()) : null;
                case 5:
                    return Double.isNaN(t.groundSpeedKts())
                            ? null : Integer.valueOf((int) Math.round(t.groundSpeedKts()));
                case 6:
                    return Double.isNaN(t.trackDeg())
                            ? null : Integer.valueOf((int) Math.round(t.trackDeg()));
                case 7:
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
