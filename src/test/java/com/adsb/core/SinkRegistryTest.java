package com.adsb.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SinkRegistryTest {

    /** In-memory forwarder that records what it received; closes count. */
    private static final class Recorder implements FrameForwarder {
        final List<byte[]> received = new ArrayList<>();
        final AtomicInteger closes = new AtomicInteger();
        boolean throwOnForward = false;

        @Override public void forward(byte[] frame) throws Exception {
            if (throwOnForward) throw new RuntimeException("boom");
            received.add(frame);
        }
        @Override public void close() { closes.incrementAndGet(); }
    }

    @Test
    void add_registers_and_snapshot_iterates() {
        SinkRegistry r = new SinkRegistry();
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        r.add("a", PayloadFormat.AVR,  a);
        r.add("b", PayloadFormat.JSON, b);
        assertEquals(2, r.size());
        int seen = 0;
        for (var s : r.snapshot()) seen++;
        assertEquals(2, seen);
    }

    @Test
    void anyWants_returns_true_only_when_at_least_one_sink_wants_that_payload() {
        SinkRegistry r = new SinkRegistry();
        assertFalse(r.anyWants(PayloadFormat.COT));
        r.add("a", PayloadFormat.AVR, new Recorder());
        assertTrue (r.anyWants(PayloadFormat.AVR));
        assertFalse(r.anyWants(PayloadFormat.JSON));
        r.add("b", PayloadFormat.JSON, new Recorder());
        assertTrue (r.anyWants(PayloadFormat.JSON));
    }

    @Test
    void remove_closes_the_forwarder_and_drops_the_entry() {
        SinkRegistry r = new SinkRegistry();
        Recorder a = new Recorder();
        r.add("a", PayloadFormat.AVR, a);
        r.remove("a");
        assertEquals(0, r.size());
        assertEquals(1, a.closes.get());
    }

    @Test
    void remove_of_unknown_id_is_a_noop() {
        SinkRegistry r = new SinkRegistry();
        r.remove("nope");
        assertEquals(0, r.size());
    }

    @Test
    void closeAll_closes_every_sink_and_clears() {
        SinkRegistry r = new SinkRegistry();
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        r.add("a", PayloadFormat.AVR, a);
        r.add("b", PayloadFormat.JSON, b);
        r.closeAll();
        assertEquals(0, r.size());
        assertEquals(1, a.closes.get());
        assertEquals(1, b.closes.get());
    }
}
