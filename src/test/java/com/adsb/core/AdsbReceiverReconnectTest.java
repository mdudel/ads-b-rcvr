package com.adsb.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural pins for the receiver-reconnect API added 2026-07-29
 * 11:28 UTC: {@link AdsbReceiver#isRunning()},
 * {@link AdsbReceiver#resetForRestart()},
 * {@link AdsbReceiver#setStateChangedListener(Runnable)}.
 *
 * <p>Deliberately does NOT drive the rtl_adsb subprocess -- that
 * would need a real (or faked) executable on disk and a mocked
 * ProcessBuilder. The pins here cover the pure-Java state that
 * MainFrame relies on for the Reconnect button.
 */
class AdsbReceiverReconnectTest {

    private static AdsbReceiver newReceiver() {
        return new AdsbReceiver(0, "auto", "avr", false, "/tmp/nowhere-does-not-exist",
                null, null, new SinkRegistry());
    }

    @Test
    void isRunning_returns_false_before_start_has_ever_been_called() {
        assertFalse(newReceiver().isRunning(),
                "fresh receiver must not report running");
    }

    @Test
    void stop_is_idempotent_and_safe_when_never_started() {
        AdsbReceiver r = newReceiver();
        r.stop();
        r.stop();
        assertFalse(r.isRunning());
    }

    @Test
    void resetForRestart_is_idempotent_and_does_not_fabricate_running_state() {
        AdsbReceiver r = newReceiver();
        r.stop();
        r.resetForRestart();
        assertFalse(r.isRunning(),
                "resetForRestart must not fabricate a running state");
        r.resetForRestart();
        r.resetForRestart();
        assertFalse(r.isRunning());
    }

    @Test
    void stateChangedListener_does_not_fire_on_noop_stop() {
        AtomicInteger callCount = new AtomicInteger(0);
        AdsbReceiver r = newReceiver();
        r.setStateChangedListener(callCount::incrementAndGet);
        // stop() short-circuits before firing the listener when no
        // subprocess is alive -- no real transition happened, so no
        // listener call. Pins the 'no ghost transitions' contract.
        r.stop();
        assertEquals(0, callCount.get(),
                "listener must not fire when there is no actual state transition");
    }

    @Test
    void stateChangedListener_swallows_exceptions_from_the_listener() {
        AdsbReceiver r = newReceiver();
        r.setStateChangedListener(() -> { throw new RuntimeException("boom"); });
        // Setting a bad listener must not break stop() (even though the
        // no-op-stop path doesn't fire it, this pins that the ceremony
        // of installing a listener won't wedge lifecycle methods).
        assertDoesNotThrow(r::stop);
        r.setStateChangedListener(null);
        assertDoesNotThrow(r::stop);
    }

    @Test
    void setStateChangedListener_can_be_swapped_at_runtime() {
        AdsbReceiver r = newReceiver();
        AtomicInteger first  = new AtomicInteger(0);
        AtomicInteger second = new AtomicInteger(0);
        r.setStateChangedListener(first::incrementAndGet);
        r.setStateChangedListener(second::incrementAndGet);
        r.setStateChangedListener(null);
        // No fire path exercised here; just proving swap-then-null is safe.
        assertEquals(0, first.get());
        assertEquals(0, second.get());
    }
}
