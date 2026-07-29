package com.adsb.ui.model;

import com.adsb.core.PayloadFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory list of {@link Connector} rows, backed by a flat
 * properties file. Not a database \u2014 the whole set is rewritten on
 * every {@link #save()}. That's fine for the expected list size
 * (single digits typically, tens at worst).
 *
 * <p><b>File shape</b> (one connector per {@code id}):
 * <pre>{@code
 * connector.<id>.name    = Ops COP UDP
 * connector.<id>.type    = UDP_UNICAST
 * connector.<id>.target  = 10.1.1.1:6969
 * connector.<id>.payload = COT
 * connector.<id>.enabled = true
 * }</pre>
 *
 * <p>The store is thread-safe: the in-memory list is a
 * {@link CopyOnWriteArrayList}, and add/remove/update publish to
 * registered listeners synchronously so the UI + the running receiver
 * both see the same view. Listener exceptions are caught and logged
 * to stderr so one bad sink doesn't break the others.
 *
 * <p>Persistence errors on save are surfaced by throwing
 * {@link IOException}; in-memory state is not rolled back if the write
 * fails (matches user expectation: "the change stuck in the UI, but
 * the file couldn't be written").
 */
public final class ConnectorStore {

    /** Property-key prefix; makes room for other config in the same file. */
    static final String PREFIX = "connector.";

    private final Path file;
    private final List<Connector> connectors = new CopyOnWriteArrayList<>();
    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();

    /**
     * @param file properties file to read/write; will be created on first
     *             {@link #save()} if it doesn't exist. Parent dir must
     *             already exist (or be creatable when save() runs).
     */
    public ConnectorStore(Path file) {
        this.file = file;
    }

    // -- lifecycle -----------------------------------------------------

    /** Load from {@link #file} if it exists; no-op if not. Publishes REPLACED. */
    public void load() throws IOException {
        connectors.clear();
        if (!Files.exists(file)) {
            fire(new Event(Event.Kind.REPLACED, null));
            return;
        }
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(file)) {
            p.load(in);
        }
        // Group properties by id.
        Map<String, Map<String, String>> byId = new LinkedHashMap<>();
        for (String key : new TreeSet<>(p.stringPropertyNames())) {
            if (!key.startsWith(PREFIX)) continue;
            String rest = key.substring(PREFIX.length());
            int dot = rest.indexOf('.');
            if (dot < 1 || dot == rest.length() - 1) continue;
            String id    = rest.substring(0, dot);
            String field = rest.substring(dot + 1);
            byId.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(field, p.getProperty(key));
        }
        for (var e : byId.entrySet()) {
            Connector c = fromMap(e.getKey(), e.getValue());
            if (c != null) connectors.add(c);
        }
        fire(new Event(Event.Kind.REPLACED, null));
    }

    /** Rewrite the properties file atomically (tmp + rename) with the current in-memory set. */
    public synchronized void save() throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Properties p = new Properties();
        for (Connector c : connectors) {
            String base = PREFIX + c.id() + ".";
            p.setProperty(base + "name",    c.name());
            p.setProperty(base + "type",    c.type().name());
            p.setProperty(base + "target",  c.target() == null ? "" : c.target());
            p.setProperty(base + "payload", c.payload().name());
            // zenohMode is meaningful only for ZENOH connectors but is
            // persisted on every record for schema simplicity. Backwards
            // compat: files written before this field existed load with
            // ZenohMode.PER_AIRCRAFT (matches the pre-mode shipping
            // default; see ZenohMode.parseOrDefault javadoc).
            p.setProperty(base + "zenohMode", c.zenohMode().name());
            p.setProperty(base + "enabled", Boolean.toString(c.enabled()));
            // New (2026-07-29) Zenoh-specific fields. Only written for
            // ZENOH connectors -- keeps the properties file tidy for
            // UDP/TCP rows. Null-safe: absent fields load as null and
            // the record ctor coerces to sensible defaults.
            if (c.type() == Connector.Type.ZENOH) {
                if (c.zenohTransport() != null)
                    p.setProperty(base + "zenohTransport", c.zenohTransport().name());
                if (c.zenohEndpoint() != null)
                    p.setProperty(base + "zenohEndpoint", c.zenohEndpoint());
                if (c.zenohOrg() != null)
                    p.setProperty(base + "zenohOrg", c.zenohOrg());
                if (c.zenohKeyExpr() != null)
                    p.setProperty(base + "zenohKeyExpr", c.zenohKeyExpr());
                if (c.zenohClientCertPath() != null)
                    p.setProperty(base + "zenohClientCertPath", c.zenohClientCertPath());
                if (c.zenohClientKeyPath() != null)
                    p.setProperty(base + "zenohClientKeyPath", c.zenohClientKeyPath());
                if (c.zenohRootCaPath() != null)
                    p.setProperty(base + "zenohRootCaPath", c.zenohRootCaPath());
                p.setProperty(base + "zenohVerifyHostname",
                        Boolean.toString(c.zenohVerifyHostname()));
            }
        }
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try (var out = Files.newBufferedWriter(tmp)) {
            p.store(out, "ADS-B receiver connectors \u2014 do not hand-edit while the UI is running");
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    // -- CRUD ----------------------------------------------------------

    /** Add a new connector and publish {@link Event.Kind#ADDED}. */
    public void add(Connector c) {
        connectors.add(c);
        fire(new Event(Event.Kind.ADDED, c));
    }

    /**
     * Replace an existing connector by id. Publishes {@link Event.Kind#UPDATED}
     * with the new value on success, no-op if id is unknown.
     */
    public void update(Connector c) {
        for (int i = 0, n = connectors.size(); i < n; i++) {
            if (connectors.get(i).id().equals(c.id())) {
                connectors.set(i, c);
                fire(new Event(Event.Kind.UPDATED, c));
                return;
            }
        }
    }

    /** Remove by id. Publishes {@link Event.Kind#REMOVED} with the removed value. */
    public void remove(String id) {
        for (int i = 0, n = connectors.size(); i < n; i++) {
            Connector c = connectors.get(i);
            if (c.id().equals(id)) {
                connectors.remove(i);
                fire(new Event(Event.Kind.REMOVED, c));
                return;
            }
        }
    }

    /** @return immutable snapshot of the current list, in insertion order. */
    public List<Connector> list() {
        return Collections.unmodifiableList(new ArrayList<>(connectors));
    }

    /** @return the connector with the given id, or null. */
    public Connector get(String id) {
        for (Connector c : connectors) if (c.id().equals(id)) return c;
        return null;
    }

    /** @return the file this store persists to. */
    public Path file() { return file; }

    // -- listeners -----------------------------------------------------

    public void addListener(Consumer<Event> l)    { listeners.add(l); }
    public void removeListener(Consumer<Event> l) { listeners.remove(l); }

    private void fire(Event e) {
        for (var l : listeners) {
            try { l.accept(e); }
            catch (RuntimeException ex) {
                System.err.println("[connector-store] listener error: " + ex);
            }
        }
    }

    /** Change event pushed to listeners. */
    public record Event(Kind kind, Connector value) {
        public enum Kind { ADDED, UPDATED, REMOVED, REPLACED }
    }

    // -- parsing helpers ----------------------------------------------

    static Connector fromMap(String id, Map<String, String> m) {
        String name    = m.get("name");
        String typeStr = m.get("type");
        String target  = m.get("target");
        String payStr  = m.get("payload");
        String modeStr = m.get("zenohMode");   // may be absent on pre-mode files
        String enabled = m.get("enabled");
        if (name == null || typeStr == null || payStr == null) return null;

        Connector.Type type;
        try { type = Connector.Type.valueOf(typeStr); }
        catch (IllegalArgumentException e) {
            System.err.println("[connector-store] skipping unknown type id=" + id + ": " + typeStr);
            return null;
        }

        // Marty 2026-07-29 14:01 UTC: "Nuke and fresh start (keep the
        // UDP producers for CoT though)". The pre-refactor Zenoh rows
        // used a semicolon-joined 'endpoint;keyPrefix' shoved into
        // 'target' -- that shape can't be safely round-tripped to the
        // new rich schema (no transport, no org, no TLS material). So
        // we drop pre-refactor Zenoh rows outright and let the
        // operator re-create them via the new dialog. Non-Zenoh rows
        // (UDP unicast/multicast, TCP server) are preserved verbatim.
        boolean isNewSchemaZenoh = (type == Connector.Type.ZENOH)
                && m.containsKey("zenohEndpoint");
        if (type == Connector.Type.ZENOH && !isNewSchemaZenoh) {
            System.err.println("[connector-store] dropping legacy Zenoh row id="
                    + id + " (pre-refactor shape; re-create via the new dialog)");
            return null;
        }

        // target is unused for ZENOH under the new schema; coerce to
        // empty string so the record ctor doesn't NPE. For other types
        // target is still required -- reject the row if missing.
        if (type != Connector.Type.ZENOH && target == null) return null;
        if (target == null) target = "";

        PayloadFormat pay;
        try { pay = PayloadFormat.valueOf(payStr); }
        catch (IllegalArgumentException e) {
            System.err.println("[connector-store] skipping malformed payload id=" + id + ": " + e);
            return null;
        }

        // Zenoh-only fields; null on non-Zenoh rows.
        ZenohTransport transport = (type == Connector.Type.ZENOH)
                ? ZenohTransport.parseOrDefault(m.get("zenohTransport"), ZenohTransport.TCP)
                : null;
        String endpoint       = m.get("zenohEndpoint");
        String org            = m.get("zenohOrg");
        String keyExpr        = m.get("zenohKeyExpr");
        String clientCertPath = m.get("zenohClientCertPath");
        String clientKeyPath  = m.get("zenohClientKeyPath");
        String rootCaPath     = m.get("zenohRootCaPath");
        boolean verifyHost    = Boolean.parseBoolean(
                m.getOrDefault("zenohVerifyHostname", "false"));

        try {
            return new Connector(id, name, type, target, pay,
                    ZenohMode.parseOrDefault(modeStr),   // null/unknown -> PER_AIRCRAFT
                    Boolean.parseBoolean(enabled == null ? "false" : enabled),
                    transport, endpoint, org, keyExpr,
                    clientCertPath, clientKeyPath, rootCaPath, verifyHost);
        } catch (IllegalArgumentException e) {
            System.err.println("[connector-store] skipping malformed connector id=" + id + ": " + e);
            return null;
        }
    }
}
