package com.adsb.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;

/**
 * Left-side collapsible dock. Owns a {@link JSplitPane} whose left
 * child swaps between the panels bound to the toolbar buttons
 * (Tracks / Connectors / Settings / About) and whose right child is
 * the always-on map.
 *
 * <p>Content-swap semantics: {@link #show} either opens the dock (if
 * closed) or replaces the current content (if open with a different
 * id). {@link #toggle} closes the dock if the requested id is already
 * showing \u2014 same button pressed twice closes.
 *
 * <p>Not thread-safe; construct + mutate on the EDT only.
 *
 * <p>Structural pattern lifted from the {@code tmsweb3190/client}
 * {@code SideDock} at Marty's 2026-07-27 UI direction. Simplified:
 * no persistence of the last-open panel + width (can add later; the
 * ads-b-rcvr UI is a first-cut).
 */
public final class SideDock extends JPanel {

    /** Divider position when the dock is closed. */
    private static final int CLOSED_DIVIDER = 0;
    /** Fallback width when the dock is first opened. */
    private static final int DEFAULT_OPEN_WIDTH = 320;
    /** Below this the content is unreadable; JSplitPane enforces on drag. */
    private static final int MIN_OPEN_WIDTH = 220;

    private final JSplitPane split;
    private final JPanel     dockShell;
    private final JLabel     titleLabel;
    private final JPanel     contentSlot;
    private final Component  mainArea;

    private String currentId;
    private int    lastOpenDivider = DEFAULT_OPEN_WIDTH;

    public SideDock(Component mainArea) {
        super(new BorderLayout());
        if (mainArea == null) throw new IllegalArgumentException("mainArea");
        this.mainArea = mainArea;

        this.titleLabel = new JLabel(" ");
        this.titleLabel.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

        JButton closeBtn = new JButton("\u2715");
        closeBtn.setToolTipText("Close panel");
        closeBtn.setFocusable(false);
        closeBtn.setMargin(new java.awt.Insets(0, 6, 0, 6));
        closeBtn.addActionListener(e -> hide());

        JPanel titleBar = new JPanel(new BorderLayout());
        titleBar.add(titleLabel, BorderLayout.CENTER);
        JPanel closeCluster = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        closeCluster.add(closeBtn);
        titleBar.add(closeCluster, BorderLayout.EAST);
        titleBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, java.awt.Color.LIGHT_GRAY));

        this.contentSlot = new JPanel(new BorderLayout());

        this.dockShell = new JPanel(new BorderLayout());
        dockShell.add(titleBar,    BorderLayout.NORTH);
        dockShell.add(contentSlot, BorderLayout.CENTER);

        this.split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, dockShell, mainArea);
        split.setDividerSize(6);
        split.setContinuousLayout(true);
        split.setResizeWeight(0.0);          // drag grows the map, not the dock
        split.setDividerLocation(CLOSED_DIVIDER);
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            int loc = (Integer) e.getNewValue();
            if (loc >= MIN_OPEN_WIDTH) lastOpenDivider = loc;
        });

        add(split, BorderLayout.CENTER);
    }

    /**
     * Open or replace: show the given content under the given title/id.
     * If the dock was closed, opens to the last-open width.
     */
    public void show(String id, String title, Component content) {
        titleLabel.setText(title);
        contentSlot.removeAll();
        contentSlot.add(content, BorderLayout.CENTER);
        contentSlot.revalidate();
        contentSlot.repaint();
        currentId = id;
        if (split.getDividerLocation() < MIN_OPEN_WIDTH) {
            split.setDividerLocation(lastOpenDivider);
        }
    }

    /** Same as {@link #show} but closes the dock if the requested id is already showing. */
    public void toggle(String id, String title, Component content) {
        if (id.equals(currentId) && split.getDividerLocation() >= MIN_OPEN_WIDTH) {
            hide();
        } else {
            show(id, title, content);
        }
    }

    /** Collapse the dock; content stays wired but hidden. */
    public void hide() {
        if (split.getDividerLocation() >= MIN_OPEN_WIDTH) {
            lastOpenDivider = split.getDividerLocation();
        }
        split.setDividerLocation(CLOSED_DIVIDER);
        currentId = null;
    }

    /** @return id of the currently-shown content, or null when closed. */
    public String currentId() { return currentId; }

    /** For unit-testable size hints; default to a comfortable 1200x800. */
    @Override public Dimension getPreferredSize() { return new Dimension(1200, 800); }
}
