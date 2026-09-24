package neuro.spike.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only global ordering. Every material revision or adjudication gets one integer
 * sequence; the same logical import replayed from an export yields the same seq values,
 * which is what keeps "equivalent input -> stable order" observable after round trips.
 */
public final class OrderStore {
    private final Db db;

    public OrderStore(Db db) {
        this.db = db;
    }

    public int maxSeq() {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT COALESCE(MAX(seq), 0) FROM global_order");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public int append(String kind, String id, String revision, String branch) {
        int seq = maxSeq() + 1;
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO global_order(seq, kind, id, revision, branch) VALUES(?,?,?,?,?)")) {
            ps.setInt(1, seq);
            ps.setString(2, kind);
            ps.setString(3, id);
            ps.setString(4, revision);
            ps.setString(5, branch);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return seq;
    }

    public record Entry(int seq, String kind, String id, String revision, String branch) {
    }

    public List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT seq, kind, id, revision, branch FROM global_order ORDER BY seq");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Entry(rs.getInt(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }
}
