package com.adsb.core;

import com.adsb.model.AdsbFrame;

import java.time.Instant;

/**
 * Decodes a raw ADS-B AVR frame (*hexbytes;) into a structured JSON string.
 *
 * Supported Downlink Formats (DF):
 *   DF0  — Short Air-Air Surveillance (altitude)
 *   DF4  — Surveillance Altitude Reply
 *   DF5  — Surveillance Identity Reply (squawk)
 *   DF11 — All-Call Reply (ICAO address only)
 *   DF17 — ADS-B Extended Squitter (the main one):
 *            TC 1-4   Identification (callsign)
 *            TC 9-18  Airborne Position (lat/lon/alt)
 *            TC 19    Airborne Velocity (speed/heading/vrate)
 *            TC 28    Aircraft Status (emergency)
 *            TC 31    Operational Status
 *   DF18 — TIS-B Extended Squitter (same payload as DF17)
 *   DF20 — Comm-B Altitude Reply
 *   DF21 — Comm-B Identity Reply
 *
 * CPR (Compact Position Reporting) lat/lon decoding requires pairing an
 * even and odd frame. This decoder performs single-frame approximate
 * decoding (surface decoding not supported — airborne only).
 */
public class AdsbDecoder {

    // ADS-B callsign character table
    private static final String CALLSIGN_CHARS =
            "#ABCDEFGHIJKLMNOPQRSTUVWXYZ#####_###############0123456789######";

    /**
     * Shared adapter delegating typed decoding to the vendored OpenSky
     * {@code libadsb} decoder. Stateful — caches per-ICAO position decoders
     * for global (even+odd) CPR pairing. Constructed lazily on first typed
     * decode so the pure-{@link #decode(String)} JSON path stays
     * dependency-free (still just JDK).
     */
    private static volatile OpenSkyFrameAdapter typedAdapter;

    private static OpenSkyFrameAdapter typedAdapter() {
        OpenSkyFrameAdapter a = typedAdapter;
        if (a == null) {
            synchronized (AdsbDecoder.class) {
                a = typedAdapter;
                if (a == null) typedAdapter = a = new OpenSkyFrameAdapter();
            }
        }
        return a;
    }

    /**
     * Decodes a single AVR line into a typed {@link AdsbFrame} for the
     * {@link com.adsb.model.AircraftStateStore} pipeline. Returns {@code null}
     * if the line is malformed, unsupported, or (for position frames) if
     * the global even+odd pair hasn't yet arrived.
     *
     * <p>Delegates to the vendored OpenSky {@link OpenSkyFrameAdapter}
     * which handles both global (no-reference) and local (with reference)
     * CPR decoding correctly. First position fix per aircraft typically
     * appears within ~5 s (ADS-B airborne position broadcasts at ~2 Hz
     * alternating even/odd formats).
     *
     * <p>Additive to {@link #decode(String)} — both may be called on the
     * same input independently; the JSON path never touches the vendored
     * decoder.
     */
    public static AdsbFrame decodeTyped(String avrLine) {
        return typedAdapter().decode(avrLine);
    }

    /**
     * Configure the shared adapter's position-filter geofence. See
     * {@link OpenSkyFrameAdapter#configureGeofence(Double, Double, double)}.
     * Safe to call before the first {@link #decodeTyped(String)} — the
     * singleton is materialised on demand.
     */
    public static void configureGeofence(Double rxLat, Double rxLon, double maxRangeNm) {
        typedAdapter().configureGeofence(rxLat, rxLon, maxRangeNm);
    }

    /** Trim leading * and trailing ; and parse hex; returns null on any parse failure. */
    private static byte[] parseAvr(String avrLine) {
        if (avrLine == null) return null;
        String hex = avrLine.trim();
        if (hex.startsWith("*")) hex = hex.substring(1);
        if (hex.endsWith(";"))   hex = hex.substring(0, hex.length() - 1);
        hex = hex.trim();
        if (hex.isEmpty() || hex.length() < 2 || hex.length() % 2 != 0) return null;
        try { return hexToBytes(hex); }
        catch (NumberFormatException e) { return null; }
    }

    /**
     * Decodes a single AVR line (e.g. "*8D4B1A00EA2B5C...;") into JSON.
     *
     * @param avrLine raw AVR line from rtl_adsb
     * @return JSON string, or null if the line cannot be decoded
     */
    public static String decode(String avrLine) {
        if (avrLine == null) return null;

        // Strip leading * and trailing ;
        String hex = avrLine.trim();
        if (hex.startsWith("*")) hex = hex.substring(1);
        if (hex.endsWith(";"))   hex = hex.substring(0, hex.length() - 1);
        hex = hex.trim();

        if (hex.isEmpty() || hex.length() < 2) return null;

        // Must be even-length hex
        if (hex.length() % 2 != 0) return null;

        byte[] msg;
        try {
            msg = hexToBytes(hex);
        } catch (NumberFormatException e) {
            return null;
        }

        int df = (msg[0] & 0xFF) >> 3;

        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"timestamp\":\"").append(Instant.now().toString()).append("\"");
        json.append(",\"raw\":\"").append(hex).append("\"");
        json.append(",\"df\":").append(df);
        json.append(",\"df_desc\":\"").append(dfDescription(df)).append("\"");

        switch (df) {
            case 0:
            case 4:
            case 20: {
                // Short surveillance — altitude
                if (msg.length >= 3) {
                    int alt = decodeGillhamAltitude(msg, df == 20 ? 2 : 1);
                    if (alt != Integer.MIN_VALUE) json.append(",\"altitude_ft\":").append(alt);
                }
                break;
            }
            case 5:
            case 21: {
                // Surveillance identity — squawk
                if (msg.length >= 3) {
                    String squawk = decodeSquawk(msg);
                    if (squawk != null) json.append(",\"squawk\":\"").append(squawk).append("\"");
                }
                break;
            }
            case 11: {
                // All-Call Reply — ICAO only
                if (msg.length >= 3) {
                    json.append(",\"icao\":\"").append(icaoHex(msg, 1)).append("\"");
                }
                break;
            }
            case 17:
            case 18: {
                // Extended Squitter — main ADS-B format
                if (msg.length >= 4) {
                    String icao = icaoHex(msg, 1);
                    json.append(",\"icao\":\"").append(icao).append("\"");
                    decodeExtendedSquitter(msg, json);
                }
                break;
            }
            default:
                // Unknown/unsupported DF — raw only
                break;
        }

        json.append("}");
        return json.toString();
    }

    // -------------------------------------------------------------------------
    // Extended Squitter (DF17/18) payload decoder
    // -------------------------------------------------------------------------

    private static void decodeExtendedSquitter(byte[] msg, StringBuilder json) {
        if (msg.length < 8) return;

        int tc = (msg[4] & 0xFF) >> 3; // Type Code (bits 33-37)
        json.append(",\"tc\":").append(tc);

        if (tc >= 1 && tc <= 4) {
            // --- Identification (callsign) ---
            json.append(",\"type\":\"identification\"");
            String callsign = decodeCallsign(msg);
            if (callsign != null) json.append(",\"callsign\":\"").append(callsign.trim()).append("\"");
            int cat = tc * 8 + (msg[4] & 0x07); // wake turbulence category
            json.append(",\"wake_category\":").append(cat);

        } else if (tc >= 9 && tc <= 18) {
            // --- Airborne Position ---
            json.append(",\"type\":\"airborne_position\"");
            int altCode = ((msg[5] & 0xFF) << 4) | ((msg[6] & 0xFF) >> 4);
            int alt = decodeModeCAlt(altCode);
            if (alt != Integer.MIN_VALUE) json.append(",\"altitude_ft\":").append(alt);

            int cprFormat = (msg[6] >> 2) & 0x01; // 0=even, 1=odd
            json.append(",\"cpr_format\":\"").append(cprFormat == 0 ? "even" : "odd").append("\"");

            // Raw CPR lat/lon (17-bit values, need pair to decode precisely)
            int cprLat = ((msg[6] & 0x03) << 15) | ((msg[7] & 0xFF) << 7) | ((msg[8] & 0xFF) >> 1);
            int cprLon = ((msg[8] & 0x01) << 16) | ((msg[9] & 0xFF) << 8) | (msg[10] & 0xFF);
            json.append(",\"cpr_lat_raw\":").append(cprLat);
            json.append(",\"cpr_lon_raw\":").append(cprLon);

            // Single-frame approximate decode (useful for display, not navigation)
            double[] latlon = cprApproxDecode(cprLat, cprLon, cprFormat);
            if (latlon != null) {
                json.append(String.format(",\"latitude\":%.6f", latlon[0]));
                json.append(String.format(",\"longitude\":%.6f", latlon[1]));
            }

        } else if (tc == 19) {
            // --- Airborne Velocity ---
            json.append(",\"type\":\"airborne_velocity\"");
            int subtype = msg[4] & 0x07;

            if (subtype == 1 || subtype == 2) {
                // Ground speed
                boolean dEW  = (msg[5] & 0x04) != 0;
                int     vEW  = ((msg[5] & 0x03) << 8) | (msg[6] & 0xFF);
                boolean dNS  = (msg[7] & 0x80) != 0;
                int     vNS  = ((msg[7] & 0x7F) << 3) | ((msg[8] & 0xFF) >> 5);

                if (vEW != 0 && vNS != 0) {
                    double ewKts = (vEW - 1) * (subtype == 2 ? 4 : 1) * (dEW ? -1 : 1);
                    double nsKts = (vNS - 1) * (subtype == 2 ? 4 : 1) * (dNS ? -1 : 1);
                    double speed   = Math.sqrt(ewKts * ewKts + nsKts * nsKts);
                    double heading = Math.toDegrees(Math.atan2(ewKts, nsKts));
                    if (heading < 0) heading += 360;
                    json.append(String.format(",\"ground_speed_kts\":%.1f", speed));
                    json.append(String.format(",\"track_deg\":%.1f", heading));
                }
            } else if (subtype == 3 || subtype == 4) {
                // Airspeed
                boolean hdgAvail = (msg[5] & 0x04) != 0;
                if (hdgAvail) {
                    int hdgRaw = ((msg[5] & 0x03) << 8) | (msg[6] & 0xFF);
                    double heading = hdgRaw * 360.0 / 1024.0;
                    json.append(String.format(",\"heading_deg\":%.1f", heading));
                }
                int airspeed = ((msg[7] & 0x7F) << 3) | ((msg[8] & 0xFF) >> 5);
                if (airspeed != 0) {
                    int kts = (airspeed - 1) * (subtype == 4 ? 4 : 1);
                    json.append(",\"airspeed_kts\":").append(kts);
                    json.append(",\"airspeed_type\":\"").append((msg[7] & 0x80) != 0 ? "TAS" : "IAS").append("\"");
                }
            }

            // Vertical rate (common to all subtypes)
            int vrSign = (msg[8] & 0x08) >> 3;
            int vrRaw  = ((msg[8] & 0x07) << 6) | ((msg[9] & 0xFF) >> 2);
            if (vrRaw != 0) {
                int vrate = (vrRaw - 1) * 64 * (vrSign == 1 ? -1 : 1);
                json.append(",\"vertical_rate_fpm\":").append(vrate);
            }

        } else if (tc == 28) {
            // --- Aircraft Status (emergency) ---
            json.append(",\"type\":\"aircraft_status\"");
            int status = (msg[5] & 0xE0) >> 5;
            json.append(",\"emergency_status\":").append(status);
            json.append(",\"emergency_desc\":\"").append(emergencyDesc(status)).append("\"");

        } else if (tc == 31) {
            // --- Operational Status ---
            json.append(",\"type\":\"operational_status\"");

        } else {
            json.append(",\"type\":\"unknown_tc\"");
        }
    }

    // -------------------------------------------------------------------------
    // Field decoders
    // -------------------------------------------------------------------------

    /** Decode 8-character callsign from TC 1-4 message (bytes 5-11). */
    private static String decodeCallsign(byte[] msg) {
        if (msg.length < 11) return null;
        StringBuilder cs = new StringBuilder(8);
        // 6 bits per character, 8 characters packed into bytes 5-10
        long bits = 0;
        for (int i = 5; i <= 10; i++) bits = (bits << 8) | (msg[i] & 0xFF);
        for (int i = 7; i >= 0; i--) {
            int idx = (int)((bits >> (i * 6)) & 0x3F);
            cs.append(CALLSIGN_CHARS.charAt(idx));
        }
        return cs.toString();
    }

    /**
     * Decode Mode C altitude (13-bit Gillham code) from surveillance replies.
     * Returns Integer.MIN_VALUE if indeterminate.
     */
    private static int decodeGillhamAltitude(byte[] msg, int byteOffset) {
        if (msg.length < byteOffset + 2) return Integer.MIN_VALUE;
        int raw = ((msg[byteOffset] & 0xFF) << 5) | ((msg[byteOffset + 1] & 0xFF) >> 3);
        return decodeModeCAlt(raw & 0x1FFF);
    }

    /**
     * Decode 13-bit Mode C altitude code.
     * Returns Integer.MIN_VALUE if indeterminate.
     */
    private static int decodeModeCAlt(int codeRaw) {
        // Extract M and Q bits
        boolean mBit = (codeRaw & 0x0040) != 0;
        boolean qBit = (codeRaw & 0x0010) != 0;

        if (mBit) return Integer.MIN_VALUE; // Metric altitude not supported

        if (qBit) {
            // 25 ft increment encoding
            int n = ((codeRaw & 0x1F80) >> 2) | ((codeRaw & 0x0020) >> 1) | (codeRaw & 0x000F);
            return n * 25 - 1000;
        }

        // Gillham code (Gray code)
        int c1 = (codeRaw >> 12) & 0x01;
        int a1 = (codeRaw >> 11) & 0x01;
        int c2 = (codeRaw >> 10) & 0x01;
        int a2 = (codeRaw >>  9) & 0x01;
        int c4 = (codeRaw >>  8) & 0x01;
        int a4 = (codeRaw >>  6) & 0x01; // skip M bit at bit 6
        int b1 = (codeRaw >>  5) & 0x01;
        // skip Q bit at bit 4
        int b2 = (codeRaw >>  3) & 0x01;
        int d1 = (codeRaw >>  2) & 0x01;
        int b4 = (codeRaw >>  1) & 0x01;
        int d2 = (codeRaw)       & 0x01;

        // Gray-decode the 500ft component
        int grayC = (c1 << 2) | (c2 << 1) | c4;
        int grayA = (a1 << 2) | (a2 << 1) | a4;
        int grayB = (b1 << 2) | (b2 << 1) | b4;
        int grayD = (d1 << 1) | d2;

        int n500 = grayDecode(grayC) * 3 + grayDecode(grayA);
        if (n500 == 0 || n500 > 13) return Integer.MIN_VALUE;

        int n100 = grayDecode(grayB) * 2 + grayDecode(grayD);
        if (n100 == 6 || n100 == 7) return Integer.MIN_VALUE;
        if (n100 > 4) n100--;
        if (n500 % 2 != 0) n100 = 6 - n100; // interleave correction

        int alt = (n500 * 500 + n100 * 100) - 1300;
        return alt;
    }

    private static int grayDecode(int gray) {
        int n = 0;
        for (; gray != 0; gray >>= 1) n ^= gray;
        return n;
    }

    /** Decode squawk from DF5/DF21 identity reply. */
    private static String decodeSquawk(byte[] msg) {
        if (msg.length < 3) return null;
        // ID field: bits C1 A1 C2 A2 C4 A4 _ B1 D1 B2 D2 B4 D4
        int raw = ((msg[2] & 0xFF) << 5) | ((msg[3] & 0xFF) >> 3);
        int c1 = (raw >> 12) & 1, a1 = (raw >> 11) & 1;
        int c2 = (raw >> 10) & 1, a2 = (raw >>  9) & 1;
        int c4 = (raw >>  8) & 1, a4 = (raw >>  6) & 1;
        int b1 = (raw >>  5) & 1, b2 = (raw >>  3) & 1;
        int d1 = (raw >>  4) & 1, d2 = (raw >>  2) & 1;
        int b4 = (raw >>  1) & 1, d4 = raw & 1;
        int a = a4 * 4 + a2 * 2 + a1;
        int b = b4 * 4 + b2 * 2 + b1;
        int c = c4 * 4 + c2 * 2 + c1;
        int d = d4 * 4 + d2 * 2 + d1;
        return String.format("%d%d%d%d", a, b, c, d);
    }

    /** ICAO 24-bit address as hex string from bytes [offset..offset+2]. */
    private static String icaoHex(byte[] msg, int offset) {
        if (msg.length < offset + 3) return "000000";
        return String.format("%02X%02X%02X",
                msg[offset] & 0xFF, msg[offset+1] & 0xFF, msg[offset+2] & 0xFF);
    }

    /**
     * Single-frame approximate CPR position decode.
     * This is an approximation — for precision, two frames (even+odd) are needed.
     * Accuracy is within ~10 nm for airborne targets.
     */
    private static double[] cprApproxDecode(int rawLat, int rawLon, int isOdd) {
        double dLat = isOdd == 0 ? 360.0 / 60.0 : 360.0 / 59.0;
        double lat  = dLat * (cprMod(rawLat, 131072) / 131072.0);
        // Wrap to [-90, 90] — simplified, not accounting for zone transitions
        if (lat > 270) lat -= 360;

        double nl = cprNL(lat);
        double dLon = (nl < 1) ? 360.0 : 360.0 / (nl - isOdd);
        double lon  = dLon * (cprMod(rawLon, 131072) / 131072.0);
        if (lon > 180) lon -= 360;

        return new double[]{lat, lon};
    }

    /** CPR NL (Number of Longitude zones) lookup. */
    private static double cprNL(double lat) {
        if (lat < 0) lat = -lat;
        if (lat < 10.47047130) return 59;
        if (lat < 14.82817437) return 58;
        if (lat < 18.18626357) return 57;
        if (lat < 21.02939493) return 56;
        if (lat < 23.54504487) return 55;
        if (lat < 25.82924707) return 54;
        if (lat < 27.93898710) return 53;
        if (lat < 29.91135686) return 52;
        if (lat < 31.77209708) return 51;
        if (lat < 33.53993436) return 50;
        if (lat < 35.22899598) return 49;
        if (lat < 36.85025108) return 48;
        if (lat < 38.41241892) return 47;
        if (lat < 39.92256684) return 46;
        if (lat < 41.38651832) return 45;
        if (lat < 42.80914012) return 44;
        if (lat < 44.19454951) return 43;
        if (lat < 45.54626723) return 42;
        if (lat < 46.86733252) return 41;
        if (lat < 48.16039128) return 40;
        if (lat < 49.42776439) return 39;
        if (lat < 50.67150166) return 38;
        if (lat < 51.89342469) return 37;
        if (lat < 53.09516153) return 36;
        if (lat < 54.27817472) return 35;
        if (lat < 55.44378444) return 34;
        if (lat < 56.59318756) return 33;
        if (lat < 57.72747354) return 32;
        if (lat < 58.84763776) return 31;
        if (lat < 59.95459277) return 30;
        if (lat < 61.04917774) return 29;
        if (lat < 62.13216659) return 28;
        if (lat < 63.20427479) return 27;
        if (lat < 64.26616523) return 26;
        if (lat < 65.31845310) return 25;
        if (lat < 66.36171008) return 24;
        if (lat < 67.39646774) return 23;
        if (lat < 68.42322022) return 22;
        if (lat < 69.44242631) return 21;
        if (lat < 70.45451075) return 20;
        if (lat < 71.45986473) return 19;
        if (lat < 72.45884545) return 18;
        if (lat < 73.45177442) return 17;
        if (lat < 74.43893416) return 16;
        if (lat < 75.42056257) return 15;
        if (lat < 76.39684391) return 14;
        if (lat < 77.36789461) return 13;
        if (lat < 78.33374083) return 12;
        if (lat < 79.29428225) return 11;
        if (lat < 80.24923213) return 10;
        if (lat < 81.19801349) return 9;
        if (lat < 82.13956981) return 8;
        if (lat < 83.07199445) return 7;
        if (lat < 83.99173563) return 6;
        if (lat < 84.89166191) return 5;
        if (lat < 85.75541621) return 4;
        if (lat < 86.53536998) return 3;
        if (lat < 87.00000000) return 2;
        return 1;
    }

    private static double cprMod(double a, double b) {
        return a - b * Math.floor(a / b);
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static String dfDescription(int df) {
        return switch (df) {
            case 0  -> "Short Air-Air Surveillance";
            case 4  -> "Surveillance Altitude Reply";
            case 5  -> "Surveillance Identity Reply";
            case 11 -> "All-Call Reply";
            case 17 -> "ADS-B Extended Squitter";
            case 18 -> "TIS-B Extended Squitter";
            case 19 -> "Military Extended Squitter";
            case 20 -> "Comm-B Altitude Reply";
            case 21 -> "Comm-B Identity Reply";
            case 24 -> "Comm-D Extended Length Message";
            default -> "Reserved/Unknown";
        };
    }

    private static String emergencyDesc(int status) {
        return switch (status) {
            case 0 -> "No emergency";
            case 1 -> "General emergency";
            case 2 -> "Lifeguard/Medical";
            case 3 -> "Minimum fuel";
            case 4 -> "No communications";
            case 5 -> "Unlawful interference";
            case 6 -> "Downed aircraft";
            default -> "Unknown";
        };
    }
}
