package atlas.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public final class Database implements AutoCloseable {
    public static final String SCHEMA = """
        PRAGMA journal_mode=WAL;
        PRAGMA foreign_keys=ON;
        CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT);
        CREATE TABLE IF NOT EXISTS waveform_refs (
          event_id TEXT NOT NULL, version INTEGER NOT NULL,
          channel INTEGER NOT NULL, t_ms REAL NOT NULL, t_end_ms REAL NOT NULL,
          raw_amplitude TEXT NOT NULL, raw_unit TEXT,
          amp_uv REAL, unit_diag TEXT,
          snippet TEXT NOT NULL, sample_rate_khz REAL NOT NULL,
          source_ref TEXT NOT NULL, time_basis TEXT NOT NULL,
          import_seq INTEGER NOT NULL,
          PRIMARY KEY (event_id, version));
        CREATE TABLE IF NOT EXISTS probe_geometry (
          version TEXT NOT NULL, channel INTEGER NOT NULL,
          x_um REAL NOT NULL, y_um REAL NOT NULL, coord_basis TEXT NOT NULL,
          import_seq INTEGER NOT NULL, PRIMARY KEY (version, channel));
        CREATE TABLE IF NOT EXISTS detection_params (
          version TEXT PRIMARY KEY, raw_threshold TEXT NOT NULL, threshold_uv REAL,
          refractory_ms REAL NOT NULL, window_ms REAL NOT NULL, created_seq INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS feature_sets (
          version TEXT PRIMARY KEY, algo TEXT NOT NULL, created_seq INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS cluster_labels (
          feature_version TEXT NOT NULL, cluster_no INTEGER NOT NULL, event_id TEXT NOT NULL,
          import_seq INTEGER NOT NULL,
          PRIMARY KEY (feature_version, cluster_no, event_id));
        CREATE TABLE IF NOT EXISTS units (
          unit_uid TEXT PRIMARY KEY, origin TEXT NOT NULL, created_seq INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS unit_status_history (
          unit_uid TEXT NOT NULL, seq INTEGER NOT NULL, status TEXT NOT NULL,
          PRIMARY KEY (unit_uid, seq));
        CREATE TABLE IF NOT EXISTS unit_cluster_map (
          feature_version TEXT NOT NULL, cluster_no INTEGER NOT NULL, unit_uid TEXT NOT NULL,
          confidence REAL NOT NULL, candidates TEXT, created_seq INTEGER NOT NULL,
          PRIMARY KEY (feature_version, cluster_no));
        CREATE TABLE IF NOT EXISTS unit_events (
          unit_uid TEXT NOT NULL, event_id TEXT NOT NULL, seq INTEGER NOT NULL,
          PRIMARY KEY (unit_uid, event_id));
        CREATE TABLE IF NOT EXISTS adjudications (
          adj_id TEXT PRIMARY KEY, seq INTEGER NOT NULL, branch TEXT NOT NULL,
          kind TEXT NOT NULL, target TEXT NOT NULL, payload TEXT NOT NULL,
          rationale TEXT NOT NULL, conflict_with TEXT, ts TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS adj_status_history (
          adj_id TEXT NOT NULL, seq INTEGER NOT NULL, status TEXT NOT NULL,
          PRIMARY KEY (adj_id, seq));
        CREATE TABLE IF NOT EXISTS evidence (
          ev_id TEXT PRIMARY KEY, kind TEXT NOT NULL, unit_uid TEXT NOT NULL,
          value REAL NOT NULL, unit TEXT NOT NULL, verdict TEXT NOT NULL,
          detail TEXT NOT NULL, seq INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS derived (
          id TEXT PRIMARY KEY, kind TEXT NOT NULL, unit_uid TEXT NOT NULL,
          window_key TEXT NOT NULL, param_version TEXT NOT NULL,
          input_digest TEXT NOT NULL, parents TEXT NOT NULL,
          payload TEXT NOT NULL, valid_range TEXT NOT NULL, basis TEXT NOT NULL,
          created_seq INTEGER NOT NULL, invalidated_seq INTEGER, invalidated_reason TEXT);
        CREATE TABLE IF NOT EXISTS diagnostics (
          id INTEGER PRIMARY KEY AUTOINCREMENT, layer TEXT NOT NULL, path TEXT NOT NULL,
          message TEXT NOT NULL, seq INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS journal (
          batch_id TEXT PRIMARY KEY, state TEXT NOT NULL, payload TEXT NOT NULL, diag TEXT);
        CREATE TABLE IF NOT EXISTS replay_log (
          step TEXT PRIMARY KEY, seq INTEGER NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL);
        """;

    public final Connection conn;

    private Database(Connection conn) { this.conn = conn; }

    public static Database open(String path) {
        try {
            if (!":memory:".equals(path)) {
                Path parent = Path.of(path).toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
            }
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement st = c.createStatement()) {
                for (String stmt : SCHEMA.split(";")) {
                    String s = stmt.trim();
                    if (!s.isEmpty()) st.execute(s);
                }
            }
            return new Database(c);
        } catch (Exception e) {
            throw new IllegalStateException("cannot open database " + path, e);
        }
    }

    @Override
    public void close() {
        try { conn.close(); } catch (Exception ignored) {}
    }
}
