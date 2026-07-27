package com.adsb.cli;

import com.adsb.core.AdsbReceiver;
import com.adsb.core.PayloadFormat;
import com.adsb.core.SinkRegistry;
import com.adsb.cot.CoTBuilder;
import com.adsb.cot.IcaoAircraftClassifier;
import com.adsb.cot.IcaoAircraftClassifier.Affiliation;
import com.adsb.cot.IcaoAircraftClassifier.Category;
import com.adsb.model.AircraftStateStore;
import com.adsb.ui.MainFrame;
import com.adsb.ui.model.Connector;
import com.adsb.ui.model.ConnectorAttacher;
import com.adsb.ui.model.ConnectorStore;

import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ADS-B Receiver entry point.
 *
 * <p>Three flavours of run:
 * <ul>
 *   <li><b>CLI only</b> (default): parse {@code --udp / --multicast /
 *       --tcp-port} into one-shot connectors and start the receiver.
 *       Matches the pre-UI behaviour, byte-for-byte on stdout.</li>
 *   <li><b>UI + CLI</b>: {@code --ui} additionally opens the Swing
 *       shell. CLI-provided sinks appear as pre-populated (in-memory)
 *       connectors in the UI's list. Runtime add/remove works.</li>
 *   <li><b>UI only</b>: {@code --ui} with no sink flags loads whatever
 *       connectors are saved in {@code ~/.adsb-rcvr/adsb-rcvr.properties}.</li>
 * </ul>
 */
public class Main {

    /** Bumped by hand for now; wire to git tag in follow-up. */
    static final String VERSION = "0.2.0";

    public static void main(String[] args) throws Exception {
        Config cfg = parseArgs(args);

        System.out.printf("[INFO] ADS-B Receiver %s%n", VERSION);

        // Fail fast if the RTL-SDR executable isn't where we expect it.
        // Rule (Marty 2026-07-27 13:30 UTC): when --rtl-path isn't set,
        // look in the current working directory. Do NOT walk PATH.
        try {
            java.io.File exe = AdsbReceiver.requireRtlAdsbExecutable(cfg.rtlPath);
            System.out.printf("[INFO] Using rtl_adsb: %s%n", exe.getAbsolutePath());
        } catch (java.io.FileNotFoundException e) {
            System.err.println(e.getMessage());
            System.exit(2);
        }

        // Shared plumbing: state store + sink registry + live CoT builder.
        AircraftStateStore stateStore = new AircraftStateStore();
        SinkRegistry sinks = new SinkRegistry();

        IcaoAircraftClassifier initialClassifier = new IcaoAircraftClassifier(
                cfg.cotAffiliation, cfg.cotCategory);
        AtomicReference<CoTBuilder> liveBuilder = new AtomicReference<>(
                new CoTBuilder(initialClassifier,
                        cfg.cotStaleAirSeconds, cfg.cotStaleGroundSeconds));

        // Connector store lives in ~/.adsb-rcvr/adsb-rcvr.properties.
        Path storePath = Paths.get(System.getProperty("user.home"),
                ".adsb-rcvr", "adsb-rcvr.properties");
        ConnectorStore connectorStore = new ConnectorStore(storePath);
        try { connectorStore.load(); }
        catch (Exception e) {
            System.err.println("[WARN] Failed to load " + storePath + ": " + e);
        }

        ConnectorAttacher attacher = new ConnectorAttacher(sinks, stateStore, liveBuilder);

        // Turn CLI sink flags into transient in-memory connectors so they
        // show up in the UI (if enabled) and attach the same way saved
        // connectors do. CLI connectors are NOT persisted \u2014 the operator
        // has to save via the UI to keep them across runs.
        List<Connector> cliConnectors = cliSinksAsConnectors(cfg);
        for (Connector c : cliConnectors) {
            connectorStore.add(c);
            try { attacher.attach(c); }
            catch (Exception e) {
                System.err.printf("[WARN] Failed to attach CLI connector \"%s\": %s%n",
                        c.name(), e.getMessage());
            }
        }

        // Attach every persistent connector that's enabled.
        for (Connector c : connectorStore.list()) {
            if (c.enabled() && !cliContains(cliConnectors, c)) {
                try { attacher.attach(c); }
                catch (Exception e) {
                    System.err.printf("[WARN] Failed to attach saved connector \"%s\": %s%n",
                            c.name(), e.getMessage());
                }
            }
        }

        // Optional UI. Opens on the EDT so the receiver doesn't block on it.
        if (cfg.ui) {
            if (GraphicsEnvironment.isHeadless()) {
                System.err.println("[WARN] --ui requested but the JVM is headless; ignoring.");
            } else {
                SwingUtilities.invokeLater(() -> {
                    MainFrame frame = new MainFrame(VERSION,
                            stateStore, connectorStore, attacher, liveBuilder,
                            cfg.cotAffiliation, cfg.cotCategory,
                            cfg.cotStaleAirSeconds, cfg.cotStaleGroundSeconds);
                    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                    frame.setVisible(true);
                });
            }
        } else if (!cfg.hasAnyForwarder() && connectorStore.list().isEmpty()) {
            cfg.verbose = true;
            System.out.println("[INFO] No connectors and no --ui \u2014 running verbose to stdout.");
            System.out.println("[INFO] Add --udp / --multicast / --tcp-port sinks or use --ui.");
        }

        // Shutdown hook closes every attached sink.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[INFO] Shutting down\u2026");
            sinks.closeAll();
        }));

        // Start receiver \u2014 blocks on the rtl_adsb stdout stream.
        AdsbReceiver receiver = new AdsbReceiver(cfg.deviceIndex, cfg.gain, cfg.format,
                cfg.verbose, cfg.rtlPath,
                stateStore, liveBuilder.get(), sinks);
        receiver.start();
    }

    private static List<Connector> cliSinksAsConnectors(Config cfg) {
        List<Connector> out = new ArrayList<>();
        if (cfg.udpHost != null) {
            out.add(Connector.newInstance("CLI UDP",
                    Connector.Type.UDP_UNICAST,
                    cfg.udpHost + ":" + cfg.udpPort,
                    cfg.payload, true));
        }
        if (cfg.multicastGroup != null) {
            out.add(Connector.newInstance("CLI Multicast",
                    Connector.Type.UDP_MULTICAST,
                    cfg.multicastGroup + ":" + cfg.multicastPort,
                    cfg.payload, true));
        }
        if (cfg.tcpPort > 0) {
            out.add(Connector.newInstance("CLI TCP",
                    Connector.Type.TCP_SERVER,
                    Integer.toString(cfg.tcpPort),
                    cfg.payload, true));
        }
        return out;
    }

    private static boolean cliContains(List<Connector> cli, Connector c) {
        for (Connector k : cli) if (k.id().equals(c.id())) return true;
        return false;
    }

    // -------------------------------------------------------------------------

    static Config parseArgs(String[] args) {
        Config cfg = new Config();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--udp": {
                    String[] parts = args[++i].split(":");
                    cfg.udpHost = parts[0];
                    cfg.udpPort = Integer.parseInt(parts[1]);
                    break;
                }
                case "--multicast": {
                    String[] parts = args[++i].split(":");
                    cfg.multicastGroup = parts[0];
                    cfg.multicastPort = Integer.parseInt(parts[1]);
                    break;
                }
                case "--tcp-port":     cfg.tcpPort = Integer.parseInt(args[++i]); break;
                case "--rtl-device":   cfg.deviceIndex = Integer.parseInt(args[++i]); break;
                case "--rtl-path":     cfg.rtlPath = args[++i]; break;
                case "--gain":         cfg.gain = args[++i]; break;
                case "--format":       cfg.format = args[++i]; break;
                case "--verbose":      cfg.verbose = true; break;
                case "--payload":      cfg.payload = PayloadFormat.parse(args[++i]); break;
                case "--cot-affiliation": cfg.cotAffiliation = parseAffiliation(args[++i]); break;
                case "--cot-category":    cfg.cotCategory    = parseCategory(args[++i]); break;
                case "--cot-stale-air":   cfg.cotStaleAirSeconds    = Integer.parseInt(args[++i]); break;
                case "--cot-stale-ground":cfg.cotStaleGroundSeconds = Integer.parseInt(args[++i]); break;
                case "--ui":           cfg.ui = true; break;
                case "--rx-latlon":
                    i++;
                    System.err.println("[INFO] --rx-latlon is no longer required and is ignored.");
                    break;
                case "-h": case "--help":
                    printUsage();
                    System.exit(0);
                    break;
                default:
                    System.err.println("[WARN] Unknown argument: " + args[i]);
            }
        }
        return cfg;
    }

    private static Affiliation parseAffiliation(String s) {
        return switch (s.trim().toLowerCase()) {
            case "friendly" -> Affiliation.FRIENDLY;
            case "neutral"  -> Affiliation.NEUTRAL;
            case "hostile"  -> Affiliation.HOSTILE;
            case "unknown"  -> Affiliation.UNKNOWN;
            case "pending"  -> Affiliation.PENDING;
            default -> throw new IllegalArgumentException("--cot-affiliation: " + s);
        };
    }

    private static Category parseCategory(String s) {
        return switch (s.trim().toLowerCase()) {
            case "civilian", "civ" -> Category.CIVILIAN;
            case "military", "mil" -> Category.MILITARY;
            default -> throw new IllegalArgumentException("--cot-category: " + s);
        };
    }

    static void printUsage() {
        System.err.println("""
            Usage: java -jar adsb-forwarder.jar [options]

            User interface:
              --ui                        Open the Swing UI (Tracks / Connectors /
                                          Settings / About). Persistent connectors
                                          live in ~/.adsb-rcvr/adsb-rcvr.properties.

            One-shot output (attached as transient in-memory connectors):
              --udp <host:port>           UDP unicast destination
              --multicast <group:port>    UDP multicast group (e.g. 239.1.1.1:30003)
              --tcp-port <port>           TCP server port (clients connect to receive)

            Payload format for the CLI-provided sinks:
              --payload <avr|json|cot>    Wire format (default: json)
                                          avr  = raw hex frames from rtl_adsb
                                          json = decoded JSON (historical shape)
                                          cot  = CoT XML air tracks (TAK-compatible)

            CoT initial settings (also editable live in the Settings dock):
              --cot-affiliation <friendly|neutral|hostile|unknown|pending>
                                          Default: neutral
              --cot-category <civilian|military>
                                          Default: civilian
              --cot-stale-air <seconds>   Airborne stale offset (default 30)
              --cot-stale-ground <seconds>  Ground stale offset (default 120)

            RTL-SDR options:
              --rtl-device <index>        Device index (default: 0)
              --rtl-path <dir>            Folder containing rtl_adsb.exe (if not on PATH)
              --gain <value>              Gain value or 'auto' (default: auto)
              --format <avr|beast|raw>    rtl_adsb frame format (default: avr)

            Other:
              --verbose                   Print frames to stdout
              -h, --help                  Show this help

            Examples:
              # UI-only, load whatever was saved last run
              java -jar adsb-forwarder.jar --ui

              # CLI-only CoT to WinTAK on localhost
              java -jar adsb-forwarder.jar --payload cot --udp 127.0.0.1:6969

              # UI + a bootstrapped multicast CoT sink
              java -jar adsb-forwarder.jar --ui --payload cot --multicast 239.2.3.1:6969
            """);
    }

    // -------------------------------------------------------------------------

    static class Config {
        String  udpHost         = null;
        int     udpPort         = 0;
        String  multicastGroup  = null;
        int     multicastPort   = 0;
        int     tcpPort         = 0;
        int     deviceIndex     = 0;
        String  rtlPath         = null;
        String  gain            = "auto";
        String  format          = "avr";
        boolean verbose         = false;
        boolean ui              = false;

        PayloadFormat payload = PayloadFormat.JSON;
        Affiliation cotAffiliation = null;
        Category    cotCategory    = null;
        int cotStaleAirSeconds     = 30;
        int cotStaleGroundSeconds  = 120;

        boolean hasAnyForwarder() {
            return udpHost != null || multicastGroup != null || tcpPort > 0;
        }
    }
}
