package spike.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** Owns the single SQLite connection. WAL + foreign keys + a busy timeout for half-write recovery. */
public final class Db implements AutoCloseable {
    private final Connection conn;

    public Db(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            String url = "jdbc:sqlite:" + path;
            conn = DriverManager.getConnection(url);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA synchronous=NORMAL");
            }
            init();
        } catch (ClassNotFoundException | SQLException e) {
            throw new RuntimeException("cannot open database " + path, e);
        }
    }

    public Connection conn() {
        return conn;
    }

    private void init() throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String ddl : Schema.DDL) {
                st.execute(ddl);
            }
        }
    }

    public void tx(RunnableSql body) {
        try {
            boolean prev = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                body.run();
                conn.commit();
            } catch (RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(prev);
            }
        } catch (SQLException e) {
            throw new RuntimeException("transaction failed", e);
        }
    }

    public void checkpoint() {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException e) {
            throw new RuntimeException("checkpoint failed", e);
        }
    }

    @Override
    public void close() {
        try {
            checkpoint();
            conn.close();
        } catch (Exception ignored) {
            // best effort
        }
    }

    @FunctionalInterface
    public interface RunnableSql {
        void run() throws SQLException;
    }
}
