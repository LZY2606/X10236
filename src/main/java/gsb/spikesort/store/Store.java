package gsb.spikesort.store;

import com.fasterxml.jackson.databind.JsonNode;
import gsb.spikesort.model.Json;
import gsb.spikesort.model.Model.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** DAO over the layered schema. Derived artifacts are versioned by event
 *  sequence so any past adjudication event can be re-entered with the
 *  dependency versions and conclusions visible at that time. */
public final class Store {
    private final Database db;

    public Store(Database db) { this.db = db; }

    public void begin() { db.begin(); }
    public void commit() { db.commit(); }
    public void rollbackQuietly() { db.rollbackQuietly(); }

    // ---------------- meta ----------------
    public void putMeta(String key, String value) {
        exec("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                key, value);
    }

    public Optional<String> meta(String key) {
        return queryOne("SELECT value FROM meta WHERE key=?", rs -> rs.getString(1), key);
    }

    // ---------------- waveform refs ----------------
    public void insertWaveformRef(WaveformRef w) {
        exec("INSERT INTO waveform_refs(id,channel,t_start_ms,t_end_ms,sampling_rate_hz,amplitude_unit,coord_frame,source_uri,raw_repr,digest) VALUES(?,?,?,?,?,?,?,?,?,?)",
                w.id(), w.channel(), w.tStartMs(), w.tEndMs(), w.samplingRateHz(),
                w.amplitudeUnit(), w.coordFrame(), w.sourceUri(), w.rawRepr(), w.digest());
    }

    public List<WaveformRef> waveformRefs() {
        return query("SELECT * FROM waveform_refs ORDER BY t_start_ms, channel, id", rs -> new WaveformRef(
                rs.getString("id"), rs.getInt("channel"), rs.getDouble("t_start_ms"),
                rs.getDouble("t_end_ms"), rs.getDouble("sampling_rate_hz"),
                rs.getString("amplitude_unit"), rs.getString("coord_frame"),
                rs.getString("source_uri"), rs.getString("raw_repr"), rs.getString("digest")));
    }

    // ---------------- probe geometry ----------------
    public void insertProbeGeometry(ProbeGeometry g) {
        exec("INSERT INTO probe_geometries(id,version,frame,sites_json,digest) VALUES(?,?,?,?,?)",
                g.id(), g.version(), g.frame(), Json.write(g.sites()), g.digest());
    }

    public List<ProbeGeometry> probeGeometries() {
        return query("SELECT * FROM probe_geometries ORDER BY version", rs -> new ProbeGeometry(
                rs.getString("id"), rs.getInt("version"), rs.getString("frame"),
                Json.MAPPER.convertValue(Json.read(rs.getString("sites_json")),
                        Json.MAPPER.getTypeFactory().constructCollectionType(List.class, ProbeGeometry.Site.class)),
                rs.getString("digest")));
    }

    public Optional<ProbeGeometry> latestProbeGeometry() {
        List<ProbeGeometry> all = probeGeometries();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    // ---------------- detection params ----------------
    public void insertDetectionParams(DetectionParams p) {
        exec("INSERT INTO detection_params(id,version,threshold_uv,refractory_ms,window_start_ms,window_end_ms,digest) VALUES(?,?,?,?,?,?,?)",
                p.id(), p.version(), p.thresholdUv(), p.refractoryMs(),
                p.windowStartMs(), p.windowEndMs(), p.digest());
    }

    public List<DetectionParams> detectionParams() {
        return query("SELECT * FROM detection_params ORDER BY version", rs -> new DetectionParams(
                rs.getString("id"), rs.getInt("version"), rs.getDouble("threshold_uv"),
                rs.getDouble("refractory_ms"), rs.getDouble("window_start_ms"),
                rs.getDouble("window_end_ms"), rs.getString("digest")));
    }

    public Optional<DetectionParams> latestDetectionParams() {
        List<DetectionParams> all = detectionParams();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    // ---------------- feature sets ----------------
    public void insertFeatureSet(FeatureSet f) {
        exec("INSERT INTO feature_sets(id,version,method,input_digest,digest) VALUES(?,?,?,?,?)",
                f.id(), f.version(), f.method(), f.inputDigest(), f.digest());
    }

    public List<FeatureSet> featureSets() {
        return query("SELECT * FROM feature_sets ORDER BY version", rs -> new FeatureSet(
                rs.getString("id"), rs.getInt("version"), rs.getString("method"),
                rs.getString("input_digest"), rs.getString("digest")));
    }

    // ---------------- units & labels ----------------
    public void insertUnit(Unit u) {
        exec("INSERT INTO units(id,created_by_event,status) VALUES(?,?,?)",
                u.id(), u.createdByEvent(), u.status());
    }

    public void updateUnitStatus(String unitId, String status) {
        exec("UPDATE units SET status=? WHERE id=?", status, unitId);
    }

    public List<Unit> units() {
        return query("SELECT * FROM units ORDER BY id", rs -> new Unit(
                rs.getString("id"), rs.getString("created_by_event"), rs.getString("status")));
    }

    public void insertClusterLabel(ClusterLabel l) {
        exec("INSERT INTO cluster_labels(id,feature_set_id,cluster_index,unit_id,assigned_event_id) VALUES(?,?,?,?,?)",
                l.id(), l.featureSetId(), l.clusterIndex(), l.unitId(), l.assignedEventId());
    }

    public List<ClusterLabel> clusterLabels() {
        return query("SELECT * FROM cluster_labels ORDER BY feature_set_id, cluster_index, unit_id",
                rs -> new ClusterLabel(rs.getString("id"), rs.getString("feature_set_id"),
                        rs.getInt("cluster_index"), rs.getString("unit_id"),
                        rs.getString("assigned_event_id")));
    }

    // ---------------- spikes ----------------
    public void insertSpike(SpikeEvent s) {
        exec("INSERT INTO spike_events(id,time_ms,channel,amplitude_raw,amplitude_uv,unit_id,waveform_ref,candidate_units_json) VALUES(?,?,?,?,?,?,?,?)",
                s.id(), s.timeMs(), s.channel(), s.amplitudeRaw(), s.amplitudeUv(),
                s.unitId(), s.waveformRefId(), Json.write(s.candidateUnitIds()));
    }

    public void updateSpikeUnit(String spikeId, String unitId, List<String> candidates) {
        exec("UPDATE spike_events SET unit_id=?, candidate_units_json=? WHERE id=?",
                unitId, Json.write(candidates), spikeId);
    }

    public void updateSpikeAmplitude(String spikeId, String amplitudeRaw, Double amplitudeUv) {
        exec("UPDATE spike_events SET amplitude_raw=?, amplitude_uv=? WHERE id=?",
                amplitudeRaw, amplitudeUv, spikeId);
    }

    /** Deterministic order: equivalent input sets always serialize identically. */
    public List<SpikeEvent> spikes() {
        return query("SELECT * FROM spike_events ORDER BY time_ms, channel, id", Store::readSpike);
    }

    public List<SpikeEvent> spikesOfUnit(String unitId) {
        return query("SELECT * FROM spike_events WHERE unit_id=? ORDER BY time_ms, channel, id",
                Store::readSpike, unitId);
    }

    public List<SpikeEvent> spikesInWindow(double startMs, double endMs) {
        return query("SELECT * FROM spike_events WHERE time_ms>=? AND time_ms<? ORDER BY time_ms, channel, id",
                Store::readSpike, startMs, endMs);
    }

    private static SpikeEvent readSpike(ResultSet rs) throws SQLException {
        double amp = rs.getDouble("amplitude_uv");
        Double ampUv = rs.wasNull() ? null : amp;
        List<String> candidates = Json.MAPPER.convertValue(Json.read(rs.getString("candidate_units_json")),
                Json.MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        return new SpikeEvent(rs.getString("id"), rs.getDouble("time_ms"), rs.getInt("channel"),
                rs.getString("amplitude_raw"), ampUv, rs.getString("unit_id"),
                rs.getString("waveform_ref"), candidates);
    }

    // ---------------- adjudication events ----------------
    public long nextEventSeq() {
        return queryOne("SELECT COALESCE(MAX(seq),0)+1 AS n FROM adjudication_events",
                rs -> rs.getLong("n")).orElse(1L);
    }

    public void insertEvent(AdjudicationEvent e) {
        exec("INSERT INTO adjudication_events(id,seq,type,branch,payload_json,parent_id,status,created_at) VALUES(?,?,?,?,?,?,?,?)",
                e.id(), e.seq(), e.type(), e.branch(), e.payloadJson(), e.parentId(),
                e.status(), e.createdAt());
    }

    public void updateEventStatus(String eventId, String status) {
        exec("UPDATE adjudication_events SET status=? WHERE id=?", status, eventId);
    }

    public List<AdjudicationEvent> events() {
        return query("SELECT * FROM adjudication_events ORDER BY seq", Store::readEvent);
    }

    public List<AdjudicationEvent> eventsUpTo(long seq) {
        return query("SELECT * FROM adjudication_events WHERE seq<=? ORDER BY seq", Store::readEvent, seq);
    }

    private static AdjudicationEvent readEvent(ResultSet rs) throws SQLException {
        return new AdjudicationEvent(rs.getString("id"), rs.getLong("seq"), rs.getString("type"),
                rs.getString("branch"), rs.getString("payload_json"), rs.getString("parent_id"),
                rs.getString("status"), rs.getString("created_at"));
    }

    // ---------------- evidence ----------------
    public void insertEvidence(EvidenceEvent e) {
        exec("INSERT INTO evidence_events(id,kind,unit_id,channel,time_ms,basis_json,adjudication_event_id) VALUES(?,?,?,?,?,?,?)",
                e.id(), e.kind(), e.unitId(), e.channel(), e.timeMs(), e.basisJson(),
                e.adjudicationEventId());
    }

    public List<EvidenceEvent> evidence() {
        return query("SELECT * FROM evidence_events ORDER BY time_ms, kind, id", rs -> new EvidenceEvent(
                rs.getString("id"), rs.getString("kind"), rs.getString("unit_id"),
                (Integer) rs.getObject("channel"), (Double) rs.getObject("time_ms"),
                rs.getString("basis_json"), rs.getString("adjudication_event_id")));
    }

    // ---------------- artifacts ----------------
    public void insertArtifact(Artifact a) {
        exec("INSERT INTO artifacts(id,kind,unit_id,input_digest,param_version,parents_json,valid_from_seq,valid_to_seq,status,invalidation_reason,payload_json) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                a.id(), a.kind(), a.unitId(), a.inputDigest(), a.paramVersion(),
                Json.write(a.parents()), a.validFromSeq(), a.validToSeq(),
                a.status(), a.invalidationReason(), a.payloadJson());
    }

    public void invalidateArtifact(String artifactId, long toSeq, String reason) {
        exec("UPDATE artifacts SET valid_to_seq=?, status='invalidated', invalidation_reason=? WHERE id=?",
                toSeq, reason, artifactId);
    }

    public List<Artifact> artifacts() {
        return query("SELECT * FROM artifacts ORDER BY kind, unit_id, valid_from_seq", Store::readArtifact);
    }

    /** Artifacts visible at a given event sequence (time travel). */
    public List<Artifact> artifactsAt(long seq) {
        return query("SELECT * FROM artifacts WHERE valid_from_seq<=? AND (valid_to_seq IS NULL OR valid_to_seq>?) ORDER BY kind, unit_id, valid_from_seq",
                Store::readArtifact, seq, seq);
    }

    public List<Artifact> currentArtifacts() {
        return query("SELECT * FROM artifacts WHERE valid_to_seq IS NULL ORDER BY kind, unit_id",
                Store::readArtifact);
    }

    private static Artifact readArtifact(ResultSet rs) throws SQLException {
        long to = rs.getLong("valid_to_seq");
        Long validTo = rs.wasNull() ? null : to;
        List<String> parents = Json.MAPPER.convertValue(Json.read(rs.getString("parents_json")),
                Json.MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        return new Artifact(rs.getString("id"), rs.getString("kind"), rs.getString("unit_id"),
                rs.getString("input_digest"), rs.getInt("param_version"), parents,
                rs.getLong("valid_from_seq"), validTo, rs.getString("status"),
                rs.getString("invalidation_reason"), rs.getString("payload_json"));
    }

    // ---------------- diagnostics ----------------
    public void insertDiagnostic(Diagnostic d) {
        exec("INSERT INTO diagnostics(id,stage,location,severity,code,message,raw_value) VALUES(?,?,?,?,?,?,?)",
                d.id(), d.stage(), d.location(), d.severity().name(), d.code(), d.message(), d.rawValue());
    }

    public List<Diagnostic> diagnostics() {
        return query("SELECT * FROM diagnostics ORDER BY stage, location, id", rs -> new Diagnostic(
                rs.getString("id"), rs.getString("stage"), rs.getString("location"),
                Severity.valueOf(rs.getString("severity")), rs.getString("code"),
                rs.getString("message"), rs.getString("raw_value")));
    }

    public void clearTable(String table) {
        exec("DELETE FROM " + table);
    }

    public void insertRow(String table, JsonNode row) {
        List<String> cols = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        row.fields().forEachRemaining(e -> {
            cols.add(e.getKey());
            JsonNode v = e.getValue();
            if (v == null || v.isNull()) vals.add(null);
            else if (v.isIntegralNumber()) vals.add(v.longValue());
            else if (v.isFloatingPointNumber()) vals.add(v.doubleValue());
            else if (v.isBoolean()) vals.add(v.booleanValue() ? 1 : 0);
            else vals.add(v.asText());
        });
        String sql = "INSERT INTO " + table + "(" + String.join(",", cols) + ") VALUES (" +
                "?,".repeat(cols.size()).replaceAll(",$", "") + ")";
        exec(sql, vals.toArray());
    }

    // ---------------- generic helpers ----------------
    private interface RowMapper<T> { T map(ResultSet rs) throws SQLException; }

    private <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(mapper.map(rs));
                return out;
            }
        } catch (SQLException e) { throw new IllegalStateException(sql, e); }
    }

    private <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
        List<T> r = query(sql, mapper, args);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    private void exec(String sql, Object... args) {
        try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException(sql, e); }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null) ps.setObject(i + 1, null);
            else ps.setObject(i + 1, args[i]);
        }
    }

    // ---------------- table dump for export ----------------
    public List<String> tableNames() {
        return List.of("meta", "waveform_refs", "probe_geometries", "detection_params",
                "feature_sets", "units", "cluster_labels", "spike_events",
                "adjudication_events", "evidence_events", "artifacts", "diagnostics");
    }

    public List<JsonNode> dumpTable(String table) {
        return query("SELECT * FROM " + table, rs -> {
            var node = Json.obj();
            var md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String name = md.getColumnName(i);
                Object v = rs.getObject(i);
                if (v == null) node.putNull(name);
                else if (v instanceof Number n) {
                    if (v instanceof Double || v instanceof Float) node.put(name, n.doubleValue());
                    else node.put(name, n.longValue());
                } else node.put(name, v.toString());
            }
            return node;
        });
    }
}
