package org.neurospike.store;

import org.neurospike.model.Material;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 标签集（聚类指派）读写；标签集整体不可变，修订即新版本。 */
public final class StoreLabels {
    private final Connection c;

    public StoreLabels(Connection c) { this.c = c; }

    public void putLabelSet(Material.LabelSet ls) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO label_set(label_set_id,version,parent_label_set_id,note,created_at)
                VALUES(?,?,?,?,?) ON CONFLICT(label_set_id) DO NOTHING
                """)) {
            ps.setString(1, ls.labelSetId()); ps.setInt(2, ls.version());
            ps.setString(3, ls.parentLabelSetId()); ps.setString(4, ls.note());
            ps.setString(5, ls.createdAt()); ps.executeUpdate();
        }
    }

    public Material.LabelSet labelSet(String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM label_set WHERE label_set_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Material.LabelSet(rs.getString("label_set_id"),
                        rs.getInt("version"), rs.getString("parent_label_set_id"),
                        rs.getString("note"), rs.getString("created_at")) : null;
            }
        }
    }

    public List<Material.LabelSet> labelSets() throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM label_set ORDER BY version,label_set_id");
             ResultSet rs = ps.executeQuery()) {
            List<Material.LabelSet> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new Material.LabelSet(rs.getString("label_set_id"),
                        rs.getInt("version"), rs.getString("parent_label_set_id"),
                        rs.getString("note"), rs.getString("created_at")));
            }
            return out;
        }
    }

    public void putAssignments(String labelSetId, List<Material.ClusterAssignment> rows) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO cluster_assignment(label_set_id,cluster_key,display_number,event_id)
                VALUES(?,?,?,?) ON CONFLICT(label_set_id,event_id) DO NOTHING
                """)) {
            for (Material.ClusterAssignment a : rows) {
                ps.setString(1, labelSetId); ps.setString(2, a.clusterKey());
                ps.setInt(3, a.displayNumber()); ps.setString(4, a.eventId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** clusterKey -> (displayNumber, eventIds)，按 displayNumber、eventId 稳定排序。 */
    public Map<String, Cluster> clusters(String labelSetId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT cluster_key,display_number,event_id FROM cluster_assignment
                WHERE label_set_id=? ORDER BY display_number,event_id
                """)) {
            ps.setString(1, labelSetId);
            Map<String, Cluster> out = new LinkedHashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString("cluster_key");
                    int num = rs.getInt("display_number");
                    out.computeIfAbsent(key, k -> new Cluster(k, num, new ArrayList<>()))
                            .eventIds().add(rs.getString("event_id"));
                }
            }
            return out;
        }
    }

    public record Cluster(String clusterKey, int displayNumber, List<String> eventIds) {
        public Cluster { eventIds = eventIds == null ? new ArrayList<>() : eventIds; }
    }
}
