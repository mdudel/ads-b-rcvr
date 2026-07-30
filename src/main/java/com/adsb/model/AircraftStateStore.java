package com.adsb.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
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

    /**
     * Position history per ICAO (for map trail rendering). Each deque holds
     * up to {@link #MAX_TRAIL_POINTS} recent lat/lon pairs. Evicted along with
     * the main track on TTL expiry.
     */
    private final Map<String, Deque<TrailPoint>> trails = new ConcurrentHashMap<>();

    /** Max trail points to retain per aircraft. ~50 = 5 min at 6s update rate. */
    private static final int MAX_TRAIL_POINTS = 50;

    // ----- fade / expiry constants (Marty 2026-07-30 12:47 UTC) -----
    /**
     * Age (ms since last update) at which a track begins fading out on
     * the UI. Below this age the track paints at full opacity.
     */
    public static final long FADE_START_MS    = 120_000L;
    /**
     * How long the fade animation lasts. Alpha decays linearly from
     * 1.0 at FADE_START_MS to 0.0 at FADE_START_MS + FADE_DURATION_MS,
     * at which point the track is evicted.
     */
    public static final long FADE_DURATION_MS =  30_000L;
    /**
     * Age (ms since last update) at which a track is removed entirely.
     * The map painter and tracks table both stop rendering it at this
     * age; the periodic eviction sweep drops it from the store shortly
     * after.
     */
    public static final long REMOVE_AT_MS     = FADE_START_MS + FADE_DURATION_MS;

    /**
     * Compute the paint alpha [0.0, 1.0] for a track given its age in
     * milliseconds since last update. Pure function so it can be unit-
     * tested without a live store.
     *
     * <ul>
     *   <li>age &lt; {@value #FADE_START_MS} ms -&gt; 1.0 (full opacity)</li>
     *   <li>{@value #FADE_START_MS} ms &le; age &lt; {@link #REMOVE_AT_MS}
     *       ms -&gt; linear ramp from 1.0 to 0.0</li>
     *   <li>age &ge; {@link #REMOVE_AT_MS} ms -&gt; 0.0 (invisible; will
     *       be evicted)</li>
     * </ul>
     */
    public static float fadeAlphaForAgeMs(long ageMs) {
        if (ageMs < FADE_START_MS) return 1.0f;
        if (ageMs >= REMOVE_AT_MS) return 0.0f;
        long fadeElapsed = ageMs - FADE_START_MS;
        return 1.0f - ((float) fadeElapsed / (float) FADE_DURATION_MS);
    }

    /**
     * Convenience: compute the paint alpha for a track relative to a
     * reference instant (typically {@code Instant.now()} at the top of
     * a paint pass).
     */
    public static float fadeAlphaFor(AdsbTrack t, Instant now) {
        if (t == null || t.lastSeen() == null) return 1.0f;
        long ageMs = Duration.between(t.lastSeen(), now).toMillis();
        return fadeAlphaForAgeMs(ageMs);
    }

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

        // Append new position to trail if it's valid and different from last
        if (next.hasPosition()) {
            trails.compute(key, (k, trail) -> {
                if (trail == null) trail = new ArrayDeque<>(MAX_TRAIL_POINTS + 1);
                // Only add if position changed (avoid duplicates when only alt/speed updated)
                if (trail.isEmpty() ||
                    trail.getLast().lat != next.latitude() ||
                    trail.getLast().lon != next.longitude()) {
                    trail.addLast(new TrailPoint(next.latitude(), next.longitude()));
                    if (trail.size() > MAX_TRAIL_POINTS) trail.removeFirst();
                }
                return trail;
            });
        }

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
     * @return immutable snapshot of the trail for the given ICAO, or an empty
     *         list if no trail exists. Each point is a lat/lon pair in the
     *         order they were received (oldest first).
     */
    public List<TrailPoint> getTrail(String icaoHex) {
        if (icaoHex == null) return Collections.emptyList();
        Deque<TrailPoint> trail = trails.get(icaoHex.toUpperCase());
        return trail == null ? Collections.emptyList() : List.copyOf(trail);
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
            Map.Entry<String, AdsbTrack> e = it.next();
            if (e.getValue().lastSeen().isBefore(cutoff)) {
                String icao = e.getKey();
                it.remove();
                trails.remove(icao);  // Evict trail with the track
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

    /** One position point in an aircraft's trail history. */
    public record TrailPoint(double lat, double lon) {}
}
