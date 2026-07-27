package com.adsb.cli;

import com.adsb.core.AdsbReceiver;
import com.adsb.transport.MulticastForwarder;
import com.adsb.transport.TcpForwarder;
import com.adsb.transport.UdpForwarder;

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

        // Start receiver — blocks until process dies or SIGINT
        AdsbReceiver receiver = new AdsbReceiver(cfg.deviceIndex, cfg.gain, cfg.format,
                cfg.verbose, cfg.rtlPath);
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
                default:
                    System.err.println("[WARN] Unknown argument: " + args[i]);
            }
        }
        return cfg;
    }

    static void printUsage() {
        System.err.println("""
            Usage: java -jar adsb-forwarder.jar [options]
            
            Output (at least one required):
              --udp <host:port>           UDP unicast destination
              --multicast <group:port>    UDP multicast group (e.g. 239.1.1.1:30003)
              --tcp-port <port>           TCP server port (clients connect to receive)
            
            RTL-SDR options:
              --rtl-device <index>        Device index (default: 0)
              --gain <value>              Gain value or 'auto' (default: auto)
              --format <avr|beast|raw>    Frame format (default: avr)
            
            Other:
              --verbose                   Print frames to stdout
            
            Examples:
              # Forward to localhost:30003 via UDP
              java -jar adsb-forwarder.jar --udp 127.0.0.1:30003
            
              # TCP server on port 30003 (e.g. for dump1090-compatible clients)
              java -jar adsb-forwarder.jar --tcp-port 30003
            
              # All three simultaneously
              java -jar adsb-forwarder.jar \\
                --udp 192.168.1.100:30003 \\
                --multicast 239.1.1.1:30003 \\
                --tcp-port 30003 --verbose
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

        boolean hasAnyForwarder() {
            return udpHost != null || multicastGroup != null || tcpPort > 0;
        }
    }
}
