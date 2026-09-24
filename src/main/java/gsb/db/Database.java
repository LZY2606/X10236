package gsb.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite store. Immutable materials, decision events, unit lineage and derived
 * records are kept in separate tables (layered storage). All multi-statement
 * writes run inside a transaction so a crash mid-write leaves the prior state.
 */
public final class Database implements AutoCloseable {
    private final Connection conn;

    public Database(String path) {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA synchronous=FULL");
            }
            migrate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open database " + path, e);
        }
    }

    public static Database inMemory() { return new Database(":memory:"); }

    public Connection conn() { return conn; }

    private void migrate() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS materials(
                  kind TEXT NOT NULL,
                  id TEXT NOT NULL,
                  version INTEGER NOT NULL,
                  content TEXT NOT NULL,
                  digest TEXT NOT NULL,
                  PRIMARY KEY(kind,id,version)
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS events(
                  seq INTEGER PRIMARY KEY AUTOINCREMENT,
                  type TEXT NOT NULL,
                  payload TEXT NOT NULL,
                  digest TEXT NOT NULL
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS units(
                  unit_uid TEXT PRIMARY KEY,
                  label TEXT NOT NULL,
                  label_version INTEGER NOT NULL,
                  parents TEXT NOT NULL,
                  born_event INTEGER NOT NULL
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS derived(
                  id TEXT PRIMARY KEY,
                  kind TEXT NOT NULL,
                  target TEXT NOT NULL,
                  version INTEGER NOT NULL,
                  input_digest TEXT NOT NULL,
                  param_version TEXT NOT NULL,
                  parents TEXT NOT NULL,
                  status TEXT NOT NULL,
                  payload TEXT,
                  diagnostics TEXT,
                  win_start REAL,
                  win_end REAL,
                  born_event INTEGER NOT NULL,
                  dead_event INTEGER,
                  invalidated_reason TEXT,
                  superseded_by TEXT
                )""");
        }
    }

    public void begin() {
        try { conn.setAutoCommit(false); }
        catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public void commit() {
        try { conn.commit(); conn.setAutoCommit(true); }
        catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public void rollback() {
        try { conn.rollback(); conn.setAutoCommit(true); }
        catch (SQLException e) { throw new IllegalStateException(e); }
    }

    @Override public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
