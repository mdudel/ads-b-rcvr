package com.adsb.ui;

import com.adsb.core.PayloadFormat;
import com.adsb.ui.model.Connector;
import com.adsb.ui.model.ConnectorAttacher;
import com.adsb.ui.model.ConnectorStore;
import com.adsb.ui.model.ZenohMode;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * The Connectors dock: shows every configured {@link Connector},
 * offers Add / Edit / Remove / Enable-toggle, and delegates the
 * attach/detach lifecycle to a shared {@link ConnectorAttacher}.
 *
 * <p>All connector types (UDP unicast, UDP multicast, TCP server,
 * Zenoh) are wire-implemented and selectable. The OK-gating +
 * per-type tooltip logic is retained so a future scaffolded-but-
 * unwired type can rejoin the dropdown grayed-out without another
 * round of surgery here.
 *
 * <p>Persistence: every mutation calls {@link ConnectorStore#save()}
 * so the properties file always mirrors the UI. Save errors surface
 * as a dialog; the in-memory list is not rolled back (matches
 * {@link ConnectorStore#save()} contract).
 */
public final class ConnectorsPanel extends JPanel {

    private final ConnectorStore     store;
    private final ConnectorAttacher  attacher;
    private final DefaultListModel<Connector> listModel = new DefaultListModel<>();
    private final JList<Connector>   list;

    public ConnectorsPanel(ConnectorStore store, ConnectorAttacher attacher) {
        super(new BorderLayout(4, 4));
        this.store = store;
        this.attacher = attacher;

        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        this.list = new JList<>(listModel);
        list.setCellRenderer(new ConnectorCellRenderer());
        list.setVisibleRowCount(6);
        list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        add(new JScrollPane(list), BorderLayout.CENTER);

        JButton addBtn    = new JButton("Add\u2026");
        JButton editBtn   = new JButton("Edit\u2026");
        JButton removeBtn = new JButton("Remove");
        JButton toggleBtn = new JButton("Toggle enabled");

        addBtn   .addActionListener(e -> onAdd());
        editBtn  .addActionListener(e -> onEdit());
        removeBtn.addActionListener(e -> onRemove());
        toggleBtn.addActionListener(e -> onToggle());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        buttons.add(addBtn);
        buttons.add(editBtn);
        buttons.add(removeBtn);
        buttons.add(toggleBtn);
        add(buttons, BorderLayout.SOUTH);

        // Refresh whenever the store changes.
        store.addListener(e -> SwingUtilities.invokeLater(this::reload));
        reload();
    }

    private void reload() {
        Connector prev = list.getSelectedValue();
        listModel.clear();
        for (Connector c : store.list()) listModel.addElement(c);
        if (prev != null) {
            for (int i = 0; i < listModel.size(); i++) {
                if (listModel.get(i).id().equals(prev.id())) {
                    list.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // actions
    // ------------------------------------------------------------------

    private void onAdd() {
        Connector c = ConnectorEditDialog.showFor(this, null);
        if (c == null) return;
        store.add(c);
        persistThen(() -> attach(c));
    }

    private void onEdit() {
        Connector sel = list.getSelectedValue();
        if (sel == null) return;
        Connector edited = ConnectorEditDialog.showFor(this, sel);
        if (edited == null) return;
        boolean wasAttached = sel.enabled() && sel.type().isImplemented();
        store.update(edited);
        persistThen(() -> {
            if (wasAttached) attacher.detach(sel.id());
            if (edited.enabled() && edited.type().isImplemented()) attach(edited);
        });
    }

    private void onRemove() {
        Connector sel = list.getSelectedValue();
        if (sel == null) return;
        int r = JOptionPane.showConfirmDialog(this,
                "Remove connector \"" + sel.name() + "\"?",
                "Remove", JOptionPane.OK_CANCEL_OPTION);
        if (r != JOptionPane.OK_OPTION) return;
        attacher.detach(sel.id());
        store.remove(sel.id());
        persistSilently();
    }

    private void onToggle() {
        Connector sel = list.getSelectedValue();
        if (sel == null) return;
        Connector flipped = sel.withEnabled(!sel.enabled());
        store.update(flipped);
        persistThen(() -> {
            if (flipped.enabled()) attach(flipped);
            else                   attacher.detach(flipped.id());
        });
    }

    private void attach(Connector c) {
        try {
            attacher.attach(c);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Failed to attach connector \"" + c.name() + "\":\n" + ex.getMessage(),
                    "Attach failed", JOptionPane.ERROR_MESSAGE);
            // Auto-disable so a broken target doesn't keep re-erroring.
            store.update(c.withEnabled(false));
            persistSilently();
        }
    }

    private void persistThen(Runnable then) {
        try { store.save(); then.run(); }
        catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Saved to " + store.file() + " failed:\n" + ex.getMessage(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void persistSilently() {
        try { store.save(); }
        catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Save failed:\n" + ex.getMessage(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ------------------------------------------------------------------
    // list renderer
    // ------------------------------------------------------------------

    private static final class ConnectorCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                       boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof Connector c) {
                String status = c.enabled() ? "\u25cf" : "\u25cb";
                // Show the Zenoh mode inline on Zenoh rows so the
                // operator can tell stream-vs-fan-out apart at a glance.
                // Suppressed for other types where the field is dead weight.
                String zenohHint = (c.type() == Connector.Type.ZENOH)
                        ? "&nbsp;&nbsp;<i>" + c.zenohMode().label() + "</i>"
                        : "";
                setText("<html><b>" + status + " " + escape(c.name()) + "</b>"
                        + "<br><small>" + c.type().label()
                        + " \u2192 " + escape(c.target())
                        + "&nbsp;&nbsp;[" + c.payload() + "]"
                        + zenohHint
                        + "</small></html>");
            }
            return this;
        }
        private static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    // ------------------------------------------------------------------
    // add/edit dialog
    // ------------------------------------------------------------------

    static final class ConnectorEditDialog {

        /** @return the new/edited connector, or null if the operator cancelled. */
        static Connector showFor(Component owner, Connector existing) {
            JTextField nameField   = new JTextField(existing == null ? "" : existing.name(), 20);
            JComboBox<Connector.Type> typeBox = new JComboBox<>(Connector.Type.values());
            typeBox.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(
                        JList<?> l, Object v, int i, boolean sel, boolean focus) {
                    super.getListCellRendererComponent(l, v, i, sel, focus);
                    if (v instanceof Connector.Type t) {
                        setText(t.label());
                        setEnabled(t.isImplemented());
                    }
                    return this;
                }
            });
            if (existing != null) typeBox.setSelectedItem(existing.type());

            JTextField targetField  = new JTextField(existing == null ? "" : existing.target(), 20);
            JComboBox<PayloadFormat> payBox = new JComboBox<>(PayloadFormat.values());
            if (existing != null) payBox.setSelectedItem(existing.payload());

            // Zenoh-only field: key-layout mode. Rendered on every form so
            // the layout is stable across type changes, but enabled only
            // for ZENOH; the operator gets a visual cue that the mode
            // is a Zenoh property, not a global one.
            JComboBox<ZenohMode> zenohModeBox = new JComboBox<>(ZenohMode.values());
            zenohModeBox.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(
                        JList<?> l, Object v, int i, boolean sel, boolean focus) {
                    super.getListCellRendererComponent(l, v, i, sel, focus);
                    if (v instanceof ZenohMode m) setText(m.label());
                    return this;
                }
            });
            zenohModeBox.setSelectedItem(
                    existing == null ? ZenohMode.PER_AIRCRAFT : existing.zenohMode());

            JCheckBox enabledBox = new JCheckBox("Enabled",
                    existing == null || existing.enabled());

            // Live per-type hint so users know what the target format is.
            JLabel targetHint = new JLabel(" ");
            targetHint.setFont(targetHint.getFont().deriveFont(java.awt.Font.ITALIC, 11f));
            Runnable syncHint = () -> {
                Connector.Type t = (Connector.Type) typeBox.getSelectedItem();
                switch (t) {
                    case UDP_UNICAST:   targetHint.setText("host:port  \u2014 e.g. 192.168.1.50:6969"); break;
                    case UDP_MULTICAST: targetHint.setText("group:port  \u2014 e.g. 239.2.3.1:6969"); break;
                    case TCP_SERVER:    targetHint.setText("port  \u2014 e.g. 30003"); break;
                    case ZENOH:         targetHint.setText("endpoint;key-prefix  \u2014 e.g. tcp/localhost:7447;adsb/cot"); break;
                }
            };
            typeBox.addActionListener(e -> syncHint.run());
            syncHint.run();

            // Zenoh mode row is only meaningful when type == ZENOH. Kept
            // visible always so the form geometry is stable, but disabled
            // (grayed) when the selected type doesn't use it. Same UX
            // convention as the OK button's isImplemented() gating.
            Runnable syncModeEnabled = () -> {
                Connector.Type t = (Connector.Type) typeBox.getSelectedItem();
                boolean isZenoh = (t == Connector.Type.ZENOH);
                zenohModeBox.setEnabled(isZenoh);
                zenohModeBox.setToolTipText(isZenoh
                        ? "Stream = one topic for everything; Per aircraft = separate topic per ICAO for CoT"
                        : "Zenoh-only setting; ignored for " + t.label());
            };
            typeBox.addActionListener(e -> syncModeEnabled.run());
            syncModeEnabled.run();

            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(4, 4, 4, 4);
            gc.anchor = GridBagConstraints.WEST;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.gridx = 0; gc.gridy = 0;

            form.add(new JLabel("Name:"),    gc); gc.gridx = 1; form.add(nameField,   gc);
            gc.gridx = 0; gc.gridy++;
            form.add(new JLabel("Type:"),    gc); gc.gridx = 1; form.add(typeBox,     gc);
            gc.gridx = 0; gc.gridy++;
            form.add(new JLabel("Target:"),  gc); gc.gridx = 1; form.add(targetField, gc);
            gc.gridx = 1; gc.gridy++;             form.add(targetHint,  gc);
            gc.gridx = 0; gc.gridy++;
            form.add(new JLabel("Payload:"), gc); gc.gridx = 1; form.add(payBox,      gc);
            gc.gridx = 0; gc.gridy++;
            form.add(new JLabel("Zenoh mode:"), gc); gc.gridx = 1; form.add(zenohModeBox, gc);
            gc.gridx = 1; gc.gridy++;             form.add(enabledBox,  gc);

            // Custom dialog so we can gate OK on type.isImplemented().
            JOptionPane pane = new JOptionPane(form,
                    JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
            JDialog dialog = pane.createDialog(owner,
                    existing == null ? "Add connector" : "Edit connector");

            Runnable syncOk = () -> {
                Connector.Type t = (Connector.Type) typeBox.getSelectedItem();
                for (Object o : findButtons(pane)) {
                    if (o instanceof JButton b && "OK".equals(b.getText())) {
                        b.setEnabled(t.isImplemented()
                                && !nameField.getText().trim().isEmpty()
                                && !targetField.getText().trim().isEmpty());
                        b.setToolTipText(t.isImplemented()
                                ? null
                                : "This connector type is scaffolded but not yet wired");
                    }
                }
            };
            typeBox.addActionListener(e -> syncOk.run());
            nameField.getDocument().addDocumentListener(new SimpleDocListener(syncOk));
            targetField.getDocument().addDocumentListener(new SimpleDocListener(syncOk));
            SwingUtilities.invokeLater(syncOk);

            dialog.setVisible(true);
            Object result = pane.getValue();
            dialog.dispose();
            if (!(result instanceof Integer) || (Integer) result != JOptionPane.OK_OPTION) {
                return null;
            }

            String name    = nameField.getText().trim();
            Connector.Type type    = (Connector.Type) typeBox.getSelectedItem();
            String target  = targetField.getText().trim();
            PayloadFormat pay = (PayloadFormat) payBox.getSelectedItem();
            ZenohMode mode = (ZenohMode) zenohModeBox.getSelectedItem();
            boolean enabled = enabledBox.isSelected();

            if (existing == null) {
                return Connector.newInstance(name, type, target, pay, mode, enabled);
            }
            return new Connector(existing.id(), name, type, target, pay, mode, enabled);
        }

        /** Walk the pane to find its OK/Cancel buttons so we can enable/disable OK. */
        private static java.util.List<JButton> findButtons(Component c) {
            java.util.List<JButton> out = new java.util.ArrayList<>();
            java.util.Deque<Component> stack = new java.util.ArrayDeque<>();
            stack.push(c);
            while (!stack.isEmpty()) {
                Component cur = stack.pop();
                if (cur instanceof JButton b) out.add(b);
                if (cur instanceof java.awt.Container cc) {
                    for (Component child : cc.getComponents()) stack.push(child);
                }
            }
            return out;
        }

        private static final class SimpleDocListener implements javax.swing.event.DocumentListener {
            private final Runnable r;
            SimpleDocListener(Runnable r) { this.r = r; }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e)  { r.run(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e)  { r.run(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        }
    }
}
