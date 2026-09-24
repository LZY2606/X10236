package neurogsb.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** SQLite 存储：不可变材料与可修改决定分层；op_journal 支持半写恢复。 */
public final class Database implements AutoCloseable {
    private final Connection conn;

    public Database(String path) {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement s = conn.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA foreign_keys=ON");
            }
            schema();
        } catch (SQLException e) {
            throw new RuntimeException("open db: " + e.getMessage(), e);
        }
    }

    public static Database inMemory() { return new Database(":memory:"); }

    public Connection conn() { return conn; }

    private void schema() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS materials(
                  id TEXT PRIMARY KEY, kind TEXT NOT NULL, version INTEGER NOT NULL,
                  payload TEXT NOT NULL, digest TEXT NOT NULL, seq INTEGER NOT NULL,
                  op_id TEXT, superseded_by TEXT);
                """);
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS spike_events(
                  id TEXT PRIMARY KEY, unit_id TEXT NOT NULL,
                  t_raw TEXT, t_ms REAL, channel INTEGER,
                  amp_raw TEXT, amp_uv REAL, waveform_ref_id TEXT,
                  f1 REAL, f2 REAL, op_id TEXT);
                """);
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS adjudication_events(
                  id TEXT PRIMARY KEY, seq INTEGER NOT NULL UNIQUE, kind TEXT NOT NULL,
                  actor TEXT, target_unit_id TEXT, payload TEXT NOT NULL,
                  parents TEXT NOT NULL, branch TEXT NOT NULL DEFAULT 'main', op_id TEXT);
                """);
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS derived_results(
                  id TEXT PRIMARY KEY, kind TEXT NOT NULL, unit_id TEXT,
                  input_digest TEXT NOT NULL, param_version TEXT NOT NULL,
                  parents TEXT NOT NULL, status TEXT NOT NULL, payload TEXT NOT NULL,
                  event_seq INTEGER NOT NULL, op_id TEXT);
                """);
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS invalidations(
                  id TEXT PRIMARY KEY, derived_id TEXT NOT NULL, reason TEXT NOT NULL,
                  scope TEXT NOT NULL, event_seq INTEGER NOT NULL);
                """);
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS candidates(
                  id TEXT PRIMARY KEY, subject_id TEXT NOT NULL, kind TEXT NOT NULL,
                  payload TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'open');
                """);
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS diagnostics(
                  id TEXT PRIMARY KEY, severity TEXT NOT NULL, position TEXT NOT NULL,
                  message TEXT NOT NULL, raw_value TEXT, material_id TEXT);
                """);
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS op_journal(
                  id TEXT PRIMARY KEY, status TEXT NOT NULL, note TEXT, started_at TEXT NOT NULL);
                """);
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT);
                """);
        }
    }

    /** 半写恢复：pending 的 op 其所有已落库行被回滚，返回恢复报告。 */
    public synchronized List<String> recover() {
        List<String> report = new ArrayList<>();
        try {
            var pending = new ArrayList<String>();
            try (var ps = conn.prepareStatement("SELECT id FROM op_journal WHERE status='pending'");
                 var rs = ps.executeQuery()) {
                while (rs.next()) pending.add(rs.getString(1));
            }
            for (String op : pending) {
                int n = 0;
                for (String table : List.of("materials", "spike_events", "adjudication_events", "derived_results")) {
                    try (var ps = conn.prepareStatement("DELETE FROM " + table + " WHERE op_id=?")) {
                        ps.setString(1, op);
                        n += ps.executeUpdate();
                    }
                }
                try (var ps = conn.prepareStatement("UPDATE op_journal SET status='rolled_back' WHERE id=?")) {
                    ps.setString(1, op);
                    ps.executeUpdate();
                }
                report.add("rolled_back op=" + op + " rows=" + n);
            }
        } catch (SQLException e) {
            throw new RuntimeException("recover: " + e.getMessage(), e);
        }
        return report;
    }

    public synchronized void beginOp(String opId, String note) {
        try (var ps = conn.prepareStatement(
                "INSERT INTO op_journal(id,status,note,started_at) VALUES(?,?,?,datetime('now'))")) {
            ps.setString(1, opId);
            ps.setString(2, "pending");
            ps.setString(3, note);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void commitOp(String opId) {
        try (var ps = conn.prepareStatement("UPDATE op_journal SET status='committed' WHERE id=?")) {
            ps.setString(1, opId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized long nextSeq() {
        try (var ps = conn.prepareStatement("SELECT COALESCE(MAX(seq),0)+1 FROM adjudication_events");
             var rs = ps.executeQuery()) {
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
