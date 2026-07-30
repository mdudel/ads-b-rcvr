package com.adsb.ui;

import com.adsb.model.AdsbTrack;
import com.adsb.model.AircraftStateStore;

import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.input.CenterMapListener;
import org.jxmapviewer.input.PanMouseInputListener;
import org.jxmapviewer.input.ZoomMouseWheelListenerCursor;
import org.jxmapviewer.painter.Painter;
import org.jxmapviewer.viewer.DefaultTileFactory;
import org.jxmapviewer.viewer.GeoPosition;
import org.jxmapviewer.viewer.TileFactoryInfo;

import javax.swing.BorderFactory;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.List;

/**
 * Map surface + floating nav bezel. Wraps a {@link JXMapViewer} in a
 * {@link JLayeredPane} so the {@link MapNavPanel} (pan bezel + zoom
 * column) can float on top of the tiles at the upper-right without
 * stealing tile real estate.
 *
 * <p>Layered-pane pattern lifted verbatim from tmsweb-client's
 * {@code MapPanel.buildMapArea} at Marty's 2026-07-27 14:28 UTC
 * direction. Same {@code ComponentListener} trick to reposition both
 * children on resize (JLayeredPane has no automatic layout).
 *
 * <p><b>TileFactoryInfo shape</b> is unchanged from commit 0255ce1 --
 * see the class javadoc there for why we don't use
 * {@code OSMTileFactoryInfo}.
 */
public final class MapPanel extends JPanel {

    private static final String OSM_BASE_URL = "https://tile.openstreetmap.org";

    private final JXMapViewer map;
    private final AircraftStateStore store;
    private volatile boolean repaintPending;
    private volatile boolean hasAutoFit;
    private final Timer repaintCoalescer;
    private volatile float mapBrightness = 1.0f;  // 0.0=black, 1.0=normal

    public MapPanel(AircraftStateStore store) {
        super(new BorderLayout());
        this.store = store;

        this.map = new JXMapViewer();

        // Custom TileFactoryInfo (see class javadoc + commit 0255ce1).
        TileFactoryInfo info = new TileFactoryInfo(
                /*minZoom*/  0,
                /*maxZoom*/  17,
                /*totalZoom*/17,
                /*tileSize*/ 256,
                /*xr2l*/     true,
                /*yt2b*/     true,
                /*baseURL*/  OSM_BASE_URL,
                "x", "y", "z") {
            @Override
            public String getTileUrl(int x, int y, int zoom) {
                int z = getTotalMapZoom() - zoom;
                return OSM_BASE_URL + "/" + z + "/" + x + "/" + y + ".png";
            }
        };
        DefaultTileFactory tf = new DefaultTileFactory(info);
        tf.setThreadPoolSize(8);
        map.setTileFactory(tf);

        // Standard mouse controls.
        var pan = new PanMouseInputListener(map);
        map.addMouseListener(pan);
        map.addMouseMotionListener(pan);
        map.addMouseListener(new CenterMapListener(map));
        map.addMouseWheelListener(new ZoomMouseWheelListenerCursor(map));

        // Sensible default view. First positioned snapshot re-centres.
        map.setZoom(11);
        map.setAddressLocation(new GeoPosition(50.0, 9.0));

        map.setOverlayPainter(new AircraftPainter());
        setBorder(BorderFactory.createEmptyBorder());

        // Wrap map + nav in a layered pane. Nav floats upper-right.
        add(buildMapArea(), BorderLayout.CENTER);

        this.repaintCoalescer = new Timer(250, e -> {
            // Repaint if a listener flagged us OR any track is currently
            // inside its fade window (need continuous repaints for the
            // 30s alpha ramp to be smooth).
            if (repaintPending || anyTrackFading()) {
                repaintPending = false;
                map.repaint();
            }
        });
        repaintCoalescer.setRepeats(true);
        repaintCoalescer.start();

        store.addListener(snap -> {
            repaintPending = true;
            if (!hasAutoFit && snap.hasPosition()) {
                hasAutoFit = true;
                SwingUtilities.invokeLater(this::fitToTracks);
            }
        });
    }

    /**
     * Build a {@link JLayeredPane} that stacks:
     * <ul>
     *   <li>The map component on {@link JLayeredPane#DEFAULT_LAYER},
     *       filling the whole area.</li>
     *   <li>The {@link MapNavPanel} on
     *       {@link JLayeredPane#PALETTE_LAYER}, anchored upper-right
     *       with a 12 px inset.</li>
     * </ul>
     *
     * <p>JLayeredPane does NOT auto-layout its children; we install a
     * {@link ComponentAdapter#componentResized} handler to reposition
     * both on every resize. Same technique tmsweb-client uses.
     */
    private JLayeredPane buildMapArea() {
        final Component mapComp = map;
        final MapNavPanel nav = new MapNavPanel(map);

        final JLayeredPane layered = new JLayeredPane();
        layered.setLayout(null);
        layered.add(mapComp, JLayeredPane.DEFAULT_LAYER);
        layered.add(nav,     JLayeredPane.PALETTE_LAYER);

        layered.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                mapComp.setBounds(0, 0, layered.getWidth(), layered.getHeight());
                Dimension pref = nav.getPreferredSize();
                int inset = 12;
                int x = Math.max(inset, layered.getWidth() - pref.width - inset);
                nav.setBounds(x, inset, pref.width, pref.height);
                layered.revalidate();
            }
        });
        return layered;
    }

    /** Centre the map on the given aircraft snapshot. Used by the table row-click. */
    public void centerOn(AdsbTrack t) {
        if (t == null || !t.hasPosition()) return;
        SwingUtilities.invokeLater(() ->
                map.setAddressLocation(new GeoPosition(t.latitude(), t.longitude())));
    }

    /**
     * Set map brightness multiplier. 1.0 = normal (default), 0.0 = fully black.
     * Applied via {@link AlphaComposite} over the tile layer so the tiles appear
     * dimmed. Thread-safe; triggers a repaint.
     */
    public void setBrightness(float brightness) {
        this.mapBrightness = Math.max(0.0f, Math.min(1.0f, brightness));
        repaintPending = true;
    }

    /**
     * @return true when at least one track's age is in the fade
     *         window [FADE_START_MS, REMOVE_AT_MS). Used by the
     *         repaint coalescer to drive continuous repaints while
     *         any track is fading, without paying that cost when
     *         everyone is fresh.
     */
    private boolean anyTrackFading() {
        java.time.Instant now = java.time.Instant.now();
        for (AdsbTrack t : store.allSnapshots()) {
            long age = java.time.Duration.between(t.lastSeen(), now).toMillis();
            if (age >= AircraftStateStore.FADE_START_MS
                    && age < AircraftStateStore.REMOVE_AT_MS) {
                return true;
            }
        }
        return false;
    }

    /** Re-centre + zoom to roughly encompass currently-positioned tracks. */
    private void fitToTracks() {
        double sumLat = 0, sumLon = 0;
        int n = 0;
        for (AdsbTrack t : store.allSnapshots()) {
            if (!t.hasPosition()) continue;
            sumLat += t.latitude();
            sumLon += t.longitude();
            n++;
        }
        if (n == 0) return;
        map.setZoom(7);
        map.setAddressLocation(new GeoPosition(sumLat / n, sumLon / n));
    }

    // ------------------------------------------------------------------

    private final class AircraftPainter implements Painter<JXMapViewer> {
        @Override
        public void paint(Graphics2D g, JXMapViewer viewer, int width, int height) {
            g = (Graphics2D) g.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);

                // Apply brightness dimming to the tile layer beneath
                if (mapBrightness < 1.0f) {
                    Composite oldComp = g.getComposite();
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f - mapBrightness));
                    g.setColor(Color.BLACK);
                    g.fillRect(0, 0, width, height);
                    g.setComposite(oldComp);
                }

                Rectangle vp = viewer.getViewportBounds();
                Font labelFont = g.getFont().deriveFont(Font.PLAIN, 10f);
                g.setFont(labelFont);

                // Label colour follows the current theme so it reads
                // in both LIGHT (dark text on light tiles) and DARK
                // (light text on dark tiles). Fallback to black if the
                // L&F hasn't installed a Label.foreground yet.
                Color labelColour = javax.swing.UIManager.getColor("Label.foreground");
                if (labelColour == null) labelColour = Color.BLACK;

                java.time.Instant paintNow = java.time.Instant.now();
                Composite baseComposite = g.getComposite();

                for (AdsbTrack t : store.allSnapshots()) {
                    if (!t.hasPosition()) continue;

                    // Fade-out: 120s->1.0, 150s->0.0 linearly (Marty
                    // 2026-07-30 12:47 UTC). At or past REMOVE_AT the
                    // eviction sweep drops the row shortly; skip painting
                    // in the meantime so we don't render invisible pixels.
                    float alpha = AircraftStateStore.fadeAlphaFor(t, paintNow);
                    if (alpha <= 0.0f) continue;

                    Point2D p = viewer.getTileFactory().geoToPixel(
                            new GeoPosition(t.latitude(), t.longitude()),
                            viewer.getZoom());
                    int x = (int) (p.getX() - vp.x);
                    int y = (int) (p.getY() - vp.y);
                    if (x < -20 || y < -20 || x > width + 20 || y > height + 20) continue;

                    // Apply per-track alpha for glyph + trail + label so
                    // the whole track fades together as one unit.
                    if (alpha < 1.0f) {
                        g.setComposite(AlphaComposite.getInstance(
                                AlphaComposite.SRC_OVER, alpha));
                    } else {
                        g.setComposite(baseComposite);
                    }

                    Color c = altitudeColour(t.preferredAltFt());

                    // Draw trail first (behind the aircraft icon)
                    List<AircraftStateStore.TrailPoint> trail = store.getTrail(t.icaoHex());
                    if (trail.size() > 1) {
                        g.setColor(c);
                        for (int i = 1; i < trail.size(); i++) {
                            AircraftStateStore.TrailPoint p1 = trail.get(i - 1);
                            AircraftStateStore.TrailPoint p2 = trail.get(i);
                            Point2D px1 = viewer.getTileFactory().geoToPixel(
                                    new GeoPosition(p1.lat(), p1.lon()), viewer.getZoom());
                            Point2D px2 = viewer.getTileFactory().geoToPixel(
                                    new GeoPosition(p2.lat(), p2.lon()), viewer.getZoom());
                            int x1 = (int) (px1.getX() - vp.x);
                            int y1 = (int) (px1.getY() - vp.y);
                            int x2 = (int) (px2.getX() - vp.x);
                            int y2 = (int) (px2.getY() - vp.y);
                            g.drawLine(x1, y1, x2, y2);
                        }
                    }

                    double heading = Double.isNaN(t.trackDeg()) ? 0.0 : t.trackDeg();
                    drawAircraftGlyph(g, x, y, heading, c);

                    String label = t.callsign() != null && !t.callsign().isBlank()
                            ? t.callsign().trim()
                            : t.icaoHex();
                    g.setColor(labelColour);
                    g.drawString(label, x + 8, y - 4);
                }
                g.setComposite(baseComposite);
            } finally {
                g.dispose();
            }
        }
    }

    private void drawAircraftGlyph(Graphics2D g, int cx, int cy,
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

    private Color altitudeColour(int altFt) {
        if (altFt == Integer.MIN_VALUE) return Color.LIGHT_GRAY;
        if (altFt <   5000) return new Color(0xC0, 0x39, 0x2B);
        if (altFt <  10000) return new Color(0xE6, 0x7E, 0x22);
        if (altFt <  20000) return new Color(0xF1, 0xC4, 0x0F);
        if (altFt <  30000) return new Color(0x27, 0xAE, 0x60);
        if (altFt <  40000) return new Color(0x29, 0x80, 0xB9);
        return                     new Color(0x8E, 0x44, 0xAD);
    }
}
