package org.neurospike.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** SQLite 连接与 schema 初始化。 */
public final class Database {
    private final String url;

    public Database(String path) {
        this.url = "jdbc:sqlite:" + path;
    }

    public Connection connect() {
        try {
            Connection c = DriverManager.getConnection(url);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA journal_mode = WAL");
                st.execute("PRAGMA busy_timeout = 5000");
            }
            return c;
        } catch (SQLException e) {
            throw new RuntimeException("cannot open database: " + url, e);
        }
    }

    public void migrate() {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS meta (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS branch (
                    name TEXT PRIMARY KEY,
                    head_seq INTEGER NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS probe_channel (
                    probe_id TEXT NOT NULL,
                    local_index INTEGER NOT NULL,
                    label TEXT NOT NULL,
                    x REAL NOT NULL,
                    y REAL NOT NULL,
                    coord_unit TEXT NOT NULL,
                    PRIMARY KEY (probe_id, local_index)
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS waveform (
                    waveform_id TEXT PRIMARY KEY,
                    channel_local_index INTEGER NOT NULL,
                    sample_count INTEGER NOT NULL,
                    sample_rate_raw TEXT NOT NULL,
                    sample_rate_unit TEXT NOT NULL,
                    sample_rate_hz REAL NOT NULL,
                    samples_per_spike INTEGER NOT NULL,
                    gain_to_unit REAL NOT NULL,
                    physical_unit TEXT NOT NULL,
                    samples BLOB NOT NULL,
                    sha256 TEXT NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS threshold_version (
                    threshold_id TEXT PRIMARY KEY,
                    version INTEGER NOT NULL,
                    probe_id TEXT NOT NULL,
                    channel_local_index INTEGER NOT NULL,
                    operator TEXT NOT NULL,
                    raw_value TEXT NOT NULL,
                    raw_unit TEXT NOT NULL,
                    si_value REAL,
                    si_unit TEXT,
                    basis TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS feature_version (
                    feature_set_id TEXT PRIMARY KEY,
                    version INTEGER NOT NULL,
                    algorithm TEXT NOT NULL,
                    params_canonical TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS spike_event (
                    event_id TEXT PRIMARY KEY,
                    time_seconds REAL NOT NULL,
                    channel_local_index INTEGER NOT NULL,
                    waveform_id TEXT NOT NULL,
                    amplitude_raw TEXT NOT NULL,
                    amplitude_raw_unit TEXT NOT NULL,
                    amplitude_si REAL,
                    amplitude_si_unit TEXT,
                    feature_set_id TEXT NOT NULL,
                    features_json TEXT NOT NULL,
                    raw_line TEXT NOT NULL,
                    valid INTEGER NOT NULL,
                    invalid_reason TEXT,
                    supersedes_order INTEGER,
                    ingest_order INTEGER NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS label_set (
                    label_set_id TEXT PRIMARY KEY,
                    version INTEGER NOT NULL,
                    parent_label_set_id TEXT,
                    note TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS cluster_assignment (
                    label_set_id TEXT NOT NULL,
                    cluster_key TEXT NOT NULL,
                    display_number INTEGER NOT NULL,
                    event_id TEXT NOT NULL,
                    PRIMARY KEY (label_set_id, event_id)
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS adjudication (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    adjudication_id TEXT NOT NULL UNIQUE,
                    branch TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    node_id TEXT,
                    payload TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS derived_node (
                    node_id TEXT PRIMARY KEY,
                    kind TEXT NOT NULL,
                    status TEXT NOT NULL,
                    input_digest TEXT NOT NULL,
                    param_version TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    birth_decision_seq INTEGER NOT NULL,
                    retired INTEGER NOT NULL DEFAULT 0
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS derived_parent (
                    node_id TEXT NOT NULL,
                    parent_id TEXT NOT NULL,
                    pos INTEGER NOT NULL,
                    PRIMARY KEY (node_id, parent_id)
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS lineage_edge (
                    edge_id TEXT PRIMARY KEY,
                    relation TEXT NOT NULL,
                    from_cluster_key TEXT NOT NULL,
                    to_cluster_key TEXT NOT NULL,
                    from_label_version INTEGER NOT NULL,
                    to_label_version INTEGER NOT NULL,
                    shared_event_id TEXT,
                    evidence_node_id TEXT
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS cluster_candidate (
                    label_set_id TEXT NOT NULL,
                    cluster_key TEXT NOT NULL,
                    event_id TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    PRIMARY KEY (label_set_id, cluster_key, event_id)
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS snapshot_pointer (
                    decision_seq INTEGER NOT NULL,
                    branch TEXT NOT NULL,
                    view_name TEXT NOT NULL,
                    node_id TEXT NOT NULL,
                    PRIMARY KEY (branch, decision_seq, view_name)
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS node_adoption (
                    decision_seq INTEGER NOT NULL,
                    node_id TEXT NOT NULL,
                    verdict TEXT NOT NULL,
                    PRIMARY KEY (decision_seq, node_id)
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS op_journal (
                    txid TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    op TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    PRIMARY KEY (txid, seq)
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS tx_commit (
                    txid TEXT PRIMARY KEY,
                    finished_at TEXT NOT NULL
                )""");
        } catch (SQLException e) {
            throw new RuntimeException("schema migration failed", e);
        }
    }
}
