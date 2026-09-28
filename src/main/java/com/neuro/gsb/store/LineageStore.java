package com.neuro.gsb.store;

import com.neuro.gsb.domain.Diagnostic;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Cluster identities, event memberships and machine-readable evidence.
 * Cluster *labels* (sorter numbers, label-set-versioned) are kept beside
 * membership and are never treated as identity. Evidence rows (refractory
 * violations, amplitude drift, multi-channel propagation, concurrent spikes,
 * shared events) are structured records, not log lines.
 */
public final class LineageStore {
    private final Database db;
    public LineageStore(Database db) { this.db = db; }

    public record Identity(String identity, long originEventSeq, String status, String parentIdentity) {}
    public record Membership(String identity, String eventKey, String labelSetVersion,
                             String clusterLabel, long assignedEventSeq) {}
    public record Evidence(long id, String identity, String kind, String payload, long eventSeq) {}

    public void createIdentity(Connection c, String identity, long eventSeq, String status, String parent) {
        try (var ps = c.prepareStatement(
                "INSERT OR IGNORE INTO unit_identity(identity,origin_event_seq,status,parent_identity) VALUES(?,?,?,?)")) {
            ps.setString(1, identity);
            ps.setLong(2, eventSeq);
            ps.setString(3, status);
            ps.setString(4, parent);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void setStatus(Connection c, String identity, String status) {
        try (var ps = c.prepareStatement("UPDATE unit_identity SET status=? WHERE identity=?")) {
            ps.setString(1, status);
            ps.setString(2, identity);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Identity> identitiesAsOf(long asOfSeq) {
        List<Identity> out = new ArrayList<>();
        try (var ps = db.conn().prepareStatement(
                "SELECT identity,origin_event_seq,status,parent_identity FROM unit_identity WHERE origin_event_seq<=? ORDER BY identity")) {
            ps.setLong(1, asOfSeq);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Identity(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<String> membersAsOf(long asOfSeq, String identity) {
        List<String> out = new ArrayList<>();
        try (var ps = db.conn().prepareStatement(
                "SELECT event_key FROM membership WHERE identity=? AND assigned_event_seq<=? ORDER BY event_key")) {
            ps.setString(1, identity);
            ps.setLong(2, asOfSeq);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public void assign(Connection c, String identity, String eventKey, String labelSetVersion,
                       String clusterLabel, long eventSeq) {
        try (var ps = c.prepareStatement(
                "INSERT OR REPLACE INTO membership(identity,event_key,label_set_version,cluster_label,assigned_event_seq) VALUES(?,?,?,?,?)")) {
            ps.setString(1, identity);
            ps.setString(2, eventKey);
            ps.setString(3, labelSetVersion);
            ps.setString(4, clusterLabel);
            ps.setLong(5, eventSeq);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Split: shared events remain on the parent identity and are copied to both children. */
    public void copyMembership(Connection c, String fromIdentity, String toIdentity, long eventSeq) {
        try (var sel = c.prepareStatement(
                "SELECT event_key,label_set_version,cluster_label FROM membership WHERE identity=? ORDER BY event_key");
             var ins = c.prepareStatement(
                "INSERT OR IGNORE INTO membership(identity,event_key,label_set_version,cluster_label,assigned_event_seq) VALUES(?,?,?,?,?)")) {
            sel.setString(1, fromIdentity);
            try (var rs = sel.executeQuery()) {
                while (rs.next()) {
                    ins.setString(1, toIdentity);
                    ins.setString(2, rs.getString(1));
                    ins.setString(3, rs.getString(2));
                    ins.setString(4, rs.getString(3));
                    ins.setLong(5, eventSeq);
                    ins.addBatch();
                }
            }
            ins.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public long addEvidence(Connection c, String identity, String kind, String payload, long eventSeq) {
        try (var ps = c.prepareStatement(
                "INSERT INTO evidence(identity,kind,payload,event_seq) VALUES(?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, identity);
            ps.setString(2, kind);
            ps.setString(3, payload);
            ps.setLong(4, eventSeq);
            ps.executeUpdate();
            try (var keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Evidence> evidenceAsOf(long asOfSeq, String identity) {
        List<Evidence> out = new ArrayList<>();
        String sql = "SELECT id,identity,kind,payload,event_seq FROM evidence WHERE event_seq<=? " +
                (identity == null ? "" : "AND identity=? ") + "ORDER BY event_seq,id";
        try (var ps = db.conn().prepareStatement(sql)) {
            ps.setLong(1, asOfSeq);
            if (identity != null) ps.setString(2, identity);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Evidence(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getLong(5)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public void addDiagnostics(Connection c, List<Diagnostic> diags, long eventSeq) {
        try (var ps = c.prepareStatement(
                "INSERT INTO diagnostic(severity,position,message,event_seq) VALUES(?,?,?,?)")) {
            for (Diagnostic d : diags) {
                ps.setString(1, d.severity().name());
                ps.setString(2, d.position());
                ps.setString(3, d.message());
                ps.setLong(4, eventSeq);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Diagnostic> diagnosticsAsOf(long asOfSeq) {
        List<Diagnostic> out = new ArrayList<>();
        try (var ps = db.conn().prepareStatement(
                "SELECT severity,position,message FROM diagnostic WHERE event_seq<=? ORDER BY id")) {
            ps.setLong(1, asOfSeq);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Diagnostic(Diagnostic.Severity.valueOf(rs.getString(1)),
                            rs.getString(2), rs.getString(3)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /** Identities that currently own an event key (as of seq). Shared events may have several. */
    public List<String> ownersOfEvent(Connection c, String eventKey, long asOfSeq) {
        List<String> out = new ArrayList<>();
        try (var ps = c.prepareStatement(
                "SELECT identity FROM membership WHERE event_key=? AND assigned_event_seq<=? ORDER BY identity")) {
            ps.setString(1, eventKey);
            ps.setLong(2, asOfSeq);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }
}
