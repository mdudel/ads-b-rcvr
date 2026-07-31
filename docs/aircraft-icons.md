# Aircraft Icons — Per-Type Map Glyphs

Issue #11 adds per-aircraft-type icons to the live map.  Instead of a plain
coloured triangle for every track, the map now renders a plan-view silhouette
that matches the actual aircraft type.

## How it works

1. **Enrichment** — when the ADS-B receiver sees a new ICAO hex, the
   `EnrichmentResolver` looks up the type-code (`B738`, `A320`, etc.) from the
   local CSV, downloaded OpenSky bundle, or the live OpenSky REST API
   (asynchronous).

2. **Icon selection** — `AircraftIconService.iconKeyFor(Enrichment)` maps the
   type-code to one of 28 PNG icons.  Unknown types fall back to a generic
   aircraft silhouette (`acft_0`).

3. **Tinting** — each icon's white fill pixels are replaced with the
   altitude-derived colour (same colour scale as the legacy triangle glyph) so
   altitude is still visible at a glance.

4. **Rotation** — the icon is rotated at draw time to match the reported
   track heading.  Rotation is NOT baked into the cached PNG so the cache stays
   small.

5. **Upgrade on arrival** — as soon as an async enrichment lookup completes the
   map repaints and the generic icon upgrades to the type-specific one.

## Icon inventory (28 icons)

| Icon key | File | Mapped ICAO type codes |
|---|---|---|
| `acft_0` | `acft_0_22.png` / `acft_0_44.png` | **Generic fallback** — all unknown types + no-enrichment |
| `L1P_0` | `L1P_0_22.png` | Light 1-piston GA; also helicopter family fallback |
| `T_737_0` | `T_737_0_22.png` | B737 B738 B739 B734 B735 B736 B38M B39M B37M; B7xx family fallback |
| `T_767_0` | `T_767_0_22.png` | B762 B763 B764 B76F |
| `T_A320_0` | `T_A320_0_22.png` | A318 A319 A320 A321 A20N A21N A19N; Airbus (A-prefix) & Embraer (E-prefix) family fallbacks |
| `T_B747_0` | `T_B747_0_22.png` | B742 B743 B744 B748 B74F |
| `T_B757_0` | `T_B757_0_22.png` | B752 B753 |
| `T_B777_0` | `T_B777_0_22.png` | B772 B773 B77L B77W B778 B779 B77F |
| `T_C130_0` | `T_C130_0_22.png` | C130 C30J L100 |
| `T_C17_0` | `T_C17_0_22.png` | C17 C17A |
| `T_A400_0` | `T_A400_0_22.png` | A400 A40M |
| `T_C5M_0` | `T_C5M_0_22.png` | C5 C5M C5A C5B |
| `T_V22_0` | `T_V22_0_22.png` | V22 MV22 CV22 |
| `T_AWACS_0` | `T_AWACS_0_22.png` | E3TF E3CF E3BS E3A |
| `T_KC10_0` | `T_KC10_0_22.png` | KC10 DC10 |
| `T_KC135_0` | `T_KC135_0_22.png` | K35R KC135 C135 E135 |
| `T_R135_0` | `T_R135_0_22.png` | RC35 R135 C135R |
| `T_BE20_0` | `T_BE20_0_22.png` | BE20 BE9L BE10 B350 B300 |
| `T_C550_0` | `T_C550_0_22.png` | C550 C551 C560 C56X C525 |
| `T_LJ35_0` | `T_LJ35_0_22.png` | LJ35 LJ31 LJ40 LJ45 LJ55 LJ60 LJ70 LJ75 |
| `T_GLF5_0` | `T_GLF5_0_22.png` | GLF5 GLF4 GLF6 GLF3 GLEX G650 G550 G500 |
| `T_G200_0` | `T_G200_0_22.png` | G200 G150 G280 |
| `T_EC130_0` | `T_EC130_0_22.png` | EC30 EC35 EC20 AS50 AS55 H130 |
| `T_EC45_0` | `T_EC45_0_22.png` | EC45 EC55 EC75 H145 H155 H175 |
| `T_H60_0` | `T_H60_0_22.png` | H60 S70 H60L UH60 S70A S70I HH60 MH60 |
| `T_SW4_0` | `T_SW4_0_22.png` | S76 S76B S76C S76D SW4 |
| `T_SERVICE_VEHICLE_0` | `T_SERVICE_VEHICLE_0_22.png` | (not in auto-mapping; reserved for ground vehicles) |
| `a320_0` | `a320_0_22.png` | (alternate lowercase A320; loaded but not mapped separately) |

## Family fallback chain

When a type-code is not in the explicit mapping table, the code falls back by
first characters:

| Prefix | Fallback icon | Rationale |
|---|---|---|
| `H` (helicopter-ish) | `L1P_0` | Light heli approximation |
| `A` (Airbus) | `T_A320_0` | Narrow-body jet |
| `B7` (Boeing 7xx) | `T_737_0` | Narrow-body jet |
| `E` (Embraer) | `T_A320_0` | Regional-jet approximation |
| `C1`, `C2`, `C4` (Cessna singles) | `L1P_0` | Light GA piston |
| _(anything else)_ | `acft_0` | Generic silhouette |

## Altitude colour (tint)

Icons are tinted using the same altitude colour scale as the legacy triangle:

| Altitude | Colour |
|---|---|
| No altitude data | Light grey |
| < 5 000 ft | Red |
| 5 000–10 000 ft | Orange |
| 10 000–20 000 ft | Yellow |
| 20 000–30 000 ft | Green |
| 30 000–40 000 ft | Blue |
| ≥ 40 000 ft | Purple |

## How to add a new icon

1. Drop the new SVG in `ICONS/` (Marty's staging area — do NOT delete it).
2. Pre-rasterise at 22 and 44 px:

   ```sh
   magick -background none ICONS/MyNewType_0.svg \
       -resize 22x22 -define png:color-type=6 \
       src/main/resources/icons/MyNewType_0_22.png

   magick -background none ICONS/MyNewType_0.svg \
       -resize 44x44 -define png:color-type=6 \
       src/main/resources/icons/MyNewType_0_44.png
   ```

3. Add the type-code → key mapping to `buildTypeMap()` in
   `AircraftIconService.java`:

   ```java
   // ---- My new type family ----
   m.put("MYTP", "MyNewType_0");
   ```

4. Add the key to `allKnownKeys()` in `AircraftIconService.java`.

5. Run `mvn -B -ntp verify` to confirm everything builds and tests pass.

## Technical notes

- **No Batik / no SVG at runtime.** Batik was not available in the Maven local
  cache and cannot be downloaded (PKIX / network restrictions).  PNGs are
  pre-rasterised offline with ImageMagick and committed to the source tree.
  This keeps the fat JAR small (2.6 MB) and eliminates the ~15 MB Batik
  transitive dependency tree.

- **acft_0.svg is corrupt.** The original `ICONS/acft_0.svg` contains a
  malformed `<polygon points="...">` element that neither ImageMagick nor
  browsers can render cleanly.  A clean replacement SVG was authored and used
  for pre-rasterisation; the original file in `ICONS/` is left untouched.

- **Cache key** is `"<iconKey>_<sizePx>_<tintRGB>"`.  Rotation is applied at
  paint time by `MapPanel.drawAircraftIcon` so the cache doesn't explode to
  28 × N_colours × 360 entries.

- **HiDPI** — 44 px PNGs are loaded for any size ≥ 40 px.  The caller
  currently requests 22 px; bump to 44 if a HiDPI display mode is added.
