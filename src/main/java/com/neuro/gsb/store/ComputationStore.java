package com.neuro.gsb.store;

import com.neuro.gsb.domain.Ids;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Derived results. Every computation records its input digest, parameter
 * version and parent dependencies. Invalidation marks a row (with the event
 * that invalidated it); nothing is deleted, so replaying an older event seq
 * shows the computations that were valid at that time.
 */
public final class ComputationStore {
    public record Computation(String id, String kind, String identity, String inputDigest,
                              String paramVersion, String parents, String payload, String status,
                              long createdEventSeq, Long invalidatedEventSeq) {}

    private final Database db;
    public ComputationStore(Database db) { this.db = db; }

    public static String computationId(String kind, String identity, String inputDigest, String paramVersion) {
        return "cmp-" + Ids.sha256(kind + "|" + identity + "|" + inputDigest + "|" + paramVersion).substring(0, 24);
    }

    public void put(Connection c, Computation cmp) {
        try (var ps = c.prepareStatement(
                "INSERT OR REPLACE INTO computation(id,kind,identity,input_digest,param_version,parents,payload,status,created_event_seq,invalidated_event_seq) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, cmp.id);
            ps.setString(2, cmp.kind);
            ps.setString(3, cmp.identity);
            ps.setString(4, cmp.inputDigest);
            ps.setString(5, cmp.paramVersion);
            ps.setString(6, cmp.parents);
            ps.setString(7, cmp.payload);
            ps.setString(8, cmp.status);
            ps.setLong(9, cmp.createdEventSeq);
            if (cmp.invalidatedEventSeq == null) ps.setNull(10, java.sql.Types.INTEGER);
            else ps.setLong(10, cmp.invalidatedEventSeq);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Invalidate computations for the given identities created before the event. Returns affected ids. */
    public List<String> invalidateForIdentities(Connection c, List<String> identities, long eventSeq) {
        List<String> affected = new ArrayList<>();
        for (String identity : identities) {
            try (var ps = c.prepareStatement(
                    "UPDATE computation SET status='INVALIDATED', invalidated_event_seq=? " +
                    "WHERE identity=? AND status='VALID'")) {
                ps.setLong(1, eventSeq);
                ps.setString(2, identity);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            affected.add(identity);
        }
        return affected;
    }

    /** Computations visible as of an event seq: valid, or invalidated only later. */
    public List<Computation> asOf(long asOfSeq, String identity) {
        List<Computation> out = new ArrayList<>();
        String sql = "SELECT id,kind,identity,input_digest,param_version,parents,payload,status,created_event_seq,invalidated_event_seq " +
                "FROM computation WHERE created_event_seq<=? " +
                "AND (invalidated_event_seq IS NULL OR invalidated_event_seq>?) " +
                (identity == null ? "" : "AND identity=? ") +
                "ORDER BY kind, id";
        try (var ps = db.conn().prepareStatement(sql)) {
            ps.setLong(1, asOfSeq);
            ps.setLong(2, asOfSeq);
            if (identity != null) ps.setString(3, identity);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    long inv = rs.getLong(10);
                    out.add(new Computation(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                            rs.getString(8), rs.getLong(9), rs.wasNull() ? null : inv));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public Optional<Computation> get(String id) {
        try (var ps = db.conn().prepareStatement(
                "SELECT id,kind,identity,input_digest,param_version,parents,payload,status,created_event_seq,invalidated_event_seq FROM computation WHERE id=?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                long inv = rs.getLong(10);
                return Optional.of(new Computation(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getLong(9), rs.wasNull() ? null : inv));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Computation> all() {
        return asOf(Long.MAX_VALUE, null);
    }
}
