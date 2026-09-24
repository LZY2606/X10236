package neurospike.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Consumer;

/**
 * SQLite connection factory. The store uses one write transaction per logical
 * operation: a crash between operations leaves the previous transaction durable
 * and never a half-applied batch, and a stale -wal is replayed by SQLite itself
 * on open (half-write / crash recovery).
 */
public final class Database {
    private final String url;
    private final Connection shared;

    private Database(String url, Connection shared) {
        this.url = url;
        this.shared = shared;
    }

    private Database(Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot create data directory", e);
        }
        this.url = "jdbc:sqlite:" + file.toAbsolutePath();
        this.shared = null;
    }

    public static Database file(Path file) {
        return new Database(file);
    }

    /** In-memory instance backed by one connection kept open for the JVM life. */
    public static Database inMemory(String name) {
        try {
            Connection c = DriverManager.getConnection(
                    "jdbc:sqlite:file:" + name + "?mode=memory&cache=shared");
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
            }
            return new Database("memory:" + name, c) {
                @Override
                public Connection connect() {
                    return c;
                }
            };
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Connection connect() {
        try {
            Connection c = DriverManager.getConnection(url);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA busy_timeout=5000");
            }
            return c;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open database: " + url, e);
        }
    }

    public void init() {
        boolean owns = shared == null;
        Connection c = connect();
        try (Statement st = c.createStatement()) {
            for (String stmt : Schema.DDL.split(";")) {
                String sql = stmt.trim();
                if (!sql.isEmpty()) {
                    st.execute(sql);
                }
            }
            if (owns) {
                c.close();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("schema init failed", e);
        }
    }

    /** Run {@code work} inside one committed transaction. */
    public void tx(Consumer<Connection> work) {
        boolean owns = shared == null;
        Connection c = connect();
        try {
            c.setAutoCommit(false);
            work.accept(c);
            c.commit();
            if (owns) {
                c.close();
            } else {
                c.setAutoCommit(true);
            }
        } catch (RuntimeException e) {
            rollbackQuiet(c, owns);
            throw e;
        } catch (SQLException e) {
            rollbackQuiet(c, owns);
            throw new IllegalStateException(e);
        }
    }

    private static void rollbackQuiet(Connection c, boolean owns) {
        try {
            c.rollback();
            if (owns) {
                c.close();
            } else {
                c.setAutoCommit(true);
            }
        } catch (SQLException ignored) {
            // best-effort cleanup
        }
    }
}
