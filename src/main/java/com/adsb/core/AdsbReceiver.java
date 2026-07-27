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
     * Handle to the running {@code rtl_adsb} subprocess so external
     * callers (shutdown hook, UI stop button) can politely tear it
     * down. Held as a volatile ref so we can null-check without
     * locking; {@link Process} itself is thread-safe for
     * {@code destroy}/{@code destroyForcibly}.
     *
     * <p><b>Why this exists (issue #13):</b> if the JVM exits without
     * signalling {@code rtl_adsb.exe} first, the child holds the USB
     * endpoint until Windows notices its parent is gone (which can
     * take 30-120s or need a device reset). The operator then sees
     * {@code usb_open error -3} on the next launch and has to unplug
     * the dongle. Cleaning up here fixes the common exit paths
     * (Ctrl+C, UI close, System.exit). Hard-kills (kill -9, JVM crash,
     * OS force-close) still leak the child because shutdown hooks
     * don't run then — nothing at the Java layer can help there.</p>
     */
    private volatile Process rtlProc;

    /**
     * Set true by {@link #stop()} so the read loop knows that any
     * subsequent IOException from the child's stdout pipe is an
     * expected consequence of teardown, not a real error. Race:
     * stop() calls destroy(), the pipe closes, readLine throws
     * IMMEDIATELY -- often before proc.isAlive() flips to false.
     * The flag closes the race deterministically.
     */
    private volatile boolean stopping;

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

    /**
     * Maximum number of times {@link #start()} will relaunch
     * {@code rtl_adsb} after seeing {@code usb_open error -3} in its
     * stderr (issue #13). Includes the initial attempt, so
     * {@code MAX_USB_LAUNCH_ATTEMPTS = 4} = one first try + three
     * retries. Set conservatively: if three ~3s waits haven't cleared
     * the USB endpoint lock, no amount of waiting will — the operator
     * needs to unplug/replug the dongle.
     */
    private static final int MAX_USB_LAUNCH_ATTEMPTS = 4;

    /** Seconds to wait between USB-lock retries. */
    private static final int USB_RETRY_DELAY_SECONDS = 3;

    /**
     * Regex substring signalling a locked USB endpoint from a previous
     * {@code rtl_adsb} (or another SDR tool) that didn't shut down
     * cleanly. The libusb-1.0 error name is {@code LIBUSB_ERROR_ACCESS};
     * librtlsdr prints the negative errno directly.
     */
    private static final String USB_ACCESS_ERROR_MARKER = "usb_open error -3";

    /**
     * Modern start: sinks come from the {@link SinkRegistry}, mutated
     * live. On {@code usb_open error -3} at launch, waits
     * {@value #USB_RETRY_DELAY_SECONDS} s and relaunches, up to
     * {@value #MAX_USB_LAUNCH_ATTEMPTS} attempts, before giving up.
     */
    public void start() throws Exception {
        for (int attempt = 1; attempt <= MAX_USB_LAUNCH_ATTEMPTS; attempt++) {
            if (stopping) return;
            LaunchResult r = launchOnce(attempt);
            if (r == LaunchResult.STARTED_AND_EXITED_NORMALLY) return;
            if (r != LaunchResult.USB_LOCKED) return; // some other error; surfaced by launchOnce
            if (attempt == MAX_USB_LAUNCH_ATTEMPTS) {
                System.err.println(
                        "[ERROR] rtl_adsb still reporting 'usb_open error -3' after "
                        + MAX_USB_LAUNCH_ATTEMPTS + " attempts.\n"
                      + "        The RTL-SDR dongle's USB endpoint appears locked.\n"
                      + "        Unplug the dongle and reconnect it, then relaunch.");
                return;
            }
            System.out.printf("[WARN] rtl_adsb hit usb_open error -3 (attempt %d/%d). "
                    + "Waiting %ds for the USB endpoint to release, then retrying…%n",
                    attempt, MAX_USB_LAUNCH_ATTEMPTS, USB_RETRY_DELAY_SECONDS);
            try { Thread.sleep(USB_RETRY_DELAY_SECONDS * 1000L); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    /** Outcome of one child-process lifecycle. */
    private enum LaunchResult {
        /** Child ran and exited normally, or shutdown()-was called. Don't retry. */
        STARTED_AND_EXITED_NORMALLY,
        /** Child failed to open the USB device (locked). Retry is worthwhile. */
        USB_LOCKED,
        /** Child failed for some other reason. Already surfaced; don't retry. */
        OTHER_FAILURE
    }

    /**
     * Spawn one {@code rtl_adsb} child, drain stdout into the payload
     * pipeline, drain stderr into a watched buffer, and classify the
     * exit result. Blocks until the child exits or {@link #stop()} is
     * called.
     */
    private LaunchResult launchOnce(int attempt) throws Exception {
        ProcessBuilder pb = buildProcess();
        pb.redirectErrorStream(false);
        if (attempt == 1) {
            System.out.println("[INFO] Starting: " + String.join(" ", pb.command()));
        } else {
            System.out.printf("[INFO] Restarting rtl_adsb (attempt %d/%d)…%n",
                    attempt, MAX_USB_LAUNCH_ATTEMPTS);
        }
        Process proc = pb.start();
        this.rtlProc = proc;

        // Watched stderr flag: set when we see the USB-locked marker.
        final java.util.concurrent.atomic.AtomicBoolean sawUsbLock =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        // Drain stderr in a background daemon thread to prevent pipe blocking.
        Thread stderrDrainer = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = err.readLine()) != null) {
                    if (line.contains(USB_ACCESS_ERROR_MARKER)) {
                        sawUsbLock.set(true);
                    }
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

            while (true) {
                try {
                    line = reader.readLine();
                } catch (java.io.IOException e) {
                    // Expected on shutdown: stop() closed the subprocess
                    // which slammed our end of its stdout pipe. Not an
                    // error, just our cue to stop reading.
                    if (stopping || !proc.isAlive()) break;
                    throw e;
                }
                if (line == null) break;
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
        } catch (java.io.IOException e) {
            // Same stream-closed race on shutdown; swallow if we're
            // already tearing down, otherwise re-throw.
            if (!stopping && proc.isAlive()) throw e;
        }

        int exit;
        try {
            exit = proc.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exit = -1;
        }
        this.rtlProc = null;
        System.out.printf("[INFO] rtl_adsb exited with code %d%n", exit);

        // Give the stderr drainer a brief chance to flush the tail of
        // its buffer so the USB-lock marker has a chance to arrive
        // before we classify. 200 ms is plenty; the child is already
        // dead and its stderr FD is at EOF.
        try { stderrDrainer.join(200); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        if (stopping) return LaunchResult.STARTED_AND_EXITED_NORMALLY;
        if (exit != 0 && sawUsbLock.get()) return LaunchResult.USB_LOCKED;
        if (exit == 0) return LaunchResult.STARTED_AND_EXITED_NORMALLY;
        return LaunchResult.OTHER_FAILURE;
    }

    /**
     * Politely terminate any running {@code rtl_adsb} subprocess.
     * Called from the shutdown hook so the child cleans up its libusb
     * handle before Windows notices we're gone. Idempotent; safe to
     * call when no subprocess is running.
     *
     * <p>Sequence: {@code destroy()} first (SIGTERM on POSIX,
     * CTRL_BREAK on Windows via Java 9+ ProcessHandle) so
     * {@code rtl_adsb} gets a chance to run its own atexit /
     * {@code rtlsdr_close()} cleanup. Wait up to 2 s. If still alive,
     * {@code destroyForcibly()} (SIGKILL / TerminateProcess). 2 s is
     * generous — rtl_adsb typically returns from its sample loop and
     * closes the device in < 200 ms.
     */
    public void stop() {
        this.stopping = true;
        Process p = this.rtlProc;
        if (p == null || !p.isAlive()) return;
        System.out.println("[INFO] Stopping rtl_adsb subprocess…");
        p.destroy();
        try {
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                System.out.println("[WARN] rtl_adsb did not exit within 2s; forcing.");
                p.destroyForcibly();
                p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        this.rtlProc = null;
    }

    private ProcessBuilder buildProcess() throws java.io.FileNotFoundException {
        List<String> cmd = new ArrayList<>();
        // Reuse the exact same resolution the pre-flight uses so we never
        // hand ProcessBuilder a bare filename that would trigger a PATH
        // walk (which was the leftover bug after the pre-flight landed:
        // pre-flight said "found in cwd", then buildProcess passed just
        // "rtl_adsb" as argv[0] and PB threw "Cannot run program").
        java.io.File exe = requireRtlAdsbExecutable(rtlPath);
        cmd.add(exe.getAbsolutePath());
        cmd.add("-d"); cmd.add(String.valueOf(deviceIndex));
        if (!"auto".equalsIgnoreCase(gain)) { cmd.add("-g"); cmd.add(gain); }
        if ("raw".equalsIgnoreCase(format)) cmd.add("-V");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(System.getenv());
        return pb;
    }

    /**
     * Pre-flight: confirm the {@code rtl_adsb} executable exists at the
     * expected location before we open the UI or attach any connectors.
     *
     * <p>Resolution rules:
     * <ol>
     *   <li>If {@code rtlPath} is set (from {@code --rtl-path}), require
     *       the executable at exactly that folder.</li>
     *   <li>Otherwise, require it in the current working directory (i.e.
     *       the folder the receiver was launched from). This matches the
     *       shipped {@code run.bat} convention on Windows where
     *       {@code rtl_adsb.exe} and the DLLs sit next to the jar.</li>
     * </ol>
     *
     * <p>{@link System#getenv("PATH")} is deliberately NOT consulted —
     * per Marty's 2026-07-27 direction, the receiver looks in the local
     * directory, not the wider PATH. Users who install {@code rtl_adsb}
     * system-wide should still pass {@code --rtl-path} to be explicit.
     *
     * @param rtlPath value of the {@code --rtl-path} CLI flag, or null
     *                to check the current working directory instead.
     * @return the resolved absolute {@link java.io.File} for the executable
     * @throws java.io.FileNotFoundException with a user-facing message if
     *         the executable is not present at the expected location.
     */
    public static java.io.File requireRtlAdsbExecutable(String rtlPath) throws java.io.FileNotFoundException {
        String exeName = IS_WINDOWS ? "rtl_adsb.exe" : "rtl_adsb";
        // For the cwd-fallback branch we read user.dir explicitly rather
        // than new File(".").getAbsoluteFile(). Reason: the JVM caches
        // the initial cwd at C-level; System.setProperty("user.dir", ...)
        // does NOT change what File(".") resolves to. Reading user.dir
        // directly lets our unit tests flip cwd for hermetic runs, and
        // has zero effect on production (real launches don't move cwd
        // mid-process).
        java.io.File dir = (rtlPath != null && !rtlPath.isBlank())
                ? new java.io.File(rtlPath)
                : new java.io.File(System.getProperty("user.dir", "."));
        java.io.File exe = new java.io.File(dir, exeName);
        if (!exe.isFile()) {
            String source = (rtlPath != null && !rtlPath.isBlank())
                    ? "--rtl-path folder"
                    : "current directory";
            // Normalise the path so the operator sees a clean form
            // (e.g. "/tmp" not "/tmp/.") in the error message.
            String shown = dir.toPath().toAbsolutePath().normalize().toString();
            throw new java.io.FileNotFoundException(
                    "[ERROR] " + exeName + " not found in " + source + " ("
                            + shown + ").\n"
                            + "        Either drop " + exeName + " (plus its DLLs) into the launch folder,\n"
                            + "        or pass --rtl-path <dir> pointing at the folder that contains it.\n"
                            + "        Download: https://github.com/rtlsdrblog/rtl-sdr-blog/releases");
        }
        return exe;
    }
}
