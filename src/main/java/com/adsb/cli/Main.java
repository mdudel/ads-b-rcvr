package com.adsb.cli;

import com.adsb.core.AdsbDecoder;
import com.adsb.core.AdsbReceiver;
import com.adsb.core.FilterMode;
import com.adsb.core.PayloadFormat;
import com.adsb.core.SinkRegistry;
import com.adsb.transport.CoTDebugForwarder;
import com.adsb.cot.CoTBuilder;
import com.adsb.cot.IcaoAircraftClassifier;
import com.adsb.cot.IcaoAircraftClassifier.Affiliation;
import com.adsb.cot.IcaoAircraftClassifier.Category;
import com.adsb.model.AircraftStateStore;
import com.adsb.ui.MainFrame;
import com.adsb.ui.ThemeMode;
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

        // Configure the position filter (Marty 2026-07-29 08:53 UTC).
        AdsbDecoder.configureFilterMode(cfg.filterMode);
        AdsbDecoder.configureGeofence(cfg.rxLat, cfg.rxLon, cfg.maxRangeNm);
        System.out.printf("[INFO] Position filter mode: %s%n", cfg.filterMode.canonical());
        if (cfg.filterMode == FilterMode.GEOFENCE || cfg.filterMode == FilterMode.BOTH) {
            if (cfg.rxLat != null && cfg.rxLon != null) {
                System.out.printf(
                        "[INFO]   geofence: explicit receiver %.5f,%.5f, envelope %.0f nm%n",
                        cfg.rxLat, cfg.rxLon,
                        cfg.maxRangeNm > 0 ? cfg.maxRangeNm : com.adsb.core.OpenSkyFrameAdapter.DEFAULT_MAX_RANGE_NM);
            } else {
                System.out.printf(
                        "[INFO]   geofence: statistical bootstrap (arms after ~%d fixes, envelope %.0f nm)%n",
                        com.adsb.core.OpenSkyFrameAdapter.BOOTSTRAP_SAMPLES,
                        cfg.maxRangeNm > 0 ? cfg.maxRangeNm : com.adsb.core.OpenSkyFrameAdapter.DEFAULT_MAX_RANGE_NM);
            }
        }

        // Connector store lives in ~/.adsb-rcvr/adsb-rcvr.properties.
        Path storePath = Paths.get(System.getProperty("user.home"),
                ".adsb-rcvr", "adsb-rcvr.properties");
        ConnectorStore connectorStore = new ConnectorStore(storePath);
        try { connectorStore.load(); }
        catch (Exception e) {
            System.err.println("[WARN] Failed to load " + storePath + ": " + e);
        }

        // Filter mode lives in the same properties file under
        // filter.mode. CLI flag wins if given, else the persisted value,
        // else KINEMATIC (Marty 2026-07-29 08:53 UTC default).
        if (!cfg.filterModeExplicit) {
            cfg.filterMode = readFilterMode(storePath);
        } else {
            writeFilterMode(storePath, cfg.filterMode);
        }

        // Theme lives in the same properties file under ui.themeMode.
        // Apply it BEFORE we open any window so the first-paint chrome
        // is already correct — no fatal FlatLaf-loading flicker.
        ThemeMode initialTheme = readThemeMode(storePath);
        if (cfg.ui && !GraphicsEnvironment.isHeadless()) {
            initialTheme.apply();
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

        // CoT debug console sink (Marty 2026-07-29 10:34 UTC).
        // Attached AFTER normal connectors so its output arrives adjacent
        // to whatever the operator is running -- easy to correlate with
        // WARN lines from the adapter or with UDP tx activity.
        if (cfg.cotDebug) {
            java.util.regex.Pattern icaoRe = null;
            if (cfg.cotDebugIcaoRegex != null) {
                try { icaoRe = java.util.regex.Pattern.compile(cfg.cotDebugIcaoRegex,
                        java.util.regex.Pattern.CASE_INSENSITIVE); }
                catch (Exception e) {
                    System.err.println("[WARN] --cot-debug-icao regex invalid, ignoring: " + e);
                }
            }
            CoTDebugForwarder debug = new CoTDebugForwarder(System.out,
                    cfg.cotDebugPretty, cfg.cotDebugRateMs, icaoRe);
            String id = "cot-debug-console";
            sinks.add(id, PayloadFormat.COT, debug);
            // Install a state-store listener mirroring ConnectorAttacher's
            // CoT wiring path -- CoT flows via state-store listener, not the
            // per-frame dispatch loop. Same shape as attacher: build XML from
            // the snapshot, hand to the forwarder.
            stateStore.addListener(snap -> {
                com.adsb.cot.CoTBuilder b = liveBuilder.get();
                if (b == null) return;
                String xml = b.build(snap);
                if (xml == null) return;
                try { debug.forward(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                catch (Exception e) {
                    System.err.println("[WARN] CoT debug console error: " + e);
                }
            });
            System.out.printf(
                    "[INFO] CoT debug console: attached (%s, rate-limit %s, filter %s)%n",
                    cfg.cotDebugPretty ? "pretty" : "single-line",
                    cfg.cotDebugRateMs > 0 ? cfg.cotDebugRateMs + "ms" : "none",
                    cfg.cotDebugIcaoRegex == null ? "all" : cfg.cotDebugIcaoRegex);
        }

        // Build the receiver up-front so both the shutdown hook AND the
        // MainFrame can see it. In UI mode the receiver runs on its own
        // daemon thread so the EDT stays free (and Reconnect button can
        // relaunch it later without blocking anything).
        AdsbReceiver receiverForUi = null;
        if (cfg.ui && !GraphicsEnvironment.isHeadless()) {
            receiverForUi = new AdsbReceiver(cfg.deviceIndex, cfg.gain, cfg.format,
                    cfg.verbose, cfg.rtlPath,
                    stateStore, liveBuilder.get(), sinks);
        }
        final AdsbReceiver uiReceiverRef = receiverForUi;   // effectively-final for the lambda

        // Optional UI. Opens on the EDT so the receiver doesn't block on it.
        if (cfg.ui) {
            if (GraphicsEnvironment.isHeadless()) {
                System.err.println("[WARN] --ui requested but the JVM is headless; ignoring.");
            } else {
                SwingUtilities.invokeLater(() -> {
                    MainFrame frame = new MainFrame(VERSION,
                            stateStore, connectorStore, attacher, liveBuilder,
                            cfg.cotAffiliation, cfg.cotCategory,
                            cfg.cotStaleAirSeconds, cfg.cotStaleGroundSeconds,
                            initialTheme,
                            newTheme -> writeThemeMode(storePath, newTheme),
                            uiReceiverRef);
                    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                    // Belt-and-braces: even though initialTheme.apply()
                    // ran BEFORE this invokeLater lambda was scheduled,
                    // FlatLaf occasionally leaves specific widget
                    // subtrees (esp. JTable in a lazily-shown
                    // JScrollPane, per Marty 2026-07-27 14:49 UTC
                    // screenshot) with cached Metal defaults. Force a
                    // full tree walk so every widget re-resolves
                    // UIManager colours against the current L&F.
                    SwingUtilities.updateComponentTreeUI(frame);
                    frame.setVisible(true);
                });
            }
        } else if (!cfg.hasAnyForwarder() && connectorStore.list().isEmpty()) {
            cfg.verbose = true;
            System.out.println("[INFO] No connectors and no --ui \u2014 running verbose to stdout.");
            System.out.println("[INFO] Add --udp / --multicast / --tcp-port sinks or use --ui.");
        }

        // Shutdown hook closes every attached sink. Order matters:
        // the rtl_adsb child MUST be terminated before the JVM exits,
        // otherwise libusb-1.0 on Windows leaks the USB endpoint and
        // the next launch fails with 'usb_open error -3' (issue #13).
        final AdsbReceiver receiver;
        if (uiReceiverRef != null) {
            receiver = uiReceiverRef;      // reuse the one MainFrame already has
        } else {
            receiver = new AdsbReceiver(cfg.deviceIndex, cfg.gain, cfg.format,
                    cfg.verbose, cfg.rtlPath,
                    stateStore, liveBuilder.get(), sinks);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[INFO] Shutting down\u2026");
            try { receiver.stop(); }
            catch (Exception e) {
                System.err.println("[WARN] Error stopping rtl_adsb: " + e);
            }
            sinks.closeAll();
        }, "adsb-shutdown"));

        if (cfg.ui && !GraphicsEnvironment.isHeadless()) {
            // UI mode: run the receiver on a daemon thread so the EDT
            // (and the Reconnect button) stays responsive. Thread
            // exits when start() returns; Reconnect spawns a fresh one.
            Thread t = new Thread(() -> {
                try { receiver.start(); }
                catch (Exception e) {
                    System.err.println("[ERROR] Receiver thread died: " + e);
                    e.printStackTrace();
                }
            }, "adsb-receiver");
            t.setDaemon(true);   // JVM stays up because Swing EDT is non-daemon
            t.start();
        } else {
            // CLI mode: blocking start() on the main thread as before.
            receiver.start();
        }
    }

    /**
     * Read {@code ui.themeMode} from the shared properties file if it
     * exists; return {@link ThemeMode#LIGHT} if the file is missing,
     * unreadable, or the key isn't set. Shares the file with
     * {@link ConnectorStore}; keeping a single properties file for all
     * UI persistence beats introducing a separate config layer for one
     * enum.
     */
    private static ThemeMode readThemeMode(Path storePath) {
        try {
            if (!java.nio.file.Files.exists(storePath)) return ThemeMode.LIGHT;
            java.util.Properties p = new java.util.Properties();
            try (var in = java.nio.file.Files.newBufferedReader(storePath)) {
                p.load(in);
            }
            return ThemeMode.fromString(p.getProperty("ui.themeMode"));
        } catch (Exception e) {
            System.err.println("[WARN] Could not read ui.themeMode from "
                    + storePath + ": " + e);
            return ThemeMode.LIGHT;
        }
    }

    /**
     * Persist {@code ui.themeMode = <canonical>} into the shared
     * properties file. Preserves every other key (including all the
     * connector.* entries {@link ConnectorStore} owns) by
     * load-mutate-store rather than overwriting.
     */
    /**
     * Read {@code filter.mode} from the shared properties file if it
     * exists; return {@link FilterMode#KINEMATIC} if the file is missing,
     * unreadable, or the key isn't set. Shares the file with connectors
     * + theme + geofence.
     */
    private static FilterMode readFilterMode(Path storePath) {
        try {
            if (!java.nio.file.Files.exists(storePath)) return FilterMode.KINEMATIC;
            java.util.Properties p = new java.util.Properties();
            try (var in = java.nio.file.Files.newBufferedReader(storePath)) {
                p.load(in);
            }
            return FilterMode.fromString(p.getProperty("filter.mode"));
        } catch (Exception e) {
            System.err.println("[WARN] Could not read filter.mode from "
                    + storePath + ": " + e);
            return FilterMode.KINEMATIC;
        }
    }

    /**
     * Persist {@code filter.mode = <canonical>} into the shared properties
     * file. Load-mutate-store so other keys are preserved.
     */
    private static void writeFilterMode(Path storePath, FilterMode mode) {
        try {
            java.nio.file.Files.createDirectories(storePath.getParent());
            java.util.Properties p = new java.util.Properties();
            if (java.nio.file.Files.exists(storePath)) {
                try (var in = java.nio.file.Files.newBufferedReader(storePath)) {
                    p.load(in);
                }
            }
            p.setProperty("filter.mode", mode.canonical());
            java.nio.file.Path tmp = storePath.resolveSibling(
                    storePath.getFileName().toString() + ".tmp");
            try (var out = java.nio.file.Files.newBufferedWriter(tmp)) {
                p.store(out, "ADS-B receiver settings");
            }
            java.nio.file.Files.move(tmp, storePath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            System.err.println("[WARN] Could not persist filter.mode: " + e);
        }
    }

    private static void writeThemeMode(Path storePath, ThemeMode mode) {
        try {
            java.nio.file.Files.createDirectories(storePath.getParent());
            java.util.Properties p = new java.util.Properties();
            if (java.nio.file.Files.exists(storePath)) {
                try (var in = java.nio.file.Files.newBufferedReader(storePath)) {
                    p.load(in);
                }
            }
            p.setProperty("ui.themeMode", mode.canonical());
            java.nio.file.Path tmp = storePath.resolveSibling(
                    storePath.getFileName().toString() + ".tmp");
            try (var out = java.nio.file.Files.newBufferedWriter(tmp)) {
                p.store(out, "ADS-B receiver settings");
            }
            java.nio.file.Files.move(tmp, storePath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            System.err.println("[WARN] Could not persist ui.themeMode: " + e);
        }
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
                case "--rx-latlon": {
                    // Optional filter parameter as of 2026-07-29: no longer
                    // required for CPR decoding (OpenSky decoder does
                    // global pair decode), but when provided, arms the
                    // adapter's receiver-relative geofence from frame #1
                    // instead of the ~20-fix statistical bootstrap.
                    String[] parts = args[++i].split(",");
                    if (parts.length != 2) {
                        throw new IllegalArgumentException(
                                "--rx-latlon expects LAT,LON (e.g. 50.04277,8.32778)");
                    }
                    cfg.rxLat = Double.parseDouble(parts[0].trim());
                    cfg.rxLon = Double.parseDouble(parts[1].trim());
                    break;
                }
                case "--max-range-nm":
                    cfg.maxRangeNm = Double.parseDouble(args[++i]);
                    break;
                case "--filter-mode":
                    cfg.filterMode = FilterMode.fromString(args[++i]);
                    cfg.filterModeExplicit = true;
                    break;
                case "--cot-debug":
                    cfg.cotDebug = true;
                    break;
                case "--cot-debug-pretty":
                    cfg.cotDebug = true;
                    cfg.cotDebugPretty = true;
                    break;
                case "--cot-debug-rate-ms":
                    cfg.cotDebug = true;
                    cfg.cotDebugRateMs = Long.parseLong(args[++i]);
                    break;
                case "--cot-debug-icao":
                    cfg.cotDebug = true;
                    cfg.cotDebugIcaoRegex = args[++i];
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

            CoT debug console (Marty 2026-07-29 10:34 UTC):
              --cot-debug                  Stream every emitted CoT event
                                          to stdout. Sees exactly what goes
                                          on the wire to UDP/multicast/TCP.
              --cot-debug-pretty           Indent XML across multiple lines
                                          (easier to read in a terminal).
                                          Enables --cot-debug automatically.
              --cot-debug-rate-ms <N>      Emit at most one line per ICAO per
                                          N milliseconds (recommend 5000 for
                                          a busy sky). Enables --cot-debug.
              --cot-debug-icao <REGEX>     Only emit for ICAOs matching this
                                          regex, e.g. 4B3810|471DB5 to tail
                                          two specific glitchy targets.
                                          Enables --cot-debug.

            Anti-jump position filter:
              --filter-mode <kinematic|geofence|both|off>
                                          Position-plausibility gate. Default
                                          KINEMATIC (per-track physics budget:
                                          last-known speed * 3 + 200 kt headroom,
                                          hard-capped at 2500 kts, OpenSky jitter
                                          bypass). GEOFENCE uses a receiver-
                                          relative box (needs --rx-latlon for
                                          explicit, else statistical bootstrap).
                                          BOTH = kinematic AND geofence. OFF = no
                                          plausibility filter (debug only).
                                          Persisted to adsb-rcvr.properties.

              --rx-latlon <LAT,LON>       Receiver position for the geofence, e.g.
                                          50.04277,8.32778. Only used in
                                          filter-mode geofence or both. Omitted:
                                          statistical bootstrap arms after ~20
                                          fixes.
              --max-range-nm <N>          Geofence outer envelope in nm
                                          (default 350). Only used in
                                          filter-mode geofence or both.

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

        /** Optional explicit receiver position for the anti-jump geofence. Null -> statistical bootstrap. */
        Double rxLat = null;
        Double rxLon = null;
        /** Geofence outer envelope, nm. 0 -> use OpenSkyFrameAdapter.DEFAULT_MAX_RANGE_NM. */
        double maxRangeNm = 0.0;
        /** Which position-plausibility filter to run. Default resolves from properties file. */
        FilterMode filterMode = FilterMode.KINEMATIC;
        /** True when --filter-mode was passed on the CLI (persist it, override any saved value). */
        boolean filterModeExplicit = false;

        // ------ CoT debug console (Marty 2026-07-29 10:34 UTC) ------
        /** Print every emitted CoT event to stdout for eyeball debugging. */
        boolean cotDebug        = false;
        /** Pretty-print the CoT XML across multiple indented lines. */
        boolean cotDebugPretty  = false;
        /** Rate-limit per-ICAO in ms. 0 = every event. Default 0. */
        long    cotDebugRateMs  = 0L;
        /** Regex filter on ICAO hex; null = all aircraft. Case-insensitive. */
        String  cotDebugIcaoRegex = null;

        boolean hasAnyForwarder() {
            return udpHost != null || multicastGroup != null || tcpPort > 0;
        }
    }
}
