package spike.data;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class Database implements AutoCloseable {

    private final String url;
    private final Connection connection;

    public Database(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SQLite driver missing", e);
        }
        this.url = "jdbc:sqlite:" + path;
        try {
            this.connection = DriverManager.getConnection(url);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }
            initSchema();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open database " + path, e);
        }
    }

    public Connection conn() {
        return connection;
    }

    public String url() {
        return url;
    }

    private void initSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            for (String stmt : Schema.DDL.split(";")) {
                String s = stmt.trim();
                if (!s.isEmpty()) {
                    st.execute(s);
                }
            }
        }
    }

    /** Monotonic logical clock: max write position across append-only tables. */
    public long nextSeq() {
        try (Statement st = connection.createStatement();
             var rs = st.executeQuery(
                     "SELECT COALESCE(MAX(s),0)+1 FROM (" +
                     " SELECT COALESCE(MAX(received_seq),0) AS s FROM material" +
                     " UNION ALL SELECT COALESCE(MAX(seq),0) FROM decision" +
                     " UNION ALL SELECT COALESCE(MAX(seq),0) FROM replay_op)")) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public long currentSeq() {
        return nextSeq() - 1;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // best effort
        }
    }
}
