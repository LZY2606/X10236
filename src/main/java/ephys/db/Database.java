package ephys.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite store. Immutable materials, stable unit identity, unstable cluster labels,
 * adjudication events, machine-readable evidence and computed results are kept in
 * separate tables so each layer can be replayed and audited independently.
 */
public final class Database implements AutoCloseable {
    private final Connection conn;

    public Database(String path) {
        try {
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA foreign_keys=ON");
            }
            migrate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open database " + path, e);
        }
    }

    public static Database inMemory() {
        return new Database(":memory:");
    }

    public Connection conn() {
        return conn;
    }

    private void migrate() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS materials (
                  id TEXT PRIMARY KEY,
                  kind TEXT NOT NULL,            -- waveform_ref|probe_geometry|detection_threshold|feature_version|spike_event
                  version INTEGER NOT NULL,
                  payload TEXT NOT NULL,         -- original representation preserved verbatim
                  digest TEXT NOT NULL,
                  imported_at TEXT NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS units (
                  id TEXT PRIMARY KEY,           -- stable identity, never a cluster number
                  origin_event TEXT,
                  status TEXT NOT NULL,          -- active|merged|split_parent|rejected
                  created_seq INTEGER NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS cluster_labels (
                  id TEXT PRIMARY KEY,
                  run_id TEXT NOT NULL,
                  cluster_no INTEGER NOT NULL,   -- unstable label, may change between runs
                  unit_id TEXT NOT NULL REFERENCES units(id),
                  window_start_s REAL NOT NULL,
                  window_end_s REAL NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS adjudication_events (
                  id TEXT PRIMARY KEY,
                  seq INTEGER NOT NULL UNIQUE,   -- deterministic replay order
                  ts TEXT NOT NULL,
                  type TEXT NOT NULL,            -- MERGE|SPLIT|ACCEPT|REJECT|PARAM_ADJUST|WINDOW_CHANGE|RESOLVE
                  actor TEXT,
                  payload TEXT NOT NULL,
                  parents TEXT NOT NULL,         -- JSON array of parent event/material ids
                  conflicts_with TEXT            -- kept, not overwritten: conflicting decisions coexist
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS evidence_events (
                  id TEXT PRIMARY KEY,
                  kind TEXT NOT NULL,            -- REFRACTORY_VIOLATION|AMPLITUDE_DRIFT|MULTI_CHANNEL_PROPAGATION
                  subjects TEXT NOT NULL,        -- JSON array of stable unit ids
                  basis TEXT NOT NULL,           -- machine-readable judgment basis with units/coordinate frames
                  created_seq INTEGER NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS computed_results (
                  id TEXT PRIMARY KEY,
                  kind TEXT NOT NULL,            -- clustering|acg|firing_rate|drift|feature_projection
                  subject TEXT,                  -- unit id or run id
                  input_digest TEXT NOT NULL,
                  param_version TEXT NOT NULL,
                  parents TEXT NOT NULL,         -- JSON array of dependency ids
                  status TEXT NOT NULL,          -- OK|BUDGET_EXHAUSTED|FORMAT_CORRUPT|SEMANTIC_UNSOLVABLE
                  payload TEXT,
                  diagnostics TEXT,
                  window_start_s REAL,
                  window_end_s REAL,
                  valid_from TEXT,
                  valid_to TEXT,
                  invalidated_by TEXT,
                  invalidation_reason TEXT
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS candidates (
                  id TEXT PRIMARY KEY,
                  decision_point TEXT NOT NULL,
                  options TEXT NOT NULL,         -- every legal candidate retained when not uniquely decidable
                  resolved_by TEXT
                )""");
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}
