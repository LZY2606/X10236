package org.neurospike.store;

import org.neurospike.model.Material;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** 裁定事件（只追加）、分支指针与元数据。 */
public final class StoreDecision {
    private final Connection c;

    public StoreDecision(Connection c) { this.c = c; }

    public long appendAdjudication(String adjudicationId, String branch, String kind,
                                   String nodeId, String payload, String createdAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO adjudication(adjudication_id,branch,kind,node_id,payload,created_at)
                VALUES(?,?,?,?,?,?)""", PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, adjudicationId); ps.setString(2, branch); ps.setString(3, kind);
            ps.setString(4, nodeId); ps.setString(5, payload); ps.setString(6, createdAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("missing generated seq");
                long seq = keys.getLong(1);
                try (PreparedStatement up = c.prepareStatement(
                        "INSERT INTO branch(name,head_seq) VALUES(?,?) "
                                + "ON CONFLICT(name) DO UPDATE SET head_seq=excluded.head_seq")) {
                    up.setString(1, branch); up.setLong(2, seq); up.executeUpdate();
                }
                return seq;
            }
        }
    }

    public List<Material.Adjudication> history(String branch) throws SQLException {
        String sql = "SELECT * FROM adjudication " +
                (branch == null ? "" : "WHERE branch=? ") + "ORDER BY seq";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (branch != null) ps.setString(1, branch);
            List<Material.Adjudication> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Material.Adjudication(rs.getString("adjudication_id"),
                            rs.getLong("seq"), rs.getString("branch"), rs.getString("kind"),
                            rs.getString("node_id"), rs.getString("payload"),
                            rs.getString("created_at")));
                }
            }
            return out;
        }
    }

    public long branchHead(String branch) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT head_seq FROM branch WHERE name=?")) {
            ps.setString(1, branch);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public void putMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO meta(key,value) VALUES(?,?) "
                        + "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key); ps.setString(2, value); ps.executeUpdate();
        }
    }

    public String meta(String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }
}
