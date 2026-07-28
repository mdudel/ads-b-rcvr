package com.adsb.ui.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the {@code endpoint;key-prefix} target-string parser for
 * {@link Connector.Type#ZENOH}. Kept separate from
 * {@link ConnectorStoreTest} because these tests hit a package-private
 * helper and the split rules have real UX consequences: a malformed
 * target should surface at attach time with a message an operator can
 * act on, not silently pass through and then fail at first frame.
 */
class ConnectorAttacherTargetTest {

    @Test
    void well_formed_target_splits_cleanly() {
        String[] ek = ConnectorAttacher.splitEndpointAndKey(
                "tcp/localhost:7447;adsb/cot", "Zenoh target");
        assertArrayEquals(new String[]{"tcp/localhost:7447", "adsb/cot"}, ek);
    }

    @Test
    void whitespace_around_the_separator_is_trimmed() {
        String[] ek = ConnectorAttacher.splitEndpointAndKey(
                "  tcp/localhost:7447 ; adsb/cot  ", "Zenoh target");
        assertArrayEquals(new String[]{"tcp/localhost:7447", "adsb/cot"}, ek,
                "operators paste with stray whitespace; trim on both sides");
    }

    @Test
    void endpoint_with_ipv6_bracket_notation_survives_the_split() {
        // Pure-Java Zenoh facade accepts tcp/[::1]:7447 for IPv6 loopback --
        // Windows localhost gotcha (see PureJavaZenohPublisher javadoc).
        // The split only cares about the FIRST semicolon, so the IPv6
        // brackets are preserved intact.
        String[] ek = ConnectorAttacher.splitEndpointAndKey(
                "tcp/[::1]:7447;adsb", "Zenoh target");
        assertArrayEquals(new String[]{"tcp/[::1]:7447", "adsb"}, ek);
    }

    @Test
    void key_prefix_with_internal_slashes_is_preserved() {
        // adsb/cot/eu-west is a perfectly valid Zenoh key expression --
        // slashes are the hierarchy separator, exactly like the payload
        // split ZenohForwarder does at forward() time. Don't crush them.
        String[] ek = ConnectorAttacher.splitEndpointAndKey(
                "tcp/router.example:7447;adsb/cot/eu-west", "Zenoh target");
        assertEquals("adsb/cot/eu-west", ek[1]);
    }

    @Test
    void multiple_semicolons_split_on_the_FIRST_one() {
        // Zenoh keys can't contain semicolons anyway, but if some future
        // extension does (e.g. query params like adsb;debug=true), the
        // split rule must be deterministic: use the FIRST separator, so
        // the endpoint half is unambiguous.
        String[] ek = ConnectorAttacher.splitEndpointAndKey(
                "tcp/localhost:7447;adsb;extra", "Zenoh target");
        assertArrayEquals(new String[]{"tcp/localhost:7447", "adsb;extra"}, ek);
    }

    @Test
    void missing_separator_throws_with_a_useful_message() {
        // Common operator mistake: paste an endpoint alone and forget
        // the key prefix. The exception message must contain the label
        // + the expected format so the UI dialog can render it verbatim.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConnectorAttacher.splitEndpointAndKey(
                        "tcp/localhost:7447", "Zenoh target"));
        assertTrue(e.getMessage().contains("Zenoh target"),
                "message should include the label: " + e.getMessage());
        assertTrue(e.getMessage().contains("endpoint;key-prefix"),
                "message should show the expected format: " + e.getMessage());
    }

    @Test
    void separator_at_start_throws() {
        // ";key-prefix" -- empty endpoint. Reject.
        assertThrows(IllegalArgumentException.class,
                () -> ConnectorAttacher.splitEndpointAndKey(";adsb", "Zenoh target"));
    }

    @Test
    void separator_at_end_throws() {
        // "endpoint;" -- empty key prefix. Reject.
        assertThrows(IllegalArgumentException.class,
                () -> ConnectorAttacher.splitEndpointAndKey(
                        "tcp/localhost:7447;", "Zenoh target"));
    }

    @Test
    void blank_halves_throw_even_when_separator_present() {
        // "   ;   " both halves whitespace after trim.
        assertThrows(IllegalArgumentException.class,
                () -> ConnectorAttacher.splitEndpointAndKey("   ;   ", "Zenoh target"));
    }

    @Test
    void null_input_throws_with_label() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConnectorAttacher.splitEndpointAndKey(null, "Zenoh target"));
        assertTrue(e.getMessage().contains("Zenoh target"));
    }
}
