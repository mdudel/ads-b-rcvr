package com.adsb.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural pins for {@link AdsbReceiver#requireRtlAdsbExecutable(String)}.
 *
 * <p>Contract per Marty 2026-07-27 13:30 UTC:
 * <ul>
 *   <li>{@code --rtl-path} absent  ==&gt;  look in current working directory.</li>
 *   <li>{@code --rtl-path} given   ==&gt;  look in that folder only.</li>
 *   <li>Miss on either             ==&gt;  throw a {@link FileNotFoundException}
 *       with a user-facing message telling the operator exactly what to do.</li>
 *   <li>PATH is deliberately NOT walked.</li>
 * </ul>
 *
 * <p>The two "missing from current directory" tests use
 * {@link #withUserDir} to temporarily flip {@code user.dir} to an empty
 * {@link TempDir} so the check is hermetic across dev machines. On
 * Marty's Windows dev box the real cwd (D:\DEV\PROJECTS\ads-b-rcvr\)
 * contains rtl_adsb.exe next to the jar, which would spuriously satisfy
 * the check and make the tests fail there. See commit that introduces
 * this helper for the field-report trace.
 */
class AdsbReceiverRequireRtlExecutableTest {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final String EXE_NAME = IS_WINDOWS ? "rtl_adsb.exe" : "rtl_adsb";

    @Test
    void found_in_explicit_rtl_path_folder(@TempDir Path dir) throws Exception {
        File exe = dir.resolve(EXE_NAME).toFile();
        Files.writeString(exe.toPath(), "not a real exe, just needs to exist");

        File resolved = AdsbReceiver.requireRtlAdsbExecutable(dir.toString());
        assertEquals(exe.getAbsolutePath(), resolved.getAbsolutePath());
    }

    @Test
    void missing_from_explicit_rtl_path_folder_throws_with_helpful_message(@TempDir Path dir) {
        FileNotFoundException ex = assertThrows(FileNotFoundException.class,
                () -> AdsbReceiver.requireRtlAdsbExecutable(dir.toString()));
        String msg = ex.getMessage();
        assertTrue(msg.contains(EXE_NAME),        "message must name the exe: " + msg);
        assertTrue(msg.contains("--rtl-path"),    "message must mention --rtl-path: " + msg);
        assertTrue(msg.contains(dir.toString()),  "message must show the searched dir: " + msg);
    }

    @Test
    void missing_from_current_directory_throws_with_helpful_message(@TempDir Path emptyDir) throws Exception {
        withUserDir(emptyDir, () -> {
            FileNotFoundException ex = assertThrows(FileNotFoundException.class,
                    () -> AdsbReceiver.requireRtlAdsbExecutable(null));
            String msg = ex.getMessage();
            assertTrue(msg.contains(EXE_NAME),            "message must name the exe: " + msg);
            assertTrue(msg.contains("current directory"), "message must mention current directory: " + msg);
            assertTrue(msg.contains("--rtl-path"),        "message must suggest --rtl-path: " + msg);
        });
    }

    @Test
    void blank_rtl_path_string_is_treated_as_absent(@TempDir Path emptyDir) throws Exception {
        // Users passing --rtl-path "" shouldn't trip into "found empty
        // folder" territory; must behave identically to the null case.
        withUserDir(emptyDir, () -> {
            FileNotFoundException ex = assertThrows(FileNotFoundException.class,
                    () -> AdsbReceiver.requireRtlAdsbExecutable(""));
            assertTrue(ex.getMessage().contains("current directory"));
        });
    }

    // ------------------------------------------------------------------

    /**
     * Run {@code body} with {@link System#getProperty(String) user.dir}
     * temporarily set to {@code dir}, restoring the previous value in a
     * finally block. The production check resolves the cwd via
     * {@code new File(".").getAbsoluteFile()} which reads {@code user.dir},
     * so overriding that system property is the cleanest way to keep the
     * test hermetic without changing the JVM's actual working directory
     * (which we cannot do reliably from Java).
     */
    private static void withUserDir(Path dir, ThrowingRunnable body) throws Exception {
        String previous = System.getProperty("user.dir");
        System.setProperty("user.dir", dir.toAbsolutePath().toString());
        try {
            body.run();
        } finally {
            if (previous != null) System.setProperty("user.dir", previous);
            else                  System.clearProperty("user.dir");
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
