@echo off
REM ============================================================
REM  setup.bat — Creates the complete adsb-forwarder project
REM              structure and all source files from scratch.
REM
REM  Run this from the folder where you want the project:
REM    cd d:\DEV\PROJECTS\ads-b-rcvr
REM    setup.bat
REM
REM  Then build:
REM    mvn clean package
REM    run.bat
REM ============================================================

echo [SETUP] Creating directory structure...

mkdir src\main\java\com\adsb\cli       2>nul
mkdir src\main\java\com\adsb\core      2>nul
mkdir src\main\java\com\adsb\transport 2>nul

echo [SETUP] Writing pom.xml...
(
echo ^<?xml version="1.0" encoding="UTF-8"?^>
echo ^<project xmlns="http://maven.apache.org/POM/4.0.0"
echo          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
echo          xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
echo                              https://maven.apache.org/xsd/maven-4.0.0.xsd"^>
echo     ^<modelVersion^>4.0.0^</modelVersion^>
echo     ^<groupId^>com.adsb^</groupId^>
echo     ^<artifactId^>adsb-forwarder^</artifactId^>
echo     ^<version^>1.0.0^</version^>
echo     ^<packaging^>jar^</packaging^>
echo     ^<properties^>
echo         ^<maven.compiler.source^>17^</maven.compiler.source^>
echo         ^<maven.compiler.target^>17^</maven.compiler.target^>
echo         ^<project.build.sourceEncoding^>UTF-8^</project.build.sourceEncoding^>
echo         ^<mainClass^>com.adsb.cli.Main^</mainClass^>
echo     ^</properties^>
echo     ^<dependencies^>
echo         ^<dependency^>
echo             ^<groupId^>org.junit.jupiter^</groupId^>
echo             ^<artifactId^>junit-jupiter^</artifactId^>
echo             ^<version^>5.10.2^</version^>
echo             ^<scope^>test^</scope^>
echo         ^</dependency^>
echo     ^</dependencies^>
echo     ^<build^>
echo         ^<plugins^>
echo             ^<plugin^>
echo                 ^<groupId^>org.apache.maven.plugins^</groupId^>
echo                 ^<artifactId^>maven-shade-plugin^</artifactId^>
echo                 ^<version^>3.5.2^</version^>
echo                 ^<executions^>
echo                     ^<execution^>
echo                         ^<phase^>package^</phase^>
echo                         ^<goals^>^<goal^>shade^</goal^>^</goals^>
echo                         ^<configuration^>
echo                             ^<createDependencyReducedPom^>false^</createDependencyReducedPom^>
echo                             ^<transformers^>
echo                                 ^<transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer"^>
echo                                     ^<mainClass^>${mainClass}^</mainClass^>
echo                                 ^</transformer^>
echo                             ^</transformers^>
echo                         ^</configuration^>
echo                     ^</execution^>
echo                 ^</executions^>
echo             ^</plugin^>
echo             ^<plugin^>
echo                 ^<groupId^>org.apache.maven.plugins^</groupId^>
echo                 ^<artifactId^>maven-surefire-plugin^</artifactId^>
echo                 ^<version^>3.2.5^</version^>
echo             ^</plugin^>
echo         ^</plugins^>
echo     ^</build^>
echo ^</project^>
) > pom.xml

echo [SETUP] Writing Main.java...
(
echo package com.adsb.cli;
echo.
echo import com.adsb.core.AdsbReceiver;
echo import com.adsb.transport.MulticastForwarder;
echo import com.adsb.transport.TcpForwarder;
echo import com.adsb.transport.UdpForwarder;
echo.
echo import java.util.ArrayList;
echo import java.util.List;
echo.
echo public class Main {
echo.
echo     public static void main(String[] args) throws Exception {
echo         Config cfg = parseArgs(args);
echo.
echo         if (!cfg.hasAnyForwarder()) {
echo             cfg.verbose = true;
echo             System.out.println("[INFO] No output specified - printing frames to console. Press Ctrl+C to stop.");
echo             System.out.println("[INFO] Use --udp, --multicast, or --tcp-port to forward frames instead.");
echo             System.out.println();
echo         }
echo.
echo         List^<AutoCloseable^> forwarders = new ArrayList^<^>();
echo.
echo         if (cfg.udpHost != null) {
echo             UdpForwarder fwd = new UdpForwarder(cfg.udpHost, cfg.udpPort);
echo             forwarders.add(fwd);
echo             System.out.printf("[INFO] UDP unicast -^> %s:%d%n", cfg.udpHost, cfg.udpPort);
echo         }
echo         if (cfg.multicastGroup != null) {
echo             MulticastForwarder fwd = new MulticastForwarder(cfg.multicastGroup, cfg.multicastPort);
echo             forwarders.add(fwd);
echo             System.out.printf("[INFO] UDP multicast -^> %s:%d%n", cfg.multicastGroup, cfg.multicastPort);
echo         }
echo         if (cfg.tcpPort ^> 0) {
echo             TcpForwarder fwd = new TcpForwarder(cfg.tcpPort);
echo             fwd.start();
echo             forwarders.add(fwd);
echo             System.out.printf("[INFO] TCP server listening on port %d%n", cfg.tcpPort);
echo         }
echo.
echo         final List^<AutoCloseable^> fwdRef = forwarders;
echo         Runtime.getRuntime().addShutdownHook(new Thread(() -^> {
echo             System.out.println("[INFO] Shutting down...");
echo             for (AutoCloseable f : fwdRef) {
echo                 try { f.close(); } catch (Exception ignored) {}
echo             }
echo         }));
echo.
echo         AdsbReceiver receiver = new AdsbReceiver(cfg.deviceIndex, cfg.gain, cfg.format, cfg.verbose, cfg.rtlPath);
echo         receiver.start(forwarders);
echo     }
echo.
echo     static Config parseArgs(String[] args) {
echo         Config cfg = new Config();
echo         for (int i = 0; i ^< args.length; i++) {
echo             switch (args[i]) {
echo                 case "--udp": { String[] p = args[++i].split(":"); cfg.udpHost = p[0]; cfg.udpPort = Integer.parseInt(p[1]); break; }
echo                 case "--multicast": { String[] p = args[++i].split(":"); cfg.multicastGroup = p[0]; cfg.multicastPort = Integer.parseInt(p[1]); break; }
echo                 case "--tcp-port": cfg.tcpPort = Integer.parseInt(args[++i]); break;
echo                 case "--rtl-device": cfg.deviceIndex = Integer.parseInt(args[++i]); break;
echo                 case "--rtl-path": cfg.rtlPath = args[++i]; break;
echo                 case "--gain": cfg.gain = args[++i]; break;
echo                 case "--format": cfg.format = args[++i]; break;
echo                 case "--verbose": cfg.verbose = true; break;
echo                 default: System.err.println("[WARN] Unknown argument: " + args[i]);
echo             }
echo         }
echo         return cfg;
echo     }
echo.
echo     static class Config {
echo         String udpHost = null;
echo         int udpPort = 0;
echo         String multicastGroup = null;
echo         int multicastPort = 0;
echo         int tcpPort = 0;
echo         int deviceIndex = 0;
echo         String rtlPath = null;
echo         String gain = "auto";
echo         String format = "avr";
echo         boolean verbose = false;
echo         boolean hasAnyForwarder() { return udpHost != null ^|^| multicastGroup != null ^|^| tcpPort ^> 0; }
echo     }
echo }
) > src\main\java\com\adsb\cli\Main.java

echo [SETUP] Writing FrameForwarder.java...
(
echo package com.adsb.core;
echo public interface FrameForwarder extends AutoCloseable {
echo     void forward(byte[] frame) throws Exception;
echo }
) > src\main\java\com\adsb\core\FrameForwarder.java

echo [SETUP] Writing AdsbReceiver.java...
(
echo package com.adsb.core;
echo.
echo import java.io.BufferedReader;
echo import java.io.File;
echo import java.io.InputStreamReader;
echo import java.nio.charset.Charset;
echo import java.nio.charset.StandardCharsets;
echo import java.util.ArrayList;
echo import java.util.List;
echo.
echo public class AdsbReceiver {
echo.
echo     private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
echo.
echo     private final int deviceIndex;
echo     private final String gain;
echo     private final String format;
echo     private final boolean verbose;
echo     private final String rtlPath;
echo.
echo     public AdsbReceiver(int deviceIndex, String gain, String format, boolean verbose, String rtlPath) {
echo         this.deviceIndex = deviceIndex;
echo         this.gain = gain;
echo         this.format = format;
echo         this.verbose = verbose;
echo         this.rtlPath = rtlPath;
echo     }
echo.
echo     public void start(List^<? extends AutoCloseable^> forwarders) throws Exception {
echo         ProcessBuilder pb = buildProcess();
echo         pb.redirectErrorStream(false);
echo         System.out.println("[INFO] Starting: " + String.join(" ", pb.command()));
echo         Process proc = pb.start();
echo.
echo         Thread stderrDrainer = new Thread(() -^> {
echo             try (BufferedReader err = new BufferedReader(new InputStreamReader(proc.getErrorStream(), Charset.defaultCharset()))) {
echo                 String line;
echo                 while ((line = err.readLine()) != null) System.err.println("[rtl_adsb] " + line);
echo             } catch (Exception ignored) {}
echo         }, "stderr-drainer");
echo         stderrDrainer.setDaemon(true);
echo         stderrDrainer.start();
echo.
echo         try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), Charset.defaultCharset()))) {
echo             String line;
echo             long frameCount = 0;
echo             while ((line = reader.readLine()) != null) {
echo                 line = line.trim();
echo                 if (line.isEmpty()) continue;
echo                 byte[] frame = (line + "\n").getBytes(StandardCharsets.US_ASCII);
echo                 if (verbose) System.out.printf("[FRAME %06d] %s%n", ++frameCount, line);
echo                 for (AutoCloseable fwd : forwarders) {
echo                     if (fwd instanceof FrameForwarder ff) {
echo                         try { ff.forward(frame); }
echo                         catch (Exception e) { System.err.println("[WARN] " + fwd.getClass().getSimpleName() + ": " + e.getMessage()); }
echo                     }
echo                 }
echo             }
echo         }
echo         int exit = proc.waitFor();
echo         System.out.printf("[INFO] rtl_adsb exited with code %d%n", exit);
echo     }
echo.
echo     private ProcessBuilder buildProcess() {
echo         List^<String^> cmd = new ArrayList^<^>();
echo         String exe = IS_WINDOWS ? "rtl_adsb.exe" : "rtl_adsb";
echo         if (rtlPath != null ^&^& !rtlPath.isBlank()) exe = rtlPath + File.separator + exe;
echo         cmd.add(exe);
echo         cmd.add("-d"); cmd.add(String.valueOf(deviceIndex));
echo         if (!"auto".equalsIgnoreCase(gain)) { cmd.add("-g"); cmd.add(gain); }
echo         if ("raw".equalsIgnoreCase(format)) cmd.add("-V");
echo         ProcessBuilder pb = new ProcessBuilder(cmd);
echo         pb.environment().putAll(System.getenv());
echo         return pb;
echo     }
echo }
) > src\main\java\com\adsb\core\AdsbReceiver.java

echo [SETUP] Writing UdpForwarder.java...
(
echo package com.adsb.transport;
echo.
echo import com.adsb.core.FrameForwarder;
echo import java.net.DatagramPacket;
echo import java.net.DatagramSocket;
echo import java.net.InetAddress;
echo.
echo public class UdpForwarder implements FrameForwarder {
echo     private final InetAddress address;
echo     private final int port;
echo     private final DatagramSocket socket;
echo.
echo     public UdpForwarder(String host, int port) throws Exception {
echo         this.address = InetAddress.getByName(host);
echo         this.port = port;
echo         this.socket = new DatagramSocket();
echo         System.out.printf("[UDP] Forwarder ready -^> %s:%d%n", host, port);
echo     }
echo.
echo     @Override public synchronized void forward(byte[] frame) throws Exception {
echo         socket.send(new DatagramPacket(frame, frame.length, address, port));
echo     }
echo.
echo     @Override public void close() {
echo         if (socket != null ^&^& !socket.isClosed()) { socket.close(); System.out.println("[UDP] Socket closed."); }
echo     }
echo }
) > src\main\java\com\adsb\transport\UdpForwarder.java

echo [SETUP] Writing MulticastForwarder.java...
(
echo package com.adsb.transport;
echo.
echo import com.adsb.core.FrameForwarder;
echo import java.net.DatagramPacket;
echo import java.net.InetAddress;
echo import java.net.MulticastSocket;
echo import java.net.NetworkInterface;
echo import java.net.SocketException;
echo import java.util.Enumeration;
echo.
echo public class MulticastForwarder implements FrameForwarder {
echo     private static final int DEFAULT_TTL = 32;
echo     private final InetAddress group;
echo     private final int port;
echo     private final MulticastSocket socket;
echo.
echo     public MulticastForwarder(String groupAddress, int port) throws Exception {
echo         this(groupAddress, port, DEFAULT_TTL);
echo     }
echo.
echo     public MulticastForwarder(String groupAddress, int port, int ttl) throws Exception {
echo         this.group = InetAddress.getByName(groupAddress);
echo         this.port = port;
echo         if (!this.group.isMulticastAddress())
echo             throw new IllegalArgumentException("Not a multicast address: " + groupAddress);
echo         this.socket = new MulticastSocket();
echo         this.socket.setTimeToLive(ttl);
echo         NetworkInterface iface = findMulticastInterface();
echo         if (iface != null) { this.socket.setNetworkInterface(iface); System.out.println("[MCAST] Interface: " + iface.getDisplayName()); }
echo         System.out.printf("[MCAST] Forwarder ready -^> %s:%d%n", groupAddress, port);
echo     }
echo.
echo     @Override public synchronized void forward(byte[] frame) throws Exception {
echo         socket.send(new DatagramPacket(frame, frame.length, group, port));
echo     }
echo.
echo     @Override public void close() {
echo         if (socket != null ^&^& !socket.isClosed()) { socket.close(); System.out.println("[MCAST] Socket closed."); }
echo     }
echo.
echo     private static NetworkInterface findMulticastInterface() {
echo         try {
echo             Enumeration^<NetworkInterface^> ifaces = NetworkInterface.getNetworkInterfaces();
echo             while (ifaces.hasMoreElements()) {
echo                 NetworkInterface ni = ifaces.nextElement();
echo                 if (ni.isUp() ^&^& !ni.isLoopback() ^&^& ni.supportsMulticast()) return ni;
echo             }
echo         } catch (SocketException ignored) {}
echo         return null;
echo     }
echo }
) > src\main\java\com\adsb\transport\MulticastForwarder.java

echo [SETUP] Writing TcpForwarder.java...
(
echo package com.adsb.transport;
echo.
echo import com.adsb.core.FrameForwarder;
echo import java.io.IOException;
echo import java.io.OutputStream;
echo import java.net.ServerSocket;
echo import java.net.Socket;
echo import java.util.ArrayList;
echo import java.util.Iterator;
echo import java.util.List;
echo import java.util.concurrent.ExecutorService;
echo import java.util.concurrent.Executors;
echo.
echo public class TcpForwarder implements FrameForwarder {
echo     private final int port;
echo     private final ServerSocket serverSocket;
echo     private final ExecutorService acceptor;
echo     private final List^<ClientConnection^> clients = new ArrayList^<^>();
echo.
echo     public TcpForwarder(int port) throws IOException {
echo         this.port = port;
echo         this.serverSocket = new ServerSocket(port);
echo         this.acceptor = Executors.newSingleThreadExecutor(r -^> { Thread t = new Thread(r, "tcp-acceptor"); t.setDaemon(true); return t; });
echo         System.out.printf("[TCP] Server socket bound to port %d%n", port);
echo     }
echo.
echo     public void start() { acceptor.submit(this::acceptLoop); }
echo.
echo     @Override public synchronized void forward(byte[] frame) {
echo         if (clients.isEmpty()) return;
echo         Iterator^<ClientConnection^> it = clients.iterator();
echo         while (it.hasNext()) {
echo             ClientConnection c = it.next();
echo             if (!c.send(frame)) { System.out.printf("[TCP] Client %s disconnected.%n", c.remoteAddr()); c.close(); it.remove(); }
echo         }
echo     }
echo.
echo     @Override public void close() {
echo         acceptor.shutdownNow();
echo         synchronized (this) { for (ClientConnection c : clients) c.close(); clients.clear(); }
echo         try { serverSocket.close(); System.out.println("[TCP] Server closed."); } catch (IOException ignored) {}
echo     }
echo.
echo     private void acceptLoop() {
echo         System.out.printf("[TCP] Waiting for clients on port %d...%n", port);
echo         while (!serverSocket.isClosed()) {
echo             try {
echo                 Socket client = serverSocket.accept();
echo                 client.setTcpNoDelay(true); client.setKeepAlive(true);
echo                 ClientConnection conn = new ClientConnection(client);
echo                 synchronized (this) { clients.add(conn); }
echo                 System.out.printf("[TCP] Client connected: %s (total=%d)%n", conn.remoteAddr(), clients.size());
echo             } catch (IOException e) { if (!serverSocket.isClosed()) System.err.println("[TCP] Accept error: " + e.getMessage()); }
echo         }
echo     }
echo.
echo     private static final class ClientConnection {
echo         private final Socket socket;
echo         private final OutputStream out;
echo         ClientConnection(Socket socket) throws IOException { this.socket = socket; this.out = socket.getOutputStream(); }
echo         boolean send(byte[] data) {
echo             if (socket.isClosed()) return false;
echo             try { out.write(data); out.flush(); return true; } catch (IOException e) { return false; }
echo         }
echo         String remoteAddr() { return socket.getRemoteSocketAddress().toString(); }
echo         void close() { try { socket.close(); } catch (IOException ignored) {} }
echo     }
echo }
) > src\main\java\com\adsb\transport\TcpForwarder.java

echo [SETUP] Writing run.bat...
(
echo @echo off
echo set JAR=target\adsb-forwarder-1.0.0.jar
echo if not exist "%%JAR%%" ( echo [ERROR] Run: mvn clean package first. & exit /b 1 )
echo java -jar "%%JAR%%" %%*
) > run.bat

echo.
echo [SETUP] Done! Project structure:
echo.
dir /s /b src\main\java\*.java
echo.
echo Next steps:
echo   mvn clean package
echo   run.bat --verbose
echo   run.bat --rtl-path "C:\rtl-sdr\bin" --tcp-port 30003 --verbose
