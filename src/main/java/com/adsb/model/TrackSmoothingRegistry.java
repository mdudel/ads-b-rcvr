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

/**
 * Holds one {@link TrackKalmanFilter} per aircraft and exposes a
 * simple {@link #smooth(String, double, double, Instant)} entry
 * point the map painter calls before rendering the icon + trail.
 *
 * <p><b>Toggle</b>: when {@link #isEnabled} returns false the
 * registry returns the raw measurement unchanged. Callers should
 * check the flag once at the top of their paint loop; per-track
 * checks are also cheap.
 *
 * <p><b>Lifecycle</b>: filters are created lazily on first update
 * for an ICAO. Eviction happens via {@link #evictOlderThan(Instant)}
 * which drops filters that haven't seen a measurement in a while
 * (matches the store's own eviction contract). Also clearable with
 * {@link #clear} when the operator toggles smoothing off then on
 * again -- avoids stale state polluting a fresh session.
 *
 * <p>Thread-safe via ConcurrentHashMap + per-filter serialisation
 * inside {@link #smooth} (single-threaded update per aircraft).
 */
public final class TrackSmoothingRegistry {

    private final Map<String, TrackKalmanFilter> filters = new ConcurrentHashMap<>();
    /**
     * Per-aircraft ring buffer of smoothed trail points, populated
     * whenever {@link #smooth} is called with a new measurement.
     * Bounded to {@link #MAX_TRAIL_POINTS} so memory stays fixed.
     * Consumed by the map painter to draw a smoothed history trail
     * in place of / alongside the store's raw trail.
     */
    private final Map<String, Deque<double[]>> smoothedTrails = new ConcurrentHashMap<>();

    private volatile boolean enabled;

    /** How long a filter can go without a measurement before eviction. */
    private static final long EVICT_STALE_SEC = 300;

    /** Max points retained in the smoothed trail per aircraft. */
    private static final int MAX_TRAIL_POINTS = 100;

    public TrackSmoothingRegistry(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Toggle the filter on or off. When flipping OFF, filters are
     * retained so re-enabling doesn't lose state during a brief
     * comparison. When flipping ON after a long period off,
     * consider calling {@link #clear} first via the UI so we start
     * from live measurements.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Drop every filter + smoothed trail; call for a clean slate. */
    public void clear() {
        filters.clear();
        smoothedTrails.clear();
    }

    /**
     * Smooth a measurement. Returns the raw input when smoothing is
     * off or the ICAO is null; otherwise feeds the per-aircraft
     * filter and returns its a-posteriori estimate.
     *
     * @return {@code [lat, lon]} pair; never null
     */
    public double[] smooth(String icaoHex, double lat, double lon, Instant when) {
        if (!enabled || icaoHex == null) return new double[] { lat, lon };
        String key = icaoHex.toUpperCase();
        TrackKalmanFilter f = filters.computeIfAbsent(
                key, k -> new TrackKalmanFilter());
        // Per-track serialisation: computeIfAbsent doesn't; explicit
        // synchronized on the filter guarantees only one update at a
        // time (paint calls should be sequential on the EDT anyway,
        // but a background listener may also feed in).
        double[] out;
        Instant prevUpdate;
        synchronized (f) {
            prevUpdate = f.lastUpdate();
            out = f.update(lat, lon, when);
        }
        // Append to the smoothed-trail ring buffer ONLY when this call
        // advanced the filter's clock (avoids duplicate entries when
        // the painter re-invokes smooth() with the same timestamp on
        // multiple repaints). First measurement (prevUpdate == null)
        // always gets recorded.
        if (prevUpdate == null || when.isAfter(prevUpdate)) {
            Deque<double[]> trail = smoothedTrails.computeIfAbsent(key,
                    k -> new ArrayDeque<>(MAX_TRAIL_POINTS + 1));
            synchronized (trail) {
                trail.addLast(new double[] { out[0], out[1] });
                while (trail.size() > MAX_TRAIL_POINTS) trail.removeFirst();
            }
        }
        return out;
    }

    /**
     * @return snapshot of the smoothed-trail for the given ICAO,
     *         oldest first. Empty when smoothing is off, no filter
     *         yet, or the aircraft has never been smoothed.
     */
    public List<double[]> getSmoothedTrail(String icaoHex) {
        if (!enabled || icaoHex == null) return Collections.emptyList();
        Deque<double[]> trail = smoothedTrails.get(icaoHex.toUpperCase());
        if (trail == null) return Collections.emptyList();
        synchronized (trail) {
            return java.util.List.copyOf(trail);
        }
    }

    /**
     * @return the current smoothed position for this ICAO, or null
     *         if the filter hasn't seen a measurement (or smoothing
     *         is off).
     */
    public double[] currentEstimate(String icaoHex) {
        if (!enabled || icaoHex == null) return null;
        TrackKalmanFilter f = filters.get(icaoHex.toUpperCase());
        return f == null ? null : f.currentEstimate();
    }

    /** @return number of filters currently held. */
    public int size() { return filters.size(); }

    /**
     * Purge filters that haven't been updated in the last
     * {@value #EVICT_STALE_SEC} seconds. Safe to call periodically
     * from a background executor; matches the store's eviction
     * cadence.
     *
     * @return number of entries removed
     */
    public int evictOlderThan(Instant now) {
        int removed = 0;
        Iterator<Map.Entry<String, TrackKalmanFilter>> it = filters.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, TrackKalmanFilter> e = it.next();
            Instant last = e.getValue().lastUpdate();
            if (last == null || Duration.between(last, now).toSeconds() > EVICT_STALE_SEC) {
                it.remove();
                smoothedTrails.remove(e.getKey());
                removed++;
            }
        }
        return removed;
    }

    /**
     * Drop the filter + smoothed trail for the given ICAO. Called by
     * {@link com.adsb.cli.Main}'s eviction sweep in lockstep with
     * the store's eviction so filter memory tracks aircraft memory.
     */
    public void removeIcao(String icaoHex) {
        if (icaoHex == null) return;
        String key = icaoHex.toUpperCase();
        filters.remove(key);
        smoothedTrails.remove(key);
    }
}
