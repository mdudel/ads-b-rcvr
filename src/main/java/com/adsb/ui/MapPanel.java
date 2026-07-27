package com.adsb.ui;

import com.adsb.model.AdsbTrack;
import com.adsb.model.AircraftStateStore;

import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.OSMTileFactoryInfo;
import org.jxmapviewer.input.CenterMapListener;
import org.jxmapviewer.input.PanMouseInputListener;
import org.jxmapviewer.input.ZoomMouseWheelListenerCursor;
import org.jxmapviewer.painter.Painter;
import org.jxmapviewer.viewer.DefaultTileFactory;
import org.jxmapviewer.viewer.GeoPosition;
import org.jxmapviewer.viewer.TileFactoryInfo;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.List;

/**
 * The always-visible map surface. Wraps a {@link JXMapViewer} with
 * an OSM tile source and a custom painter that draws every currently-
 * known aircraft as a heading-rotated glyph coloured by altitude band.
 *
 * <p>The painter reads the latest snapshot list from
 * {@link AircraftStateStore#allSnapshots()} on every repaint, so the
 * map picture always matches the store even if updates arrive faster
 * than the painter can keep up.
 *
 * <p>Repaint cadence: state-store updates trigger a coalesced repaint
 * via a 250 ms {@link Timer} (four frames per second is enough for
 * ADS-B tracks; painting on every update would spin the EDT with
 * ~40 aircraft x 2 Hz = 80 repaints/second).
 */
public final class MapPanel extends JPanel {

    private final JXMapViewer map;
    private final AircraftStateStore store;
    private volatile boolean repaintPending;
    private volatile boolean hasAutoFit;
    private final Timer repaintCoalescer;

    public MapPanel(AircraftStateStore store) {
        super(new BorderLayout());
        this.store = store;

        this.map = new JXMapViewer();

        // OSM standard tiles.
        //
        // Two things must be right or every tile fetch fails silently
        // and the map shows only the JXMapViewer "no tiles loaded"
        // clock-glyph placeholders (exactly what Marty saw at 2026-07-27
        // 13:48 UTC):
        //   1. HTTPS. The no-arg OSMTileFactoryInfo() ctor uses
        //      http://tile.openstreetmap.org which OSM blocks. We pass
        //      the https base URL to the (name, baseUrl) overload.
        //   2. A real User-Agent header. OSM's tile-usage policy blocks
        //      the Java default 'Java/17.x.x'.
        //      AbstractTileFactory.setUserAgent() flows into every HTTP
        //      request.
        //
        // Ref: https://operations.osmfoundation.org/policies/tiles/
        //
        // If a corporate/private tile server is swapped in later, both
        // knobs move together (baseUrl + user-agent).
        TileFactoryInfo info = new OSMTileFactoryInfo(
                "OpenStreetMap", "https://tile.openstreetmap.org");
        DefaultTileFactory tf = new DefaultTileFactory(info);
        tf.setThreadPoolSize(8);
        tf.setUserAgent("ads-b-rcvr/0.2 (+https://github.com/mdudel/ads-b-rcvr)");
        map.setTileFactory(tf);

        // Wire the standard JXMapViewer mouse controls (pan by drag, zoom by
        // wheel, double-click centres).
        var pan = new PanMouseInputListener(map);
        map.addMouseListener(pan);
        map.addMouseMotionListener(pan);
        map.addMouseListener(new CenterMapListener(map));
        map.addMouseWheelListener(new ZoomMouseWheelListenerCursor(map));

        // Sensible default: zoom out enough to see a continent, centre on
        // Frankfurt. The first positioned snapshot re-centres on the mean
        // of currently-positioned tracks so the operator sees their live
        // traffic without having to pan.
        map.setZoom(6);
        map.setAddressLocation(new GeoPosition(50.0, 9.0));

        map.setOverlayPainter(new AircraftPainter());
        setBorder(BorderFactory.createEmptyBorder());
        add(map, BorderLayout.CENTER);

        // Coalesce: many state-store updates in a burst collapse into one
        // repaint every 250 ms.
        this.repaintCoalescer = new Timer(250, e -> {
            if (repaintPending) {
                repaintPending = false;
                map.repaint();
            }
        });
        repaintCoalescer.setRepeats(true);
        repaintCoalescer.start();

        store.addListener(snap -> {
            repaintPending = true;
            // Auto-fit on the FIRST positioned snapshot only. After that
            // we leave the operator's chosen view alone; otherwise every
            // new frame would yank the map away from whatever they had
            // panned or zoomed to.
            if (!hasAutoFit && snap.hasPosition()) {
                hasAutoFit = true;
                SwingUtilities.invokeLater(() -> fitToTracks(11));
            }
        });
    }

    /** Centre the map on the given aircraft snapshot (used by the table row-click). */
    public void centerOn(AdsbTrack t) {
        if (t == null || !t.hasPosition()) return;
        SwingUtilities.invokeLater(() ->
                map.setAddressLocation(new GeoPosition(t.latitude(), t.longitude())));
    }

    /**
     * Re-centre + set zoom so the map roughly encompasses every
     * currently-positioned track. Rough on purpose: we snap to a fixed
     * zoom and centre on the mean position, rather than solving the
     * tight-fit bounding problem -- the operator will fine-tune with
     * mouse wheel and pan.
     */
    private void fitToTracks(int zoom) {
        double sumLat = 0, sumLon = 0;
        int n = 0;
        for (AdsbTrack t : store.allSnapshots()) {
            if (!t.hasPosition()) continue;
            sumLat += t.latitude();
            sumLon += t.longitude();
            n++;
        }
        if (n == 0) return;
        map.setZoom(zoom);
        map.setAddressLocation(new GeoPosition(sumLat / n, sumLon / n));
    }

    /** Painter that reads the store on demand and draws every positioned aircraft. */
    private final class AircraftPainter implements Painter<JXMapViewer> {
        @Override
        public void paint(Graphics2D g, JXMapViewer viewer, int width, int height) {
            g = (Graphics2D) g.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);

                Rectangle vp = viewer.getViewportBounds();
                Font labelFont = g.getFont().deriveFont(Font.PLAIN, 10f);
                g.setFont(labelFont);

                for (AdsbTrack t : snapshotList()) {
                    if (!t.hasPosition()) continue;
                    Point2D p = viewer.getTileFactory().geoToPixel(
                            new GeoPosition(t.latitude(), t.longitude()),
                            viewer.getZoom());
                    int x = (int) (p.getX() - vp.x);
                    int y = (int) (p.getY() - vp.y);
                    if (x < -20 || y < -20 || x > width + 20 || y > height + 20) continue;

                    double heading = Double.isNaN(t.trackDeg()) ? 0.0 : t.trackDeg();
                    Color c = altitudeColour(t.preferredAltFt());
                    drawAircraftGlyph(g, x, y, heading, c);

                    // Label: callsign if known, else ICAO hex.
                    String label = t.callsign() != null && !t.callsign().isBlank()
                            ? t.callsign().trim()
                            : t.icaoHex();
                    g.setColor(Color.BLACK);
                    g.drawString(label, x + 8, y - 4);
                }
            } finally {
                g.dispose();
            }
        }
    }

    /** Snapshot the store's current tracks. See {@link AircraftStateStore#allSnapshots()}. */
    private List<AdsbTrack> snapshotList() {
        return store.allSnapshots();
    }

    // ------------------------------------------------------------------
    // glyph rendering
    // ------------------------------------------------------------------

    /**
     * Draw a filled triangle pointing towards {@code headingDeg}
     * (0 deg = north, clockwise), 14 px tall, centred on
     * {@code (cx, cy)}.
     */
    private static void drawAircraftGlyph(Graphics2D g, int cx, int cy,
                                           double headingDeg, Color fill) {
        AffineTransform old = g.getTransform();
        try {
            g.translate(cx, cy);
            g.rotate(Math.toRadians(headingDeg));
            Path2D.Double p = new Path2D.Double();
            p.moveTo( 0, -9);
            p.lineTo( 6,  7);
            p.lineTo( 0,  4);
            p.lineTo(-6,  7);
            p.closePath();
            g.setColor(fill);
            g.fill(p);
            g.setColor(Color.BLACK);
            g.draw(p);
        } finally {
            g.setTransform(old);
        }
    }

    /**
     * Colour band by altitude. Nothing scientific -- just a visual cue:
     * low aircraft (arriving/departing) tend to be more interesting to
     * an operator watching a specific field, high aircraft (cruising)
     * fade back.
     */
    private static Color altitudeColour(int altFt) {
        if (altFt == Integer.MIN_VALUE) return Color.LIGHT_GRAY;
        if (altFt <   5000) return new Color(0xC0, 0x39, 0x2B);   // red    - low
        if (altFt <  10000) return new Color(0xE6, 0x7E, 0x22);   // orange
        if (altFt <  20000) return new Color(0xF1, 0xC4, 0x0F);   // amber
        if (altFt <  30000) return new Color(0x27, 0xAE, 0x60);   // green
        if (altFt <  40000) return new Color(0x29, 0x80, 0xB9);   // blue
        return                     new Color(0x8E, 0x44, 0xAD);   // purple - high cruise
    }
}
