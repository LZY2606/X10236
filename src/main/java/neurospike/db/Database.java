package neurospike.db;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Supplier;

/** SQLite handle with a global event log clock (seq) and transactional writes. */
public final class Database implements AutoCloseable {
    private final Connection conn;

    private Database(Connection conn) { this.conn = conn; }

    public static Database open(Path path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA busy_timeout=5000");
            }
            Database db = new Database(c);
            db.migrate();
            return db;
        } catch (SQLException e) {
            throw new RuntimeException("cannot open database: " + path, e);
        }
    }

    public static Database inMemory() {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
            Database db = new Database(c);
            db.migrate();
            return db;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Connection conn() { return conn; }

    private void migrate() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS event_log(
                  seq INTEGER PRIMARY KEY AUTOINCREMENT,
                  kind TEXT NOT NULL,
                  payload TEXT NOT NULL)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS waveform_ref(
                  id TEXT PRIMARY KEY,
                  event_id TEXT NOT NULL,
                  channel INTEGER NOT NULL,
                  t_raw TEXT NOT NULL, t_unit TEXT NOT NULL,
                  samples_raw TEXT NOT NULL, sample_unit TEXT NOT NULL,
                  fs_raw TEXT NOT NULL, fs_unit TEXT NOT NULL,
                  t_ms REAL, samples_uv TEXT, fs_hz REAL,
                  norm_status TEXT NOT NULL,
                  valid_from_seq INTEGER NOT NULL, valid_to_seq INTEGER,
                  digest TEXT NOT NULL)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS probe_geometry(
                  id TEXT PRIMARY KEY,
                  version INTEGER NOT NULL,
                  channel INTEGER NOT NULL,
                  x_raw TEXT, x_unit TEXT, y_raw TEXT, y_unit TEXT,
                  x_um REAL, y_um REAL,
                  basis TEXT NOT NULL,
                  norm_status TEXT NOT NULL,
                  valid_from_seq INTEGER NOT NULL, valid_to_seq INTEGER)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS detection_param(
                  id TEXT PRIMARY KEY,
                  version INTEGER NOT NULL,
                  threshold_raw TEXT, threshold_unit TEXT, threshold_uv REAL,
                  refractory_raw TEXT, refractory_unit TEXT, refractory_ms REAL,
                  norm_status TEXT NOT NULL,
                  valid_from_seq INTEGER NOT NULL, valid_to_seq INTEGER)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS feature_set(
                  id TEXT PRIMARY KEY,
                  feature_version TEXT NOT NULL,
                  event_id TEXT NOT NULL,
                  components TEXT NOT NULL,
                  valid_from_seq INTEGER NOT NULL, valid_to_seq INTEGER)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS cluster_label(
                  id TEXT PRIMARY KEY,
                  version INTEGER NOT NULL,
                  event_id TEXT NOT NULL,
                  cluster_no INTEGER NOT NULL,
                  unit_uid TEXT NOT NULL,
                  valid_from_seq INTEGER NOT NULL, valid_to_seq INTEGER)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS decision_event(
                  id TEXT PRIMARY KEY,
                  seq INTEGER NOT NULL,
                  kind TEXT NOT NULL,
                  subject_uid TEXT NOT NULL,
                  actor TEXT, rationale TEXT,
                  payload TEXT NOT NULL,
                  supersedes TEXT,
                  status TEXT NOT NULL,
                  valid_from_seq INTEGER NOT NULL, valid_to_seq INTEGER)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS derived_result(
                  id TEXT PRIMARY KEY,
                  kind TEXT NOT NULL,
                  subject_uid TEXT NOT NULL,
                  input_digest TEXT NOT NULL,
                  param_version INTEGER NOT NULL,
                  parent_ids TEXT NOT NULL,
                  payload TEXT NOT NULL,
                  status TEXT NOT NULL,
                  invalidation_reason TEXT,
                  seq_created INTEGER NOT NULL, seq_invalidated INTEGER)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS diagnostic(
                  id TEXT PRIMARY KEY,
                  seq INTEGER NOT NULL,
                  source TEXT NOT NULL,
                  position TEXT NOT NULL,
                  code TEXT NOT NULL,
                  message TEXT NOT NULL,
                  raw_value TEXT)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS analysis_window(
                  id INTEGER PRIMARY KEY CHECK(id=1),
                  start_ms REAL NOT NULL, end_ms REAL NOT NULL,
                  basis TEXT NOT NULL)""");
        }
    }

    /** Append to the event log; returns the global seq (the history clock). */
    public long log(String kind, String payloadJson) {
        try (var ps = conn.prepareStatement("INSERT INTO event_log(kind,payload) VALUES(?,?)")) {
            ps.setString(1, kind);
            ps.setString(2, payloadJson);
            ps.executeUpdate();
            try (var rs = conn.createStatement().executeQuery("SELECT last_insert_rowid()")) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public long currentSeq() {
        try (var rs = conn.createStatement().executeQuery("SELECT COALESCE(MAX(seq),0) FROM event_log")) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Transactional section: any failure rolls back everything (half-write recovery). */
    public <T> T inTx(Supplier<T> body) {
        try {
            conn.setAutoCommit(false);
            try {
                T r = body.get();
                conn.commit();
                return r;
            } catch (RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    public boolean isEmpty() {
        try (var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM event_log")) {
            rs.next();
            return rs.getLong(1) == 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
