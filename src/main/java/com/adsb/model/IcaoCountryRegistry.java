package com.adsb.model;

/**
 * ICAO 24-bit address -&gt; country of registration.
 *
 * <p>Reference: ICAO Annex 10 Volume III, Appendix to Chapter 9
 * (Aircraft Address Allocation). The 24-bit space is carved into
 * per-state blocks of varying sizes; larger states (US, Russia,
 * China, ...) get bigger blocks. Ranges are inclusive and mutually
 * exclusive.
 *
 * <p>Coverage: every state in the ITU allocation table that emits
 * meaningful ADS-B traffic today. Rare / very small allocations
 * (some Pacific island states, some sub-blocks not yet assigned)
 * fall through to {@code null} rather than mis-labelling.
 *
 * <p>Pure static class, no instances, no external data files -- this
 * ships in the jar as pure Java. Enrichment via an external CSV /
 * OpenSky database is Option B (separate ticket).
 */
public final class IcaoCountryRegistry {

    private IcaoCountryRegistry() {}

    /** One inclusive range in the allocation table. */
    private record Range(int startHex, int endHex, String country) {}

    /**
     * Sorted by startHex ascending; binary-searchable but small
     * enough (~180 entries) that a linear scan is fine and easier
     * to audit against the ICAO table.
     *
     * <p>Non-exhaustive: I picked the states with real ADS-B traffic
     * volume in EMEA / North America / East Asia. If Marty sees a
     * neighbour showing "Unknown" that a real flight tracker would
     * name, add a row here -- ICAO Annex 10 Volume III is the ground
     * truth.
     */
    private static final Range[] RANGES = {
            // Africa (partial)
            new Range(0x004000, 0x0043FF, "Zimbabwe"),
            new Range(0x006000, 0x006FFF, "Mozambique"),
            new Range(0x008000, 0x00FFFF, "South Africa"),
            new Range(0x010000, 0x017FFF, "Egypt"),
            new Range(0x018000, 0x01FFFF, "Libya"),
            new Range(0x020000, 0x027FFF, "Morocco"),
            new Range(0x028000, 0x02FFFF, "Tunisia"),
            new Range(0x030000, 0x0303FF, "Botswana"),
            new Range(0x032000, 0x032FFF, "Burundi"),
            new Range(0x034000, 0x034FFF, "Cameroon"),
            new Range(0x036000, 0x0367FF, "Comoros"),
            new Range(0x038000, 0x038FFF, "Congo"),
            new Range(0x03E000, 0x03EFFF, "Ivory Coast"),
            new Range(0x040000, 0x040FFF, "Djibouti"),
            new Range(0x042000, 0x042FFF, "Ethiopia"),
            new Range(0x044000, 0x044FFF, "Equatorial Guinea"),
            new Range(0x046000, 0x046FFF, "Ghana"),
            new Range(0x048000, 0x048FFF, "Guinea"),
            new Range(0x04A000, 0x04A3FF, "Guinea-Bissau"),
            new Range(0x04C000, 0x04CFFF, "Kenya"),
            new Range(0x050000, 0x050FFF, "Liberia"),
            new Range(0x054000, 0x054FFF, "Madagascar"),
            new Range(0x058000, 0x058FFF, "Malawi"),
            new Range(0x05A000, 0x05A3FF, "Maldives"),
            new Range(0x05C000, 0x05CFFF, "Mali"),
            new Range(0x05E000, 0x05E3FF, "Mauritania"),
            new Range(0x060000, 0x0603FF, "Mauritius"),
            new Range(0x062000, 0x062FFF, "Niger"),
            new Range(0x064000, 0x064FFF, "Nigeria"),
            new Range(0x068000, 0x068FFF, "Uganda"),
            new Range(0x06A000, 0x06A3FF, "Qatar"),
            new Range(0x06C000, 0x06CFFF, "Central African Rep"),
            new Range(0x06E000, 0x06EFFF, "Rwanda"),
            new Range(0x070000, 0x070FFF, "Senegal"),
            new Range(0x074000, 0x0743FF, "Seychelles"),
            new Range(0x076000, 0x0763FF, "Sierra Leone"),
            new Range(0x078000, 0x078FFF, "Somalia"),
            new Range(0x07A000, 0x07A3FF, "Swaziland"),
            new Range(0x07C000, 0x07CFFF, "Sudan"),
            new Range(0x080000, 0x080FFF, "Tanzania"),
            new Range(0x084000, 0x084FFF, "Chad"),
            new Range(0x088000, 0x088FFF, "Togo"),
            new Range(0x08A000, 0x08AFFF, "Zambia"),
            new Range(0x08C000, 0x08CFFF, "DR Congo"),
            new Range(0x090000, 0x090FFF, "Angola"),
            new Range(0x094000, 0x0943FF, "Benin"),
            new Range(0x096000, 0x0963FF, "Cape Verde"),
            new Range(0x098000, 0x0983FF, "Gabon"),
            new Range(0x09A000, 0x09AFFF, "Gambia"),
            new Range(0x09C000, 0x09CFFF, "Burkina Faso"),
            new Range(0x09E000, 0x09E3FF, "Sao Tome"),

            // Americas (partial)
            new Range(0x0A0000, 0x0A7FFF, "Algeria"),
            new Range(0x0A8000, 0x0AFFFF, "Bahamas"),   // (misalloc guard; see notes)
            new Range(0x0B0000, 0x0B0FFF, "Barbados"),
            new Range(0x0B2000, 0x0B2FFF, "Belize"),
            new Range(0x0B4000, 0x0B4FFF, "Colombia"),
            new Range(0x0B6000, 0x0B6FFF, "Costa Rica"),
            new Range(0x0B8000, 0x0B8FFF, "Cuba"),
            new Range(0x0BA000, 0x0BAFFF, "El Salvador"),
            new Range(0x0BC000, 0x0BCFFF, "Guatemala"),
            new Range(0x0BE000, 0x0BEFFF, "Guyana"),
            new Range(0x0C0000, 0x0C0FFF, "Haiti"),
            new Range(0x0C2000, 0x0C2FFF, "Honduras"),
            new Range(0x0C4000, 0x0C4FFF, "Saint Vincent"),
            new Range(0x0C6000, 0x0C6FFF, "Jamaica"),
            new Range(0x0C8000, 0x0C8FFF, "Nicaragua"),
            new Range(0x0CA000, 0x0CAFFF, "Panama"),
            new Range(0x0CC000, 0x0CCFFF, "Dominican Rep"),
            new Range(0x0D0000, 0x0D7FFF, "Chile"),

            // Europe -- the big blocks
            new Range(0x100000, 0x1FFFFF, "Russia"),
            new Range(0x201000, 0x2013FF, "Namibia"),
            new Range(0x202000, 0x2023FF, "Eritrea"),
            new Range(0x300000, 0x33FFFF, "Italy"),
            new Range(0x340000, 0x37FFFF, "Spain"),
            new Range(0x380000, 0x3BFFFF, "France"),
            new Range(0x3C0000, 0x3FFFFF, "Germany"),
            new Range(0x400000, 0x43FFFF, "United Kingdom"),
            new Range(0x440000, 0x447FFF, "Austria"),
            new Range(0x448000, 0x44FFFF, "Belgium"),
            new Range(0x450000, 0x457FFF, "Bulgaria"),
            new Range(0x458000, 0x45FFFF, "Denmark"),
            new Range(0x460000, 0x467FFF, "Finland"),
            new Range(0x468000, 0x46FFFF, "Greece"),
            new Range(0x470000, 0x477FFF, "Hungary"),
            new Range(0x478000, 0x47FFFF, "Norway"),
            new Range(0x480000, 0x487FFF, "Netherlands"),
            new Range(0x488000, 0x48FFFF, "Poland"),
            new Range(0x490000, 0x497FFF, "Portugal"),
            new Range(0x498000, 0x49FFFF, "Czech Republic"),
            new Range(0x4A0000, 0x4A7FFF, "Romania"),
            new Range(0x4A8000, 0x4AFFFF, "Sweden"),
            new Range(0x4B0000, 0x4B7FFF, "Switzerland"),
            new Range(0x4B8000, 0x4BFFFF, "Turkey"),
            new Range(0x4C0000, 0x4C7FFF, "Yugoslavia"),
            new Range(0x4C8000, 0x4C83FF, "Cyprus"),
            new Range(0x4CA000, 0x4CAFFF, "Ireland"),
            new Range(0x4CC000, 0x4CCFFF, "Iceland"),
            new Range(0x4D0000, 0x4D03FF, "Luxembourg"),
            new Range(0x4D2000, 0x4D2FFF, "Malta"),
            new Range(0x4D4000, 0x4D43FF, "Monaco"),
            new Range(0x500000, 0x5003FF, "San Marino"),
            new Range(0x501000, 0x5013FF, "Albania"),
            new Range(0x501C00, 0x501FFF, "Croatia"),
            new Range(0x502C00, 0x502FFF, "Latvia"),
            new Range(0x503C00, 0x503FFF, "Lithuania"),
            new Range(0x504C00, 0x504FFF, "North Macedonia"),
            new Range(0x505C00, 0x505FFF, "Moldova"),
            new Range(0x506C00, 0x506FFF, "Slovakia"),
            new Range(0x507C00, 0x507FFF, "Slovenia"),
            new Range(0x508000, 0x50FFFF, "Ukraine"),
            new Range(0x510000, 0x5103FF, "Belarus"),
            new Range(0x511000, 0x5113FF, "Estonia"),
            new Range(0x512000, 0x5123FF, "Andorra"),
            new Range(0x513000, 0x5133FF, "Serbia"),
            new Range(0x514000, 0x5143FF, "Montenegro"),

            // Asia
            new Range(0x600000, 0x6003FF, "Armenia"),
            new Range(0x600800, 0x600BFF, "Azerbaijan"),
            new Range(0x601000, 0x6013FF, "Kyrgyzstan"),
            new Range(0x601800, 0x601BFF, "Turkmenistan"),
            new Range(0x680000, 0x6803FF, "Bhutan"),
            new Range(0x681000, 0x6813FF, "Micronesia"),
            new Range(0x682000, 0x6823FF, "Mongolia"),
            new Range(0x683000, 0x6833FF, "Kazakhstan"),
            new Range(0x684000, 0x6843FF, "Palau"),
            new Range(0x700000, 0x700FFF, "Afghanistan"),
            new Range(0x702000, 0x702FFF, "Bangladesh"),
            new Range(0x704000, 0x704FFF, "Myanmar"),
            new Range(0x706000, 0x706FFF, "Kuwait"),
            new Range(0x708000, 0x708FFF, "Laos"),
            new Range(0x70A000, 0x70AFFF, "Nepal"),
            new Range(0x70C000, 0x70C3FF, "Oman"),
            new Range(0x70E000, 0x70EFFF, "Cambodia"),
            new Range(0x710000, 0x717FFF, "Saudi Arabia"),
            new Range(0x718000, 0x71FFFF, "South Korea"),
            new Range(0x720000, 0x727FFF, "North Korea"),
            new Range(0x728000, 0x72FFFF, "Iraq"),
            new Range(0x730000, 0x737FFF, "Iran"),
            new Range(0x738000, 0x73FFFF, "Israel"),
            new Range(0x740000, 0x747FFF, "Jordan"),
            new Range(0x748000, 0x74FFFF, "Lebanon"),
            new Range(0x750000, 0x757FFF, "Malaysia"),
            new Range(0x758000, 0x75FFFF, "Philippines"),
            new Range(0x760000, 0x767FFF, "Pakistan"),
            new Range(0x768000, 0x76FFFF, "Singapore"),
            new Range(0x770000, 0x777FFF, "Sri Lanka"),
            new Range(0x778000, 0x77FFFF, "Syria"),
            new Range(0x780000, 0x7BFFFF, "China"),
            new Range(0x7C0000, 0x7FFFFF, "Australia"),
            new Range(0x800000, 0x83FFFF, "India"),
            new Range(0x840000, 0x87FFFF, "Japan"),
            new Range(0x880000, 0x887FFF, "Thailand"),
            new Range(0x888000, 0x88FFFF, "Viet Nam"),
            new Range(0x890000, 0x890FFF, "Yemen"),
            new Range(0x894000, 0x894FFF, "Bahrain"),
            new Range(0x895000, 0x8953FF, "Brunei"),
            new Range(0x896000, 0x896FFF, "UAE"),
            new Range(0x897000, 0x8973FF, "Solomon Islands"),
            new Range(0x898000, 0x898FFF, "Papua New Guinea"),
            new Range(0x899000, 0x8993FF, "Taiwan"),  // (used in practice; not officially assigned)
            new Range(0x8A0000, 0x8A7FFF, "Indonesia"),

            // Oceania + North America
            new Range(0x900000, 0x9003FF, "Marshall Islands"),
            new Range(0x901000, 0x9013FF, "Cook Islands"),
            new Range(0x902000, 0x9023FF, "Samoa"),
            new Range(0xA00000, 0xAFFFFF, "United States"),
            new Range(0xC00000, 0xC3FFFF, "Canada"),
            new Range(0xC80000, 0xC87FFF, "New Zealand"),
            new Range(0xC88000, 0xC88FFF, "Fiji"),
            new Range(0xC8A000, 0xC8A3FF, "Nauru"),
            new Range(0xC8C000, 0xC8C3FF, "Saint Lucia"),
            new Range(0xC8D000, 0xC8D3FF, "Tonga"),
            new Range(0xC8E000, 0xC8E3FF, "Kiribati"),
            new Range(0xC90000, 0xC903FF, "Vanuatu"),

            // South America
            new Range(0xE00000, 0xE3FFFF, "Argentina"),
            new Range(0xE40000, 0xE7FFFF, "Brazil"),
            new Range(0xE80000, 0xE80FFF, "Chile"),        // supplementary
            new Range(0xE84000, 0xE84FFF, "Ecuador"),
            new Range(0xE88000, 0xE88FFF, "Paraguay"),
            new Range(0xE8C000, 0xE8CFFF, "Peru"),
            new Range(0xE90000, 0xE90FFF, "Suriname"),
            new Range(0xE94000, 0xE94FFF, "Uruguay"),
            new Range(0xE98000, 0xE98FFF, "Bolivia"),
            new Range(0xE9C000, 0xE9CFFF, "Venezuela"),

            // Special / reserved
            new Range(0xF00000, 0xF07FFF, "ICAO temp"),
            new Range(0xF09000, 0xF093FF, "ICAO special"),
    };

    /**
     * @param icaoHex ICAO 24-bit address as a hex string. Case-insensitive,
     *                leading/trailing whitespace tolerated. Optional
     *                {@code "0x"} prefix accepted. Must decode to exactly
     *                24 bits (0x000000..0xFFFFFF).
     * @return country of registration, or {@code null} when the code is
     *         syntactically invalid, is not in any known allocation, or
     *         is the reserved all-zeros / all-ones address.
     */
    public static String countryFor(String icaoHex) {
        if (icaoHex == null) return null;
        String s = icaoHex.trim();
        if (s.regionMatches(true, 0, "0x", 0, 2)) s = s.substring(2);
        if (s.isEmpty() || s.length() > 6) return null;
        int code;
        try {
            code = Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return null;
        }
        if (code <= 0 || code >= 0xFFFFFF) return null;
        // Linear scan -- 180-ish entries, called at UI render cadence
        // (~500 ms) per row; a HashMap keyed on truncated prefixes would
        // be faster but not measurably so and much harder to audit.
        for (Range r : RANGES) {
            if (code >= r.startHex() && code <= r.endHex()) return r.country();
        }
        return null;
    }
}
