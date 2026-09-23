package org.neurospike.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class Database {
    private final String url;

    public Database(String path) {
        this.url = "jdbc:sqlite:" + path;
    }

    public Connection connect() {
        try {
            Connection conn = DriverManager.getConnection(url);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA journal_mode = WAL");
                st.execute("PRAGMA synchronous = FULL");
            }
            return conn;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open database: " + url, e);
        }
    }

    public void migrate() {
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (String ddl : Schema.DDL) {
                st.execute(ddl);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("migration failed", e);
        }
    }

    /** Integrity probe used after crash/half-write recovery. */
    public boolean integrityOk() {
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            var rs = st.executeQuery("PRAGMA integrity_check");
            return rs.next() && "ok".equalsIgnoreCase(rs.getString(1));
        } catch (SQLException e) {
            return false;
        }
    }
}
