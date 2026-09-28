package com.neuro.gsb.store;

import com.neuro.gsb.domain.Ids;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Immutable materials: raw waveform references, probe geometry, detection
 * thresholds, feature versions, cluster label sets and detection events are
 * stored separately and never updated — a revision inserts a new material.
 */
public final class MaterialStore {
    public enum Kind {
        WAVEFORM_REF, PROBE_GEOMETRY, DETECTION_THRESHOLD, FEATURE_VERSION,
        CLUSTER_LABELS, DETECTION_EVENTS
    }

    public record Material(String id, Kind kind, String content, String contentHash,
                           String sourceName, long createdSeq) {}

    private final Database db;
    public MaterialStore(Database db) { this.db = db; }

    /** Insert-only. Same kind+content yields the same id (idempotent). */
    public Material put(Connection c, Kind kind, String content, String sourceName, long createdSeq) {
        String id = Ids.materialId(kind.name(), content);
        String hash = Ids.sha256(content);
        try (var ps = c.prepareStatement(
                "INSERT OR IGNORE INTO material(id,kind,content,content_hash,source_name,created_seq) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, kind.name());
            ps.setString(3, content);
            ps.setString(4, hash);
            ps.setString(5, sourceName);
            ps.setLong(6, createdSeq);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return get(id).orElseThrow();
    }

    public Optional<Material> get(String id) {
        try (var ps = db.conn().prepareStatement(
                "SELECT id,kind,content,content_hash,source_name,created_seq FROM material WHERE id=?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Material(rs.getString(1), Kind.valueOf(rs.getString(2)),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Materials of a kind visible as of an event seq, newest first. */
    public List<Material> byKindAsOf(Kind kind, long asOfSeq) {
        List<Material> out = new ArrayList<>();
        try (var ps = db.conn().prepareStatement(
                "SELECT id,kind,content,content_hash,source_name,created_seq FROM material " +
                "WHERE kind=? AND created_seq<=? ORDER BY created_seq DESC, id")) {
            ps.setString(1, kind.name());
            ps.setLong(2, asOfSeq);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Material(rs.getString(1), kind, rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getLong(6)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<Material> all() {
        return byKindAsOfAll(Long.MAX_VALUE);
    }

    private List<Material> byKindAsOfAll(long asOf) {
        List<Material> out = new ArrayList<>();
        for (Kind k : Kind.values()) out.addAll(byKindAsOf(k, asOf));
        out.sort((a, b) -> a.id.compareTo(b.id));
        return out;
    }
}
