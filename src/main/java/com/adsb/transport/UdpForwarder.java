package com.adsb.transport;

import com.adsb.core.FrameForwarder;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Forwards ADS-B frames via UDP unicast.
 *
 * Each frame is sent as a single UDP datagram to the configured host:port.
 * Compatible with tools like Virtual Radar Server, PlaneFinder, etc.
 */
public class UdpForwarder implements FrameForwarder {

    private final InetAddress  address;
    private final int          port;
    private final DatagramSocket socket;

    public UdpForwarder(String host, int port) throws Exception {
        this.address = InetAddress.getByName(host);
        this.port    = port;
        this.socket  = new DatagramSocket();
        System.out.printf("[UDP] Forwarder ready -> %s:%d%n", host, port);
    }

    @Override
    public synchronized void forward(byte[] frame) throws Exception {
        DatagramPacket pkt = new DatagramPacket(frame, frame.length, address, port);
        socket.send(pkt);
    }

    @Override
    public void close() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
            System.out.println("[UDP] Socket closed.");
        }
    }
}
