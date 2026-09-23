package gsb.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Owns the SQLite connection, the layered schema and crash recovery.
 *
 *  Layers (stored separately, immutable materials vs. mutable decisions):
 *    materials : waveform_ref, probe_geometry, detection_threshold,
 *                feature_version, detection_event, cluster_label
 *    decisions : adjudication_event, candidate, evidence
 *    derived   : derived_result (input digest + parameter version + parents)
 *    support   : unit, diagnostic, import_batch, meta, write_journal
 *
 *  Every multi-row write goes through tx(): a journal row is written BEFORE
 *  the transaction starts and completed AFTER commit. On open, journal rows
 *  left incomplete mark interrupted writes; SQLite's atomic commit guarantees
 *  their payload never partially landed, and recovery reports them. */
public final class Database implements AutoCloseable {
    private final Connection conn;
    private final List<String> recoveryReport = new ArrayList<>();

    private Database(Connection conn) {
        this.conn = conn;
    }

    public static Database open(String path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA synchronous=FULL");
            }
            Database db = new Database(c);
            db.createSchema();
            db.recover();
            return db;
        } catch (Exception e) {
            throw new IllegalStateException("cannot open database " + path + ": " + e.getMessage(), e);
        }
    }

    public static Database inMemory() {
        return open(":memory:");
    }

    public Connection conn() { return conn; }
    public List<String> recoveryReport() { return recoveryReport; }

    private void createSchema() throws Exception {
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS meta(
                  key TEXT PRIMARY KEY, value TEXT NOT NULL)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS write_journal(
                  tx_id TEXT PRIMARY KEY,
                  note TEXT NOT NULL,
                  completed INTEGER NOT NULL DEFAULT 0,
                  recovered INTEGER NOT NULL DEFAULT 0)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS waveform_ref(
                  id TEXT PRIMARY KEY, channel INTEGER NOT NULL,
                  t_start_ms REAL NOT NULL, t_end_ms REAL NOT NULL,
                  sample_rate_hz REAL NOT NULL, unit TEXT NOT NULL,
                  samples TEXT NOT NULL, source_uri TEXT NOT NULL,
                  sha256 TEXT NOT NULL, seq INTEGER NOT NULL)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS probe_geometry(
                  id TEXT PRIMARY KEY, version INTEGER NOT NULL,
                  frame TEXT NOT NULL, origin TEXT NOT NULL,
                  sites_json TEXT NOT NULL,
                  valid_from_ms REAL NOT NULL, valid_to_ms REAL NOT NULL,
                  seq INTEGER NOT NULL)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS detection_threshold(
                  id TEXT PRIMARY KEY, version INTEGER NOT NULL,
                  channel INTEGER NOT NULL, value REAL NOT NULL,
                  unit TEXT NOT NULL,
                  valid_from_ms REAL NOT NULL, valid_to_ms REAL NOT NULL,
                  seq INTEGER NOT NULL)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS feature_version(
                  id TEXT PRIMARY KEY, tag TEXT NOT NULL,
                  algorithm TEXT NOT NULL, params_json TEXT NOT NULL,
                  seq INTEGER NOT NULL)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS detection_event(
                  id TEXT PRIMARY KEY, batch_id TEXT NOT NULL,
                  channel INTEGER NOT NULL, t_ms REAL NOT NULL,
                  t_unit_raw TEXT NOT NULL, amplitude REAL NOT NULL,
                  amp_unit_raw TEXT NOT NULL, waveform_ref_id TEXT,
                  seq INTEGER NOT NULL)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS unit(
                  id TEXT PRIMARY KEY, kind TEXT NOT NULL,
                  origin_json TEXT NOT NULL, merged_into TEXT,
                  seq INTEGER NOT NULL)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS cluster_label(
                  id TEXT PRIMARY KEY, run_id TEXT NOT NULL,
                  cluster_no INTEGER NOT NULL, unit_id TEXT NOT NULL,
                  feature_version_id TEXT NOT NULL, seq INTEGER NOT NULL)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS adjudication_event(
                  id TEXT PRIMARY KEY, seq INTEGER NOT NULL,
                  type TEXT NOT NULL, actor TEXT NOT NULL,
                  payload_json TEXT NOT NULL, parents_json TEXT NOT NULL,
                  supersedes TEXT)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS candidate(
                  id TEXT PRIMARY KEY, decision_ref TEXT NOT NULL,
                  option_json TEXT NOT NULL, adopted INTEGER NOT NULL DEFAULT 0,
                  rationale TEXT NOT NULL, seq INTEGER NOT NULL)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS evidence(
                  id TEXT PRIMARY KEY, kind TEXT NOT NULL,
                  about_json TEXT NOT NULL, strength REAL NOT NULL,
                  t_ms REAL NOT NULL, window_json TEXT NOT NULL,
                  note TEXT NOT NULL, seq INTEGER NOT NULL)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS derived_result(
                  id TEXT PRIMARY KEY, kind TEXT NOT NULL,
                  unit_id TEXT NOT NULL, input_digest TEXT NOT NULL,
                  param_version TEXT NOT NULL, parents_json TEXT NOT NULL,
                  valid_from_ms REAL NOT NULL, valid_to_ms REAL NOT NULL,
                  status TEXT NOT NULL, invalidation_reason TEXT,
                  payload_json TEXT NOT NULL,
                  created_seq INTEGER NOT NULL, invalidated_seq INTEGER)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS diagnostic(
                  id TEXT PRIMARY KEY, stage TEXT NOT NULL,
                  severity TEXT NOT NULL, location TEXT NOT NULL,
                  message TEXT NOT NULL, raw_value TEXT, seq INTEGER NOT NULL)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS import_batch(
                  id TEXT PRIMARY KEY, outcome TEXT NOT NULL,
                  detail TEXT NOT NULL, seq INTEGER NOT NULL)""");
        }
    }

    /** Marks interrupted writes from a previous run as recovered. */
    private void recover() throws Exception {
        List<String> dangling = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT tx_id, note FROM write_journal WHERE completed=0 AND recovered=0")) {
            while (rs.next()) dangling.add(rs.getString(1) + " (" + rs.getString(2) + ")");
        }
        for (String d : dangling) {
            recoveryReport.add("rolled-back incomplete tx: " + d);
        }
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("UPDATE write_journal SET recovered=1 WHERE completed=0");
        }
    }

    /** Runs body inside a journaled transaction. If anything throws, the
     *  transaction rolls back and the journal keeps an incomplete row, which
     *  the next open() reports as a recovered half-write. */
    public synchronized void tx(String note, Runnable body) {
        String txId = "tx_" + System.nanoTime();
        try {
            try (Statement s = conn.createStatement()) {
                s.executeUpdate("INSERT INTO write_journal(tx_id,note,completed) VALUES('"
                        + txId + "','" + note.replace("'", "''") + "',0)");
            }
            conn.setAutoCommit(false);
            try {
                body.run();
                conn.commit();
            } catch (RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
            try (Statement s = conn.createStatement()) {
                s.executeUpdate("UPDATE write_journal SET completed=1 WHERE tx_id='" + txId + "'");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try { conn.close(); } catch (Exception ignored) {}
    }
}
