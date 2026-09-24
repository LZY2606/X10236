package org.neuro.spikeledger.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class Db implements AutoCloseable {
    private final Connection conn;

    public Db(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            this.conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA busy_timeout=5000");
            }
            initSchema();
            conn.commit();
        } catch (Exception e) {
            throw new IllegalStateException("cannot open database: " + path, e);
        }
    }

    public Connection conn() {
        return conn;
    }

    public void commit() {
        try {
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void rollback() {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
        }
    }

    private void initSchema() throws SQLException, IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("schema.sql missing");
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Statement st = conn.createStatement()) {
            for (String part : sql.split(";")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                    st.execute(trimmed);
                }
            }
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}
