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
 *   <li>{@code --rtl-path} absent \u2192 look in current working directory.</li>
 *   <li>{@code --rtl-path} given \u2192 look in that folder only.</li>
 *   <li>Miss on either \u2192 throw a {@link FileNotFoundException} with a
 *       user-facing message telling the operator exactly what to do.</li>
 *   <li>PATH is deliberately NOT walked.</li>
 * </ul>
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
        assertTrue(msg.contains(EXE_NAME),         "message must name the exe: " + msg);
        assertTrue(msg.contains("--rtl-path"),      "message must mention --rtl-path: " + msg);
        assertTrue(msg.contains(dir.toString()),    "message must show the searched dir: " + msg);
    }

    @Test
    void missing_from_current_directory_throws_with_helpful_message() {
        // The sandbox's cwd never has rtl_adsb in it, so this always
        // exercises the miss branch. If it ever spuriously passes,
        // that itself would be a signal worth investigating.
        FileNotFoundException ex = assertThrows(FileNotFoundException.class,
                () -> AdsbReceiver.requireRtlAdsbExecutable(null));
        String msg = ex.getMessage();
        assertTrue(msg.contains(EXE_NAME),                "message must name the exe: " + msg);
        assertTrue(msg.contains("current directory"),      "message must mention current directory: " + msg);
        assertTrue(msg.contains("--rtl-path"),             "message must suggest --rtl-path as the fix: " + msg);
    }

    @Test
    void blank_rtl_path_string_is_treated_as_absent() {
        // Same failure shape as null \u2014 users passing --rtl-path "" shouldn't
        // trip into "found empty folder" territory.
        FileNotFoundException ex = assertThrows(FileNotFoundException.class,
                () -> AdsbReceiver.requireRtlAdsbExecutable(""));
        assertTrue(ex.getMessage().contains("current directory"));
    }
}
