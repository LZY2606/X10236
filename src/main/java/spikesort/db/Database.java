package spikesort.db;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite storage. Immutable materials (waveform refs, probe geometry, detection
 * thresholds, feature versions, cluster labels) live in separate tables from the
 * mutable decision log (adjudication events). Derived records carry input digests,
 * parameter versions and parent dependencies, and are invalidated - never rewritten.
 *
 * Write intents give half-write recovery: an intent row is committed before the
 * transactional body runs; on open, any intent still in state 'begin' belongs to a
 * crashed write (its body was rolled back) and is marked 'aborted'.
 */
public final class Database implements AutoCloseable {
    private final Connection conn;

    private Database(Connection conn) {
        this.conn = conn;
    }

    public static Database open(Path file) {
        try {
            if (file.getParent() != null) file.getParent().toFile().mkdirs();
            return connect("jdbc:sqlite:" + file.toAbsolutePath());
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open " + file + ": " + e.getMessage(), e);
        }
    }

    public static Database inMemory() {
        try {
            return connect("jdbc:sqlite::memory:");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Database connect(String url) throws SQLException {
        Connection c = DriverManager.getConnection(url);
        Database db = new Database(c);
        db.init();
        return db;
    }

    private void init() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
        }
        try (Statement st = conn.createStatement()) {
            for (String ddl : Schema.ALL) st.execute(ddl);
        }
        recover();
    }

    /** Marks intents left in 'begin' state by a crashed write as aborted. */
    private void recover() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE write_intents SET state='aborted', "
                    + "detail=COALESCE(detail,'') || ' [recovered: crashed write rolled back]' "
                    + "WHERE state='begin'");
        }
    }

    public Connection conn() { return conn; }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
