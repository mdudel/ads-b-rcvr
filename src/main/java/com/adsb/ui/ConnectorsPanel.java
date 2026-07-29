package com.adsb.ui;

import com.adsb.core.PayloadFormat;
import com.adsb.ui.model.Connector;
import com.adsb.ui.model.ConnectorAttacher;
import com.adsb.ui.model.ConnectorStore;
import com.adsb.ui.model.ZenohMode;
import com.adsb.ui.model.ZenohTransport;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
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
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
 *
 * <p><b>Zenoh sub-form</b> (added 2026-07-29 for Marty's rich Zenoh
 * connector work): when the type dropdown is set to ZENOH, the
 * generic "Target" line hides and a dedicated grid appears with
 * dropdown Transport, separate Endpoint / Root topic / Topic fields,
 * three Browse-button rows for the TLS material, and a Verify-host
 * checkbox. TLS rows grey out when the transport is TCP/WS.
 * "Last-used cert dir" is remembered in the properties file via
 * {@link #lastCertDirRef} so repeated Browse clicks don't force the
 * operator to re-navigate to their PEM stash.
 */
public final class ConnectorsPanel extends JPanel {

    private final ConnectorStore     store;
    private final ConnectorAttacher  attacher;
    private final DefaultListModel<Connector> listModel = new DefaultListModel<>();
    private final JList<Connector>   list;

    /**
     * Mutable ref holding the last directory a Browse button visited.
     * Persisted by the Main bootstrap under {@code ui.lastCertDir} so
     * it survives restarts. Nullable when nothing has been remembered
     * yet.
     */
    private final Supplier<String>   lastCertDirRef;
    private final Consumer<String>   lastCertDirSetter;

    /** 2-arg ctor for tests / minimal callers (no cert-dir persistence). */
    public ConnectorsPanel(ConnectorStore store, ConnectorAttacher attacher) {
        this(store, attacher, () -> null, s -> {});
    }

    /**
     * @param lastCertDirRef supplier that returns the last dir a Browse
     *                       button visited (may return null).
     * @param lastCertDirSetter setter called whenever a Browse button
     *                          commits a new directory; typical impl
     *                          persists to the properties file.
     */
    public ConnectorsPanel(ConnectorStore store, ConnectorAttacher attacher,
                           Supplier<String> lastCertDirRef,
                           Consumer<String> lastCertDirSetter) {
        super(new BorderLayout(4, 4));
        this.store = store;
        this.attacher = attacher;
        this.lastCertDirRef    = lastCertDirRef    == null ? () -> null : lastCertDirRef;
        this.lastCertDirSetter = lastCertDirSetter == null ? s -> {}    : lastCertDirSetter;

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
        Connector c = ConnectorEditDialog.showFor(this, null, lastCertDirRef, lastCertDirSetter);
        if (c == null) return;
        store.add(c);
        persistThen(() -> attach(c));
    }

    private void onEdit() {
        Connector sel = list.getSelectedValue();
        if (sel == null) return;
        Connector edited = ConnectorEditDialog.showFor(this, sel, lastCertDirRef, lastCertDirSetter);
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
                // For Zenoh rows the 'target' is empty; render the
                // richer field set inline so the operator can see at
                // a glance what's configured.
                String targetDisplay = (c.type() == Connector.Type.ZENOH)
                        ? renderZenohTarget(c)
                        : escape(c.target());
                String zenohHint = (c.type() == Connector.Type.ZENOH)
                        ? "&nbsp;&nbsp;<i>" + c.zenohMode().label() + "</i>"
                        : "";
                setText("<html><b>" + status + " " + escape(c.name()) + "</b>"
                        + "<br><small>" + c.type().label()
                        + " \u2192 " + targetDisplay
                        + "&nbsp;&nbsp;[" + c.payload() + "]"
                        + zenohHint
                        + "</small></html>");
            }
            return this;
        }
        private static String renderZenohTarget(Connector c) {
            String scheme = c.zenohTransport() == null ? "tcp" : c.zenohTransport().scheme();
            String ep = c.zenohEndpoint() == null ? "?" : c.zenohEndpoint();
            String key = c.zenohKeyExpr() == null ? "?" : c.zenohKeyExpr();
            String org = (c.zenohOrg() == null || c.zenohOrg().isBlank()) ? "" : (c.zenohOrg() + "/");
            return escape(scheme + "/" + ep + "  \u2192  " + org + key);
        }
        private static String escape(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    // ------------------------------------------------------------------
    // add/edit dialog
    // ------------------------------------------------------------------

    static final class ConnectorEditDialog {

        /** @return the new/edited connector, or null if the operator cancelled. */
        static Connector showFor(Component owner, Connector existing,
                                 Supplier<String> lastCertDirRef,
                                 Consumer<String> lastCertDirSetter) {

            // Shared (all-types) fields ------------------------------------
            JTextField nameField = new JTextField(existing == null ? "" : existing.name(), 20);

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

            JTextField targetField = new JTextField(
                    existing == null ? "" : (existing.target() == null ? "" : existing.target()), 20);

            JComboBox<PayloadFormat> payBox = new JComboBox<>(PayloadFormat.values());
            if (existing != null) payBox.setSelectedItem(existing.payload());

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

            // Zenoh-specific fields ----------------------------------------
            JComboBox<ZenohTransport> zenohTransportBox = new JComboBox<>(ZenohTransport.values());
            zenohTransportBox.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(
                        JList<?> l, Object v, int i, boolean sel, boolean focus) {
                    super.getListCellRendererComponent(l, v, i, sel, focus);
                    if (v instanceof ZenohTransport t) {
                        setText(t.name() + "  \u2014  " + t.description());
                    }
                    return this;
                }
            });
            zenohTransportBox.setSelectedItem(existing != null && existing.zenohTransport() != null
                    ? existing.zenohTransport() : ZenohTransport.TCP);

            JTextField zenohEndpointField = new JTextField(
                    existing != null && existing.zenohEndpoint() != null
                            ? existing.zenohEndpoint() : "", 20);
            JTextField zenohOrgField = new JTextField(
                    existing != null && existing.zenohOrg() != null
                            ? existing.zenohOrg() : "", 20);
            JTextField zenohTopicField = new JTextField(
                    existing != null && existing.zenohKeyExpr() != null
                            ? existing.zenohKeyExpr() : "", 20);

            JTextField zenohCertField = new JTextField(
                    existing != null && existing.zenohClientCertPath() != null
                            ? existing.zenohClientCertPath() : "", 20);
            JTextField zenohKeyField = new JTextField(
                    existing != null && existing.zenohClientKeyPath() != null
                            ? existing.zenohClientKeyPath() : "", 20);
            JTextField zenohCaField = new JTextField(
                    existing != null && existing.zenohRootCaPath() != null
                            ? existing.zenohRootCaPath() : "", 20);
            JButton zenohCertBrowse = browseButton(owner, zenohCertField,
                    "Choose TLS client certificate PEM", lastCertDirRef, lastCertDirSetter);
            JButton zenohKeyBrowse  = browseButton(owner, zenohKeyField,
                    "Choose TLS client private key PEM", lastCertDirRef, lastCertDirSetter);
            JButton zenohCaBrowse   = browseButton(owner, zenohCaField,
                    "Choose CA / truststore PEM",       lastCertDirRef, lastCertDirSetter);

            JCheckBox zenohVerifyBox = new JCheckBox("Verify TLS hostname",
                    existing != null && existing.zenohVerifyHostname());
            zenohVerifyBox.setToolTipText("Leave unchecked for IP endpoints (Tailscale / CGNAT); "
                    + "check when connecting to a real DNS name that appears in the server cert SAN");

            JLabel zenohPreviewLabel = new JLabel(" ");
            zenohPreviewLabel.setFont(zenohPreviewLabel.getFont().deriveFont(java.awt.Font.ITALIC, 11f));

            // Live target-hint (used only for non-Zenoh types)
            JLabel targetHint = new JLabel(" ");
            targetHint.setFont(targetHint.getFont().deriveFont(java.awt.Font.ITALIC, 11f));

            // -----------------------------------------------------------------
            // Form layout: two rows of shared fields, then either the
            // generic Target/hint row (non-Zenoh) or the Zenoh sub-form.
            // Both variants render into the same form panel with the
            // non-applicable rows hidden -- lets the dialog size stay
            // stable and the user's brain not jump.
            // -----------------------------------------------------------------
            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(4, 4, 4, 4);
            gc.anchor = GridBagConstraints.WEST;
            gc.fill = GridBagConstraints.HORIZONTAL;

            int y = 0;
            addRow(form, gc, y++, "Name:",    nameField);
            addRow(form, gc, y++, "Type:",    typeBox);

            // ----- generic Target row (visible only for non-Zenoh types)
            JLabel   targetLabel = new JLabel("Target:");
            addRow(form, gc, y++, targetLabel, targetField);
            gc.gridx = 1; gc.gridy = y++; form.add(targetHint, gc);
            final int genericTargetRowStart = 2;   // rows 2..3 host generic Target
            final int genericTargetRowEnd   = 3;

            // ----- Zenoh sub-form rows
            int zStart = y;
            JLabel lblTransport = new JLabel("Transport:");
            addRow(form, gc, y++, lblTransport, zenohTransportBox);
            JLabel lblEndpoint  = new JLabel("Endpoint (host:port):");
            addRow(form, gc, y++, lblEndpoint,  zenohEndpointField);
            JLabel lblOrg       = new JLabel("Root topic:");
            addRow(form, gc, y++, lblOrg,       zenohOrgField);
            JLabel lblTopic     = new JLabel("Topic:");
            addRow(form, gc, y++, lblTopic,     zenohTopicField);
            JLabel lblCert      = new JLabel("Client cert (PEM):");
            addBrowseRow(form, gc, y++, lblCert, zenohCertField, zenohCertBrowse);
            JLabel lblKey       = new JLabel("Client key (PEM):");
            addBrowseRow(form, gc, y++, lblKey,  zenohKeyField,  zenohKeyBrowse);
            JLabel lblCa        = new JLabel("Truststore CA (PEM):");
            addBrowseRow(form, gc, y++, lblCa,   zenohCaField,   zenohCaBrowse);
            gc.gridx = 1; gc.gridy = y++; form.add(zenohVerifyBox, gc);
            gc.gridx = 1; gc.gridy = y++; form.add(zenohPreviewLabel, gc);
            int zEnd = y - 1;

            // ----- payload + mode + enabled (bottom shared block)
            addRow(form, gc, y++, "Payload:",     payBox);
            JLabel lblMode = new JLabel("Zenoh mode:");
            addRow(form, gc, y++, lblMode,        zenohModeBox);
            gc.gridx = 1; gc.gridy = y++; form.add(enabledBox, gc);

            java.util.List<Component> genericRow = java.util.List.of(
                    targetLabel, targetField, targetHint);
            java.util.List<Component> zenohRows  = java.util.List.of(
                    lblTransport, zenohTransportBox,
                    lblEndpoint,  zenohEndpointField,
                    lblOrg,       zenohOrgField,
                    lblTopic,     zenohTopicField,
                    lblCert,      zenohCertField, zenohCertBrowse,
                    lblKey,       zenohKeyField,  zenohKeyBrowse,
                    lblCa,        zenohCaField,   zenohCaBrowse,
                    zenohVerifyBox, zenohPreviewLabel,
                    lblMode,      zenohModeBox);
            java.util.List<Component> tlsRows = java.util.List.of(
                    lblCert, zenohCertField, zenohCertBrowse,
                    lblKey,  zenohKeyField,  zenohKeyBrowse,
                    lblCa,   zenohCaField,   zenohCaBrowse,
                    zenohVerifyBox);

            Runnable syncVisibility = () -> {
                Connector.Type t = (Connector.Type) typeBox.getSelectedItem();
                boolean isZenoh = (t == Connector.Type.ZENOH);
                for (Component g : genericRow) g.setVisible(!isZenoh);
                for (Component z : zenohRows)  z.setVisible(isZenoh);

                // Grey out TLS rows for TCP / WS
                if (isZenoh) {
                    ZenohTransport zt = (ZenohTransport) zenohTransportBox.getSelectedItem();
                    boolean tls = zt != null && zt.isTls();
                    for (Component r : tlsRows) r.setEnabled(tls);
                }

                // Per-type hint for the generic target field
                if (!isZenoh) {
                    switch (t) {
                        case UDP_UNICAST:   targetHint.setText("host:port  \u2014 e.g. 192.168.1.50:6969"); break;
                        case UDP_MULTICAST: targetHint.setText("group:port  \u2014 e.g. 239.2.3.1:6969"); break;
                        case TCP_SERVER:    targetHint.setText("port  \u2014 e.g. 30003"); break;
                        default:            targetHint.setText(" ");
                    }
                }
                // Live Zenoh preview
                if (isZenoh) {
                    ZenohTransport zt = (ZenohTransport) zenohTransportBox.getSelectedItem();
                    String ep = zenohEndpointField.getText().trim();
                    String org = zenohOrgField.getText().trim();
                    String topic = zenohTopicField.getText().trim();
                    String fullKey = org.isEmpty() ? topic : (org + "/" + topic);
                    zenohPreviewLabel.setText("<html>\u2192 <tt>"
                            + (zt == null ? "tcp" : zt.scheme()) + "/" + (ep.isEmpty() ? "?" : ep)
                            + "</tt>  publishes under  <tt>" + (fullKey.isEmpty() ? "?" : fullKey)
                            + "</tt></html>");
                }
                form.revalidate();
                form.repaint();
                Component top = SwingUtilities.getWindowAncestor(form);
                if (top instanceof java.awt.Window w) w.pack();
            };
            typeBox.addActionListener(e -> syncVisibility.run());
            zenohTransportBox.addActionListener(e -> syncVisibility.run());
            SimpleDocListener liveSync = new SimpleDocListener(syncVisibility);
            zenohEndpointField.getDocument().addDocumentListener(liveSync);
            zenohOrgField.getDocument().addDocumentListener(liveSync);
            zenohTopicField.getDocument().addDocumentListener(liveSync);

            // Custom dialog so we can gate OK on type.isImplemented()
            // AND on Zenoh-required fields.
            JOptionPane pane = new JOptionPane(form,
                    JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
            JDialog dialog = pane.createDialog(owner,
                    existing == null ? "Add connector" : "Edit connector");

            Runnable syncOk = () -> {
                Connector.Type t = (Connector.Type) typeBox.getSelectedItem();
                for (Object o : findButtons(pane)) {
                    if (o instanceof JButton b && "OK".equals(b.getText())) {
                        boolean ok = t.isImplemented()
                                && !nameField.getText().trim().isEmpty();
                        if (t == Connector.Type.ZENOH) {
                            ok = ok
                                    && !zenohEndpointField.getText().trim().isEmpty()
                                    && !zenohTopicField.getText().trim().isEmpty();
                            ZenohTransport zt = (ZenohTransport) zenohTransportBox.getSelectedItem();
                            if (zt != null && zt.isTls()) {
                                ok = ok
                                        && !zenohCertField.getText().trim().isEmpty()
                                        && !zenohKeyField.getText().trim().isEmpty()
                                        && !zenohCaField.getText().trim().isEmpty();
                            }
                        } else {
                            ok = ok && !targetField.getText().trim().isEmpty();
                        }
                        b.setEnabled(ok);
                        b.setToolTipText(t.isImplemented()
                                ? null
                                : "This connector type is scaffolded but not yet wired");
                    }
                }
            };
            typeBox.addActionListener(e -> syncOk.run());
            zenohTransportBox.addActionListener(e -> syncOk.run());
            SimpleDocListener okSync = new SimpleDocListener(syncOk);
            nameField.getDocument().addDocumentListener(okSync);
            targetField.getDocument().addDocumentListener(okSync);
            zenohEndpointField.getDocument().addDocumentListener(okSync);
            zenohTopicField.getDocument().addDocumentListener(okSync);
            zenohCertField.getDocument().addDocumentListener(okSync);
            zenohKeyField.getDocument().addDocumentListener(okSync);
            zenohCaField.getDocument().addDocumentListener(okSync);
            SwingUtilities.invokeLater(() -> {
                syncVisibility.run();
                syncOk.run();
            });

            dialog.setPreferredSize(new Dimension(560, dialog.getPreferredSize().height));
            dialog.pack();
            dialog.setVisible(true);
            Object result = pane.getValue();
            dialog.dispose();
            if (!(result instanceof Integer) || (Integer) result != JOptionPane.OK_OPTION) {
                return null;
            }

            String name = nameField.getText().trim();
            Connector.Type type = (Connector.Type) typeBox.getSelectedItem();
            PayloadFormat pay = (PayloadFormat) payBox.getSelectedItem();
            ZenohMode mode = (ZenohMode) zenohModeBox.getSelectedItem();
            boolean enabled = enabledBox.isSelected();

            if (type == Connector.Type.ZENOH) {
                ZenohTransport zt = (ZenohTransport) zenohTransportBox.getSelectedItem();
                String ep    = zenohEndpointField.getText().trim();
                String org   = zenohOrgField.getText().trim();
                String topic = zenohTopicField.getText().trim();
                String cert  = zenohCertField.getText().trim();
                String key   = zenohKeyField.getText().trim();
                String ca    = zenohCaField.getText().trim();
                boolean verify = zenohVerifyBox.isSelected();
                if (existing == null) {
                    return Connector.newZenoh(name, zt, ep, org, topic, pay, mode,
                            emptyToNull(cert), emptyToNull(key), emptyToNull(ca),
                            verify, enabled);
                }
                return new Connector(existing.id(), name, Connector.Type.ZENOH,
                        "", pay, mode, enabled,
                        zt, ep, org, topic,
                        emptyToNull(cert), emptyToNull(key), emptyToNull(ca),
                        verify);
            }

            String target = targetField.getText().trim();
            if (existing == null) {
                return Connector.newInstance(name, type, target, pay, mode, enabled);
            }
            // Preserve every Zenoh field on the existing row that we don't
            // touch here -- if the operator edited a UDP row that had
            // stale null Zenoh fields, they stay null; nothing to lose.
            return new Connector(existing.id(), name, type, target, pay, mode, enabled,
                    existing.zenohTransport(),
                    existing.zenohEndpoint(),
                    existing.zenohOrg(),
                    existing.zenohKeyExpr(),
                    existing.zenohClientCertPath(),
                    existing.zenohClientKeyPath(),
                    existing.zenohRootCaPath(),
                    existing.zenohVerifyHostname());
        }

        // -------------------------------------------------------------
        // helpers
        // -------------------------------------------------------------

        private static void addRow(JPanel form, GridBagConstraints gc, int y,
                                   String labelText, Component field) {
            addRow(form, gc, y, new JLabel(labelText), field);
        }
        private static void addRow(JPanel form, GridBagConstraints gc, int y,
                                   JLabel label, Component field) {
            gc.gridx = 0; gc.gridy = y; gc.gridwidth = 1; form.add(label, gc);
            gc.gridx = 1;                                  form.add(field, gc);
        }
        private static void addBrowseRow(JPanel form, GridBagConstraints gc, int y,
                                          JLabel label, Component field, Component browse) {
            gc.gridx = 0; gc.gridy = y; gc.gridwidth = 1; form.add(label, gc);
            JPanel row = new JPanel(new BorderLayout(4, 0));
            row.add(field, BorderLayout.CENTER);
            row.add(browse, BorderLayout.EAST);
            gc.gridx = 1;                                  form.add(row, gc);
        }

        /**
         * Build a "Browse..." button that opens a JFileChooser starting at
         * the last-used cert dir (falling back to the current value of the
         * target field, then user.home). On successful selection, populates
         * the field and calls {@code lastCertDirSetter} so the choice
         * survives the next Add/Edit.
         */
        private static JButton browseButton(Component owner, JTextField field, String title,
                                            Supplier<String> lastCertDirRef,
                                            Consumer<String> lastCertDirSetter) {
            JButton b = new JButton("Browse\u2026");
            b.setFocusable(false);
            b.addActionListener(e -> {
                JFileChooser fc = new JFileChooser();
                fc.setDialogTitle(title);
                // Start at existing field value if it points somewhere real,
                // else the last-used cert dir, else user.home.
                String cur = field.getText().trim();
                if (!cur.isEmpty()) {
                    File f = new File(cur);
                    if (f.getParentFile() != null && f.getParentFile().isDirectory()) {
                        fc.setCurrentDirectory(f.getParentFile());
                    } else if (f.isDirectory()) {
                        fc.setCurrentDirectory(f);
                    }
                } else {
                    String last = lastCertDirRef.get();
                    if (last != null && !last.isBlank()) {
                        File d = new File(last);
                        if (d.isDirectory()) fc.setCurrentDirectory(d);
                    }
                }
                int rv = fc.showOpenDialog(owner);
                if (rv == JFileChooser.APPROVE_OPTION) {
                    File chosen = fc.getSelectedFile();
                    if (chosen != null) {
                        field.setText(chosen.getAbsolutePath());
                        File parent = chosen.getParentFile();
                        if (parent != null) lastCertDirSetter.accept(parent.getAbsolutePath());
                    }
                }
            });
            return b;
        }

        private static String emptyToNull(String s) {
            if (s == null) return null;
            String t = s.trim();
            return t.isEmpty() ? null : t;
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
