package spikesort.db;

import spikesort.util.Hashes;
import spikesort.util.Json;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Typed-ish row access over the SQLite schema. All methods are synchronized. */
public final class Store {
    private static final Set<String> TABLES = Set.of(
            "meta", "waveform_refs", "probe_geometry", "detection_thresholds",
            "feature_versions", "detection_events", "cluster_labels",
            "adjudication_events", "evidence_events", "derived_records",
            "diagnostics", "write_intents");

    private final Database db;

    public Store(Database db) {
        this.db = db;
    }

    private void checkTable(String table) {
        if (!TABLES.contains(table)) throw new IllegalArgumentException("unknown table " + table);
    }

    public synchronized void insert(String table, Map<String, Object> row) {
        checkTable(table);
        String cols = String.join(",", row.keySet());
        String marks = String.join(",", row.keySet().stream().map(k -> "?").toList());
        String sql = "INSERT OR IGNORE INTO " + table + " (" + cols + ") VALUES (" + marks + ")";
        try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
            int i = 1;
            for (Object v : row.values()) bind(ps, i++, v);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("insert into " + table + ": " + e.getMessage(), e);
        }
    }

    public synchronized int update(String sql, Object... args) {
        try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) bind(ps, i + 1, args[i]);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static void bind(PreparedStatement ps, int i, Object v) throws SQLException {
        if (v == null) ps.setObject(i, null);
        else if (v instanceof Integer n) ps.setInt(i, n);
        else if (v instanceof Long n) ps.setLong(i, n);
        else if (v instanceof Double n) ps.setDouble(i, n);
        else ps.setString(i, String.valueOf(v));
    }

    public synchronized List<Map<String, Object>> query(String sql, Object... args) {
        try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) bind(ps, i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= md.getColumnCount(); c++) {
                        row.put(md.getColumnLabel(c), rs.getObject(c));
                    }
                    out.add(row);
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    public synchronized List<Map<String, Object>> all(String table, String orderBy) {
        checkTable(table);
        return query("SELECT * FROM " + table + " ORDER BY " + orderBy);
    }

    public synchronized Map<String, Object> find(String table, String id) {
        checkTable(table);
        List<Map<String, Object>> rows = query("SELECT * FROM " + table + " WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public synchronized long count(String table) {
        checkTable(table);
        return (long) query("SELECT COUNT(*) c FROM " + table).get(0).get("c");
    }

    // ---- meta / global sequence ----

    public synchronized String meta(String key, String fallback) {
        List<Map<String, Object>> rows = query("SELECT v FROM meta WHERE k=?", key);
        return rows.isEmpty() ? fallback : (String) rows.get(0).get("v");
    }

    public synchronized void setMeta(String key, String value) {
        update("INSERT INTO meta(k,v) VALUES(?,?) "
                + "ON CONFLICT(k) DO UPDATE SET v=excluded.v", key, value);
    }

    /** Next value of the single global event sequence (adjudications, evidence, materials). */
    public synchronized long nextSeq() {
        long v = Long.parseLong(meta("seq", "0")) + 1;
        setMeta("seq", Long.toString(v));
        return v;
    }

    public synchronized long currentSeq() {
        return Long.parseLong(meta("seq", "0"));
    }

    // ---- transactions ----

    public synchronized <T> T tx(Supplier<T> body) {
        try {
            db.conn().setAutoCommit(false);
            try {
                T result = body.get();
                db.conn().commit();
                return result;
            } catch (RuntimeException e) {
                db.conn().rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        } finally {
            try {
                db.conn().setAutoCommit(true);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public synchronized void tx(Runnable body) {
        tx(() -> { body.run(); return null; });
    }

    // ---- diagnostics ----

    public synchronized void addDiagnostic(String stage, String location, String severity,
                                           String message, String rawValue) {
        String id = Hashes.contentId("diag",
                List.of(stage, location, severity, message, String.valueOf(rawValue)));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("stage", stage);
        row.put("location", location);
        row.put("severity", severity);
        row.put("message", message);
        row.put("raw_value", rawValue);
        insert("diagnostics", row);
    }

    // ---- write intents (half-write recovery) ----

    public synchronized String beginIntent(String kind, String detail) {
        String id = Hashes.contentId("intent",
                List.of(kind, detail, Instant.now().toString(), Math.random()));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("kind", kind);
        row.put("state", "begin");
        row.put("detail", detail);
        row.put("created_at", Instant.now().toString());
        insert("write_intents", row);
        return id;
    }

    public synchronized void finishIntent(String id, String state) {
        update("UPDATE write_intents SET state=? WHERE id=?", state, id);
    }

    public synchronized boolean isEmpty() {
        return count("waveform_refs") == 0 && count("adjudication_events") == 0;
    }

    public synchronized String exportDigest() {
        return Hashes.sha256(Json.canonical(Exporter.tablesSnapshot(this)));
    }
}
