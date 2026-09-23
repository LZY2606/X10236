package com.neurogsb.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite store. Layers are physically separated:
 * immutable materials (waveform refs, probe geometry, thresholds, feature
 * versions, cluster labels, recording spans) live in their own table;
 * mutable adjudication decisions live in events/adjudications; derived
 * results are append-only and carry input digest, parameter version and
 * parent dependencies.
 */
public final class Database implements AutoCloseable {
    private final Connection conn;

    public Database(String path) {
        try {
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement s = conn.createStatement()) {
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA journal_mode=WAL");
            }
            migrate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open database " + path, e);
        }
    }

    public static Database inMemory() {
        return new Database(":memory:");
    }

    private void migrate() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS materials(
                  material_id TEXT PRIMARY KEY,
                  kind TEXT NOT NULL,
                  content TEXT NOT NULL,
                  content_hash TEXT NOT NULL,
                  created_seq INTEGER NOT NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS units(
                  unit_uid TEXT PRIMARY KEY,
                  origin TEXT NOT NULL,
                  created_seq INTEGER NOT NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS unit_labels(
                  label_set_id TEXT NOT NULL REFERENCES materials(material_id),
                  unit_uid TEXT NOT NULL REFERENCES units(unit_uid),
                  cluster_no INTEGER NOT NULL,
                  PRIMARY KEY(label_set_id, unit_uid)
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS events(
                  event_id TEXT PRIMARY KEY,
                  seq INTEGER NOT NULL UNIQUE,
                  kind TEXT NOT NULL,
                  payload TEXT NOT NULL,
                  parents TEXT NOT NULL,
                  created_at TEXT NOT NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS derived(
                  derived_id TEXT PRIMARY KEY,
                  unit_uid TEXT NOT NULL REFERENCES units(unit_uid),
                  metric TEXT NOT NULL,
                  input_digest TEXT NOT NULL,
                  param_version TEXT NOT NULL,
                  parents TEXT NOT NULL,
                  valid_from_s REAL NOT NULL,
                  valid_to_s REAL NOT NULL,
                  status TEXT NOT NULL,
                  invalidation_reason TEXT,
                  value TEXT NOT NULL,
                  created_seq INTEGER NOT NULL,
                  invalidated_seq INTEGER
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS adjudications(
                  adj_id TEXT PRIMARY KEY,
                  target_unit_uid TEXT NOT NULL REFERENCES units(unit_uid),
                  round INTEGER NOT NULL,
                  decision TEXT NOT NULL,
                  rationale TEXT,
                  event_id TEXT NOT NULL REFERENCES events(event_id),
                  status TEXT NOT NULL,
                  created_seq INTEGER NOT NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS candidates(
                  adj_id TEXT NOT NULL REFERENCES adjudications(adj_id),
                  candidate TEXT NOT NULL,
                  PRIMARY KEY(adj_id, candidate)
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS evidence(
                  evidence_id TEXT PRIMARY KEY,
                  unit_uid TEXT NOT NULL REFERENCES units(unit_uid),
                  kind TEXT NOT NULL,
                  payload TEXT NOT NULL,
                  event_id TEXT NOT NULL REFERENCES events(event_id),
                  created_seq INTEGER NOT NULL
                )""");
        }
    }

    public Connection conn() {
        return conn;
    }

    public long nextSeq() {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(seq),0)+1 FROM events")) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void begin() {
        exec("BEGIN IMMEDIATE");
    }

    public void commit() {
        exec("COMMIT");
    }

    public void rollback() {
        exec("ROLLBACK");
    }

    private void exec(String sql) {
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public int update(String sql, Object... args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    public List<Map<String, Object>> query(String sql, Object... args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) {
                        row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    }
                    out.add(row);
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    public Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = query(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof Long l) ps.setLong(i + 1, l);
            else if (a instanceof Integer n) ps.setInt(i + 1, n);
            else if (a instanceof Double d) ps.setDouble(i + 1, d);
            else if (a == null) ps.setObject(i + 1, null);
            else ps.setString(i + 1, a.toString());
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
