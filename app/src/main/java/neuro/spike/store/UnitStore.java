package neuro.spike.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stable unit identity. Cluster numbers are labels that change across label versions;
 * units get a permanent stable id created the first time a cluster lineage appears.
 */
public final class UnitStore {
    private final Db db;

    public UnitStore(Db db) {
        this.db = db;
    }

    public String ensureUnit(String stableId, int firstSeenSeq, int firstClusterNo) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT OR IGNORE INTO unit(stable_id, first_seen_seq, first_cluster_no) VALUES(?,?,?)")) {
            ps.setString(1, stableId);
            ps.setInt(2, firstSeenSeq);
            ps.setInt(3, firstClusterNo);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return stableId;
    }

    public Map<String, Integer> firstClusterNumbers() {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT stable_id, first_cluster_no FROM unit ORDER BY first_seen_seq, stable_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getInt(2));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }
}
