# ADS-B RTL-SDR Forwarder — Windows

Receives ADS-B Mode S frames from an RTL-SDR dongle via `rtl_adsb.exe` and
forwards them over **UDP unicast**, **UDP multicast**, or **TCP**.
All three transports can run simultaneously.

**Payload format is selectable** via `--payload`:

| Value | Wire content | Downstream consumers |
|-------|--------------|----------------------|
| `json` *(default)* | Decoded JSON, one object per frame | Custom pipelines, log processors |
| `avr` | Raw hex AVR frames (`*8D...;`) | Virtual Radar Server, PlaneFinder, dump1090-compatible tools |
| `cot` | CoT XML `<event>` per aircraft snapshot | **WinTAK, ATAK, GCCS-J COP, TAK Server** |

---

## Prerequisites

### 1. RTL-SDR Windows drivers + tools

Download the pre-built Windows binaries from:
**https://github.com/rtlsdrblog/rtl-sdr-blog/releases**
(or the original: https://osmocom.org/projects/rtl-sdr/wiki/Rtl-sdr)

Extract the ZIP — you need `rtl_adsb.exe` and the supporting DLLs
(`rtlsdr.dll`, `libusb-1.0.dll`, etc.) to stay together in the same folder.

**Install the WinUSB driver** with Zadig:
1. Download Zadig: https://zadig.akeo.ie/
2. Plug in your RTL-SDR dongle
3. Open Zadig → Options → List All Devices
4. Select your RTL-SDR device (e.g. "Bulk-In, Interface (Interface 0)")
5. Select driver: **WinUSB** → click "Install Driver"

Verify in a CMD window:
```cmd
cd C:\rtl-sdr\bin
rtl_adsb.exe -d 0
```
You should see frames like `*8D4B1A00EA...;` scrolling past.

### 2. Java 17+

Download from: https://adoptium.net/  
After install, verify:
```cmd
java -version
```

### 3. Maven 3.8+ (to build)

Download from: https://maven.apache.org/download.cgi  
Extract, add `bin\` to your PATH.  
Verify:
```cmd
mvn -version
```

---

## Build

Open a CMD window in the project folder:

```cmd
build.bat
```

This produces `target\adsb-forwarder-1.0.0.jar`.

---

## Usage

```cmd
run.bat [options]

Output (at least one required):
  --udp <host:port>           UDP unicast destination
  --multicast <group:port>    UDP multicast group (e.g. 239.1.1.1:30003)
  --tcp-port <port>           TCP server port (clients connect here)

RTL-SDR options:
  --rtl-device <index>        Device index (default: 0)
  --rtl-path <dir>            Path to folder containing rtl_adsb.exe
                              (only needed if rtl_adsb.exe is NOT on your PATH)
  --gain <value>              Gain value e.g. 40, or omit for auto
  --format <avr|raw>          Frame format (default: avr)

Payload format (applies to every enabled sink):
  --payload <avr|json|cot>    Wire format (default: json)

CoT options (only used when --payload cot):
  --cot-affiliation <friendly|neutral|hostile|unknown|pending>
                              Default: neutral (correct for civil airliners)
  --cot-category <civilian|military>
                              Default: civilian
  --cot-stale-air <seconds>   Stale offset for airborne tracks (default 30)
  --cot-stale-ground <seconds> Stale offset for on-ground tracks (default 120)

Other:
  --verbose                   Print frames to console
```

---

## Examples

### CoT XML air tracks to WinTAK / ATAK (new)
```cmd
run.bat --payload cot --udp 127.0.0.1:6969 --verbose
```
Emits one CoT `<event>` per aircraft *snapshot update* (emit-on-change). Point
your TAK client at the same UDP address. Default type is `a-n-A-C-F` (neutral
civilian fixed-wing); use `--cot-affiliation` / `--cot-category` to override.

### TCP server on port 30003
```cmd
run.bat --tcp-port 30003 --verbose
```

### UDP unicast to another machine on the LAN
```cmd
run.bat --udp 192.168.1.50:30003
```

### Specify rtl_adsb.exe location + fixed gain
```cmd
run.bat --rtl-path "C:\rtl-sdr\bin" --gain 40 --tcp-port 30003 --verbose
```

### All three transports simultaneously
```cmd
run.bat ^
  --rtl-path "C:\rtl-sdr\bin" ^
  --gain 40 ^
  --udp 192.168.1.100:30003 ^
  --multicast 239.1.1.1:30003 ^
  --tcp-port 30003 ^
  --verbose
```

### Receive a TCP stream (in a second CMD window)
```cmd
REM Requires ncat from https://nmap.org/ncat/
ncat 127.0.0.1 30003
```

---

## Receiving Multicast — Java snippet

```java
MulticastSocket socket = new MulticastSocket(30003);
InetAddress group = InetAddress.getByName("239.1.1.1");
socket.joinGroup(group);

byte[] buf = new byte[512];
while (true) {
    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
    socket.receive(pkt);
    String frame = new String(pkt.getData(), 0, pkt.getLength());
    System.out.println("Received: " + frame.trim());
}
```

---

## Frame Format (AVR)

```
*8D4B1A00EA2B5C5A5A5A5A5A;
```

- Starts with `*`
- Hex-encoded Mode S message bytes
- Ends with `;` followed by newline
- DF17 (Extended Squitter) carries position, velocity, and identification

---

## Architecture

```
RTL-SDR USB dongle
        │
  rtl_adsb.exe   ← subprocess spawned by AdsbReceiver
        │  stdout (AVR: *8D4B1A00...;)
        ▼
  AdsbReceiver (Java)
        │
        ├──► UdpForwarder        → UDP unicast  → host:port
        ├──► MulticastForwarder  → UDP multicast → 239.x.x.x:port
        └──► TcpForwarder        → TCP server   → N clients
```

---

## Troubleshooting

**`'rtl_adsb.exe' is not recognized`**  
Add the folder containing `rtl_adsb.exe` to your PATH, or use `--rtl-path "C:\path\to\bin"`.

**`usb_open error -3` or `No supported devices found`**  
Zadig WinUSB driver is not installed. Redo the Zadig step above.

**Device claimed by another process**  
Close SDR#, ADSB#, or any other SDR application before starting.

**No frames appear (but no errors)**  
- Try a fixed gain: `--gain 40` or `--gain 49` (maximum)
- Ensure your antenna is connected and tuned for 1090 MHz
- Run `rtl_adsb.exe` directly in CMD first to confirm the dongle works

**Windows Firewall blocking TCP/UDP**  
Allow Java through Windows Firewall, or run:
```cmd
netsh advfirewall firewall add rule name="ADS-B Forwarder" ^
  protocol=TCP dir=in localport=30003 action=allow
```
"# ads-b-rcvr" 
