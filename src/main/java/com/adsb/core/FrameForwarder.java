package com.adsb.core;

/**
 * Common interface for all ADS-B frame forwarders.
 * Implementations must be thread-safe.
 */
public interface FrameForwarder extends AutoCloseable {

    /**
     * Forward a single ADS-B frame (as raw bytes) to the configured destination.
     *
     * @param frame raw frame bytes (e.g. "*8D4B1A00...\n" in AVR format)
     * @throws Exception if the send fails
     */
    void forward(byte[] frame) throws Exception;
}
