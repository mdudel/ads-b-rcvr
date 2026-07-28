# ADS-B RTL-SDR Forwarder — Windows

Receives ADS-B Mode S frames from an RTL-SDR dongle via `rtl_adsb.exe` and
forwards them over **UDP unicast**, **UDP multicast**, or **TCP**.
All three transports can run simultaneously, in any mix of payload formats,
managed either via CLI flags or the built-in **Swing UI**.

## Quick start with the UI

```cmd
run.bat --ui
```

Opens a window with:
- **Map** in the centre — aircraft glyphs rotated by heading, coloured by altitude
- **Toolbar** on top (Tracks / Connectors / Settings / About) each opening a side dock
- **Tracks** — sortable table of every aircraft; click a row to centre the map
- **Connectors** — add / edit / remove / enable-toggle any number of
  UDP unicast / multicast / TCP output sinks, each with its own payload
  (avr / json / cot). Zenoh is present in the type list but disabled
  until issue #4 lands.
- **Settings** — CoT affiliation / category / stale timeouts, live-editable

Connectors persist to `~/.adsb-rcvr/adsb-rcvr.properties` on every change,
so the next run reopens the same set.

**Payload format** is selectable per-connector in the UI, or globally on
the CLI via `--payload`:

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

### JSON to a downstream pipeline (default)
```cmd
run.bat --udp 127.0.0.1:30003
```

### Raw AVR frames to Virtual Radar Server / dump1090-compatible tools
```cmd
run.bat --payload avr --tcp-port 30003
```

### CoT XML air tracks to WinTAK / ATAK / TAK Server
```cmd
run.bat --payload cot --udp 127.0.0.1:6969 --verbose
```
No receiver location needed — CPR positions are recovered from paired
even+odd frames (ICAO Doc 9871 App. C global decode), so the receiver
runs anywhere without knowing where it is. First fix per aircraft
typically appears within ~5 s (aircraft broadcast alternating
even/odd position frames at ~2 Hz).

### CoT to a multicast group with a longer stale window
```cmd
run.bat --payload cot --multicast 239.2.3.1:6969 --cot-stale-air 60
```

### Specify rtl_adsb.exe location + fixed gain
```cmd
run.bat --rtl-path "C:\rtl-sdr\bin" --gain 40 --tcp-port 30003 --verbose
```

### Fan-out to all three transports at once
```cmd
run.bat ^
  --rtl-path "C:\rtl-sdr\bin" ^
  --gain 40 ^
  --udp 192.168.1.100:30003 ^
  --multicast 239.1.1.1:30003 ^
  --tcp-port 30003 ^
  --verbose
```
All enabled sinks share the payload format selected by `--payload`. If you
need different formats on different sinks, run two receiver processes with
different `--payload` values (`rtl_adsb.exe` can be shared via `rtl_tcp`).

### Receive a TCP stream (in a second CMD window)
```cmd
REM Requires ncat from https://nmap.org/ncat/
ncat 127.0.0.1 30003
```

---

## Payload formats

A single `--payload` flag selects the wire format across every enabled sink.
Default is `json` (byte-identical to earlier releases).

### `--payload avr` — raw hex frames

One line per frame, exactly as `rtl_adsb.exe` emits them:

```
*8D4CA1FA234994B84DAA9CBA5DFB;
*8D4CA1FA582986BFC1E7217A9A2E;
*8D4CA1FA99453801FD05B067ADF9;
```

Use this when the downstream tool is a Mode-S decoder in its own right
(Virtual Radar Server, PlaneFinder, `dump1090`-family tools).

### `--payload json` (default) — decoded per-frame JSON

One JSON object per frame, terminated with `\n`:

```json
{"timestamp":"2026-07-27T09:44:49.736Z","raw":"8D4CA1FA234994B84DAA9CBA5DFB",
 "df":17,"df_desc":"ADS-B Extended Squitter","icao":"4CA1FA","tc":4,
 "type":"identification","callsign":"RYR8SZ","wake_category":36}
```

Each frame is stateless — identification, position, and velocity arrive in
separate JSON objects. Downstream tools are responsible for aggregating
by ICAO if they need a merged aircraft view.

### `--payload cot` — CoT XML air tracks

One CoT `<event>` document per **aggregated aircraft snapshot update**
(see "Multi-frame aggregation" below). Single-line format, splits cleanly
on `<?xml` when streamed over TCP:

```xml
<?xml version='1.0' standalone='yes'?><event version="2.0"
  type="a-n-A-C-F" uid="ICAO-4CA1FA" how="m-g"
  time="2026-07-27T09:44:49.838Z"
  start="2026-07-27T09:44:49.838Z"
  stale="2026-07-27T09:45:19.838Z">
  <point lat="48.123456" lon="11.654321" hae="10668.0"
         ce="9999999.0" le="9999999.0"/>
  <detail>
    <contact callsign="RYR8SZ"/>
    <track speed="257.22" course="45.0"/>
    <remarks>RYR8SZ 4CA1FA SQUAWK 1234 CAT A3 ALT 35000ft</remarks>
  </detail>
</event>
```

Default CoT type is `a-n-A-C-F` (**a**tom, **n**eutral, **A**ir,
**C**ivilian, **F**ixed-wing) — the correct MIL-STD-2525 encoding for a
globally-registered civil airliner. Override affiliation/category via
`--cot-affiliation` and `--cot-category`.

The US, UK, and German military ICAO ranges are recognised by the built-in
classifier and produce `a-f-A-M-F` (friendly, military, fixed-wing).
CLI flags override the range table.

---

## Multi-frame aggregation (why `--payload cot` differs)

ADS-B is chatty and fragmented: an aircraft's identification (callsign),
position, and velocity arrive in **separate frames**, often seconds apart.

- `--payload avr` and `--payload json` emit **one output per input frame**
  — stateless, high fan-out.
- `--payload cot` maintains an in-memory per-ICAO `AircraftStateStore` that
  merges every incoming frame into the aircraft's known state, then emits
  a fresh `<event>` **on every merged snapshot update** ("emit-on-change").

Consequences a WinTAK/ATAK operator will notice:

- The first `<event>` for a new aircraft may lack `<track>` (velocity) or
  even lack `<contact callsign="…"/>` — those fields fill in as frames
  arrive. TAK receivers merge on `uid`, so the icon updates in place.
- Aircraft `uid` is `ICAO-<HEX_UPPER>` (e.g. `ICAO-4CA1FA`). Multiple
  receivers pointed at the same TAK Server converge on a single track.
- `<contact callsign>` falls back to `ICAO-<HEX>` when no callsign has
  been received yet.
- `stale` defaults to **30 s** airborne / **120 s** on-ground. A 500 kt
  airborne track has already flown ~30 nm in 120 s, so keeping stale
  short is essential for a useful map picture.

---

## Consuming CoT in WinTAK / ATAK

1. Start the receiver targeting a UDP port your TAK client can reach:

   ```cmd
   run.bat --payload cot --udp 127.0.0.1:6969 --verbose
   ```

2. **Verify** CoT bytes are on the wire before touching your TAK client
   (isolates any receiver-side issue from any TAK-side issue):

   ```cmd
   REM ncat from https://nmap.org/ncat/
   ncat -ul 6969
   ```
   Expect: `<?xml …?><event …>` per aircraft, one every few seconds.

3. **Configure the TAK client** to accept the feed:

   - **WinTAK**: *Settings → Network Preferences → Manage Inputs*
     → *Add* → Protocol: `UDP`, Address: `127.0.0.1`, Port: `6969`.
   - **ATAK-Civ (Android)**: *Settings → Network Preferences →
     Manage Server Connections → Add UDP*.
   - **TAK Server**: point a data-source at the same UDP endpoint;
     the server will fan out to connected clients.

4. Aircraft icons should appear on the map within a few seconds. Neutral
   civilian air symbols by default; label = callsign (or `ICAO-<HEX>`
   until callsign arrives).

5. If nothing appears:
   - Confirm step 2 first — no bytes = receiver problem, not TAK problem.
   - Windows Firewall: allow inbound UDP on your chosen port (see
     Troubleshooting below).
   - Multicast: TAK on the same machine typically wants the UDP unicast
     input above; use `--multicast` only when the client is elsewhere on
     a multicast-enabled LAN.

---

## Publishing to Zenoh

The **Zenoh** connector type publishes each forwarded frame to a
[Zenoh](https://zenoh.io) router as a PUSH message. Zero external
dependencies — the receiver ships a vendored pure-Java Zenoh 1.x
client (no JNI, no native libraries).

### Connector target format

```
endpoint;key-prefix
```

- **endpoint** — any scheme the pure-Java facade accepts:
  `tcp/host:port`, `tls/host:port`, `ws/host:port`, `wss/host:port`.
  Example: `tcp/localhost:7447`.
- **key-prefix** — base Zenoh key expression, e.g. `adsb/cot`. Leading
  and trailing slashes are trimmed.

### Mode: stream vs per-aircraft

Every Zenoh connector picks a **mode** (dropdown on the Add / Edit
form, persisted as `connector.<id>.zenohMode`):

- **Stream (one topic)** — every frame publishes to the base key
  prefix as-is, regardless of payload. All CoT / JSON / AVR lands on
  one topic. Best for downstream consumers that want the whole feed
  as a single stream and will filter themselves.
- **Per aircraft (fan out)** — CoT frames publish to a per-aircraft
  sub-key derived from the `uid="ICAO-XXXXXX"` attribute. Non-CoT
  payloads (JSON / AVR) still land on the base key because there's no
  reliable per-entity key to derive.

Backward compat: connectors saved before this option existed load
as **Per aircraft** (the pre-option shipping default).

### Emitted key expression per (payload, mode) pair

| Payload | Mode         | Emitted key                    |
|---------|--------------|--------------------------------|
| CoT     | Per aircraft | `<key-prefix>/<ICAO24>`        |
| CoT     | Stream       | `<key-prefix>`                 |
| JSON    | either       | `<key-prefix>`                 |
| AVR     | either       | `<key-prefix>`                 |

Example: with target `tcp/localhost:7447;adsb/cot` and mode
**Per aircraft**, an aircraft with ICAO `4CA1FA` publishes to key
`adsb/cot/4CA1FA`. Subscribe to one aircraft with `adsb/cot/4CA1FA`
or the whole fleet with `adsb/cot/**`. With mode **Stream**, every
frame lands on `adsb/cot` regardless.

### Smoke recipe against a local zenohd

1. Start a Zenoh router on your box (any recent `zenohd` release):
   ```bash
   zenohd --listen tcp/0.0.0.0:7447 --listen tcp/[::]:7447
   ```
   The double `--listen` is important on Windows — the default
   `tcp/[::]:7447` alone does NOT dual-bind to IPv4 and
   `localhost` typically resolves to `127.0.0.1` first.

2. Start a subscriber in another shell (using any Zenoh client;
   `zenoh-cli` shown here):
   ```bash
   z_sub -k 'adsb/cot/**'
   ```

3. In the ADS-B receiver UI, add a Zenoh connector:
   - Name: `Ops Zenoh`
   - Type: `Zenoh`
   - Target: `tcp/localhost:7447;adsb/cot`
   - Payload: `CoT XML`
   - Enabled: yes

4. Watch aircraft snapshots flow through the subscriber, one
   `adsb/cot/<ICAO>` per aircraft update.

### Windows localhost gotcha

If you get `Connection refused: getsockopt`, either start `zenohd`
with both `tcp/0.0.0.0:7447` **and** `tcp/[::]:7447` (as above), or
change the connector target to `tcp/[::1]:7447;adsb/cot` to force
IPv6 loopback.

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
        ├─ --payload avr
        │     └──► raw hex bytes
        │
        ├─ --payload json (default)
        │     └──► AdsbDecoder.decode() → JSON bytes
        │
        └─ --payload cot
              └──► AdsbDecoder.decodeTyped() → AdsbFrame
                    └──► AircraftStateStore.update()  (per-ICAO merge)
                          └──► snapshot listener
                                └──► CoTBuilder.build() → XML bytes

  Bytes from the selected payload feed all enabled sinks in parallel:
        ├──► UdpForwarder        → UDP unicast  → host:port
        ├──► MulticastForwarder  → UDP multicast → 239.x.x.x:port
        └──► TcpForwarder        → TCP server   → N clients
```

Key packages:

| Package | Purpose |
|---------|---------|
| `com.adsb.core`     | Receiver process, forwarder interface, `--payload` enum, `OpenSkyFrameAdapter` bridge |
| `com.adsb.transport`| UDP unicast / multicast / TCP fan-out / **Zenoh** sinks |
| `com.adsb.model`    | `AdsbTrack` snapshot, sealed `AdsbFrame` hierarchy, `AircraftStateStore` |
| `com.adsb.cot`      | `IcaoAircraftClassifier` (MIL-STD-2525), `CoTBuilder` (single-line XML) |
| `org.opensky.*`     | Vendored [OpenSky java-adsb](https://github.com/openskynetwork/java-adsb) decoder — provides Mode-S parsing + global (no-reference) CPR position decoding |
| `io.mdudel.zenoh.*` | Vendored [pure-Java Zenoh 1.x client](https://github.com/mdudel/simple-zenoh-java-client) — no JNI, no native libs, TCP/TLS/WS/WSS |

---

## Tests

```cmd
mvn -B -ntp test
```

Unit tests pin:

- CoT XML byte-shape (golden strings, `<track>` element omitted when velocity
  unknown, stale offset air vs ground, UID uppercase normalisation).
- Locale independence: same XML bytes under `de-DE` / `fr-FR` JVMs
  (verifies `Locale.ROOT` numeric formatting).
- XML attribute escaping for `& < > " '`.
- `IcaoAircraftClassifier`: ICAO range table (US/UK/DE military),
  CLI override precedence, DO-260B category → function suffix.
- `TrackMerger`: per-frame merge rules, listener registration order,
  listener exception isolation.
- `PayloadFormat`: canonical + synonym parsing.

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

**No CoT icons in WinTAK/ATAK even though `ncat -ul 6969` shows bytes**
- Confirm the TAK client is set to `UDP` input on the exact same port.
- On the same machine as the receiver, use unicast (`127.0.0.1`), not
  multicast; multicast loopback is off by default on Windows.
- Emit-on-change means the first `<event>` for an aircraft may not have a
  callsign yet. Give it a few seconds for identification frames to arrive.

