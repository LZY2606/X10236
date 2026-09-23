package com.neurospike.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.neurospike.core.Json;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite persistence. Immutable materials, adjudication events, evidence
 * events, computed results and diagnostics live in separate tables. A
 * write-ahead batch log makes half-written imports recoverable: batches that
 * never reached COMMITTED are rolled back on open.
 */
public final class Database implements AutoCloseable {
    private final Connection conn;

    private Database(Connection conn) {
        this.conn = conn;
    }

    public static Database open(Path path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
            Database db = new Database(c);
            db.init();
            db.recoverHalfWrittenBatches();
            return db;
        } catch (Exception e) {
            throw new IllegalStateException("cannot open database " + path, e);
        }
    }

    public static Database inMemory() {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
            Database db = new Database(c);
            db.init();
            return db;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Connection conn() {
        return conn;
    }

    private void init() throws Exception {
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT NOT NULL)");
            // Immutable materials layer: raw waveform refs, probe geometry,
            // detection thresholds, feature versions, cluster labels.
            s.execute("""
                    CREATE TABLE IF NOT EXISTS materials(
                      id TEXT NOT NULL, kind TEXT NOT NULL, version INTEGER NOT NULL,
                      raw_json TEXT NOT NULL, digest TEXT NOT NULL, seq INTEGER NOT NULL,
                      PRIMARY KEY(id, version))""");
            // Detected spike events (immutable detection output).
            s.execute("""
                    CREATE TABLE IF NOT EXISTS spikes(
                      id TEXT PRIMARY KEY, cluster_key TEXT NOT NULL, channel INTEGER NOT NULL,
                      time_ms REAL, amp_uv REAL, waveform_ref TEXT,
                      raw_json TEXT NOT NULL, digest TEXT NOT NULL, seq INTEGER NOT NULL)""");
            // Mutable decisions layer: adjudication events, kept separate from materials.
            s.execute("""
                    CREATE TABLE IF NOT EXISTS adjudications(
                      id TEXT PRIMARY KEY, seq INTEGER NOT NULL, branch TEXT NOT NULL,
                      kind TEXT NOT NULL, subjects_json TEXT NOT NULL, payload_json TEXT NOT NULL,
                      rationale TEXT, status TEXT NOT NULL, supersedes TEXT)""");
            // Machine-readable evidence events.
            s.execute("""
                    CREATE TABLE IF NOT EXISTS evidence(
                      id TEXT PRIMARY KEY, seq INTEGER NOT NULL, kind TEXT NOT NULL,
                      subjects_json TEXT NOT NULL, payload_json TEXT NOT NULL)""");
            // Derived results with input digest, parameter version and parents.
            s.execute("""
                    CREATE TABLE IF NOT EXISTS computed(
                      id TEXT PRIMARY KEY, kind TEXT NOT NULL, cluster_key TEXT,
                      window_start_ms REAL, window_end_ms REAL,
                      input_digest TEXT NOT NULL, param_version TEXT NOT NULL,
                      parents_json TEXT NOT NULL, payload_json TEXT NOT NULL,
                      status TEXT NOT NULL, invalidation_reason TEXT,
                      invalidated_seq INTEGER, seq INTEGER NOT NULL)""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS diagnostics(
                      seq INTEGER PRIMARY KEY AUTOINCREMENT, stage TEXT, position TEXT,
                      severity TEXT, message TEXT)""");
            // Batch write log for half-write recovery.
            s.execute("""
                    CREATE TABLE IF NOT EXISTS write_log(
                      batch_id TEXT PRIMARY KEY, state TEXT NOT NULL, applied_json TEXT NOT NULL)""");
        }
    }

    public long nextSeq() {
        try {
            long v;
            try (PreparedStatement ps = conn.prepareStatement("SELECT v FROM kv WHERE k='seq'")) {
                ResultSet rs = ps.executeQuery();
                v = rs.next() ? Long.parseLong(rs.getString(1)) : 0L;
            }
            v++;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO kv(k,v) VALUES('seq',?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
                ps.setString(1, Long.toString(v));
                ps.executeUpdate();
            }
            return v;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void setSeqAtLeast(long v) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO kv(k,v) VALUES('seq',?) ON CONFLICT(k) DO UPDATE SET v=MAX(CAST(v AS INTEGER), excluded.v)")) {
            ps.setString(1, Long.toString(v));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String kvGet(String k) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT v FROM kv WHERE k=?")) {
            ps.setString(1, k);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void kvSet(String k, String v) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO kv(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
            ps.setString(1, k);
            ps.setString(2, v);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- half-write recovery -------------------------------------------------

    public void beginBatch(String batchId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO write_log(batch_id,state,applied_json) VALUES(?,'BEGIN','[]')")) {
            ps.setString(1, batchId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void recordApplied(String batchId, String table, String id) {
        try {
            String applied;
            try (PreparedStatement ps = conn.prepareStatement("SELECT applied_json FROM write_log WHERE batch_id=?")) {
                ps.setString(1, batchId);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) throw new IllegalStateException("no such batch " + batchId);
                applied = rs.getString(1);
            }
            var arr = (com.fasterxml.jackson.databind.node.ArrayNode) Json.read(applied);
            var entry = Json.M.createObjectNode();
            entry.put("table", table);
            entry.put("id", id);
            arr.add(entry);
            try (PreparedStatement ps = conn.prepareStatement("UPDATE write_log SET applied_json=? WHERE batch_id=?")) {
                ps.setString(1, Json.canonical(arr));
                ps.setString(2, batchId);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void commitBatch(String batchId) {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE write_log SET state='COMMITTED' WHERE batch_id=?")) {
            ps.setString(1, batchId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Rolls back every batch that never reached COMMITTED. */
    public List<String> recoverHalfWrittenBatches() {
        List<String> recovered = new ArrayList<>();
        try {
            List<String[]> open = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT batch_id, applied_json FROM write_log WHERE state='BEGIN'")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) open.add(new String[]{rs.getString(1), rs.getString(2)});
            }
            for (String[] b : open) {
                JsonNode applied = Json.read(b[1]);
                for (JsonNode e : applied) {
                    String table = e.get("table").asText();
                    if (!List.of("materials", "spikes", "adjudications", "evidence", "computed").contains(table))
                        continue;
                    try (PreparedStatement del = conn.prepareStatement("DELETE FROM " + table + " WHERE id=?")) {
                        del.setString(1, e.get("id").asText());
                        del.executeUpdate();
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE write_log SET state='ABORTED' WHERE batch_id=?")) {
                    ps.setString(1, b[0]);
                    ps.executeUpdate();
                }
                recovered.add(b[0]);
            }
            return recovered;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
