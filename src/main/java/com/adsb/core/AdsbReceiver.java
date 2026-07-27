package com.adsb.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class AdsbReceiver {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private final int     deviceIndex;
    private final String  gain;
    private final String  format;
    private final boolean verbose;
    private final String  rtlPath;

    public AdsbReceiver(int deviceIndex, String gain, String format,
                        boolean verbose, String rtlPath) {
        this.deviceIndex = deviceIndex;
        this.gain        = gain;
        this.format      = format;
        this.verbose     = verbose;
        this.rtlPath     = rtlPath;
    }

    public void start(List<? extends AutoCloseable> forwarders) throws Exception {
        ProcessBuilder pb = buildProcess();
        pb.redirectErrorStream(false);
        System.out.println("[INFO] Starting: " + String.join(" ", pb.command()));
        Process proc = pb.start();

        // Drain stderr in a background daemon thread to prevent pipe blocking
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

        // Read AVR frames from stdout, decode to JSON, forward
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), Charset.defaultCharset()))) {

            String line;
            long frameCount = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Decode hex AVR frame -> JSON
                String json = AdsbDecoder.decode(line);
                if (json == null) continue; // skip malformed/unsupported frames

                byte[] frame = (json + "\n").getBytes(StandardCharsets.UTF_8);

                if (verbose) {
                    System.out.printf("[FRAME %06d] %s%n", ++frameCount, json);
                }

                for (AutoCloseable fwd : forwarders) {
                    if (fwd instanceof FrameForwarder ff) {
                        try {
                            ff.forward(frame);
                        } catch (Exception e) {
                            System.err.println("[WARN] Forward error ("
                                    + fwd.getClass().getSimpleName() + "): " + e.getMessage());
                        }
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
