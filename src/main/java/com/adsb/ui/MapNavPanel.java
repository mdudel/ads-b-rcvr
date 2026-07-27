package com.adsb.ui;

import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.viewer.GeoPosition;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.geom.Point2D;

/**
 * Floating on-map navigation cluster: a 4-cardinal bezel
 * (N/E/S/W arrows) sitting on top of a stacked
 * {@code +} + vertical zoom slider + {@code -} column.
 *
 * <p>Structural pattern lifted verbatim from the tmsweb3190/client
 * {@code MapNavPanel} at Marty's 2026-07-27 14:28 UTC direction
 * ("same style and controls for the map when I don't have a mouse").
 * Simplifications for this project:
 * <ul>
 *   <li>Text glyphs ({@code \u25B2}, {@code +}) instead of the
 *       Material Icons font -- avoids dragging in the icon-font
 *       infrastructure just to render 6 button labels.</li>
 *   <li>Binds directly to {@link JXMapViewer} rather than through a
 *       {@code MapProvider} interface. We don't have (or need) the
 *       full provider abstraction here.</li>
 * </ul>
 *
 * <p>Renders as a NON-OPAQUE column meant to overlay the map
 * component (see {@link MapPanel}, which wraps map + this widget in
 * a {@link javax.swing.JLayeredPane} anchored to the upper-right).
 * No background panel around the cluster -- individual buttons and
 * the slider sit directly on the map tiles, each with its own thick
 * contrasting border so the outline reads against any tile source.
 * Border colour is pulled from {@code UIManager.Label.foreground} so
 * it follows LIGHT / DARK through the FlatLaf palette.
 *
 * <p>Pan: {@link #PAN_FRACTION} of the map component's current width
 * / height per arrow click. Zoom: vertical slider is the primary
 * control; {@code +} / {@code -} clicks are one-step nudges.
 */
public final class MapNavPanel extends JPanel {

    /** Pan step as a fraction of the map component's viewport extent. */
    static final double PAN_FRACTION = 0.10;

    /**
     * JXMapViewer stores zoom as "distance from totalMapZoom"
     * (0 = closest, totalMapZoom = world). Our custom TileFactoryInfo
     * uses {@code totalMapZoom = 17} (see {@link MapPanel}). So the
     * viewer-zoom valid range is [0, 17]; we constrain to [1, 17] to
     * avoid the "single tile fills the world" degenerate case that
     * some tile providers refuse to render.
     */
    static final int VIEWER_ZOOM_MIN = 1;
    static final int VIEWER_ZOOM_MAX = 17;

    private final JXMapViewer map;
    /** Held so we can push new state programmatically when the map's
     *  zoom changes via the mouse wheel or +/- buttons. */
    private final JSlider zoomSlider;
    /** Guard against re-entrancy when we programmatically move the
     *  slider (which fires a ChangeEvent that would otherwise call
     *  map.setZoom recursively). */
    private boolean syncingSliderFromMap;

    public MapNavPanel(JXMapViewer map) {
        super();
        if (map == null) throw new IllegalArgumentException("map is null");
        this.map = map;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        add(buildBezel());
        add(Box.createRigidArea(new Dimension(0, 6)));

        this.zoomSlider = buildZoomSlider();
        add(buildZoomColumn(zoomSlider));

        // Sync slider knob whenever the map's zoom changes externally
        // (mouse wheel, our own +/- clicks). Firing during our OWN
        // slider->setZoom path is safe because of the syncing guard.
        map.addPropertyChangeListener("zoom", evt -> syncSliderFromMap());
    }

    // ------------------------------------------------------------------
    // Bezel: N on top, W-blank-E middle, S on bottom (3x3 grid, four
    // corner slots empty).
    // ------------------------------------------------------------------

    private JPanel buildBezel() {
        JPanel bezel = new JPanel(new GridBagLayout());
        bezel.setOpaque(false);
        bezel.setAlignmentX(CENTER_ALIGNMENT);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(1, 1, 1, 1);
        g.fill = GridBagConstraints.NONE;

        // Material Icons Regular chevrons. Falls back to unicode
        // triangles if the icon font resource is missing (see
        // MaterialIcon.UnicodeFallbackIcon). Icon inherits the host
        // component's foreground so it re-tints on theme switch.
        JButton n = panButton(MaterialIcon.of(MaterialIcon.Glyph.PAN_UP,    22), "Pan north");
        JButton s = panButton(MaterialIcon.of(MaterialIcon.Glyph.PAN_DOWN,  22), "Pan south");
        JButton w = panButton(MaterialIcon.of(MaterialIcon.Glyph.PAN_LEFT,  22), "Pan west");
        JButton e = panButton(MaterialIcon.of(MaterialIcon.Glyph.PAN_RIGHT, 22), "Pan east");
        n.addActionListener(ev -> panPixels(0,        -stepY()));
        s.addActionListener(ev -> panPixels(0,         stepY()));
        w.addActionListener(ev -> panPixels(-stepX(),  0));
        e.addActionListener(ev -> panPixels( stepX(),  0));

        g.gridx = 1; g.gridy = 0; bezel.add(n, g);
        g.gridx = 0; g.gridy = 1; bezel.add(w, g);
        g.gridx = 2; g.gridy = 1; bezel.add(e, g);
        g.gridx = 1; g.gridy = 2; bezel.add(s, g);
        return bezel;
    }

    // ------------------------------------------------------------------
    // Zoom column: + on top, vertical slider in the middle, - on the
    // bottom. Slider is the primary control; +/- are one-step nudges.
    // ------------------------------------------------------------------

    private JPanel buildZoomColumn(JSlider slider) {
        JPanel z = new JPanel();
        z.setLayout(new BoxLayout(z, BoxLayout.Y_AXIS));
        z.setOpaque(false);
        z.setAlignmentX(CENTER_ALIGNMENT);

        JButton plus  = zoomButton(MaterialIcon.of(MaterialIcon.Glyph.ZOOM_IN,  18), "Zoom in");
        JButton minus = zoomButton(MaterialIcon.of(MaterialIcon.Glyph.ZOOM_OUT, 18), "Zoom out");
        plus .addActionListener(ev -> stepZoom(-1));   // viewer-zoom -1 = OSM Z+1 = closer
        minus.addActionListener(ev -> stepZoom(+1));   // viewer-zoom +1 = OSM Z-1 = wider

        plus  .setAlignmentX(CENTER_ALIGNMENT);
        slider.setAlignmentX(CENTER_ALIGNMENT);
        minus .setAlignmentX(CENTER_ALIGNMENT);

        z.add(plus);
        z.add(Box.createRigidArea(new Dimension(0, 2)));
        z.add(slider);
        z.add(Box.createRigidArea(new Dimension(0, 2)));
        z.add(minus);
        return z;
    }

    private JSlider buildZoomSlider() {
        // Slider's user-facing values are OSM Z (1 = world, 17 = street)
        // so operators see them the right way up. The JXMapViewer zoom
        // is the inverse; we translate in setValue / getValue paths.
        int userZoom = viewerZoomToUserZoom(map.getZoom());
        JSlider s = new JSlider(SwingConstants.VERTICAL,
                VIEWER_ZOOM_MIN, VIEWER_ZOOM_MAX, userZoom);
        s.setInverted(false); // top = high user-zoom = closer, bottom = wider
        s.setOpaque(false);
        s.setFocusable(false);
        s.setPaintTicks(false);
        s.setPaintLabels(false);
        Dimension d = new Dimension(32, 130);
        s.setPreferredSize(d);
        s.setMinimumSize(d);
        s.setMaximumSize(d);
        s.setToolTipText("Zoom (drag): top = street, bottom = world");
        s.addChangeListener(ev -> {
            if (syncingSliderFromMap) return;
            int wantedUserZoom = s.getValue();
            int wantedViewerZoom = userZoomToViewerZoom(wantedUserZoom);
            if (wantedViewerZoom != map.getZoom()) {
                map.setZoom(wantedViewerZoom);
            }
        });
        return s;
    }

    /**
     * User-facing "zoom in from world to street" 1..17
     * &harr; JXMapViewer internal 0..17 (which is inverted). Formula
     * intentionally symmetric so the slider ticks are 1:1 with
     * viewer ticks.
     */
    private static int userZoomToViewerZoom(int userZoom) {
        // viewer = totalMapZoom - userZoom + minViewerZoom; using
        // MapPanel's totalMapZoom = 17 this gives viewer = 17 - userZoom.
        int v = 17 - userZoom;
        if (v < 0) v = 0;
        if (v > 17) v = 17;
        return v;
    }

    private static int viewerZoomToUserZoom(int viewerZoom) {
        int u = 17 - viewerZoom;
        if (u < VIEWER_ZOOM_MIN) u = VIEWER_ZOOM_MIN;
        if (u > VIEWER_ZOOM_MAX) u = VIEWER_ZOOM_MAX;
        return u;
    }

    /**
     * Copy the map's current zoom into the slider without triggering
     * the slider's ChangeListener (which would recurse into
     * {@code map.setZoom}). Called from the JXMapViewer PropertyChange
     * listener so mouse-wheel + our own +/- clicks both move the knob.
     */
    private void syncSliderFromMap() {
        syncingSliderFromMap = true;
        try {
            zoomSlider.setValue(viewerZoomToUserZoom(map.getZoom()));
        } finally {
            syncingSliderFromMap = false;
        }
    }

    private void stepZoom(int viewerDelta) {
        int next = map.getZoom() + viewerDelta;
        if (next < 0)  next = 0;
        if (next > 17) next = 17;
        map.setZoom(next);
    }

    // ------------------------------------------------------------------
    // Pan primitives -- translate the map centre by the given pixel
    // offset. Uses geoToPixel / pixelToGeo round-trip because
    // JXMapViewer doesn't expose a "pan by pixels" API directly.
    // ------------------------------------------------------------------

    private void panPixels(int dx, int dy) {
        GeoPosition centre = map.getCenterPosition();
        int zoom = map.getZoom();
        Point2D centrePx = map.getTileFactory().geoToPixel(centre, zoom);
        Point2D newCentrePx = new Point2D.Double(
                centrePx.getX() + dx,
                centrePx.getY() + dy);
        GeoPosition newCentre = map.getTileFactory().pixelToGeo(newCentrePx, zoom);
        map.setCenterPosition(newCentre);
    }

    private int stepX() {
        int w = (getParent() != null) ? getParent().getWidth() : 0;
        if (w <= 0) w = 800;
        return (int) Math.max(1, Math.round(w * PAN_FRACTION));
    }
    private int stepY() {
        int h = (getParent() != null) ? getParent().getHeight() : 0;
        if (h <= 0) h = 600;
        return (int) Math.max(1, Math.round(h * PAN_FRACTION));
    }

    // ------------------------------------------------------------------
    // Button factories -- fixed pixel sizes so the bezel geometry is
    // deterministic across LaFs / theme switches. Border pulls from
    // UIManager.Label.foreground so it contrasts against any tile
    // source in both LIGHT and DARK modes.
    // ------------------------------------------------------------------

    private static JButton panButton(javax.swing.Icon icon, String tooltip) {
        return sizedButton(icon, tooltip, 40, 36);
    }

    private static JButton zoomButton(javax.swing.Icon icon, String tooltip) {
        return sizedButton(icon, tooltip, 32, 28);
    }

    private static JButton sizedButton(javax.swing.Icon icon, String tooltip,
                                        int w, int h) {
        JButton b = new JButton(icon);
        b.setMargin(new Insets(0, 0, 0, 0));
        b.setPreferredSize(new Dimension(w, h));
        b.setMinimumSize(new Dimension(w, h));
        b.setMaximumSize(new Dimension(w, h));
        b.setFocusable(false);
        b.setToolTipText(tooltip);
        b.setHorizontalAlignment(SwingConstants.CENTER);
        b.setBorder(BorderFactory.createLineBorder(borderColour(), 2, true));
        return b;
    }

    private static Color borderColour() {
        Color c = UIManager.getColor("Label.foreground");
        return (c != null) ? c : Color.BLACK;
    }

    /** Test hook: read the zoom slider so tests can drive it. */
    JSlider getZoomSlider() { return zoomSlider; }
}
