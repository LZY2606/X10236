package spikespec;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.function.Supplier;

/**
 * SQLite storage. Immutable materials (waveform refs, probe geometry,
 * detection thresholds, feature versions, cluster labels) live in their own
 * tables, separate from the mutable decision/event log and the derived layer.
 */
public final class Db implements AutoCloseable {
    public final Connection conn;

    private Db(Connection conn) { this.conn = conn; }

    public static Db open(Path path) {
        try {
            if (path.getParent() != null) path.getParent().toFile().mkdirs();
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA foreign_keys=ON");
            }
            Db db = new Db(c);
            db.createSchema();
            db.recover();
            return db;
        } catch (Exception e) {
            throw new RuntimeException("cannot open database " + path, e);
        }
    }

    private void createSchema() throws java.sql.SQLException {
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)",
            // half-write recovery journal: 'begin' rows surviving a crash are incomplete
            "CREATE TABLE IF NOT EXISTS write_ops(op_id TEXT PRIMARY KEY, description TEXT, status TEXT, created_at TEXT)",
            // ---- immutable materials ----
            "CREATE TABLE IF NOT EXISTS clusters(cluster_uid TEXT PRIMARY KEY, created_seq INTEGER, retired_seq INTEGER, retire_reason TEXT)",
            "CREATE TABLE IF NOT EXISTS cluster_labels(id TEXT PRIMARY KEY, cluster_uid TEXT, label TEXT, seq INTEGER, valid_from_seq INTEGER, valid_to_seq INTEGER, note TEXT)",
            "CREATE TABLE IF NOT EXISTS probe_geometry(id TEXT PRIMARY KEY, channel INTEGER, shank TEXT, x REAL, y REAL, unit TEXT, frame TEXT, raw_json TEXT)",
            "CREATE TABLE IF NOT EXISTS detection_thresholds(id TEXT PRIMARY KEY, channel INTEGER, value REAL, unit TEXT, version INTEGER, valid_from_ms REAL, valid_to_ms REAL, raw_json TEXT)",
            "CREATE TABLE IF NOT EXISTS feature_versions(id TEXT PRIMARY KEY, name TEXT, version INTEGER, params_json TEXT, created_seq INTEGER)",
            "CREATE TABLE IF NOT EXISTS waveform_refs(id TEXT PRIMARY KEY, cluster_uid TEXT, channel INTEGER, start_ms REAL, sample_rate_hz REAL, unit TEXT, coord_frame TEXT, samples_json TEXT, source_ref TEXT, raw_json TEXT, created_seq INTEGER)",
            "CREATE TABLE IF NOT EXISTS spike_events(id TEXT PRIMARY KEY, cluster_uid TEXT, time_ms REAL, channel INTEGER, amplitude REAL, amp_unit TEXT, amp_raw TEXT, waveform_ref_id TEXT, shared_group_id TEXT, created_seq INTEGER)",
            // ---- decision layer (mutable, append-only event log) ----
            "CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY, seq INTEGER UNIQUE, kind TEXT, cluster_uid TEXT, payload_json TEXT, param_version INTEGER, parent_ids_json TEXT, input_digest TEXT, status TEXT, created_at TEXT)",
            "CREATE TABLE IF NOT EXISTS conflicts(id TEXT PRIMARY KEY, cluster_uid TEXT, candidate_event_ids_json TEXT, adopted_event_id TEXT, status TEXT, created_seq INTEGER, resolved_seq INTEGER)",
            "CREATE TABLE IF NOT EXISTS cluster_membership(event_id TEXT, cluster_uid TEXT, seq INTEGER, active INTEGER, PRIMARY KEY(event_id, cluster_uid, seq))",
            // ---- derived layer: every result records inputs, params, parents ----
            "CREATE TABLE IF NOT EXISTS derived_results(id TEXT PRIMARY KEY, kind TEXT, cluster_uid TEXT, window_start_ms REAL, window_end_ms REAL, input_digest TEXT, param_version INTEGER, parent_ids_json TEXT, payload_json TEXT, created_seq INTEGER, invalidated_seq INTEGER, invalid_reason TEXT)",
            "CREATE TABLE IF NOT EXISTS review_window(id INTEGER PRIMARY KEY CHECK(id=1), start_ms REAL, end_ms REAL, version INTEGER)",
            "CREATE TABLE IF NOT EXISTS diagnostics(id TEXT PRIMARY KEY, seq INTEGER, stage TEXT, position TEXT, severity TEXT, message TEXT, raw_value TEXT)"
        };
        try (Statement s = conn.createStatement()) {
            for (String d : ddl) s.execute(d);
        }
    }

    /**
     * Half-write recovery: any write_op still marked 'begin' belongs to a
     * transaction that never committed. Its data rolled back with the
     * transaction; here we only mark the journal row and record a diagnostic
     * so the interruption is visible instead of silent.
     */
    public void recover() {
        try (Statement s = conn.createStatement()) {
            ResultSet rs = s.executeQuery(
                    "SELECT op_id, description FROM write_ops WHERE status='begin'");
            java.util.List<String[]> dangling = new java.util.ArrayList<>();
            while (rs.next()) dangling.add(new String[]{rs.getString(1), rs.getString(2)});
            for (String[] row : dangling) {
                try (var ps = conn.prepareStatement(
                        "UPDATE write_ops SET status='recovered-incomplete' WHERE op_id=?")) {
                    ps.setString(1, row[0]);
                    ps.executeUpdate();
                }
                try (var ps = conn.prepareStatement(
                        "INSERT INTO diagnostics(id, seq, stage, position, severity, message, raw_value)"
                                + " VALUES(?,?,?,?,?,?,?)")) {
                    ps.setString(1, "diag-recovery-" + row[0]);
                    ps.setLong(2, 0);
                    ps.setString(3, "recovery");
                    ps.setString(4, "write_ops." + row[0]);
                    ps.setString(5, "WARN");
                    ps.setString(6, "incomplete write recovered at startup; transaction rolled back: " + row[1]);
                    ps.setString(7, null);
                    ps.executeUpdate();
                }
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public <T> T tx(Supplier<T> body) {
        try {
            conn.setAutoCommit(false);
            T v = body.get();
            conn.commit();
            conn.setAutoCommit(true);
            return v;
        } catch (RuntimeException e) {
            rollbackQuietly();
            throw e;
        } catch (Exception e) {
            rollbackQuietly();
            throw new RuntimeException(e);
        }
    }

    public void txRun(Runnable body) {
        tx(() -> { body.run(); return null; });
    }

    private void rollbackQuietly() {
        try { conn.rollback(); conn.setAutoCommit(true); } catch (Exception ignored) {}
    }

    @Override
    public void close() {
        try { conn.close(); } catch (Exception ignored) {}
    }
}
