package com.adsb.ui;

import javax.swing.BorderFactory;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.event.HyperlinkEvent;
import java.awt.BorderLayout;
import java.awt.Desktop;

/** About/help dock. Static content + clickable links. */
public final class AboutPanel extends JPanel {

    public AboutPanel(String version) {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JEditorPane html = new JEditorPane("text/html", body(version));
        html.setEditable(false);
        html.setOpaque(false);
        html.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                try {
                    if (Desktop.isDesktopSupported()
                            && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(e.getURL().toURI());
                    }
                } catch (Exception ignored) {}
            }
        });
        add(new JScrollPane(html), BorderLayout.CENTER);
    }

    private static String body(String version) {
        return "<html><body style='font-family:sans-serif;font-size:11pt'>"
                + "<h2>ADS-B Receiver</h2>"
                + "<p>Version " + version + "</p>"
                + "<p>Receives ADS-B Mode-S frames from an RTL-SDR dongle and forwards them as "
                + "raw AVR, decoded JSON, or CoT XML to any number of user-configured "
                + "connectors.</p>"
                + "<h3>Links</h3>"
                + "<ul>"
                + "<li><a href='https://github.com/mdudel/ads-b-rcvr'>Source on GitHub</a></li>"
                + "<li><a href='https://github.com/mdudel/ads-b-rcvr/issues'>Issues</a></li>"
                + "<li><a href='https://github.com/openskynetwork/java-adsb'>OpenSky java-adsb</a> "
                + "(vendored decoder)</li>"
                + "</ul>"
                + "<h3>Tips</h3>"
                + "<ul>"
                + "<li>Aircraft take ~5 s to first-fix after they appear \u2014 that's the "
                + "OpenSky global (even+odd pair) CPR decode warmup.</li>"
                + "<li>Add UDP/multicast/TCP connectors from the Connectors dock to forward "
                + "frames to WinTAK / ATAK / GCCS-J COP.</li>"
                + "<li>Zenoh is coming soon (issue #4).</li>"
                + "</ul>"
                + "</body></html>";
    }
}
