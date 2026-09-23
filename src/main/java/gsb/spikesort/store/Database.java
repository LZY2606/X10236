package gsb.spikesort.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** SQLite database handle. All writes happen inside explicit transactions so
 *  a half-finished write can always be rolled back (half-write recovery). */
public final class Database implements AutoCloseable {
    private final Connection conn;

    private Database(Connection conn) { this.conn = conn; }

    public static Database open(String path) {
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
        } catch (SQLException e) { throw new IllegalStateException("open db: " + path, e); }
    }

    public static Database inMemory() {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
            Database db = new Database(c);
            db.migrate();
            return db;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public Connection conn() { return conn; }

    public void begin() {
        try { conn.setAutoCommit(false); }
        catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public void commit() {
        try { conn.commit(); conn.setAutoCommit(true); }
        catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public void rollbackQuietly() {
        try { conn.rollback(); conn.setAutoCommit(true); }
        catch (SQLException ignored) {}
    }

    private void migrate() {
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            """
            CREATE TABLE IF NOT EXISTS waveform_refs(
              id TEXT PRIMARY KEY, channel INTEGER NOT NULL,
              t_start_ms REAL NOT NULL, t_end_ms REAL NOT NULL,
              sampling_rate_hz REAL NOT NULL,
              amplitude_unit TEXT NOT NULL, coord_frame TEXT NOT NULL,
              source_uri TEXT NOT NULL, raw_repr TEXT NOT NULL, digest TEXT NOT NULL)
            """,
            """
            CREATE TABLE IF NOT EXISTS probe_geometries(
              id TEXT PRIMARY KEY, version INTEGER NOT NULL, frame TEXT NOT NULL,
              sites_json TEXT NOT NULL, digest TEXT NOT NULL)
            """,
            """
            CREATE TABLE IF NOT EXISTS detection_params(
              id TEXT PRIMARY KEY, version INTEGER NOT NULL,
              threshold_uv REAL NOT NULL, refractory_ms REAL NOT NULL,
              window_start_ms REAL NOT NULL, window_end_ms REAL NOT NULL,
              digest TEXT NOT NULL)
            """,
            """
            CREATE TABLE IF NOT EXISTS feature_sets(
              id TEXT PRIMARY KEY, version INTEGER NOT NULL, method TEXT NOT NULL,
              input_digest TEXT NOT NULL, digest TEXT NOT NULL)
            """,
            """
            CREATE TABLE IF NOT EXISTS units(
              id TEXT PRIMARY KEY, created_by_event TEXT NOT NULL, status TEXT NOT NULL)
            """,
            """
            CREATE TABLE IF NOT EXISTS cluster_labels(
              id TEXT PRIMARY KEY, feature_set_id TEXT NOT NULL,
              cluster_index INTEGER NOT NULL, unit_id TEXT NOT NULL,
              assigned_event_id TEXT NOT NULL)
            """,
            """
            CREATE TABLE IF NOT EXISTS spike_events(
              id TEXT PRIMARY KEY, time_ms REAL NOT NULL, channel INTEGER NOT NULL,
              amplitude_raw TEXT NOT NULL, amplitude_uv REAL,
              unit_id TEXT, waveform_ref TEXT,
              candidate_units_json TEXT NOT NULL DEFAULT '[]')
            """,
            """
            CREATE TABLE IF NOT EXISTS adjudication_events(
              id TEXT PRIMARY KEY, seq INTEGER NOT NULL, type TEXT NOT NULL,
              branch TEXT NOT NULL, payload_json TEXT NOT NULL,
              parent_id TEXT, status TEXT NOT NULL, created_at TEXT NOT NULL)
            """,
            """
            CREATE TABLE IF NOT EXISTS evidence_events(
              id TEXT PRIMARY KEY, kind TEXT NOT NULL, unit_id TEXT,
              channel INTEGER, time_ms REAL, basis_json TEXT NOT NULL,
              adjudication_event_id TEXT)
            """,
            """
            CREATE TABLE IF NOT EXISTS artifacts(
              id TEXT PRIMARY KEY, kind TEXT NOT NULL, unit_id TEXT,
              input_digest TEXT NOT NULL, param_version INTEGER NOT NULL,
              parents_json TEXT NOT NULL,
              valid_from_seq INTEGER NOT NULL, valid_to_seq INTEGER,
              status TEXT NOT NULL, invalidation_reason TEXT,
              payload_json TEXT NOT NULL)
            """,
            """
            CREATE TABLE IF NOT EXISTS diagnostics(
              id TEXT PRIMARY KEY, stage TEXT NOT NULL, location TEXT NOT NULL,
              severity TEXT NOT NULL, code TEXT NOT NULL, message TEXT NOT NULL,
              raw_value TEXT)
            """
        };
        try (Statement s = conn.createStatement()) {
            for (String sql : ddl) s.execute(sql);
        } catch (SQLException e) { throw new IllegalStateException("migrate", e); }
    }

    @Override public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
