package eph.archive;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JDBC helpers; no ORM so the storage layer stays directly testable. */
public final class Sql {

    private Sql() {
    }

    public static void upsert(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        }
    }

    public static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

    public static List<Map<String, Object>> query(Connection c, String sql, Object... args) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (var rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) {
                        row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> one(Connection c, String sql, Object... args) {
        List<Map<String, Object>> rows = query(c, sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
