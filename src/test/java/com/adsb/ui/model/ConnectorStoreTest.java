package com.adsb.ui.model;

import com.adsb.core.PayloadFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConnectorStoreTest {

    @Test
    void save_and_load_round_trips_every_field(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("adsb-rcvr.properties");
        ConnectorStore a = new ConnectorStore(f);

        Connector c1 = Connector.newInstance("Ops COP", Connector.Type.UDP_UNICAST,
                "10.1.1.1:6969", PayloadFormat.COT, true);
        Connector c2 = Connector.newInstance("Log JSON", Connector.Type.UDP_MULTICAST,
                "239.2.3.1:30003", PayloadFormat.JSON, false);
        a.add(c1);
        a.add(c2);
        a.save();

        ConnectorStore b = new ConnectorStore(f);
        b.load();
        List<Connector> loaded = b.list();
        assertEquals(2, loaded.size(), "both connectors should reload");

        Connector reloaded1 = b.get(c1.id());
        assertNotNull(reloaded1);
        assertEquals("Ops COP",                 reloaded1.name());
        assertEquals(Connector.Type.UDP_UNICAST,reloaded1.type());
        assertEquals("10.1.1.1:6969",           reloaded1.target());
        assertEquals(PayloadFormat.COT,         reloaded1.payload());
        assertTrue  (                           reloaded1.enabled());

        Connector reloaded2 = b.get(c2.id());
        assertNotNull(reloaded2);
        assertEquals(Connector.Type.UDP_MULTICAST, reloaded2.type());
        assertFalse (reloaded2.enabled());
    }

    @Test
    void load_from_missing_file_is_a_noop(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("does-not-exist.properties");
        ConnectorStore s = new ConnectorStore(f);
        s.load();
        assertTrue(s.list().isEmpty());
    }

    @Test
    void listeners_fire_on_add_update_remove(@TempDir Path dir) {
        ConnectorStore s = new ConnectorStore(dir.resolve("x.properties"));
        List<ConnectorStore.Event.Kind> kinds = new ArrayList<>();
        s.addListener(e -> kinds.add(e.kind()));

        Connector c = Connector.newInstance("A", Connector.Type.UDP_UNICAST,
                "1.1.1.1:1", PayloadFormat.JSON, true);
        s.add(c);
        s.update(c.withEnabled(false));
        s.remove(c.id());

        assertEquals(List.of(
                ConnectorStore.Event.Kind.ADDED,
                ConnectorStore.Event.Kind.UPDATED,
                ConnectorStore.Event.Kind.REMOVED), kinds);
    }

    @Test
    void update_of_unknown_id_is_a_noop() {
        ConnectorStore s = new ConnectorStore(Path.of("/tmp/won't-be-written.properties"));
        Connector ghost = Connector.newInstance("Ghost", Connector.Type.UDP_UNICAST,
                "1:1", PayloadFormat.JSON, true);
        s.update(ghost);
        assertTrue(s.list().isEmpty());
    }

    @Test
    void listener_exception_does_not_break_the_chain() {
        ConnectorStore s = new ConnectorStore(Path.of("/tmp/x.properties"));
        AtomicInteger reached = new AtomicInteger();
        s.addListener(e -> { throw new RuntimeException("boom"); });
        s.addListener(e -> reached.incrementAndGet());
        s.add(Connector.newInstance("X", Connector.Type.UDP_UNICAST,
                "1:1", PayloadFormat.JSON, true));
        assertEquals(1, reached.get());
    }

    @Test
    void zenoh_new_schema_round_trips_through_save_and_load(@TempDir Path dir) throws Exception {
        // Post-2026-07-29-refactor: Zenoh connectors use the rich-form
        // fields (transport / endpoint / org / keyExpr / TLS material)
        // instead of a semicolon-joined target. Every field must survive
        // save/load unchanged.
        Path f = dir.resolve("mode.properties");
        ConnectorStore a = new ConnectorStore(f);

        Connector stream = Connector.newZenoh("Zenoh Stream",
                ZenohTransport.TCP, "localhost:7447",
                "", "adsb",
                PayloadFormat.COT, ZenohMode.STREAM,
                null, null, null, false, true);
        Connector tls = Connector.newZenoh("Zenoh mTLS",
                ZenohTransport.TLS, "100.64.165.203:7447",
                "goatnet", "tracks/adsb",
                PayloadFormat.COT, ZenohMode.PER_AIRCRAFT,
                "/tmp/client.pem", "/tmp/client.key", "/tmp/ca.pem",
                false, true);
        a.add(stream);
        a.add(tls);
        a.save();

        ConnectorStore b = new ConnectorStore(f);
        b.load();

        Connector s = b.get(stream.id());
        assertNotNull(s, "stream connector must round-trip");
        assertEquals(ZenohTransport.TCP, s.zenohTransport());
        assertEquals("localhost:7447", s.zenohEndpoint());
        assertEquals("adsb", s.zenohKeyExpr());
        assertEquals(ZenohMode.STREAM, s.zenohMode());

        Connector t = b.get(tls.id());
        assertNotNull(t, "TLS connector must round-trip");
        assertEquals(ZenohTransport.TLS, t.zenohTransport());
        assertEquals("100.64.165.203:7447", t.zenohEndpoint());
        assertEquals("goatnet", t.zenohOrg());
        assertEquals("tracks/adsb", t.zenohKeyExpr());
        assertEquals("/tmp/client.pem", t.zenohClientCertPath());
        assertEquals("/tmp/client.key", t.zenohClientKeyPath());
        assertEquals("/tmp/ca.pem", t.zenohRootCaPath());
        assertFalse(t.zenohVerifyHostname());
        assertEquals(ZenohMode.PER_AIRCRAFT, t.zenohMode());
    }

    @Test
    void legacy_zenoh_rows_are_dropped_udp_rows_preserved(@TempDir Path dir) throws Exception {
        // Marty 2026-07-29 14:01 UTC: "Nuke and fresh start (keep the
        // UDP producers for CoT though)". A properties file with the
        // pre-refactor Zenoh shape (semicolon-joined 'target' string,
        // no zenohEndpoint key) is intentionally dropped. Non-Zenoh
        // rows (UDP unicast/multicast, TCP server) are preserved so
        // the operator's shipping CoT-over-UDP pipeline keeps working.
        Path f = dir.resolve("mixed-legacy.properties");
        java.nio.file.Files.writeString(f, String.join("\n",
                // Legacy Zenoh -- MUST be dropped
                "connector.legacy-zenoh.name=Old Zenoh",
                "connector.legacy-zenoh.type=ZENOH",
                "connector.legacy-zenoh.target=tcp/localhost:7447;adsb/cot",
                "connector.legacy-zenoh.payload=COT",
                "connector.legacy-zenoh.enabled=true",
                // UDP row -- MUST survive
                "connector.udp-cop.name=Ops COP",
                "connector.udp-cop.type=UDP_UNICAST",
                "connector.udp-cop.target=239.2.3.1:6969",
                "connector.udp-cop.payload=COT",
                "connector.udp-cop.enabled=true",
                ""));
        ConnectorStore s = new ConnectorStore(f);
        s.load();
        assertNull(s.get("legacy-zenoh"),
                "legacy Zenoh row must be dropped (Marty 2026-07-29: nuke & fresh start)");
        Connector udp = s.get("udp-cop");
        assertNotNull(udp, "non-Zenoh row must be preserved");
        assertEquals(Connector.Type.UDP_UNICAST, udp.type());
        assertEquals("239.2.3.1:6969", udp.target());
        assertEquals(PayloadFormat.COT, udp.payload());
    }

    @Test
    void new_schema_zenoh_row_with_zenohEndpoint_key_is_kept(@TempDir Path dir) throws Exception {
        // The dropping heuristic is 'ZENOH row without zenohEndpoint key'
        // -- rows written by the new schema always have that key so they
        // pass through. Pinning this so a future refactor doesn't
        // accidentally drop rows the operator just saved from the new
        // dialog.
        Path f = dir.resolve("new-schema.properties");
        java.nio.file.Files.writeString(f, String.join("\n",
                "connector.abc.name=New Zenoh",
                "connector.abc.type=ZENOH",
                "connector.abc.target=",
                "connector.abc.payload=COT",
                "connector.abc.zenohMode=PER_AIRCRAFT",
                "connector.abc.enabled=true",
                "connector.abc.zenohTransport=TCP",
                "connector.abc.zenohEndpoint=localhost:7447",
                "connector.abc.zenohKeyExpr=adsb/cot",
                ""));
        ConnectorStore s = new ConnectorStore(f);
        s.load();
        Connector c = s.get("abc");
        assertNotNull(c, "new-schema Zenoh row (has zenohEndpoint) must be kept");
        assertEquals(ZenohTransport.TCP, c.zenohTransport());
        assertEquals("localhost:7447", c.zenohEndpoint());
        assertEquals("adsb/cot", c.zenohKeyExpr());
    }

    @Test
    void malformed_property_lines_are_skipped_not_thrown(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("mixed.properties");
        java.nio.file.Files.writeString(f, String.join("\n",
                // good record
                "connector.abc.name=Good",
                "connector.abc.type=UDP_UNICAST",
                "connector.abc.target=1.1.1.1:1",
                "connector.abc.payload=JSON",
                "connector.abc.enabled=true",
                // bad type
                "connector.xyz.name=Bad",
                "connector.xyz.type=NOT_A_TYPE",
                "connector.xyz.target=2.2.2.2:2",
                "connector.xyz.payload=JSON",
                "connector.xyz.enabled=false",
                // incomplete
                "connector.def.name=Missing",
                ""));
        ConnectorStore s = new ConnectorStore(f);
        s.load();
        List<Connector> loaded = s.list();
        assertEquals(1, loaded.size(), "only the good record should load");
        assertEquals("Good", loaded.get(0).name());
    }
}
