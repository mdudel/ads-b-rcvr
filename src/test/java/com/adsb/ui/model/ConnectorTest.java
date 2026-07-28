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
        assertThrows(NullPointerException.class, () ->
                new Connector(null, "n", Connector.Type.UDP_UNICAST, "1:1", PayloadFormat.JSON, true));
        assertThrows(NullPointerException.class, () ->
                new Connector("id", null, Connector.Type.UDP_UNICAST, "1:1", PayloadFormat.JSON, true));
        assertThrows(NullPointerException.class, () ->
                new Connector("id", "n", null, "1:1", PayloadFormat.JSON, true));
        assertThrows(NullPointerException.class, () ->
                new Connector("id", "n", Connector.Type.UDP_UNICAST, null, PayloadFormat.JSON, true));
        assertThrows(NullPointerException.class, () ->
                new Connector("id", "n", Connector.Type.UDP_UNICAST, "1:1", null, true));
    }
}
