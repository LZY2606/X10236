package org.neuro.spike.store;

import org.neuro.spike.model.Cluster;
import org.neuro.spike.model.DecisionEvent;
import org.neuro.spike.model.DerivedResult;
import org.neuro.spike.model.Diagnostic;
import org.neuro.spike.model.Evidence;
import org.neuro.spike.model.FeaturePoint;
import org.neuro.spike.model.FeatureVersion;
import org.neuro.spike.model.LineageLink;
import org.neuro.spike.model.LineageRelation;
import org.neuro.spike.model.Material;
import org.neuro.spike.model.MaterialType;
import org.neuro.spike.model.Proposal;
import org.neuro.spike.model.ResultStatus;
import org.neuro.spike.util.Json;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite-backed provenance archive. The store is intentionally dumb: it
 * preserves layers and order; business rules live in the engine so both the
 * HTTP layer and tests invoke the same code paths.
 */
public final class SpikeStore implements AutoCloseable {
    private final Connection connection;

    public SpikeStore(String path) {
        try {
            String url = "jdbc:sqlite:" + path;
            this.connection = DriverManager.getConnection(url);
            this.connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA foreign_keys=ON");
                for (String ddl : Schema.DDL) {
                    st.execute(ddl);
                }
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open store: " + path, e);
        }
    }

    public static SpikeStore inMemory() {
        return new SpikeStore(":memory:");
    }

    public Connection raw() {
        return connection;
    }

    // ---- ordering -----------------------------------------------------

    public int nextSeq() {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT next_seq FROM seq_counter WHERE id=1")) {
            rs.next();
            int value = rs.getInt(1);
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE seq_counter SET next_seq = ? WHERE id=1")) {
                ps.setInt(1, value + 1);
                ps.executeUpdate();
            }
            return value;
        } catch (SQLException e) {
            throw new IllegalStateException("seq allocation failed", e);
        }
    }

    public int headSeq() {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(seq) FROM ("
                     + "SELECT seq FROM material UNION ALL SELECT seq FROM feature_version "
                     + "UNION ALL SELECT seq FROM cluster UNION ALL SELECT seq FROM decision "
                     + "UNION ALL SELECT seq FROM event_log UNION ALL SELECT seq FROM derived_result "
                     + "UNION ALL SELECT seq FROM evidence UNION ALL SELECT seq FROM proposal "
                     + "UNION ALL SELECT seq FROM cluster_lineage)")) {
            rs.next();
            int v = rs.getInt(1);
            return rs.wasNull() ? 0 : v;
        } catch (SQLException e) {
            throw new IllegalStateException("head seq failed", e);
        }
    }

    public void commit() {
        try {
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("commit failed", e);
        }
    }

    // ---- half-write intents ------------------------------------------

    public String beginIntent(String detail) {
        int seq = nextSeq();
        String intentId = "int:" + seq;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO write_intent(intent_id, started_seq, committed, detail) VALUES(?,?,0,?)")) {
            ps.setString(1, intentId);
            ps.setInt(2, seq);
            ps.setString(3, detail);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("begin intent failed", e);
        }
        return intentId;
    }

    public void commitIntent(String intentId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE write_intent SET committed=1 WHERE intent_id=?")) {
            ps.setString(1, intentId);
            ps.executeUpdate();
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("commit intent failed", e);
        }
    }

    /** @return ids of intents that were started but never committed (crash mid-write). */
    public List<String> openIntents() {
        List<String> ids = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT intent_id FROM write_intent WHERE committed=0 ORDER BY started_seq")) {
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("open intents failed", e);
        }
        return ids;
    }

    // ---- materials (immutable layer) ---------------------------------

    public void insertMaterial(Material m) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO material(uid,type,payload_json,unit,frame,diagnostics_json,seq) "
                        + "VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, m.uid());
            ps.setString(2, m.type().name());
            ps.setString(3, m.payloadJson());
            ps.setString(4, m.unit());
            ps.setString(5, m.frame());
            ps.setString(6, Json.write(m.diagnostics() == null ? List.of() : m.diagnostics()));
            ps.setInt(7, m.seq());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("insert material failed: " + m.uid(), e);
        }
    }

    public List<Material> materialsAt(int atSeq) {
        List<Material> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uid,type,payload_json,unit,frame,diagnostics_json,seq FROM material "
                        + "WHERE seq<=? ORDER BY seq,uid")) {
            ps.setInt(1, atSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Material(
                            rs.getString(1),
                            MaterialType.valueOf(rs.getString(2)),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getString(5),
                            Json.parse(rs.getString(6), Json.MAPPER.getTypeFactory()
                                    .constructCollectionType(List.class, Diagnostic.class)),
                            rs.getInt(7)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("read materials failed", e);
        }
        return out;
    }

    public Optional<Material> material(String uid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uid,type,payload_json,unit,frame,diagnostics_json,seq FROM material WHERE uid=?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Material(
                        rs.getString(1),
                        MaterialType.valueOf(rs.getString(2)),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        Json.parse(rs.getString(6), Json.MAPPER.getTypeFactory()
                                .constructCollectionType(List.class, Diagnostic.class)),
                        rs.getInt(7)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("read material failed", e);
        }
    }

    // ---- feature versions + points -----------------------------------

    public void insertFeatureVersion(FeatureVersion fv) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO feature_version(uid,name,revision,spec_json,seq) VALUES(?,?,?,?,?)")) {
            ps.setString(1, fv.uid());
            ps.setString(2, fv.name());
            ps.setInt(3, fv.revision());
            ps.setString(4, fv.specJson());
            ps.setInt(5, fv.seq());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("insert feature version failed", e);
        }
    }

    public List<FeatureVersion> featureVersionsAt(int atSeq) {
        List<FeatureVersion> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uid,name,revision,spec_json,seq FROM feature_version WHERE seq<=? ORDER BY seq")) {
            ps.setInt(1, atSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new FeatureVersion(rs.getString(1), rs.getString(2),
                            rs.getInt(3), rs.getString(4), rs.getInt(5)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("read feature versions failed", e);
        }
        return out;
    }

    public void insertFeaturePoint(FeaturePoint p) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO feature_point(uid,feature_version,event_uid,x,y,seq) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, p.uid());
            ps.setString(2, p.featureVersion());
            ps.setString(3, p.eventUid());
            ps.setDouble(4, p.x());
            ps.setDouble(5, p.y());
            ps.setInt(6, p.seq());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("insert feature point failed", e);
        }
    }

    public List<FeaturePoint> featurePointsAt(String featureVersion, int atSeq) {
        List<FeaturePoint> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uid,feature_version,event_uid,x,y,seq FROM feature_point "
                        + "WHERE feature_version=? AND seq<=? ORDER BY event_uid")) {
            ps.setString(1, featureVersion);
            ps.setInt(2, atSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new FeaturePoint(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getDouble(4), rs.getDouble(5), rs.getInt(6)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("read feature points failed", e);
        }
        return out;
    }

    // ---- clusters (labels; uid is the stable identity) ---------------

    public void insertCluster(Cluster c, String batch) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cluster(uid,label,param_version,feature_version,t_start,t_end,"
                        + "members_json,batch,seq) VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, c.uid());
            ps.setInt(2, c.label());
            ps.setString(3, c.paramVersion());
            ps.setString(4, c.featureVersion());
            if (c.tStart() < 0) {
                ps.setNull(5, java.sql.Types.REAL);
            } else {
                ps.setDouble(5, c.tStart());
            }
            if (c.tEnd() < 0) {
                ps.setNull(6, java.sql.Types.REAL);
            } else {
                ps.setDouble(6, c.tEnd());
            }
            ps.setString(7, Json.write(c.members()));
            ps.setString(8, batch);
            ps.setInt(9, c.seq());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("insert cluster failed: " + c.uid(), e);
        }
    }

    public List<Cluster> clustersAt(int atSeq) {
        List<Cluster> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uid,label,param_version,feature_version,t_start,t_end,members_json,seq "
                        + "FROM cluster WHERE seq<=? ORDER BY seq,label")) {
            ps.setInt(1, atSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Double ts = rs.getObject(5, Double.class);
                    Double te = rs.getObject(6, Double.class);
                    out.add(new Cluster(rs.getString(1), rs.getInt(2), rs.getString(3),
                            rs.getString(4),
                            ts == null ? -1 : ts,
                            te == null ? -1 : te,
                            Json.parse(rs.getString(7), Json.MAPPER.getTypeFactory()
                                    .constructCollectionType(List.class, String.class)),
                            rs.getInt(8)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("read clusters failed", e);
        }
    }

    public int latestClusterSeq() {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(seq),0) FROM cluster")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("latest cluster seq failed", e);
        }
    }

    // ---- lineage ------------------------------------------------------

    public void insertLineage(LineageLink link) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cluster_lineage(uid,relation,parents_json,children_json,"
                        + "shared_events_json,rationale,seq) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, link.uid());
            ps.setString(2, link.relation().name());
            ps.setString(3, Json.write(link.parentClusters()));
            ps.setString(4, Json.write(link.childClusters()));
            ps.setString(5, Json.write(link.sharedEvents()));
            ps.setString(6, link.rationale());
            ps.setInt(7, link.seq());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("insert lineage failed", e);
        }
    }

    public List<LineageLink> lineageAt(int atSeq) {
        List<LineageLink> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uid,relation,parents_json,children_json,shared_events_json,rationale,seq "
                        + "FROM cluster_lineage WHERE seq<=? ORDER BY seq")) {
            ps.setInt(1, atSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LineageLink(
                            rs.getString(1),
                            LineageRelation.valueOf(rs.getString(2)),
                            Json.parse(rs.getString(3), Json.MAPPER.getTypeFactory()
                                    .constructCollectionType(List.class, String.class)),
                            Json.parse(rs.getString(4), Json.MAPPER.getTypeFactory()
                                    .constructCollectionType(List.class, String.class)),
                            Json.parse(rs.getString(5), Json.MAPPER.getTypeFactory()
                                    .constructCollectionType(List.class, String.class)),
                            rs.getString(6),
                            rs.getInt(7)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("read lineage failed", e);
        }
    }

    // ---- proposals ----------------------------------------------------

    public void insertProposal(Proposal p) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO proposal(uid,kind,cluster_uids_json,candidates_json,detail_json,status,seq) "
                        + "VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, p.uid());
            ps.setString(2, p.kind());
            ps.setString(3, Json.write(p.clusterUids()));
            ps.setString(4, Json.write(p.candidates()));
            ps.setString(5, p.detailJson());
            ps.setString(6, p.status());
            ps.setInt(7, p.seq());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("insert proposal failed", e);
        }
    }

    public void updateProposalStatus(String uid, String status) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE proposal SET status=? WHERE uid=?")) {
            ps.setString(1, status);
            ps.setString(2, uid);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("update proposal failed", e);
        }
    }

    public List<Proposal> proposalsAt(int atSeq) {
        List<Proposal> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uid,kind,cluster_uids_json,candidates_json,detail_json,status,seq "
                        + "FROM proposal WHERE seq<=? ORDER BY seq")) {
            ps.setInt(1, atSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Proposal(
                            rs.getString(1), rs.getString(2),
                            Json.parse(rs.getString(3), Json.MAPPER.getTypeFactory()
                                    .constructCollectionType(List.class, String.class)),
                            Json.parse(rs.getString(4), Json.MAPPER.getTypeFactory()
                                    .constructCollectionType(List.class, List.class)),
                            rs.getString(5), rs.getString(6), rs.getInt(7)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("read proposals failed", e);
        }
    }

    // ---- decisions (append-only, mutable layer) ----------------------

    public void insertDecision(DecisionEvent d) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO decision(uid,action,target_proposal,subject_cluster,payload_json,"
                        + "decision_version,intent_id,seq) VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, d.uid());
            ps.setString(2, d.action());
            ps.setString(3, d.targetProposal());
            ps.setString(4, d.subjectCluster());
            ps.setString(5, d.payloadJson());
            ps.setString(6, d.decisionVersion());
            ps.setString(7, d.intentId());
            ps.setInt(8, d.seq());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("insert decision failed", e);
        }
    }

    public List<DecisionEvent> decisionsAt(int atSeq) {
        List<DecisionEvent> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uid,action,target_proposal,subject_cluster,payload_json,decision_version,"
                        + "intent_id,seq FROM decision WHERE seq<=? ORDER BY seq")) {
            ps.setInt(1, atSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new DecisionEvent(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(7), rs.getInt(8)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("read decisions failed", e);
        }
    }

    // ---- evidence -----------------------------------------------------

    public void insertEvidence(Evidence e) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO evidence(uid,kind,cluster_uid,detail_json,strength,seq) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, e.uid());
            ps.setString(2, e.kind().name());
            ps.setString(3, e.clusterUid());
            ps.setString(4, e.detailJson());
            ps.setDouble(5, e.strength());
            ps.setInt(6, e.seq());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("insert evidence failed", e);
        }
    }

    public List<Evidence> evidenceAt(int atSeq) {
        List<Evidence> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uid,kind,cluster_uid,detail_json,strength,seq FROM evidence WHERE seq<=? ORDER BY seq")) {
            ps.setInt(1, atSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Evidence(rs.getString(1),
                            org.neuro.spike.model.EvidenceKind.valueOf(rs.getString(2)),
                            rs.getString(3), rs.getString(4), rs.getDouble(5), rs.getInt(6)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("read evidence failed", e);
        }
    }

    public void deleteEvidenceAfter(int seqExclusive) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM evidence WHERE seq>=?")) {
            ps.setInt(1, seqExclusive);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("delete evidence failed", e);
        }
    }

    // ---- event log ----------------------------------------------------

    public void insertEvent(EventLog e) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO event_log(uid,type,subject_uid,payload_json,basis,derivation_id,seq) "
                        + "VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, e.uid());
            ps.setString(2, e.type());
            ps.setString(3, e.subjectUid());
            ps.setString(4, e.payloadJson());
            ps.setString(5, e.basis());
            ps.setString(6, e.derivationId());
            ps.setString(7, e.seq());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("insert event failed", e);
        }
    }

    public List<EventLog> eventsAt(int atSeq) {
        List<EventLog> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uid,type,subject_uid,payload_json,basis,derivation_id,seq FROM event_log "
                        + "WHERE seq<=? ORDER BY seq")) {
            ps.setInt(1, atSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new EventLog(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("read events failed", e);
        }
    }

    // ---- derived results ---------------------------------------------

    public void insertDerived(DerivedResult r) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO derived_result(uid,kind,target_uid,input_digest,param_version,"
                        + "feature_version,parents_json,status,output_json,diagnostics_json,"
                        + "budget_limit,budget_used,t_start,t_end,window_key,seq) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, r.uid());
            ps.setString(2, r.kind());
            ps.setString(3, r.targetUid());
            ps.setString(4, r.inputDigest());
            ps.setString(5, r.paramVersion());
            ps.setString(6, r.featureVersion());
            ps.setString(7, Json.write(r.parents()));
            ps.setString(8, r.status().name());
            ps.setString(9, r.outputJson());
            ps.setString(10, Json.write(r.diagnostics() == null ? List.of() : r.diagnostics()));
            ps.setInt(11, r.budgetLimit());
            ps.setInt(12, r.budgetUsed());
            if (r.tStart() < 0) {
                ps.setNull(13, java.sql.Types.INTEGER);
            } else {
                ps.setInt(13, r.tStart());
            }
            if (r.tEnd() < 0) {
                ps.setNull(14, java.sql.Types.INTEGER);
            } else {
                ps.setInt(14, r.tEnd());
            }
            ps.setString(15, r.windowKey());
            ps.setInt(16, r.seq());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("insert derived failed", e);
        }
    }

    public List<DerivedResult> derivedAt(int atSeq) {
        List<DerivedResult> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uid,kind,target_uid,input_digest,param_version,feature_version,parents_json,"
                        + "status,output_json,diagnostics_json,budget_limit,budget_used,t_start,t_end,"
                        + "window_key,seq FROM derived_result WHERE seq<=? ORDER BY seq")) {
            ps.setInt(1, atSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Integer ts = rs.getObject(13, Integer.class);
                    Integer te = rs.getObject(14, Integer.class);
                    out.add(new DerivedResult(
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6),
                            Json.parse(rs.getString(7), Json.MAPPER.getTypeFactory()
                                    .constructCollectionType(List.class, String.class)),
                            ResultStatus.valueOf(rs.getString(8)),
                            rs.getString(9),
                            Json.parse(rs.getString(10), Json.MAPPER.getTypeFactory()
                                    .constructCollectionType(List.class, Diagnostic.class)),
                            rs.getInt(11), rs.getInt(12),
                            ts == null ? -1 : ts,
                            te == null ? -1 : te,
                            rs.getString(15), rs.getInt(16)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("read derived failed", e);
        }
    }

    public void deleteDerivedForTargets(java.util.Collection<String> targetUids, int afterSeq) {
        if (targetUids.isEmpty()) {
            return;
        }
        String in = String.join(",", targetUids.stream().map(x -> "?").toList());
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM derived_result WHERE seq>=? AND target_uid IN (" + in + ")")) {
            ps.setInt(1, afterSeq);
            int i = 2;
            for (String t : targetUids) {
                ps.setString(i++, t);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("delete derived failed", e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("close failed", e);
        }
    }
}
