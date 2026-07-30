package com.adsb.ui;

import com.adsb.cot.CoTBuilder;
import com.adsb.cot.IcaoAircraftClassifier;
import com.adsb.enrichment.EnrichmentResolver;
import com.adsb.model.TrackSmoothingRegistry;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Settings dock: exposes the knobs the CLI used to own (CoT
 * affiliation / category / stale timeouts) so the operator can
 * change them without restarting.
 *
 * <p>Changes are pushed to the caller via a {@link BiConsumer} so
 * this panel doesn't need to know about the receiver, the store, or
 * whatever downstream cares. Two calls: {@code onCoTChanged} rebuilds
 * the {@link CoTBuilder}; {@code onClassifierChanged} rebuilds the
 * {@link IcaoAircraftClassifier}. The MainFrame wires those to the
 * running receiver's CoT listener.
 *
 * <p>First cut only. Themes / map cache dir / gain override are
 * follow-up work.
 */
public final class SettingsPanel extends JPanel {

    private final JComboBox<IcaoAircraftClassifier.Affiliation> affilBox;
    private final JComboBox<IcaoAircraftClassifier.Category>    catBox;
    private final JSpinner staleAirSpin;
    private final JSpinner staleGroundSpin;
    private final JSlider brightnessSlider;

    /**
     * Legacy 6-arg ctor: brightness starts at 1.0 (fully bright),
     * enrichment row is hidden. Defers to the primary ctor.
     */
    public SettingsPanel(IcaoAircraftClassifier.Affiliation initialAffil,
                         IcaoAircraftClassifier.Category    initialCat,
                         int initialStaleAir, int initialStaleGround,
                         SettingsListener onChange,
                         java.util.function.Consumer<Float> onBrightnessChanged) {
        this(initialAffil, initialCat, initialStaleAir, initialStaleGround,
                onChange, onBrightnessChanged, 1.0f);
    }

    /**
     * Legacy 7-arg ctor: no enrichment row.
     */
    public SettingsPanel(IcaoAircraftClassifier.Affiliation initialAffil,
                         IcaoAircraftClassifier.Category    initialCat,
                         int initialStaleAir, int initialStaleGround,
                         SettingsListener onChange,
                         java.util.function.Consumer<Float> onBrightnessChanged,
                         float initialBrightness) {
        this(initialAffil, initialCat, initialStaleAir, initialStaleGround,
                onChange, onBrightnessChanged, initialBrightness,
                null, () -> null, s -> {});
    }

    /**
     * Legacy 10-arg ctor: no track-smoothing toggle.
     */
    public SettingsPanel(IcaoAircraftClassifier.Affiliation initialAffil,
                         IcaoAircraftClassifier.Category    initialCat,
                         int initialStaleAir, int initialStaleGround,
                         SettingsListener onChange,
                         java.util.function.Consumer<Float> onBrightnessChanged,
                         float initialBrightness,
                         EnrichmentResolver enrichment,
                         Supplier<String> enrichmentDirRef,
                         Consumer<String> enrichmentDirSetter) {
        this(initialAffil, initialCat, initialStaleAir, initialStaleGround,
                onChange, onBrightnessChanged, initialBrightness,
                enrichment, enrichmentDirRef, enrichmentDirSetter,
                null, false, b -> {});
    }

    public SettingsPanel(IcaoAircraftClassifier.Affiliation initialAffil,
                         IcaoAircraftClassifier.Category    initialCat,
                         int initialStaleAir, int initialStaleGround,
                         SettingsListener onChange,
                         java.util.function.Consumer<Float> onBrightnessChanged,
                         float initialBrightness,
                         EnrichmentResolver enrichment,
                         Supplier<String> enrichmentDirRef,
                         Consumer<String> enrichmentDirSetter,
                         TrackSmoothingRegistry smoothing,
                         boolean initialSmoothingEnabled,
                         Consumer<Boolean> onSmoothingChanged) {
        super(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        this.affilBox = new JComboBox<>(IcaoAircraftClassifier.Affiliation.values());
        affilBox.setSelectedItem(
                initialAffil == null ? IcaoAircraftClassifier.Affiliation.NEUTRAL : initialAffil);

        this.catBox = new JComboBox<>(IcaoAircraftClassifier.Category.values());
        catBox.setSelectedItem(
                initialCat == null ? IcaoAircraftClassifier.Category.CIVILIAN : initialCat);

        this.staleAirSpin    = new JSpinner(new SpinnerNumberModel(initialStaleAir,    5,  3600, 5));
        this.staleGroundSpin = new JSpinner(new SpinnerNumberModel(initialStaleGround, 5, 86400, 30));

        // Slider value is percent; brightness is 0.0-1.0. Clamp on the
        // way in so a corrupted properties file can't wedge the slider.
        int initialPct = Math.max(0, Math.min(100, Math.round(initialBrightness * 100f)));
        this.brightnessSlider = new JSlider(JSlider.HORIZONTAL, 0, 100, initialPct);
        brightnessSlider.setMajorTickSpacing(25);
        brightnessSlider.setMinorTickSpacing(5);
        brightnessSlider.setPaintTicks(true);
        brightnessSlider.setPaintLabels(true);
        brightnessSlider.setToolTipText("Adjust map brightness (0=dark, 100=normal)");

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill   = GridBagConstraints.HORIZONTAL;

        int y = 0;
        gc.gridx = 0; gc.gridy = y; add(new JLabel("CoT affiliation:"),  gc);
        gc.gridx = 1;                add(affilBox,                        gc);
        y++;
        gc.gridx = 0; gc.gridy = y; add(new JLabel("CoT category:"),     gc);
        gc.gridx = 1;                add(catBox,                          gc);
        y++;
        gc.gridx = 0; gc.gridy = y; add(new JLabel("Stale air (s):"),    gc);
        gc.gridx = 1;                add(staleAirSpin,                    gc);
        y++;
        gc.gridx = 0; gc.gridy = y; add(new JLabel("Stale ground (s):"), gc);
        gc.gridx = 1;                add(staleGroundSpin,                 gc);
        y++;
        gc.gridx = 0; gc.gridy = y; add(new JLabel("Map brightness:"),   gc);
        gc.gridx = 1;                add(brightnessSlider,                gc);
        y++;

        // Enrichment section (Marty 2026-07-30 14:05 UTC): local CSV dir
        // + Browse + Reload + Download OpenSky bundle. Only rendered when
        // an EnrichmentResolver was passed in.
        if (enrichment != null) {
            gc.gridx = 0; gc.gridy = y; gc.gridwidth = 2;
            add(new JLabel("─ Aircraft metadata ─"), gc);
            gc.gridwidth = 1;
            y++;

            JTextField dirField = new JTextField(
                    enrichmentDirRef.get() == null ? "" : enrichmentDirRef.get(), 24);
            dirField.setToolTipText("Directory containing OpenSky-format aircraft-database *.csv files");
            JButton browseBtn = new JButton("Browse\u2026");
            browseBtn.setFocusable(false);
            browseBtn.addActionListener(e -> {
                JFileChooser fc = new JFileChooser();
                fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                String cur = dirField.getText();
                if (cur != null && !cur.isBlank()) {
                    File f = new File(cur);
                    if (f.isDirectory()) fc.setCurrentDirectory(f);
                }
                int rc = fc.showOpenDialog(this);
                if (rc == JFileChooser.APPROVE_OPTION) {
                    Path chosen = fc.getSelectedFile().toPath();
                    dirField.setText(chosen.toString());
                    enrichmentDirSetter.accept(chosen.toString());
                }
            });

            JButton reloadBtn = new JButton("Reload");
            reloadBtn.setFocusable(false);
            reloadBtn.setToolTipText("Re-parse every *.csv in the local dir");
            JLabel statusLbl = new JLabel(enrichment.statusLine());
            reloadBtn.addActionListener(e -> {
                if (enrichment.localDir() != null) enrichment.localDir().reload();
                if (enrichment.bundle()   != null) enrichment.bundle().reload();
                statusLbl.setText(enrichment.statusLine());
            });

            JButton downloadBtn = new JButton("Download bundle");
            downloadBtn.setFocusable(false);
            downloadBtn.setToolTipText("Fetch the current OpenSky aircraft-database snapshot");
            downloadBtn.addActionListener(e -> {
                downloadBtn.setEnabled(false);
                downloadBtn.setText("Downloading\u2026");
                new SwingWorker<Boolean, Void>() {
                    @Override protected Boolean doInBackground() {
                        Path bundleDir = Paths.get(
                                System.getProperty("user.home"),
                                ".adsb-rcvr", "aircraft-db");
                        return enrichment.downloadBundle(bundleDir, List.of());
                    }
                    @Override protected void done() {
                        try {
                            boolean ok = get();
                            downloadBtn.setText(ok ? "Download bundle" : "Download FAILED");
                            statusLbl.setText(enrichment.statusLine());
                        } catch (Exception ex) {
                            downloadBtn.setText("Download FAILED");
                        } finally {
                            downloadBtn.setEnabled(true);
                        }
                    }
                }.execute();
            });

            gc.gridx = 0; gc.gridy = y; add(new JLabel("Local dir:"), gc);
            gc.gridx = 1;                add(dirField,                gc);
            y++;
            JPanel btnRow = new JPanel();
            btnRow.add(browseBtn);
            btnRow.add(reloadBtn);
            btnRow.add(downloadBtn);
            gc.gridx = 0; gc.gridy = y; gc.gridwidth = 2; add(btnRow, gc);
            gc.gridwidth = 1;
            y++;
            gc.gridx = 0; gc.gridy = y; gc.gridwidth = 2; add(statusLbl, gc);
            gc.gridwidth = 1;
            y++;
        }

        // Track-smoothing toggle (Marty 2026-07-30 15:27 UTC, #16).
        // Only shown when a registry was supplied; hidden in test /
        // headless bootstraps that don't wire one.
        if (smoothing != null) {
            gc.gridx = 0; gc.gridy = y; gc.gridwidth = 2;
            add(new JLabel("─ Track display ─"), gc);
            gc.gridwidth = 1;
            y++;
            JCheckBox smoothToggle = new JCheckBox(
                    "Smooth track paths (Kalman)", initialSmoothingEnabled);
            smoothToggle.setFocusable(false);
            smoothToggle.setToolTipText(
                    "Apply a per-aircraft Kalman filter to displayed"
                    + " positions + history trail. Display only --"
                    + " store, table, popup, and CoT emissions stay raw.");
            smoothToggle.addActionListener(e -> {
                boolean on = smoothToggle.isSelected();
                smoothing.setEnabled(on);
                onSmoothingChanged.accept(on);
            });
            gc.gridx = 0; gc.gridy = y; gc.gridwidth = 2;
            add(smoothToggle, gc);
            gc.gridwidth = 1;
            y++;
        }

        // Fill remaining vertical space so widgets sit at the top.
        gc.gridx = 0; gc.gridy = y; gc.gridwidth = 2; gc.weighty = 1.0;
        gc.fill = GridBagConstraints.BOTH;
        add(new JPanel(), gc);

        java.awt.event.ActionListener push = e -> {
            if (onChange != null) onChange.settingsChanged(
                    (IcaoAircraftClassifier.Affiliation) affilBox.getSelectedItem(),
                    (IcaoAircraftClassifier.Category) catBox.getSelectedItem(),
                    ((Number) staleAirSpin.getValue()).intValue(),
                    ((Number) staleGroundSpin.getValue()).intValue());
        };
        affilBox.addActionListener(push);
        catBox.addActionListener(push);
        staleAirSpin.addChangeListener(e -> push.actionPerformed(null));
        staleGroundSpin.addChangeListener(e -> push.actionPerformed(null));

        if (onBrightnessChanged != null) {
            brightnessSlider.addChangeListener(e -> {
                float brightness = brightnessSlider.getValue() / 100.0f;
                onBrightnessChanged.accept(brightness);
            });
        }
    }

    /** Fired whenever any settings widget changes. */
    @FunctionalInterface
    public interface SettingsListener {
        void settingsChanged(IcaoAircraftClassifier.Affiliation affiliation,
                             IcaoAircraftClassifier.Category    category,
                             int staleAirSeconds,
                             int staleGroundSeconds);
    }
}
