package com.adsb.ui;

import com.adsb.cot.CoTBuilder;
import com.adsb.cot.IcaoAircraftClassifier;
import com.adsb.model.AircraftStateStore;
import com.adsb.ui.model.ConnectorAttacher;
import com.adsb.ui.model.ConnectorStore;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Main window. Layout (cribbed from tmsweb3190/client MainFrame):
 * <pre>
 *   NORTH  \u2192 slim toolbar (Tracks / Connectors / Settings / About)
 *   CENTER \u2192 SideDock: left = swappable panel, right = MapPanel
 *   SOUTH  \u2192 status bar (aircraft count, sink count)
 * </pre>
 *
 * <p>Each toolbar button toggles its panel in the SideDock (same
 * button pressed twice closes; different button swaps).
 *
 * <p>Live wiring: the CoT settings are held in an
 * {@link AtomicReference} pair so the settings panel can rebuild
 * the {@link CoTBuilder} / {@link IcaoAircraftClassifier} on change
 * without touching the ConnectorAttacher directly. Existing CoT
 * connectors keep their listener; new emissions use the fresh
 * builder because the listener reads through the AtomicReference
 * on every callback.
 */
public final class MainFrame extends JFrame {

    private static final String ID_TRACKS     = "tracks";
    private static final String ID_CONNECTORS = "connectors";
    private static final String ID_SETTINGS   = "settings";
    private static final String ID_ABOUT      = "about";

    private final AircraftStateStore store;
    private final ConnectorStore     connectorStore;
    private final ConnectorAttacher  attacher;
    private final MapPanel           mapPanel;
    private final SideDock           sideDock;
    private final TracksPanel        tracksPanel;
    private final ConnectorsPanel    connectorsPanel;
    private final SettingsPanel      settingsPanel;
    private final AboutPanel         aboutPanel;

    /** Toggle-selected visuals on the toolbar buttons. */
    private final java.util.Map<String, JButton> toolbarButtons = new java.util.LinkedHashMap<>();

    /** Live CoT builder ref \u2014 rebuilt when the SettingsPanel fires. */
    private final AtomicReference<CoTBuilder> liveBuilder;

    public MainFrame(String version,
                     AircraftStateStore store,
                     ConnectorStore connectorStore,
                     ConnectorAttacher attacher,
                     AtomicReference<CoTBuilder> liveBuilder,
                     IcaoAircraftClassifier.Affiliation initialAffil,
                     IcaoAircraftClassifier.Category    initialCat,
                     int initialStaleAir, int initialStaleGround) {
        super("ADS-B Receiver \u2014 " + version);
        this.store           = store;
        this.connectorStore  = connectorStore;
        this.attacher        = attacher;
        this.liveBuilder     = liveBuilder;

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setSize(new Dimension(1200, 800));
        setLocationRelativeTo(null);

        this.mapPanel         = new MapPanel(store);
        this.tracksPanel      = new TracksPanel(store, mapPanel::centerOn);
        this.connectorsPanel  = new ConnectorsPanel(connectorStore, attacher);
        this.settingsPanel    = new SettingsPanel(
                initialAffil, initialCat, initialStaleAir, initialStaleGround,
                (aff, cat, sa, sg) -> {
                    liveBuilder.set(new CoTBuilder(new IcaoAircraftClassifier(aff, cat), sa, sg));
                });
        this.aboutPanel       = new AboutPanel(version);

        this.sideDock = new SideDock(mapPanel);

        // ----- toolbar -----
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        addToolbarButton(toolbar, ID_TRACKS,     "Tracks",     "Aircraft table (list of every tracked ICAO)",
                () -> sideDock.toggle(ID_TRACKS,     "Tracks",     tracksPanel));
        addToolbarButton(toolbar, ID_CONNECTORS, "Connectors", "Add / edit / remove output connectors",
                () -> sideDock.toggle(ID_CONNECTORS, "Connectors", connectorsPanel));
        addToolbarButton(toolbar, ID_SETTINGS,   "Settings",   "CoT + receiver settings",
                () -> sideDock.toggle(ID_SETTINGS,   "Settings",   settingsPanel));
        addToolbarButton(toolbar, ID_ABOUT,      "About",      "Version + help + links",
                () -> sideDock.toggle(ID_ABOUT,      "About",      aboutPanel));

        // ----- status bar -----
        JLabel countLabel = new JLabel("tracks: 0");
        JLabel sinkLabel  = new JLabel("sinks: 0");
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        JPanel southLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        southLeft.add(countLabel);
        southLeft.add(sinkLabel);
        south.add(southLeft, BorderLayout.WEST);

        // Track count from the store; sink count from the connector store snapshot.
        store.addListener(t -> countLabel.setText("tracks: " + store.size()));
        Runnable refreshSinks = () -> {
            int enabled = 0;
            for (var c : connectorStore.list()) if (c.enabled()) enabled++;
            sinkLabel.setText("sinks: " + enabled + " / " + connectorStore.list().size());
        };
        connectorStore.addListener(e -> SwingUtilities.invokeLater(refreshSinks));
        Timer periodic = new Timer(1000, e -> refreshSinks.run());
        periodic.setRepeats(true);
        periodic.start();
        refreshSinks.run();

        // ----- shell -----
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(toolbar,  BorderLayout.NORTH);
        getContentPane().add(sideDock, BorderLayout.CENTER);
        getContentPane().add(south,    BorderLayout.SOUTH);
    }

    private void addToolbarButton(JToolBar bar, String id, String label,
                                   String tooltip, Runnable onClick) {
        JButton b = new JButton(label);
        b.setToolTipText(tooltip);
        b.setFocusable(false);
        b.addActionListener(e -> onClick.run());
        toolbarButtons.put(id, b);
        bar.add(b);
    }
}
