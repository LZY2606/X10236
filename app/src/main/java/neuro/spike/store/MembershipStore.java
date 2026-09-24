package neuro.spike.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Membership rows connect immutable cluster-label assignments to stable unit ids.
 * Split-shared events are first-class: the same detection id may map to two units.
 */
public final class MembershipStore {
    private final Db db;

    public MembershipStore(Db db) {
        this.db = db;
    }

    public void put(String unitId, String labelId, String detectionId,
                    int clusterNumber, double weight, int seq) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                """
                INSERT OR REPLACE INTO membership
                  (unit_id, label_id, detection_id, cluster_number, weight, seq)
                VALUES (?,?,?,?,?,?)
                """)) {
            ps.setString(1, unitId);
            ps.setString(2, labelId);
            ps.setString(3, detectionId);
            ps.setInt(4, clusterNumber);
            ps.setDouble(5, weight);
            ps.setInt(6, seq);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Membership(String unitId, String labelId, String detectionId,
                             int clusterNumber, double weight, int seq) {
    }

    public List<Membership> unitDetectionsAt(String unitId, int atSeq) {
        List<Membership> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                """
                SELECT unit_id, label_id, detection_id, cluster_number, weight, seq
                FROM membership
                WHERE unit_id=? AND seq<=?
                ORDER BY detection_id, cluster_number
                """)) {
            ps.setString(1, unitId);
            ps.setInt(2, atSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Membership(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getInt(4), rs.getDouble(5), rs.getInt(6)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /** Most recent membership rows for the unit at or before the view seq. */
    public List<Membership> currentUnitDetections(String unitId, String labelId) {
        List<Membership> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                """
                SELECT unit_id, label_id, detection_id, cluster_number, weight, seq
                FROM membership
                WHERE unit_id=? AND label_id=?
                ORDER BY detection_id, cluster_number
                """)) {
            ps.setString(1, unitId);
            ps.setString(2, labelId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Membership(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getInt(4), rs.getDouble(5), rs.getInt(6)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /** detectionId -> cluster numbers currently carrying it (split sharing). */
    public Map<String, List<Integer>> sharingAt(String labelId) {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                """
                SELECT detection_id, cluster_number FROM membership
                WHERE label_id=? ORDER BY detection_id, cluster_number
                """)) {
            ps.setString(1, labelId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getInt(2));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }
}
