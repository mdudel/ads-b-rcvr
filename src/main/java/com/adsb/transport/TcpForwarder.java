package com.adsb.transport;

import com.adsb.core.FrameForwarder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TCP server that accepts incoming client connections and streams all ADS-B frames
 * to every connected client.
 *
 * This is compatible with tools expecting a raw TCP AVR stream on port 30003,
 * such as Virtual Radar Server (VRS), PlaneFinder, FlightAware's piaware, etc.
 *
 * Usage:
 *   Connect with: nc <host> 30003
 *   or configure VRS with: "SBS BaseStation over TCP"
 */
public class TcpForwarder implements FrameForwarder {

    private final int             port;
    private final ServerSocket    serverSocket;
    private final ExecutorService acceptor;
    private final List<ClientConnection> clients = new ArrayList<>();

    public TcpForwarder(int port) throws IOException {
        this.port         = port;
        this.serverSocket = new ServerSocket(port);
        this.acceptor     = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tcp-acceptor");
            t.setDaemon(true);
            return t;
        });
        System.out.printf("[TCP] Server socket bound to port %d%n", port);
    }

    /** Starts the accept loop in a background daemon thread. */
    public void start() {
        acceptor.submit(this::acceptLoop);
    }

    @Override
    public synchronized void forward(byte[] frame) {
        if (clients.isEmpty()) return;

        Iterator<ClientConnection> it = clients.iterator();
        while (it.hasNext()) {
            ClientConnection c = it.next();
            if (!c.send(frame)) {
                System.out.printf("[TCP] Client %s disconnected.%n", c.remoteAddr());
                c.close();
                it.remove();
            }
        }
    }

    @Override
    public void close() {
        acceptor.shutdownNow();
        synchronized (this) {
            for (ClientConnection c : clients) c.close();
            clients.clear();
        }
        try {
            serverSocket.close();
            System.out.println("[TCP] Server closed.");
        } catch (IOException ignored) {}
    }

    // -------------------------------------------------------------------------

    private void acceptLoop() {
        System.out.printf("[TCP] Waiting for clients on port %d...%n", port);
        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                client.setKeepAlive(true);
                ClientConnection conn = new ClientConnection(client);
                synchronized (this) {
                    clients.add(conn);
                }
                System.out.printf("[TCP] Client connected: %s (total=%d)%n",
                        conn.remoteAddr(), clients.size());
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    System.err.println("[TCP] Accept error: " + e.getMessage());
                }
            }
        }
    }

    // -------------------------------------------------------------------------

    private static final class ClientConnection {
        private final Socket       socket;
        private final OutputStream out;

        ClientConnection(Socket socket) throws IOException {
            this.socket = socket;
            this.out    = socket.getOutputStream();
        }

        /** @return false if the client has disconnected or the write failed */
        boolean send(byte[] data) {
            if (socket.isClosed()) return false;
            try {
                out.write(data);
                out.flush();
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        String remoteAddr() {
            return socket.getRemoteSocketAddress().toString();
        }

        void close() {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
