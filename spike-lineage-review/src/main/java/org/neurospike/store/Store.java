package org.neurospike.store;

import org.neurospike.domain.Json;
import org.neurospike.domain.Diagnostic;
import org.neurospike.domain.Models.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SQLite-backed storage layer. All persistence rules live here and are unit-testable
 * directly; no business rule relies on the browser.
 */
public class Store implements AutoCloseable {
    private final Database db;

    public Store(Database db) {
        this.db = db;
        db.migrate();
    }

    public Database database() {
        return db;
    }

    public Connection open() {
        return db.connect();
    }

    public interface Tx<T> {
        T run(Connection c) throws SQLException;
    }

    public <T> T tx(Tx<T> body) {
        try (Connection c = db.connect()) {
            c.setAutoCommit(false);
            try {
                T out = body.run(c);
                c.commit();
                return out;
            } catch (RuntimeException | SQLException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("transaction failed", e);
        }
    }

    // ---- meta ----
    public void putMeta(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public Optional<String> meta(String key) {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- immutable material layer ----
    public void insertWaveform(Connection c, WaveformRef w) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO waveform_ref VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, w.refId());
            ps.setString(2, w.uri());
            ps.setString(3, w.checksum());
            ps.setString(4, w.format());
            ps.setString(5, w.timeOrigin());
            ps.setString(6, w.timeUnit());
            ps.setDouble(7, w.sampleRateHz());
            ps.setInt(8, w.sampleCount());
            ps.setLong(9, w.sampleIndex());
            ps.setInt(10, w.sampleLength());
            ps.setString(11, Json.write(w.channelGains()));
            ps.executeUpdate();
        }
    }

    public void insertSite(Connection c, String refId, Site s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO site VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, refId);
            ps.setInt(2, s.channel());
            ps.setString(3, s.x() == null ? null : s.x().raw());
            ps.setString(4, s.y() == null ? null : s.y().raw());
            ps.setString(5, s.z() == null ? null : s.z().raw());
            setDouble(ps, 6, s.x() == null ? null : s.x().value());
            setDouble(ps, 7, s.y() == null ? null : s.y().value());
            setDouble(ps, 8, s.z() == null ? null : s.z().value());
            String unit = s.x() != null && s.x().unit() != null ? s.x().unit()
                    : (s.y() != null ? s.y().unit() : "");
            ps.setString(9, unit);
            ps.setString(10, s.frame());
            ps.setInt(11, allConvertible(s) ? 1 : 0);
            ps.setString(12, firstDiagnostic(s));
            ps.executeUpdate();
        }
    }

    public void insertThreshold(Connection c, Threshold t) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO threshold VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, t.thresholdId());
            ps.setInt(2, t.channel());
            ps.setString(3, t.value() == null ? null : t.value().raw());
            setDouble(ps, 4, t.value() == null ? null : t.value().value());
            ps.setString(5, t.value() == null ? "" : t.value().unit());
            ps.setString(6, t.value() == null ? "" : t.value().reference());
            ps.setString(7, t.rule());
            ps.setString(8, t.featureVersion());
            ps.setInt(9, t.value() != null && t.value().convertible() ? 1 : 0);
            ps.setString(10, t.value() == null ? null : t.value().diagnostic());
            ps.executeUpdate();
        }
    }

    public void insertSpike(Connection c, SpikeEvent s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO spike VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, s.eventId());
            ps.setString(2, s.time() == null ? null : s.time().raw());
            setDouble(ps, 3, s.time() == null ? null : s.time().value());
            ps.setString(4, s.time() == null ? "" : s.time().unit());
            ps.setString(5, s.time() == null ? "" : s.time().reference());
            ps.setInt(6, s.convertibleTime() ? 1 : 0);
            ps.setInt(7, s.primaryChannel());
            ps.setString(8, s.waveformRefId());
            ps.setLong(9, s.sampleIndex());
            ps.setInt(10, s.sampleLength());
            ps.setString(11, Json.write(s.channels()));
            ps.setString(12, Json.write(s.measurements()));
            ps.setString(13, s.time() == null ? null : s.time().diagnostic());
            ps.executeUpdate();
        }
    }

    public void insertFeature(Connection c, FeatureSet f) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO feature_set VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, f.featureId());
            ps.setLong(2, 0);
            ps.setString(3, f.version());
            ps.setString(4, f.basis());
            ps.setString(5, Json.write(f.values()));
            ps.setString(6, f.diagnostic());
            ps.executeUpdate();
        }
    }

    public void bindFeatureToEvent(Connection c, String featureId, long eventId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE feature_set SET event_id=? WHERE feature_id=?")) {
            ps.setLong(1, eventId);
            ps.setString(2, featureId);
            ps.executeUpdate();
        }
    }

    private static boolean allConvertible(Site s) {
        return (s.x() == null || s.x().convertible()) && (s.y() == null || s.y().convertible())
                && (s.z() == null || s.z().convertible());
    }

    private static String firstDiagnostic(Site s) {
        if (s.x() != null && !s.x().convertible()) {
            return s.x().diagnostic();
        }
        if (s.y() != null && !s.y().convertible()) {
            return s.y().diagnostic();
        }
        if (s.z() != null && !s.z().convertible()) {
            return s.z().diagnostic();
        }
        return null;
    }

    private static void setDouble(PreparedStatement ps, int idx, Double v) throws SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.REAL);
        } else {
            ps.setDouble(idx, v);
        }
    }

    // ---- clustering / derived layer ----
    public void insertClusterVersion(Connection c, ClusterVersion v) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO cluster_version VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, v.clusterId());
            ps.setString(2, v.version());
            ps.setInt(3, v.seq());
            ps.setString(4, v.featureVersion());
            ps.setString(5, v.thresholdId());
            ps.setString(6, v.timeWindowStart());
            ps.setString(7, v.timeWindowEnd());
            ps.setString(8, Json.write(v.params()));
            ps.setString(9, v.inputDigest());
            ps.setString(10, Json.write(v.parents()));
            ps.setString(11, v.createdAt());
            ps.executeUpdate();
        }
    }

    public void insertUnit(Connection c, Unit u) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO unit VALUES(?,?,?,?,?)")) {
            ps.setString(1, u.unitId());
            ps.setString(2, u.firstSeenCluster());
            ps.setInt(3, u.firstLabel());
            ps.setString(4, u.status());
            ps.setString(5, u.note());
            ps.executeUpdate();
        }
    }

    public void insertAssignment(Connection c, Assignment a) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO assignment VALUES(?,?,?,?,?)")) {
            ps.setString(1, a.clusterId());
            ps.setLong(2, a.eventId());
            ps.setString(3, a.unitId());
            ps.setInt(4, a.label());
            ps.setDouble(5, a.score());
            ps.executeUpdate();
        }
    }

    public void insertLineage(Connection c, LineageEdge e, String clusterId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO lineage_edge VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, e.edgeId());
            ps.setString(2, e.fromId());
            ps.setString(3, e.toId());
            ps.setString(4, e.relation());
            ps.setLong(5, e.sharedEvents());
            ps.setString(6, e.viaDecisionId());
            ps.setString(7, clusterId);
            ps.executeUpdate();
        }
    }

    public void insertDecision(Connection c, DecisionEvent d) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO decision_event VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, d.decisionId());
            ps.setLong(2, d.seq());
            ps.setString(3, d.type());
            ps.setString(4, d.actor());
            ps.setString(5, d.atEvent());
            ps.setString(6, Json.write(d.payload()));
            ps.setString(7, d.basisRuleId());
            ps.setString(8, d.parentClusterId());
            ps.setString(9, d.adopted());
            ps.setString(10, d.createdAt());
            ps.executeUpdate();
        }
    }

    public void insertDerived(Connection c, DerivedResult r) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO derived_result VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, r.resultId());
            ps.setString(2, r.kind());
            ps.setString(3, r.scope());
            ps.setString(4, r.clusterId());
            ps.setString(5, r.unitId());
            ps.setString(6, r.windowStart());
            ps.setString(7, r.windowEnd());
            ps.setString(8, r.inputDigest());
            ps.setString(9, r.paramVersion());
            ps.setString(10, Json.write(r.parents()));
            ps.setString(11, Json.write(r.content()));
            ps.setString(12, r.createdAt());
            ps.executeUpdate();
        }
    }

    public void insertDiagnostic(Connection c, String stage, Diagnostic d) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO diagnostic_record(stage,code,file,line,column,message,raw_value,context_json) "
                        + "VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, stage);
            ps.setString(2, d.code());
            ps.setString(3, d.file());
            ps.setInt(4, d.line());
            ps.setInt(5, d.column());
            ps.setString(6, d.message());
            ps.setString(7, d.rawValue());
            ps.setString(8, Json.write(d.context() == null ? Map.of() : d.context()));
            ps.executeUpdate();
        }
    }

    public void insertReplayStep(Connection c, ReplayStep s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO replay_step(seq,stage,scenario,input_json,decision_state,affected_json,result_id) "
                        + "VALUES(?,?,?,?,?,?,?)")) {
            ps.setInt(1, s.seq());
            ps.setString(2, s.stage());
            ps.setString(3, s.scenario());
            ps.setString(4, Json.write(s.inputFragment()));
            ps.setString(5, s.decisionState());
            ps.setString(6, Json.write(s.affectedObjectIds()));
            ps.setString(7, s.resultId());
            ps.executeUpdate();
        }
    }

    public void insertArchiveCheck(Connection c, ArchiveCheck a) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO archive_check VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, a.checkId());
            ps.setString(2, a.scenario());
            ps.setString(3, a.stableIdentity());
            ps.setString(4, a.claim());
            ps.setString(5, a.verdict());
            ps.setString(6, a.basis());
            ps.setString(7, Json.write(a.evidence()));
            ps.setString(8, a.adoptedResultId());
            ps.setString(9, a.rejectedResultId());
            ps.setString(10, a.createdAt());
            ps.executeUpdate();
        }
    }

    // ---- queries ----
    public List<SpikeEvent> spikes(String clusterId) {
        List<SpikeEvent> out = new ArrayList<>();
        String sql = clusterId == null
                ? "SELECT * FROM spike ORDER BY event_id"
                : "SELECT s.* FROM spike s JOIN assignment a ON a.event_id=s.event_id "
                        + "WHERE a.cluster_id=? ORDER BY s.event_id";
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (clusterId != null) {
                ps.setString(1, clusterId);
            }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(mapSpike(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<SpikeEvent> allSpikes() {
        return spikes(null);
    }

    public SpikeEvent spike(long eventId) {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM spike WHERE event_id=?")) {
            ps.setLong(1, eventId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? mapSpike(rs) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<WaveformRef> waveforms() {
        List<WaveformRef> out = new ArrayList<>();
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM waveform_ref ORDER BY ref_id"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                List<Quant> gains = Json.list(rs.getString(11)).stream()
                        .map(m -> Json.MAPPER.convertValue(m, Quant.class)).toList();
                out.add(new WaveformRef(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getDouble(7),
                        rs.getInt(8), rs.getLong(9), rs.getInt(10), gains));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<ClusterVersion> clusterVersions() {
        List<ClusterVersion> out = new ArrayList<>();
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM cluster_version ORDER BY seq"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new ClusterVersion(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        Json.map(rs.getString(8)), rs.getString(9), Json.list(rs.getString(10))
                        .stream().map(x -> (String) x).toList(), rs.getString(11)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public ClusterVersion clusterVersion(String clusterId) {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM cluster_version WHERE cluster_id=?")) {
            ps.setString(1, clusterId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? new ClusterVersion(rs.getString(1), rs.getString(2), rs.getInt(3),
                    rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                    Json.map(rs.getString(8)), rs.getString(9),
                    Json.list(rs.getString(10)).stream().map(x -> (String) x).toList(),
                    rs.getString(11)) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Unit> units() {
        List<Unit> out = new ArrayList<>();
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM unit ORDER BY unit_id"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Unit(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getString(5)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /** Returns rows (clusterId,eventId,unitId,label,score). */
    public List<Assignment> assignments(String clusterId) {
        List<Assignment> out = new ArrayList<>();
        String sql = "SELECT cluster_id,event_id,unit_id,label,score FROM assignment"
                + (clusterId == null ? "" : " WHERE cluster_id=?")
                + " ORDER BY event_id, unit_id";
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (clusterId != null) {
                ps.setString(1, clusterId);
            }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(new Assignment(rs.getLong(2), rs.getString(3), rs.getString(1),
                        rs.getInt(4), rs.getDouble(5)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<DecisionEvent> decisions() {
        List<DecisionEvent> out = new ArrayList<>();
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM decision_event ORDER BY seq"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new DecisionEvent(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), Json.map(rs.getString(6)),
                        rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<DerivedResult> derivedByCluster(String clusterId) {
        List<DerivedResult> out = new ArrayList<>();
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM derived_result WHERE cluster_id=? ORDER BY kind, unit_id, scope")) {
            ps.setString(1, clusterId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(mapDerived(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public DerivedResult derived(String resultId) {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM derived_result WHERE result_id=?")) {
            ps.setString(1, resultId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? mapDerived(rs) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<DerivedResult> derivedByKind(String kind) {
        List<DerivedResult> out = new ArrayList<>();
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM derived_result WHERE kind=? ORDER BY result_id")) {
            ps.setString(1, kind);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(mapDerived(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<LineageEdge> lineage() {
        List<LineageEdge> out = new ArrayList<>();
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT edge_id,from_id,to_id,relation,shared_events,via_decision_id FROM lineage_edge "
                        + "ORDER BY edge_id"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new LineageEdge(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.getString(6)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<ArchiveCheck> archiveChecks() {
        List<ArchiveCheck> out = new ArrayList<>();
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM archive_check ORDER BY check_id"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new ArchiveCheck(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), Json.map(rs.getString(7)),
                        rs.getString(8), rs.getString(9), rs.getString(10)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<ReplayStep> replaySteps() {
        List<ReplayStep> out = new ArrayList<>();
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT seq,stage,scenario,input_json,decision_state,affected_json,result_id "
                        + "FROM replay_step ORDER BY seq"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new ReplayStep(rs.getInt(1), rs.getString(2), rs.getString(3),
                        Json.map(rs.getString(4)), rs.getString(5),
                        Json.list(rs.getString(6)).stream().map(x -> (String) x).toList(),
                        rs.getString(7)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<Map<String, Object>> diagnostics() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT stage,code,file,line,column,message,raw_value FROM diagnostic_record ORDER BY diag_id");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("stage", rs.getString(1));
                row.put("code", rs.getString(2));
                row.put("file", rs.getString(3));
                row.put("line", rs.getInt(4));
                row.put("column", rs.getInt(5));
                row.put("message", rs.getString(6));
                row.put("rawValue", rs.getString(7));
                out.add(row);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public long count(String table) {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM " + table); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static SpikeEvent mapSpike(ResultSet rs) throws SQLException {
        Quant time = new Quant(rs.getString("time_raw"),
                rs.getObject("time_value") == null ? null : rs.getDouble("time_value"),
                rs.getString("time_unit"), rs.getString("time_origin"),
                rs.getInt("time_convertible") == 1, rs.getString("time_diagnostic"));
        Map<String, Object> rawMeas = Json.map(rs.getString("measurements_json"));
        Map<String, Quant> meas = new java.util.LinkedHashMap<>();
        rawMeas.forEach((k, v) -> meas.put(k, Json.MAPPER.convertValue(v, Quant.class)));
        return new SpikeEvent(rs.getLong("event_id"), time, rs.getInt("primary_channel"),
                rs.getString("waveform_ref_id"), rs.getLong("sample_index"),
                rs.getInt("sample_length"), meas,
                Json.list(rs.getString("channels_json")).stream().map(x -> (Integer) x).toList(),
                rs.getInt("time_convertible") == 1);
    }

    private static DerivedResult mapDerived(ResultSet rs) throws SQLException {
        return new DerivedResult(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9),
                Json.list(rs.getString(10)).stream().map(x -> (String) x).toList(),
                Json.map(rs.getString(11)), rs.getString(12));
    }

    @Override
    public void close() {
    }
}
