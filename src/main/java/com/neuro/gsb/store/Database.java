package com.neuro.gsb.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * SQLite storage. WAL mode, foreign keys on. Every multi-statement write
 * runs inside a single transaction, so a crash mid-write rolls back cleanly.
 * Import sessions are registered in their own transaction first; a session
 * still marked STARTED at startup means the process died mid-import and is
 * marked ABORTED by {@link #recover()}.
 */
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
            db.recover();
            return db;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open database " + path, e);
        }
    }

    public static Database inMemory() {
        return open(":memory:");
    }

    public Connection conn() { return conn; }

    private void migrate() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS material (
                  id TEXT PRIMARY KEY,
                  kind TEXT NOT NULL,
                  content TEXT NOT NULL,
                  content_hash TEXT NOT NULL,
                  source_name TEXT NOT NULL,
                  created_seq INTEGER NOT NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS event (
                  seq INTEGER PRIMARY KEY AUTOINCREMENT,
                  id TEXT NOT NULL UNIQUE,
                  kind TEXT NOT NULL,
                  payload TEXT NOT NULL,
                  parents TEXT NOT NULL,
                  created_at TEXT NOT NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS unit_identity (
                  identity TEXT PRIMARY KEY,
                  origin_event_seq INTEGER NOT NULL,
                  status TEXT NOT NULL,
                  parent_identity TEXT
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS membership (
                  identity TEXT NOT NULL,
                  event_key TEXT NOT NULL,
                  label_set_version TEXT NOT NULL,
                  cluster_label TEXT NOT NULL,
                  assigned_event_seq INTEGER NOT NULL,
                  PRIMARY KEY (identity, event_key)
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS evidence (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  identity TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  payload TEXT NOT NULL,
                  event_seq INTEGER NOT NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS computation (
                  id TEXT PRIMARY KEY,
                  kind TEXT NOT NULL,
                  identity TEXT,
                  input_digest TEXT NOT NULL,
                  param_version TEXT NOT NULL,
                  parents TEXT NOT NULL,
                  payload TEXT NOT NULL,
                  status TEXT NOT NULL,
                  created_event_seq INTEGER NOT NULL,
                  invalidated_event_seq INTEGER
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS diagnostic (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  severity TEXT NOT NULL,
                  position TEXT NOT NULL,
                  message TEXT NOT NULL,
                  event_seq INTEGER NOT NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS import_session (
                  id TEXT PRIMARY KEY,
                  status TEXT NOT NULL,
                  detail TEXT
                )""");
        }
    }

    /** Mark imports that were in flight when the process died as ABORTED. */
    public List<String> recover() {
        List<String> aborted = new ArrayList<>();
        try (var ps = conn.prepareStatement("SELECT id FROM import_session WHERE status='STARTED'");
             var rs = ps.executeQuery()) {
            while (rs.next()) aborted.add(rs.getString(1));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        for (String id : aborted) {
            try (var ps = conn.prepareStatement(
                    "UPDATE import_session SET status='ABORTED', detail='recovered: process died mid-import' WHERE id=?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return aborted;
    }

    /** Run a multi-statement write atomically. Rolls back on any failure. */
    public <T> T inTransaction(Function<Connection, T> work) {
        try {
            conn.setAutoCommit(false);
            try {
                T result = work.apply(conn);
                conn.commit();
                return result;
            } catch (RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("transaction failed", e);
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    /** Commit one statement outside the main transaction (session bookkeeping). */
    public void registerSession(String id, String status, String detail) {
        try (var ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO import_session(id,status,detail) VALUES(?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, status);
            ps.setString(3, detail);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
