package neuro.spike.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Access to the six separated material kinds plus time bases. Materials are immutable:
 * a revised threshold or label version is a new (kind,id,revision) row, never an update.
 */
public final class MaterialStore {
    private final Db db;

    public MaterialStore(Db db) {
        this.db = db;
    }

    public void put(String kind, String id, String revision, int seq,
                    String payload, String digest, String importedIn) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                """
                INSERT INTO material(kind, id, seq, revision, payload, input_digest, imported_in)
                VALUES (?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, kind);
            ps.setString(2, id);
            ps.setInt(3, seq);
            ps.setString(4, revision);
            ps.setString(5, payload);
            ps.setString(6, digest);
            ps.setString(7, importedIn);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Row(String kind, String id, String revision, int seq,
                      String payload, String digest, String importedIn) {
    }

    public Optional<Row> latest(String kind, String id, int atSeq) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                """
                SELECT kind, id, revision, seq, payload, input_digest, imported_in
                FROM material WHERE kind=? AND id=? AND seq<=?
                ORDER BY seq DESC LIMIT 1
                """)) {
            ps.setString(1, kind);
            ps.setString(2, id);
            ps.setInt(3, atSeq);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return Optional.empty();
    }

    public List<Row> kindAtOrBefore(String kind, int atSeq) {
        List<Row> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                """
                SELECT kind, id, revision, seq, payload, input_digest, imported_in
                FROM material m
                WHERE kind=? AND seq<=?
                  AND seq = (SELECT MAX(seq) FROM material
                             WHERE kind=m.kind AND id=m.id AND seq<=?)
                ORDER BY seq, id
                """)) {
            ps.setString(1, kind);
            ps.setInt(2, atSeq);
            ps.setInt(3, atSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<Row> allRows() {
        List<Row> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                """
                SELECT kind, id, revision, seq, payload, input_digest, imported_in
                FROM material ORDER BY seq, kind, id
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(read(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<Integer> seqsOfKind(String kind) {
        List<Integer> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT DISTINCT seq FROM material WHERE kind=? ORDER BY seq");
             ) {
            ps.setString(1, kind);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getInt(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private Row read(ResultSet rs) throws SQLException {
        return new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                rs.getString(5), rs.getString(6), rs.getString(7));
    }

    /** Distinct detection ids currently assigned to a unit (used for overlap mapping). */
    public Set<String> detectionsOfUnit(String unitId, int atSeq) {
        Set<String> out = new LinkedHashSet<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                """
                SELECT DISTINCT detection_id FROM membership
                WHERE unit_id=? AND seq<=?
                """)) {
            ps.setString(1, unitId);
            ps.setInt(2, atSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<String> unitIdsAt(int atSeq) {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT DISTINCT unit_id FROM membership WHERE seq<=? ORDER BY unit_id")) {
            ps.setInt(1, atSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }
}
