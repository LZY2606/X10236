package gsb.spikes;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * SQLite store. Immutable materials, append-only adjudication events and
 * derived computations live in separate tables. A write-intent journal makes
 * half-written import batches recoverable on reopen.
 */
public final class Db implements AutoCloseable {
    public final Connection conn;

    private Db(Connection conn) { this.conn = conn; }

    public static Db open(Path path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA synchronous=FULL");
            }
            Db db = new Db(c);
            db.init();
            return db;
        } catch (SQLException e) {
            throw new IllegalStateException("open db failed: " + path, e);
        }
    }

    private void init() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS counters(name TEXT PRIMARY KEY, value INTEGER NOT NULL)""");
            // Immutable materials, content-addressed, separated by kind.
            s.execute("""
                CREATE TABLE IF NOT EXISTS materials(
                  kind TEXT NOT NULL, id TEXT PRIMARY KEY, digest TEXT NOT NULL,
                  content TEXT NOT NULL, seq INTEGER NOT NULL, import_intent TEXT)""");
            // Append-only adjudication events; history is never overwritten.
            s.execute("""
                CREATE TABLE IF NOT EXISTS events(
                  id TEXT PRIMARY KEY, seq INTEGER NOT NULL, branch TEXT NOT NULL,
                  type TEXT NOT NULL, payload TEXT NOT NULL, parents TEXT NOT NULL,
                  created TEXT NOT NULL, import_intent TEXT)""");
            // Stable unit identities; cluster numbers are labels, never identity.
            s.execute("""
                CREATE TABLE IF NOT EXISTS units(
                  id TEXT PRIMARY KEY, seq INTEGER NOT NULL, label TEXT,
                  status TEXT NOT NULL, origin TEXT NOT NULL, import_intent TEXT)""");
            // Evidence rows: refractory / drift / propagation are evidence only.
            s.execute("""
                CREATE TABLE IF NOT EXISTS evidence(
                  id TEXT PRIMARY KEY, seq INTEGER NOT NULL, unit_id TEXT NOT NULL,
                  kind TEXT NOT NULL, metrics TEXT NOT NULL,
                  win_from REAL NOT NULL, win_to REAL NOT NULL,
                  sources TEXT NOT NULL, import_intent TEXT)""");
            // Derived computations: input digest, param version, parent deps,
            // validity range and invalidation reason.
            s.execute("""
                CREATE TABLE IF NOT EXISTS computations(
                  id TEXT PRIMARY KEY, seq INTEGER NOT NULL, kind TEXT NOT NULL,
                  subject TEXT NOT NULL, input_digest TEXT NOT NULL,
                  param_version TEXT NOT NULL, parents TEXT NOT NULL,
                  status TEXT NOT NULL, result TEXT NOT NULL,
                  win_from REAL, win_to REAL, invalidation_reason TEXT,
                  import_intent TEXT)""");
            // Counterexample archive: baseline + single-source mutations.
            s.execute("""
                CREATE TABLE IF NOT EXISTS counterexamples(
                  id TEXT PRIMARY KEY, seq INTEGER NOT NULL, name TEXT NOT NULL,
                  baseline TEXT NOT NULL, mutation TEXT NOT NULL,
                  expected_scope TEXT NOT NULL, actual_scope TEXT,
                  verdict TEXT, adopted INTEGER NOT NULL DEFAULT 0,
                  import_intent TEXT)""");
            // Write-intent journal for half-write recovery.
            s.execute("""
                CREATE TABLE IF NOT EXISTS write_intents(
                  id TEXT PRIMARY KEY, status TEXT NOT NULL,
                  expected INTEGER NOT NULL, actual INTEGER NOT NULL DEFAULT 0,
                  note TEXT)""");
        }
    }

    public long nextSeq() {
        try {
            conn.createStatement().execute(
                    "INSERT OR IGNORE INTO counters(name,value) VALUES('seq',0)");
            try (Statement s = conn.createStatement()) {
                s.execute("UPDATE counters SET value=value+1 WHERE name='seq'");
                try (ResultSet rs = s.executeQuery("SELECT value FROM counters WHERE name='seq'")) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public <T> T inTx(Function<Connection, T> fn) {
        try {
            conn.setAutoCommit(false);
            try {
                T v = fn.apply(conn);
                conn.commit();
                return v;
            } catch (RuntimeException | Error e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    /** Recover from half-written import batches: roll back partial rows, mark intent. */
    public List<String> recover() {
        List<String> recovered = new ArrayList<>();
        try {
            List<String> open = new ArrayList<>();
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT id FROM write_intents WHERE status='open'")) {
                while (rs.next()) open.add(rs.getString(1));
            }
            for (String intent : open) {
                inTx(c -> {
                    int n = 0;
                    for (String table : List.of("materials", "events", "units", "evidence", "computations", "counterexamples")) {
                        try (PreparedStatement ps = c.prepareStatement(
                                "DELETE FROM " + table + " WHERE import_intent=?")) {
                            ps.setString(1, intent);
                            n += ps.executeUpdate();
                        }
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE write_intents SET status='recovered', actual=? WHERE id=?")) {
                        ps.setInt(1, n);
                        ps.setString(2, intent);
                        ps.executeUpdate();
                    }
                    return null;
                });
                recovered.add(intent);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return recovered;
    }

    public List<String> queryStrings(String sql, Object... args) {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public long count(String table) {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
