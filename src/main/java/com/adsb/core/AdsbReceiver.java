package com.adsb.core;

import com.adsb.cot.CoTBuilder;
import com.adsb.model.AdsbFrame;
import com.adsb.model.AircraftStateStore;
import com.adsb.model.TrackMerger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the {@code rtl_adsb} subprocess and the per-frame dispatch loop.
 * Sinks (UDP/multicast/TCP/etc) are pulled from a {@link SinkRegistry}
 * so the UI can attach/detach connectors at runtime without
 * restarting the receiver.
 *
 * <p><b>Payload dispatch:</b> each frame may be materialised in up to
 * three shapes (AVR / JSON / CoT). Each shape is computed only if at
 * least one registered sink wants it (see
 * {@link SinkRegistry#anyWants(PayloadFormat)}), so a receiver with
 * only AVR sinks pays nothing for JSON parse work.
 *
 * <p>CoT is a special case: it is not emitted per-frame from the
 * dispatch loop. Instead, {@code CoTForwarder} (wired by the caller
 * to be a {@link AircraftStateStore} listener) emits one CoT
 * document per aggregated snapshot update. This class only touches
 * the state store when at least one CoT sink is registered.
 */
public class AdsbReceiver {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private final int     deviceIndex;
    private final String  gain;
    private final String  format;
    private final boolean verbose;
    private final String  rtlPath;
    private final AircraftStateStore stateStore;
    private final CoTBuilder     cotBuilder;
    private final SinkRegistry   sinks;

    /**
     * @param stateStore may be null when the receiver is only serving
     *                   stateless AVR/JSON sinks
     * @param cotBuilder may be null when no CoT sink is envisaged; only
     *                   consulted by whichever caller wires the CoT
     *                   state-store listener
     * @param sinks      the live sink set; iterate per frame and
     *                   dispatch. Never null.
     */
    public AdsbReceiver(int deviceIndex, String gain, String format,
                        boolean verbose, String rtlPath,
                        AircraftStateStore stateStore,
                        CoTBuilder cotBuilder,
                        SinkRegistry sinks) {
        this.deviceIndex = deviceIndex;
        this.gain        = gain;
        this.format      = format;
        this.verbose     = verbose;
        this.rtlPath     = rtlPath;
        this.stateStore  = stateStore;
        this.cotBuilder  = cotBuilder;
        this.sinks       = sinks == null ? new SinkRegistry() : sinks;
    }

    /**
     * Legacy back-compat constructor: no state store, no CoT, no sinks
     * (empty registry). Retained so any external caller that used the
     * pre-connector API still compiles; production has moved on.
     */
    public AdsbReceiver(int deviceIndex, String gain, String format,
                        boolean verbose, String rtlPath) {
        this(deviceIndex, gain, format, verbose, rtlPath,
             null, null, new SinkRegistry());
    }

    public SinkRegistry sinks() { return sinks; }

    /**
     * Old {@code start(List<AutoCloseable>)} contract kept for
     * pre-connector callers (like the earlier smoke tests). Each item
     * is registered into the SinkRegistry as an AVR-payload sink
     * (matching legacy behaviour of "put raw frames on the wire").
     */
    public void start(List<? extends AutoCloseable> legacyForwarders) throws Exception {
        int i = 0;
        for (AutoCloseable ac : legacyForwarders) {
            if (ac instanceof FrameForwarder ff) {
                sinks.add("legacy-" + (i++), PayloadFormat.JSON, ff);
            }
        }
        start();
    }

    /** Modern start: sinks come from the {@link SinkRegistry}, mutated live. */
    public void start() throws Exception {
        ProcessBuilder pb = buildProcess();
        pb.redirectErrorStream(false);
        System.out.println("[INFO] Starting: " + String.join(" ", pb.command()));
        Process proc = pb.start();

        // Drain stderr in a background daemon thread to prevent pipe blocking.
        Thread stderrDrainer = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = err.readLine()) != null) {
                    System.err.println("[rtl_adsb] " + line);
                }
            } catch (Exception ignored) {}
        }, "stderr-drainer");
        stderrDrainer.setDaemon(true);
        stderrDrainer.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), Charset.defaultCharset()))) {

            String line;
            long frameCount = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                frameCount++;

                // Feed the state store whenever a CoT sink or the UI is
                // interested (both subscribe via AircraftStateStore listeners).
                if (stateStore != null) {
                    AdsbFrame typed = AdsbDecoder.decodeTyped(line);
                    if (typed != null) TrackMerger.merge(stateStore, typed);
                }

                // Compute each payload shape lazily \u2014 only if some sink wants it.
                byte[] avrBytes  = null;
                byte[] jsonBytes = null;
                boolean anyAvr  = sinks.anyWants(PayloadFormat.AVR);
                boolean anyJson = sinks.anyWants(PayloadFormat.JSON);

                if (anyAvr) {
                    avrBytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
                }
                if (anyJson) {
                    String json = AdsbDecoder.decode(line);
                    if (json != null) jsonBytes = (json + "\n").getBytes(StandardCharsets.UTF_8);
                }

                if (verbose) {
                    System.out.printf("[FRAME %06d] %s%n", frameCount, line);
                }

                for (SinkRegistry.AttachedSink s : sinks.snapshot()) {
                    byte[] payloadBytes = switch (s.payload()) {
                        case AVR  -> avrBytes;
                        case JSON -> jsonBytes;
                        case COT  -> null;       // CoT flows via the state-store listener
                    };
                    if (payloadBytes == null) continue;
                    try {
                        s.forwarder().forward(payloadBytes);
                    } catch (Exception e) {
                        System.err.println("[WARN] Forward error on sink " + s.id()
                                + " (" + s.payload() + "): " + e.getMessage());
                    }
                }
            }
        }

        int exit = proc.waitFor();
        System.out.printf("[INFO] rtl_adsb exited with code %d%n", exit);
    }

    private ProcessBuilder buildProcess() {
        List<String> cmd = new ArrayList<>();
        String exe = IS_WINDOWS ? "rtl_adsb.exe" : "rtl_adsb";
        if (rtlPath != null && !rtlPath.isBlank()) exe = rtlPath + File.separator + exe;
        cmd.add(exe);
        cmd.add("-d"); cmd.add(String.valueOf(deviceIndex));
        if (!"auto".equalsIgnoreCase(gain)) { cmd.add("-g"); cmd.add(gain); }
        if ("raw".equalsIgnoreCase(format)) cmd.add("-V");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(System.getenv());
        return pb;
    }
}
