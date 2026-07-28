package com.adsb.ui.model;

import com.adsb.core.PayloadFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConnectorTest {

    @Test
    void newInstance_generates_unique_ids() {
        Connector a = Connector.newInstance("A", Connector.Type.UDP_UNICAST,
                "1:1", PayloadFormat.JSON, true);
        Connector b = Connector.newInstance("A", Connector.Type.UDP_UNICAST,
                "1:1", PayloadFormat.JSON, true);
        assertNotEquals(a.id(), b.id());
    }

    @Test
    void withEnabled_produces_a_new_record_and_leaves_id_stable() {
        Connector a = Connector.newInstance("A", Connector.Type.UDP_UNICAST,
                "1:1", PayloadFormat.JSON, true);
        Connector b = a.withEnabled(false);
        assertNotSame(a, b);
        assertEquals(a.id(), b.id());
        assertTrue(a.enabled());
        assertFalse(b.enabled());
    }

    @Test
    void every_type_is_implemented_and_has_a_label() {
        // Post-#4 (Zenoh wiring): every dropdown type is wire-implemented.
        // The isImplemented() predicate is retained so a future scaffolded-
        // but-unwired type can rejoin the dropdown grayed-out without
        // another round of surgery, but today all four return true.
        for (Connector.Type t : Connector.Type.values()) {
            assertTrue(t.isImplemented(),
                    "type " + t + " should be implemented after #4 lands");
            assertNotNull(t.label(),
                    "type " + t + " must have a display label");
            assertFalse(t.label().isBlank(),
                    "type " + t + " label must not be blank");
        }
        // Zenoh label should not still advertise "coming soon" -- the
        // dropdown gates ATTACH on isImplemented, but users read the label.
        assertFalse(Connector.Type.ZENOH.label().toLowerCase().contains("coming"),
                "post-#4 the Zenoh label should be plain 'Zenoh', not 'coming soon'");
    }

    @Test
    void required_fields_rejected_when_null() {
        // Canonical ctor arg order (post-ZenohMode): id, name, type, target,
        // payload, zenohMode, enabled. Every ref field except zenohMode
        // must throw NPE when null; zenohMode is coerced to PER_AIRCRAFT
        // by the compact ctor (backward-compat with pre-mode saves).
        assertThrows(NullPointerException.class, () ->
                new Connector(null, "n", Connector.Type.UDP_UNICAST, "1:1",
                        PayloadFormat.JSON, ZenohMode.PER_AIRCRAFT, true));
        assertThrows(NullPointerException.class, () ->
                new Connector("id", null, Connector.Type.UDP_UNICAST, "1:1",
                        PayloadFormat.JSON, ZenohMode.PER_AIRCRAFT, true));
        assertThrows(NullPointerException.class, () ->
                new Connector("id", "n", null, "1:1",
                        PayloadFormat.JSON, ZenohMode.PER_AIRCRAFT, true));
        assertThrows(NullPointerException.class, () ->
                new Connector("id", "n", Connector.Type.UDP_UNICAST, null,
                        PayloadFormat.JSON, ZenohMode.PER_AIRCRAFT, true));
        assertThrows(NullPointerException.class, () ->
                new Connector("id", "n", Connector.Type.UDP_UNICAST, "1:1",
                        null, ZenohMode.PER_AIRCRAFT, true));
    }

    @Test
    void null_zenoh_mode_is_coerced_to_per_aircraft_not_thrown() {
        // Backward-compat contract: pre-#4-followup saves and legacy call
        // sites don't know about ZenohMode. The compact ctor MUST coerce
        // a null zenohMode to PER_AIRCRAFT so nothing NPE's downstream.
        // If someone tightens this to throw NPE later, the fromMap()
        // loader (which passes ZenohMode.parseOrDefault) still works,
        // but every direct legacy caller will start throwing at attach
        // time. Pin the coercion.
        Connector c = new Connector("id", "n", Connector.Type.ZENOH,
                "tcp/localhost:7447;adsb/cot", PayloadFormat.COT,
                (ZenohMode) null, true);
        assertEquals(ZenohMode.PER_AIRCRAFT, c.zenohMode(),
                "null zenohMode must coerce to PER_AIRCRAFT for backward compat");
    }

    @Test
    void with_zenoh_mode_produces_a_new_record_with_stable_id() {
        Connector a = Connector.newInstance("A", Connector.Type.ZENOH,
                "tcp/localhost:7447;adsb", PayloadFormat.COT,
                ZenohMode.PER_AIRCRAFT, true);
        Connector b = a.withZenohMode(ZenohMode.STREAM);
        assertNotSame(a, b);
        assertEquals(a.id(), b.id(),
                "withZenohMode must keep the same id so SinkRegistry handles stay valid");
        assertEquals(ZenohMode.PER_AIRCRAFT, a.zenohMode());
        assertEquals(ZenohMode.STREAM, b.zenohMode());
        // Other fields untouched.
        assertEquals(a.name(), b.name());
        assertEquals(a.type(), b.type());
        assertEquals(a.target(), b.target());
        assertEquals(a.payload(), b.payload());
        assertEquals(a.enabled(), b.enabled());
    }

    @Test
    void legacy_5_arg_newInstance_defaults_to_per_aircraft() {
        // The 5-arg newInstance overload (no ZenohMode) was the API
        // before the mode field landed. It must continue to work and
        // default to PER_AIRCRAFT so pre-mode call sites keep the
        // shipping semantics of commit 8e4aca2.
        Connector c = Connector.newInstance("Legacy", Connector.Type.ZENOH,
                "tcp/localhost:7447;adsb", PayloadFormat.COT, true);
        assertEquals(ZenohMode.PER_AIRCRAFT, c.zenohMode(),
                "5-arg newInstance must default to PER_AIRCRAFT");
    }
}
