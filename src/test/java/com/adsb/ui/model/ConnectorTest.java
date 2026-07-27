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
    void zenoh_is_present_but_flagged_not_implemented() {
        assertTrue(Connector.Type.UDP_UNICAST.isImplemented());
        assertTrue(Connector.Type.UDP_MULTICAST.isImplemented());
        assertTrue(Connector.Type.TCP_SERVER.isImplemented());
        assertFalse(Connector.Type.ZENOH.isImplemented());
        assertTrue(Connector.Type.ZENOH.label().toLowerCase().contains("zenoh"));
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
