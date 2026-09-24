package neuro.spike.store;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Thin SQLite connection holder. Foreign keys and full synchronous WAL are enforced so
 * that the half-write recovery behaviour is deterministic and directly testable.
 */
public final class Db implements AutoCloseable {

    private final Connection connection;

    public Db(Path sqliteFile) {
        try {
            String url = "jdbc:sqlite:" + sqliteFile.toAbsolutePath();
            this.connection = DriverManager.getConnection(url);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA synchronous=FULL");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open database " + sqliteFile, e);
        }
    }

    /** In-memory instance used by unit tests. */
    public static Db memory() {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
            }
            Db db = new Db(c);
            db.initSchema();
            return db;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Db(Connection connection) {
        this.connection = connection;
    }

    public Connection connection() {
        return connection;
    }

    public void initSchema() {
        try (Statement st = connection.createStatement()) {
            for (String stmt : Schema.DDL.split(";")) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("schema initialisation failed", e);
        }
    }

    public void tx(RunnableSql body) {
        try {
            connection.setAutoCommit(false);
            body.run();
            connection.commit();
            connection.setAutoCommit(true);
        } catch (RuntimeException e) {
            rollback();
            throw e;
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException(e);
        }
    }

    private void rollback() {
        try {
            connection.rollback();
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // best-effort rollback
        }
    }

    @FunctionalInterface
    public interface RunnableSql {
        void run() throws SQLException;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
