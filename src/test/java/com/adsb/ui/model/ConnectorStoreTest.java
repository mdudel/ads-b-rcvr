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
