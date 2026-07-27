package com.adsb.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Thread-safe per-ICAO aggregation of ADS-B state.
 * <p>
 * Callers (typically the decoder wired into {@link com.adsb.core.AdsbReceiver})
 * apply per-field updates through {@link #update(String, Updater)}. Each call
 * builds an updated {@link AdsbTrack} snapshot from the previous state, stores
 * it atomically, and broadcasts to registered listeners.
 * <p>
 * Listeners are invoked on the caller's thread synchronously, so keep them
 * cheap (the CoT builder + a socket write are both cheap; heavier work should
 * be handed off to a queue). All listeners are called once per successful
 * update; listener exceptions are caught, logged to stderr, and do not
 * prevent the other listeners from running.
 * <p>
 * TTL eviction: {@link #evictOlderThan(Duration)} drops tracks silent for
 * longer than the given duration. Callers may schedule this on a background
 * timer if long-lived aircraft-list cleanliness matters; the current
 * receiver does not require it.
 */
public final class AircraftStateStore {

    /** Map of ICAO-hex (uppercase) -&gt; latest snapshot. */
    private final Map<String, AdsbTrack> tracks = new ConcurrentHashMap<>();

    /** Snapshot listeners. Copy-on-write so add/remove is safe under concurrent publish. */
    private final List<Consumer<AdsbTrack>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Register a listener invoked once per successful {@link #update} call
     * with the newly-installed snapshot.
     *
     * @param listener non-null; add order is preserved for invocation order
     */
    public void addListener(Consumer<AdsbTrack> listener) {
        if (listener == null) throw new IllegalArgumentException("listener");
        listeners.add(listener);
    }

    /** Remove a previously-registered listener; no-op if not present. */
    public void removeListener(Consumer<AdsbTrack> listener) {
        listeners.remove(listener);
    }

    /**
     * Merge-and-publish. The updater receives the current {@link AdsbTrack.Builder}
     * (pre-populated from the previous snapshot, or a fresh builder if this is the
     * first sighting) and applies whatever field(s) the caller wants to change.
     * The resulting snapshot is installed atomically and broadcast to listeners.
     *
     * @param icaoHex ICAO 24-bit address in hex (any case; normalised to upper)
     * @param updater called synchronously to mutate the builder before build()
     * @return the newly-installed snapshot
     */
    public AdsbTrack update(String icaoHex, Updater updater) {
        if (icaoHex == null || icaoHex.isBlank())
            throw new IllegalArgumentException("icaoHex");
        String key = icaoHex.toUpperCase();

        AdsbTrack next = tracks.compute(key, (k, prev) -> {
            AdsbTrack.Builder b = (prev == null) ? AdsbTrack.builder(k) : prev.toBuilder();
            updater.apply(b);
            b.lastSeen(Instant.now());
            return b.build();
        });

        // Broadcast outside the compute() lambda so listeners aren't holding the
        // per-key lock while they publish downstream (which may involve socket I/O).
        for (Consumer<AdsbTrack> l : listeners) {
            try {
                l.accept(next);
            } catch (RuntimeException e) {
                System.err.println("[state-store] listener error: " + e);
            }
        }
        return next;
    }

    /** @return current snapshot for the ICAO, or null if unknown. */
    public AdsbTrack get(String icaoHex) {
        if (icaoHex == null) return null;
        return tracks.get(icaoHex.toUpperCase());
    }

    /** @return number of tracks currently held. */
    public int size() {
        return tracks.size();
    }

    /**
     * @return snapshot list of every track currently held, in no
     *         particular order. The returned list is a fresh
     *         {@link java.util.ArrayList}; callers may sort/filter it
     *         without disturbing the store.
     */
    public java.util.List<AdsbTrack> allSnapshots() {
        return new java.util.ArrayList<>(tracks.values());
    }

    /**
     * Drop tracks whose {@code lastSeen} is older than {@code now - maxAge}.
     * Intended for periodic housekeeping; safe to call while updates are
     * flowing in on other threads.
     *
     * @param maxAge how long to retain a silent track
     * @return number of entries evicted
     */
    public int evictOlderThan(Duration maxAge) {
        Instant cutoff = Instant.now().minus(maxAge);
        int removed = 0;
        Iterator<Map.Entry<String, AdsbTrack>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().lastSeen().isBefore(cutoff)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Functional interface for merge-and-publish updates. Not {@code Consumer} so it reads cleaner at the call site. */
    @FunctionalInterface
    public interface Updater {
        void apply(AdsbTrack.Builder builder);
    }
}
