package com.adsb.cli;

import com.adsb.core.AdsbReceiver;
import com.adsb.core.FrameForwarder;
import com.adsb.core.PayloadFormat;
import com.adsb.cot.CoTBuilder;
import com.adsb.cot.IcaoAircraftClassifier;
import com.adsb.cot.IcaoAircraftClassifier.Affiliation;
import com.adsb.cot.IcaoAircraftClassifier.Category;
import com.adsb.model.AircraftStateStore;
import com.adsb.transport.MulticastForwarder;
import com.adsb.transport.TcpForwarder;
import com.adsb.transport.UdpForwarder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ADS-B Forwarder — receives ADS-B frames from an RTL-SDR via rtl_adsb
 * and forwards them over UDP unicast, UDP multicast, or TCP.
 *
 * Usage:
 *   java -jar adsb-forwarder.jar [options]
 *
 * Options:
 *   --udp <host:port>            Forward to UDP unicast address
 *   --multicast <group:port>     Forward to UDP multicast group (e.g. 239.1.1.1:30003)
 *   --tcp-port <port>            Start a TCP server and stream to connected clients
 *   --rtl-device <index>         RTL-SDR device index (default: 0)
 *   --gain <value>               RTL-SDR gain (default: auto)
 *   --format <avr|beast|raw>     Output format (default: avr)
 *   --verbose                    Print decoded frames to stdout
 */
public class Main {

    public static void main(String[] args) throws Exception {
        Config cfg = parseArgs(args);

        System.out.printf("[INFO] Payload format: %s%n", cfg.payload);

        // No forwarder args → default to console scroll mode
        if (!cfg.hasAnyForwarder()) {
            cfg.verbose = true;
            System.out.println("[INFO] No output specified — printing frames to console. Press Ctrl+C to stop.");
            System.out.println("[INFO] Use --udp, --multicast, or --tcp-port to forward frames instead.");
            System.out.println();
        }

        List<AutoCloseable> forwarders = new ArrayList<>();

        // Build forwarders
        if (cfg.udpHost != null) {
            UdpForwarder fwd = new UdpForwarder(cfg.udpHost, cfg.udpPort);
            forwarders.add(fwd);
            System.out.printf("[INFO] UDP unicast -> %s:%d%n", cfg.udpHost, cfg.udpPort);
        }
        if (cfg.multicastGroup != null) {
            MulticastForwarder fwd = new MulticastForwarder(cfg.multicastGroup, cfg.multicastPort);
            forwarders.add(fwd);
            System.out.printf("[INFO] UDP multicast -> %s:%d%n", cfg.multicastGroup, cfg.multicastPort);
        }
        if (cfg.tcpPort > 0) {
            TcpForwarder fwd = new TcpForwarder(cfg.tcpPort);
            fwd.start();
            forwarders.add(fwd);
            System.out.printf("[INFO] TCP server listening on port %d%n", cfg.tcpPort);
        }

        // Register shutdown hook
        final List<AutoCloseable> fwdRef = forwarders;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[INFO] Shutting down...");
            for (AutoCloseable f : fwdRef) {
                try { f.close(); } catch (Exception ignored) {}
            }
        }));

        // State store + optional CoT listener. Both are always created when
        // --payload cot is selected; the listener writes CoT XML bytes into
        // the same forwarder set on every AircraftStateStore snapshot update.
        AircraftStateStore stateStore = new AircraftStateStore();
        CoTBuilder cotBuilder = null;
        if (cfg.payload == PayloadFormat.COT) {
            IcaoAircraftClassifier classifier = new IcaoAircraftClassifier(
                    cfg.cotAffiliation, cfg.cotCategory);
            cotBuilder = new CoTBuilder(classifier,
                    cfg.cotStaleAirSeconds, cfg.cotStaleGroundSeconds);

            final CoTBuilder cotRef = cotBuilder;
            final List<AutoCloseable> sinks = forwarders;
            stateStore.addListener(snapshot -> {
                String xml = cotRef.build(snapshot);
                if (xml == null) return; // no position yet
                byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
                if (cfg.verbose) System.out.printf("[COT] %s%n", xml);
                for (AutoCloseable f : sinks) {
                    if (f instanceof FrameForwarder ff) {
                        try { ff.forward(bytes); }
                        catch (Exception e) {
                            System.err.println("[WARN] CoT forward error ("
                                    + f.getClass().getSimpleName() + "): " + e.getMessage());
                        }
                    }
                }
            });
        }

        // Start receiver — blocks until process dies or SIGINT
        AdsbReceiver receiver = new AdsbReceiver(cfg.deviceIndex, cfg.gain, cfg.format,
                cfg.verbose, cfg.rtlPath,
                cfg.payload, stateStore, cotBuilder);
        receiver.start(forwarders);
    }

    // -------------------------------------------------------------------------
    // Argument parsing
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
                case "--tcp-port":
                    cfg.tcpPort = Integer.parseInt(args[++i]);
                    break;
                case "--rtl-device":
                    cfg.deviceIndex = Integer.parseInt(args[++i]);
                    break;
                case "--rtl-path":
                    cfg.rtlPath = args[++i];
                    break;
                case "--gain":
                    cfg.gain = args[++i];
                    break;
                case "--format":
                    cfg.format = args[++i];
                    break;
                case "--verbose":
                    cfg.verbose = true;
                    break;
                case "--payload":
                    cfg.payload = PayloadFormat.parse(args[++i]);
                    break;
                case "--cot-affiliation":
                    cfg.cotAffiliation = parseAffiliation(args[++i]);
                    break;
                case "--cot-category":
                    cfg.cotCategory = parseCategory(args[++i]);
                    break;
                case "--cot-stale-air":
                    cfg.cotStaleAirSeconds = Integer.parseInt(args[++i]);
                    break;
                case "--cot-stale-ground":
                    cfg.cotStaleGroundSeconds = Integer.parseInt(args[++i]);
                    break;
                case "--rx-latlon":
                    // Legacy flag from an earlier CoT commit. The OpenSky-backed
                    // decoder does global (no-reference) CPR decoding, so a
                    // receiver position is no longer needed. Silently consume
                    // the value to keep old command lines working.
                    i++; // consume the value
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

            Output (at least one required, or run with no sink for stdout scroll):
              --udp <host:port>           UDP unicast destination
              --multicast <group:port>    UDP multicast group (e.g. 239.1.1.1:30003)
              --tcp-port <port>           TCP server port (clients connect to receive)

            Payload format (applies to every enabled sink):
              --payload <avr|json|cot>    Wire format (default: json)
                                          avr  = raw hex frames from rtl_adsb
                                          json = decoded JSON (historical shape)
                                          cot  = CoT XML air tracks (TAK-compatible)

            CoT options (only used when --payload cot):
              --cot-affiliation <friendly|neutral|hostile|unknown|pending>
                                          Default: neutral (correct for civil airliners)
              --cot-category <civilian|military>
                                          Default: civilian
              --cot-stale-air <seconds>   Stale offset for airborne tracks (default 30)
              --cot-stale-ground <seconds>  Stale offset for on-ground tracks (default 120)

            RTL-SDR options:
              --rtl-device <index>        Device index (default: 0)
              --rtl-path <dir>            Folder containing rtl_adsb.exe (if not on PATH)
              --gain <value>              Gain value or 'auto' (default: auto)
              --format <avr|beast|raw>    rtl_adsb frame format (default: avr)

            Other:
              --verbose                   Print frames to stdout
              -h, --help                  Show this help

            Examples:
              # Decoded JSON to localhost (historical default)
              java -jar adsb-forwarder.jar --udp 127.0.0.1:30003

              # Raw AVR frames to a downstream decoder
              java -jar adsb-forwarder.jar --payload avr --tcp-port 30005

              # CoT XML air tracks to WinTAK / ATAK
              java -jar adsb-forwarder.jar --payload cot --udp 127.0.0.1:6969

              # CoT to a multicast group, longer air-stale window, verbose
              java -jar adsb-forwarder.jar --payload cot \\
                --multicast 239.2.3.1:6969 --cot-stale-air 60 --verbose
            """);
    }

    // -------------------------------------------------------------------------
    // Config holder
    // -------------------------------------------------------------------------

    static class Config {
        String udpHost      = null;
        int    udpPort      = 0;
        String multicastGroup = null;
        int    multicastPort  = 0;
        int    tcpPort      = 0;
        int    deviceIndex  = 0;
        String rtlPath      = null;  // e.g. C:\rtl-sdr\bin
        String gain         = "auto";
        String format       = "avr";
        boolean verbose     = false;

        // Payload / CoT knobs
        PayloadFormat payload = PayloadFormat.JSON;
        Affiliation cotAffiliation = null;   // null -> classifier default (neutral)
        Category    cotCategory    = null;   // null -> classifier default (civilian)
        int cotStaleAirSeconds     = 30;
        int cotStaleGroundSeconds  = 120;

        boolean hasAnyForwarder() {
            return udpHost != null || multicastGroup != null || tcpPort > 0;
        }
    }
}
