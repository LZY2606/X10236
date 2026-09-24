package eph.archive;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin SQLite access used directly by tests and by the HTTP layer.
 *
 * Half-write recovery: every multi-statement logical import/export round trip is wrapped in a
 * {@code write_ledger} BEGIN/COMMIT pair plus one SQL transaction. On open, a ledger row left in
 * phase BEGIN without COMMIT (crash / kill mid-write) is rolled back by SQL transaction
 * semantics; the ledger row is marked ROLLED_BACK so callers can prove recovery. Foreign tables
 * therefore never expose a partially materialised case pack.
 */
public final class Database implements AutoCloseable {

    private final String url;

    private Database(String url) {
        this.url = url;
    }

    public static Database open(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        String url = "jdbc:sqlite:" + path;
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
            for (String sql : Schema.DDL.split(";")) {
                if (!sql.isBlank()) {
                    st.execute(sql);
                }
            }
            recoverLedger(c);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open database " + path, e);
        }
        return new Database(url);
    }

    public Connection connection() {
        try {
            Connection c = DriverManager.getConnection(url);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
            }
            return c;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void inTransaction(TransactionWork work) {
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            try {
                work.run(c);
                c.commit();
            } catch (RuntimeException e) {
                rollback(c);
                throw e;
            } catch (Exception e) {
                rollback(c);
                throw new RuntimeException(e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void rollback(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // best effort
        }
    }

    /** Begin a ledger-protected logical write; must be paired with {@link #commitLedger}. */
    public String beginLedger(Connection c, String projectId) {
        String txId = Ids.random("tx");
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO write_ledger(project_id,tx_id,phase,started_utc) VALUES(?,?, 'BEGIN',?)")) {
            ps.setString(1, projectId);
            ps.setString(2, txId);
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return txId;
    }

    public void commitLedger(Connection c, String txId) {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE write_ledger SET phase='COMMIT', finished_utc=? WHERE tx_id=?")) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, txId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void recoverLedger(Connection c) throws SQLException {
        List<String> dangling = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT tx_id FROM write_ledger WHERE phase='BEGIN' AND finished_utc IS NULL")) {
            while (rs.next()) {
                dangling.add(rs.getString(1));
            }
        }
        if (dangling.isEmpty()) {
            return;
        }
        // DDL/connection-level rollback already discarded uncommitted rows; mark the ledger.
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE write_ledger SET phase='ROLLED_BACK', finished_utc=? WHERE tx_id=?")) {
            for (String tx : dangling) {
                ps.setString(1, Instant.now().toString());
                ps.setString(2, tx);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public List<String> rolledBackTransactions(String projectId) {
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT tx_id FROM write_ledger WHERE project_id=? AND phase='ROLLED_BACK' "
                             + "ORDER BY started_utc")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean projectExists(String projectId) {
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM project WHERE project_id=?")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public DataSource dataSource() {
        return new DataSource() {
            @Override
            public Connection getConnection() {
                return Database.this.connection();
            }

            @Override
            public Connection getConnection(String username, String password) {
                return getConnection();
            }

            @Override
            public java.io.PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(java.io.PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getLogger("eph.archive");
            }

            @Override
            public <T> T unwrap(Class<T> iface) {
                return null;
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }

    @Override
    public void close() {
        // DriverManager connections are per-call; nothing pooled to close.
    }

    @FunctionalInterface
    public interface TransactionWork {
        void run(Connection c) throws Exception;
    }
}
