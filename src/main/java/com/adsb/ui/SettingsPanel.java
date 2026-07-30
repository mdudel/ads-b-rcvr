package com.adsb.ui;

import com.adsb.cot.CoTBuilder;
import com.adsb.cot.IcaoAircraftClassifier;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.BiConsumer;

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
     * Legacy 6-arg ctor: brightness starts at 1.0 (fully bright).
     * Defers to the 7-arg primary ctor. Kept so headless tests + any
     * pre-persistence caller stay green.
     */
    public SettingsPanel(IcaoAircraftClassifier.Affiliation initialAffil,
                         IcaoAircraftClassifier.Category    initialCat,
                         int initialStaleAir, int initialStaleGround,
                         SettingsListener onChange,
                         java.util.function.Consumer<Float> onBrightnessChanged) {
        this(initialAffil, initialCat, initialStaleAir, initialStaleGround,
                onChange, onBrightnessChanged, 1.0f);
    }

    public SettingsPanel(IcaoAircraftClassifier.Affiliation initialAffil,
                         IcaoAircraftClassifier.Category    initialCat,
                         int initialStaleAir, int initialStaleGround,
                         SettingsListener onChange,
                         java.util.function.Consumer<Float> onBrightnessChanged,
                         float initialBrightness) {
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
