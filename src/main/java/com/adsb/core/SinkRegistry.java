package com.adsb.core;

import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe registry of active sinks, each carrying its own
 * {@link PayloadFormat}. The receiver produces per-frame bytes for
 * every payload representation the registry currently subscribes to,
 * then dispatches each sink its chosen format.
 *
 * <p>Motivation: the UI's ConnectorsPanel needs to add / remove
 * per-target sinks at runtime, and different sinks may want different
 * payloads at the same time (e.g. UDP unicast in CoT to WinTAK,
 * multicast in JSON to a log aggregator). The old single-global
 * {@code --payload} flag couldn't express that.
 *
 * <p>Backward compat: {@link com.adsb.cli.Main} still accepts
 * {@code --payload} and translates each CLI sink flag into one
 * {@link AttachedSink} at startup. See {@code Main} for the mapping.
 */
public final class SinkRegistry {

    private final CopyOnWriteArrayList<AttachedSink> sinks = new CopyOnWriteArrayList<>();

    /** Register a sink. Returns a handle that can be used with {@link #remove}. */
    public AttachedSink add(String id, PayloadFormat payload, FrameForwarder forwarder) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(payload);
        Objects.requireNonNull(forwarder);
        AttachedSink s = new AttachedSink(id, payload, forwarder);
        sinks.add(s);
        return s;
    }

    /**
     * Detach and close the sink with the given id. Silently no-op if the
     * id is unknown (matches "remove is idempotent" convention).
     */
    public void remove(String id) {
        Iterator<AttachedSink> it = sinks.iterator();
        while (it.hasNext()) {
            AttachedSink s = it.next();
            if (s.id().equals(id)) {
                sinks.remove(s);
                try { s.forwarder().close(); }
                catch (Exception e) {
                    System.err.println("[sink-registry] close error on " + id + ": " + e);
                }
                return;
            }
        }
    }

    /** @return iterable snapshot; safe to iterate while other threads mutate the registry. */
    public Iterable<AttachedSink> snapshot() {
        // CopyOnWriteArrayList's iterator is a snapshot by contract.
        return sinks;
    }

    /** @return count of currently-attached sinks. */
    public int size() {
        return sinks.size();
    }

    /** @return true if at least one attached sink wants the given payload. */
    public boolean anyWants(PayloadFormat p) {
        for (AttachedSink s : sinks) if (s.payload() == p) return true;
        return false;
    }

    /** Close and remove every registered sink. Called from the receiver's shutdown hook. */
    public void closeAll() {
        for (AttachedSink s : sinks) {
            try { s.forwarder().close(); }
            catch (Exception ignored) { /* best-effort during shutdown */ }
        }
        sinks.clear();
    }

    /** Immutable value-record for a single attached sink. */
    public record AttachedSink(String id, PayloadFormat payload, FrameForwarder forwarder) {}
}
