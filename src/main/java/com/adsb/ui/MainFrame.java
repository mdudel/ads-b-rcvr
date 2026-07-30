package com.adsb.ui;

import com.adsb.core.AdsbReceiver;
import com.adsb.cot.CoTBuilder;
import com.adsb.cot.IcaoAircraftClassifier;
import com.adsb.enrichment.EnrichmentResolver;
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
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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

    /**
     * @param initialTheme starting theme; the toolbar cycle button flips
     *                     it and calls {@code onThemeChanged} so the caller
     *                     can persist it. May be null (defaults to LIGHT).
     * @param onThemeChanged called every time the operator cycles the
     *                     theme; typical impl writes to the config file.
     *                     Nullable if persistence isn't wired.
     * @param receiver     live {@link AdsbReceiver} for the Reconnect
     *                     toolbar button (Marty 2026-07-29 11:28 UTC).
     *                     Nullable: when null, the Reconnect button is
     *                     hidden (headless smoke tests / non-UI paths).
     * @param lastCertDirRef supplier of the last directory a Zenoh cert
     *                     Browse button visited; nullable (defaults to
     *                     'no memory'). Persistence lives in Main so
     *                     the UI never touches the properties file.
     * @param lastCertDirSetter setter called when a Browse button
     *                     commits a new directory; nullable no-op if
     *                     persistence isn't wired.
     */
    /**
     * Legacy 12-arg convenience ctor for callers that don't need the
     * lastCertDir persistence (tests, headless smoke). Defers to the
     * full ctor with no-op supplier/setter and default brightness.
     */
    public MainFrame(String version,
                     AircraftStateStore store,
                     ConnectorStore connectorStore,
                     ConnectorAttacher attacher,
                     AtomicReference<CoTBuilder> liveBuilder,
                     IcaoAircraftClassifier.Affiliation initialAffil,
                     IcaoAircraftClassifier.Category    initialCat,
                     int initialStaleAir, int initialStaleGround,
                     ThemeMode initialTheme,
                     Consumer<ThemeMode> onThemeChanged,
                     AdsbReceiver receiver) {
        this(version, store, connectorStore, attacher, liveBuilder,
                initialAffil, initialCat, initialStaleAir, initialStaleGround,
                initialTheme, onThemeChanged, receiver, () -> null, s -> {});
    }

    /**
     * Legacy 14-arg convenience ctor for callers that don't need the
     * brightness persistence (tests, headless smoke). Defers to the
     * full ctor with brightness=1.0 and a no-op persistence setter.
     */
    public MainFrame(String version,
                     AircraftStateStore store,
                     ConnectorStore connectorStore,
                     ConnectorAttacher attacher,
                     AtomicReference<CoTBuilder> liveBuilder,
                     IcaoAircraftClassifier.Affiliation initialAffil,
                     IcaoAircraftClassifier.Category    initialCat,
                     int initialStaleAir, int initialStaleGround,
                     ThemeMode initialTheme,
                     Consumer<ThemeMode> onThemeChanged,
                     AdsbReceiver receiver,
                     java.util.function.Supplier<String> lastCertDirRef,
                     java.util.function.Consumer<String> lastCertDirSetter) {
        this(version, store, connectorStore, attacher, liveBuilder,
                initialAffil, initialCat, initialStaleAir, initialStaleGround,
                initialTheme, onThemeChanged, receiver,
                lastCertDirRef, lastCertDirSetter,
                1.0f, b -> {});
    }

    /**
     * Legacy 16-arg ctor: no enrichment resolver. Delegates to the
     * 19-arg primary with a null resolver -- enrichment columns stay
     * empty and the Settings enrichment row is hidden.
     */
    public MainFrame(String version,
                     AircraftStateStore store,
                     ConnectorStore connectorStore,
                     ConnectorAttacher attacher,
                     AtomicReference<CoTBuilder> liveBuilder,
                     IcaoAircraftClassifier.Affiliation initialAffil,
                     IcaoAircraftClassifier.Category    initialCat,
                     int initialStaleAir, int initialStaleGround,
                     ThemeMode initialTheme,
                     Consumer<ThemeMode> onThemeChanged,
                     AdsbReceiver receiver,
                     java.util.function.Supplier<String> lastCertDirRef,
                     java.util.function.Consumer<String> lastCertDirSetter,
                     float initialMapBrightness,
                     Consumer<Float> onMapBrightnessChanged) {
        this(version, store, connectorStore, attacher, liveBuilder,
                initialAffil, initialCat, initialStaleAir, initialStaleGround,
                initialTheme, onThemeChanged, receiver,
                lastCertDirRef, lastCertDirSetter,
                initialMapBrightness, onMapBrightnessChanged,
                null, () -> null, s -> {});
    }

    public MainFrame(String version,
                     AircraftStateStore store,
                     ConnectorStore connectorStore,
                     ConnectorAttacher attacher,
                     AtomicReference<CoTBuilder> liveBuilder,
                     IcaoAircraftClassifier.Affiliation initialAffil,
                     IcaoAircraftClassifier.Category    initialCat,
                     int initialStaleAir, int initialStaleGround,
                     ThemeMode initialTheme,
                     Consumer<ThemeMode> onThemeChanged,
                     AdsbReceiver receiver,
                     java.util.function.Supplier<String> lastCertDirRef,
                     java.util.function.Consumer<String> lastCertDirSetter,
                     float initialMapBrightness,
                     Consumer<Float> onMapBrightnessChanged,
                     EnrichmentResolver enrichment,
                     java.util.function.Supplier<String> enrichmentDirRef,
                     java.util.function.Consumer<String> enrichmentDirSetter) {
        super("ADS-B Receiver \u2014 " + version);
        this.store           = store;
        this.connectorStore  = connectorStore;
        this.attacher        = attacher;
        this.liveBuilder     = liveBuilder;

        // DO_NOTHING here + explicit windowClosing handler below so we
        // can run the shutdown hook (which stops rtl_adsb + releases the
        // USB endpoint) before the JVM exits. DISPOSE_ON_CLOSE alone
        // leaves the receiver thread blocked on rtl_adsb stdout so the
        // JVM never exits, the operator kills it via Task Manager, and
        // rtl_adsb gets orphaned holding the USB device (issue #13).
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                // System.exit runs shutdown hooks (which stop rtl_adsb)
                // then terminates every non-daemon thread including the
                // receiver's blocking read loop. Clean tear-down.
                dispose();
                System.exit(0);
            }
        });
        setSize(new Dimension(1200, 800));
        setLocationRelativeTo(null);

        this.mapPanel         = new MapPanel(store);
        // Apply persisted brightness BEFORE the panel first paints so
        // the operator doesn't see a full-bright flash on start.
        mapPanel.setBrightness(initialMapBrightness);
        // Details-popup opener: same handler for map-click and
        // tracks-table double-click so both gestures produce the
        // same result (Marty 2026-07-30 15:01 UTC, issue #15).
        final EnrichmentResolver enrichmentForPopup = enrichment;
        final java.util.function.Consumer<com.adsb.model.AdsbTrack> openDetails =
                t -> TrackDetailsDialog.showFor(this, t.icaoHex(), store, enrichmentForPopup);

        this.tracksPanel      = new TracksPanel(store, enrichment,
                mapPanel::centerOn, openDetails);
        mapPanel.setOnTrackClicked(openDetails);
        this.connectorsPanel  = new ConnectorsPanel(connectorStore, attacher,
                lastCertDirRef, lastCertDirSetter);
        // Compose the brightness callback: update the live MapPanel AND
        // persist to disk so the setting survives a restart. Persistence
        // is nullable-safe -- if the caller didn't wire it we still get
        // in-session behaviour.
        final Consumer<Float> brightnessSink = (onMapBrightnessChanged == null)
                ? mapPanel::setBrightness
                : b -> { mapPanel.setBrightness(b); onMapBrightnessChanged.accept(b); };
        this.settingsPanel    = new SettingsPanel(
                initialAffil, initialCat, initialStaleAir, initialStaleGround,
                (aff, cat, sa, sg) -> {
                    liveBuilder.set(new CoTBuilder(new IcaoAircraftClassifier(aff, cat), sa, sg));
                },
                brightnessSink,
                initialMapBrightness,
                enrichment,
                enrichmentDirRef,
                enrichmentDirSetter);
        this.aboutPanel       = new AboutPanel(version);

        this.sideDock = new SideDock(mapPanel);

        // ----- toolbar -----
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        addToolbarButton(toolbar, ID_TRACKS,     "Tracks",
                MaterialIcon.of(MaterialIcon.Glyph.OVERLAYS, 18),
                "Aircraft table (list of every tracked ICAO)",
                () -> sideDock.toggle(ID_TRACKS,     "Tracks",     tracksPanel));
        addToolbarButton(toolbar, ID_CONNECTORS, "Connectors",
                MaterialIcon.of(MaterialIcon.Glyph.TOOLS, 18),
                "Add / edit / remove output connectors",
                () -> sideDock.toggle(ID_CONNECTORS, "Connectors", connectorsPanel));
        addToolbarButton(toolbar, ID_SETTINGS,   "Settings",
                MaterialIcon.of(MaterialIcon.Glyph.SETTINGS, 18),
                "CoT + receiver settings",
                () -> sideDock.toggle(ID_SETTINGS,   "Settings",   settingsPanel));
        addToolbarButton(toolbar, ID_ABOUT,      "About",
                MaterialIcon.of(MaterialIcon.Glyph.ABOUT, 18),
                "Version + help + links",
                () -> sideDock.toggle(ID_ABOUT,      "About",      aboutPanel));

        // Reconnect button (Marty 2026-07-29 11:28 UTC): kickstarts the
        // rtl_adsb subprocess again after a failed startup (dongle wasn't
        // plugged in, USB endpoint locked from a prior orphaned child,
        // etc.). Also shows a live status hint so the operator can see
        // whether the receiver is currently running.
        toolbar.add(javax.swing.Box.createHorizontalGlue());
        if (receiver != null) {
            JLabel rxStatus = new JLabel("rx: " + (receiver.isRunning() ? "running" : "stopped"));
            rxStatus.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            toolbar.add(rxStatus);

            JButton reconnectBtn = new JButton("Reconnect",
                    MaterialIcon.of(MaterialIcon.Glyph.SETTINGS, 18));
            reconnectBtn.setToolTipText(
                    "Restart the rtl_adsb subprocess (use after plugging the dongle back in)");
            reconnectBtn.setFocusable(false);
            reconnectBtn.setHorizontalTextPosition(javax.swing.SwingConstants.RIGHT);
            reconnectBtn.setIconTextGap(6);
            java.util.concurrent.atomic.AtomicBoolean reconnectInFlight =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            Runnable refreshBtn = () -> {
                boolean running = receiver.isRunning();
                boolean inflight = reconnectInFlight.get();
                reconnectBtn.setEnabled(!running && !inflight);
                rxStatus.setText("rx: " + (running ? "running"
                                        : inflight ? "reconnecting\u2026"
                                                   : "stopped"));
            };
            reconnectBtn.addActionListener(e -> {
                if (!reconnectInFlight.compareAndSet(false, true)) return;
                refreshBtn.run();
                Thread t = new Thread(() -> {
                    try {
                        receiver.resetForRestart();
                        receiver.start();   // blocks until the retry loop finishes
                    } catch (Exception ex) {
                        System.err.println("[ERROR] Reconnect failed: " + ex);
                        ex.printStackTrace();
                    } finally {
                        reconnectInFlight.set(false);
                        SwingUtilities.invokeLater(refreshBtn);
                    }
                }, "adsb-reconnect");
                t.setDaemon(true);
                t.start();
            });
            toolbar.add(reconnectBtn);

            receiver.setStateChangedListener(() -> SwingUtilities.invokeLater(refreshBtn));
            Timer rxPoll = new Timer(2000, e -> refreshBtn.run());
            rxPoll.setRepeats(true);
            rxPoll.start();
            refreshBtn.run();
        }

        // Theme cycle button, right side. Two-state (LIGHT <-> DARK)
        // matching Marty's 2026-07-27 14:28 UTC ask. Label reflects
        // the CURRENT theme so the button reads as "you are here";
        // clicking flips and updates.
        final ThemeMode[] currentTheme = { initialTheme == null ? ThemeMode.LIGHT : initialTheme };
        JButton themeBtn = new JButton(themeButtonLabel(currentTheme[0]));
        themeBtn.setToolTipText("Cycle theme: LIGHT <-> DARK");
        themeBtn.setFocusable(false);
        themeBtn.addActionListener(e -> {
            currentTheme[0] = currentTheme[0].next();
            currentTheme[0].apply();
            themeBtn.setText(themeButtonLabel(currentTheme[0]));
            if (onThemeChanged != null) onThemeChanged.accept(currentTheme[0]);
        });
        toolbar.add(themeBtn);

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

        // Debug hook: -Dadsb.ui.autoOpen=tracks|connectors|settings|about
        // pre-opens the named dock on startup. Used for headless
        // screenshot smoke tests where we can't drive xdotool.
        String autoOpen = System.getProperty("adsb.ui.autoOpen");
        if (autoOpen != null) {
            SwingUtilities.invokeLater(() -> {
                switch (autoOpen.trim().toLowerCase()) {
                    case "tracks"     -> sideDock.show(ID_TRACKS,     "Tracks",     tracksPanel);
                    case "connectors" -> sideDock.show(ID_CONNECTORS, "Connectors", connectorsPanel);
                    case "settings"   -> sideDock.show(ID_SETTINGS,   "Settings",   settingsPanel);
                    case "about"      -> sideDock.show(ID_ABOUT,      "About",      aboutPanel);
                    default           -> System.err.println(
                            "[WARN] adsb.ui.autoOpen: unknown panel '" + autoOpen + "'");
                }
            });
        }
    }

    private void addToolbarButton(JToolBar bar, String id, String label,
                                   javax.swing.Icon icon,
                                   String tooltip, Runnable onClick) {
        JButton b = new JButton(label, icon);
        b.setToolTipText(tooltip);
        b.setFocusable(false);
        // Icon on the left of the label, small gap between them.
        b.setHorizontalTextPosition(javax.swing.SwingConstants.RIGHT);
        b.setIconTextGap(6);
        b.addActionListener(e -> onClick.run());
        toolbarButtons.put(id, b);
        bar.add(b);
    }

    /** "Theme: Light" / "Theme: Dark" for the top-right cycle button. */
    private static String themeButtonLabel(ThemeMode m) {
        return "Theme: " + (m == ThemeMode.DARK ? "Dark" : "Light");
    }
}
