package com.adsb.transport;

import com.adsb.core.FrameForwarder;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

/**
 * Forwards ADS-B frames via UDP multicast.
 *
 * Multicast groups must be in the range 224.0.0.0 – 239.255.255.255.
 * Recommended group for ADS-B: 239.1.1.1:30003 (site-local, administratively scoped).
 *
 * Receivers join the group with:
 *   MulticastSocket s = new MulticastSocket(30003);
 *   s.joinGroup(InetAddress.getByName("239.1.1.1"));
 *
 * Or with socat / nc:
 *   socat UDP4-RECVFROM:30003,ip-add-membership=239.1.1.1:eth0 STDOUT
 */
public class MulticastForwarder implements FrameForwarder {

    private static final int DEFAULT_TTL = 32; // site-local scope

    private final InetAddress    group;
    private final int            port;
    private final int            ttl;
    private final MulticastSocket socket;

    public MulticastForwarder(String groupAddress, int port) throws Exception {
        this(groupAddress, port, DEFAULT_TTL);
    }

    public MulticastForwarder(String groupAddress, int port, int ttl) throws Exception {
        this.group = InetAddress.getByName(groupAddress);
        this.port  = port;
        this.ttl   = ttl;

        if (!this.group.isMulticastAddress()) {
            throw new IllegalArgumentException(
                "Not a multicast address: " + groupAddress +
                " (must be in 224.0.0.0 – 239.255.255.255)");
        }

        this.socket = new MulticastSocket();
        this.socket.setTimeToLive(ttl);

        // Prefer a non-loopback interface for multicast
        NetworkInterface iface = findMulticastInterface();
        if (iface != null) {
            this.socket.setNetworkInterface(iface);
            System.out.printf("[MCAST] Using interface: %s%n", iface.getDisplayName());
        }

        System.out.printf("[MCAST] Forwarder ready -> %s:%d (TTL=%d)%n", groupAddress, port, ttl);
    }

    @Override
    public synchronized void forward(byte[] frame) throws Exception {
        DatagramPacket pkt = new DatagramPacket(frame, frame.length, group, port);
        socket.send(pkt);
    }

    @Override
    public void close() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
            System.out.println("[MCAST] Socket closed.");
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Finds the first non-loopback, UP interface that supports multicast.
     * Falls back to null (system default) if none found.
     */
    private static NetworkInterface findMulticastInterface() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (ni.isUp() && !ni.isLoopback() && ni.supportsMulticast()) {
                    return ni;
                }
            }
        } catch (SocketException ignored) {}
        return null;
    }
}
