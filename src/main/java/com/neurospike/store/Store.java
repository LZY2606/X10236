package com.neurospike.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neurospike.core.Diagnostic;
import com.neurospike.core.Json;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Data access over {@link Database}. Every listing is returned in a stable,
 * content-defined order so equivalent inputs yield equivalent outputs.
 */
public final class Store {
    private final Database db;

    public Store(Database db) {
        this.db = db;
    }

    public Database db() {
        return db;
    }

    // ---- materials -----------------------------------------------------------

    public void putMaterial(String id, String kind, int version, JsonNode raw, String digest, long seq) {
        exec("INSERT INTO materials(id,kind,version,raw_json,digest,seq) VALUES(?,?,?,?,?,?)",
                id, kind, version, Json.canonical(raw), digest, seq);
    }

    public List<ObjectNode> materialsOfKind(String kind) {
        return queryObjects(
                "SELECT id,kind,version,raw_json,digest,seq FROM materials WHERE kind=? ORDER BY id,version",
                rs -> materialRow(rs), kind);
    }

    public List<ObjectNode> allMaterials() {
        return queryObjects("SELECT id,kind,version,raw_json,digest,seq FROM materials ORDER BY id,version",
                this::materialRow);
    }

    public ObjectNode latestMaterial(String id) {
        List<ObjectNode> r = queryObjects(
                "SELECT id,kind,version,raw_json,digest,seq FROM materials WHERE id=? ORDER BY version DESC LIMIT 1",
                this::materialRow, id);
        return r.isEmpty() ? null : r.get(0);
    }

    private ObjectNode materialRow(ResultSet rs) throws Exception {
        ObjectNode n = Json.M.createObjectNode();
        n.put("id", rs.getString("id"));
        n.put("kind", rs.getString("kind"));
        n.put("version", rs.getInt("version"));
        n.set("raw", Json.read(rs.getString("raw_json")));
        n.put("digest", rs.getString("digest"));
        n.put("seq", rs.getLong("seq"));
        return n;
    }

    // ---- spikes ----------------------------------------------------------------

    public void putSpike(String id, String clusterKey, int channel, Double timeMs, Double ampUv,
                         String waveformRef, JsonNode raw, String digest, long seq) {
        exec("INSERT INTO spikes(id,cluster_key,channel,time_ms,amp_uv,waveform_ref,raw_json,digest,seq) VALUES(?,?,?,?,?,?,?,?,?)",
                id, clusterKey, channel, timeMs, ampUv, waveformRef, Json.canonical(raw), digest, seq);
    }

    public List<ObjectNode> allSpikes() {
        return queryObjects("SELECT * FROM spikes ORDER BY id", this::spikeRow);
    }

    /** Spikes of a cluster ordered by normalized time; unconvertible times sort last, then by id. */
    public List<ObjectNode> spikesOfCluster(String clusterKey) {
        return queryObjects(
                "SELECT * FROM spikes WHERE cluster_key=? ORDER BY time_ms IS NULL, time_ms, id",
                this::spikeRow, clusterKey);
    }

    private ObjectNode spikeRow(ResultSet rs) throws Exception {
        ObjectNode n = Json.M.createObjectNode();
        n.put("id", rs.getString("id"));
        n.put("clusterKey", rs.getString("cluster_key"));
        n.put("channel", rs.getInt("channel"));
        double t = rs.getDouble("time_ms");
        if (rs.wasNull()) n.putNull("timeMs"); else n.put("timeMs", t);
        double a = rs.getDouble("amp_uv");
        if (rs.wasNull()) n.putNull("ampUv"); else n.put("ampUv", a);
        String wf = rs.getString("waveform_ref");
        if (wf == null) n.putNull("waveformRef"); else n.put("waveformRef", wf);
        n.set("raw", Json.read(rs.getString("raw_json")));
        n.put("digest", rs.getString("digest"));
        n.put("seq", rs.getLong("seq"));
        return n;
    }

    // ---- adjudications ---------------------------------------------------------

    public void putAdjudication(String id, long seq, String branch, String kind, JsonNode subjects,
                                JsonNode payload, String rationale, String status, String supersedes) {
        exec("INSERT INTO adjudications(id,seq,branch,kind,subjects_json,payload_json,rationale,status,supersedes) VALUES(?,?,?,?,?,?,?,?,?)",
                id, seq, branch, kind, Json.canonical(subjects), Json.canonical(payload), rationale, status, supersedes);
    }

    public void setAdjudicationStatus(String id, String status) {
        exec("UPDATE adjudications SET status=? WHERE id=?", status, id);
    }

    public List<ObjectNode> adjudications() {
        return queryObjects("SELECT * FROM adjudications ORDER BY seq, id", this::adjudicationRow);
    }

    public ObjectNode adjudication(String id) {
        List<ObjectNode> r = queryObjects("SELECT * FROM adjudications WHERE id=?", this::adjudicationRow, id);
        return r.isEmpty() ? null : r.get(0);
    }

    private ObjectNode adjudicationRow(ResultSet rs) throws Exception {
        ObjectNode n = Json.M.createObjectNode();
        n.put("id", rs.getString("id"));
        n.put("seq", rs.getLong("seq"));
        n.put("branch", rs.getString("branch"));
        n.put("kind", rs.getString("kind"));
        n.set("subjects", Json.read(rs.getString("subjects_json")));
        n.set("payload", Json.read(rs.getString("payload_json")));
        String r = rs.getString("rationale");
        if (r == null) n.putNull("rationale"); else n.put("rationale", r);
        n.put("status", rs.getString("status"));
        String s = rs.getString("supersedes");
        if (s == null) n.putNull("supersedes"); else n.put("supersedes", s);
        return n;
    }

    // ---- evidence ----------------------------------------------------------------

    public void putEvidence(String id, long seq, String kind, JsonNode subjects, JsonNode payload) {
        exec("INSERT OR IGNORE INTO evidence(id,seq,kind,subjects_json,payload_json) VALUES(?,?,?,?,?)",
                id, seq, kind, Json.canonical(subjects), Json.canonical(payload));
    }

    public List<ObjectNode> evidence() {
        return queryObjects("SELECT * FROM evidence ORDER BY seq, id", this::evidenceRow);
    }

    private ObjectNode evidenceRow(ResultSet rs) throws Exception {
        ObjectNode n = Json.M.createObjectNode();
        n.put("id", rs.getString("id"));
        n.put("seq", rs.getLong("seq"));
        n.put("kind", rs.getString("kind"));
        n.set("subjects", Json.read(rs.getString("subjects_json")));
        n.set("payload", Json.read(rs.getString("payload_json")));
        return n;
    }

    // ---- computed results ---------------------------------------------------------

    public void putComputed(String id, String kind, String clusterKey, Double wStart, Double wEnd,
                            String inputDigest, String paramVersion, JsonNode parents, JsonNode payload, long seq) {
        exec("INSERT OR IGNORE INTO computed(id,kind,cluster_key,window_start_ms,window_end_ms,input_digest,param_version,parents_json,payload_json,status,seq) VALUES(?,?,?,?,?,?,?,?,?,'VALID',?)",
                id, kind, clusterKey, wStart, wEnd, inputDigest, paramVersion,
                Json.canonical(parents), Json.canonical(payload), seq);
    }

    public void invalidateComputed(String id, String reason, long invalidatedSeq) {
        exec("UPDATE computed SET status='INVALIDATED', invalidation_reason=?, invalidated_seq=? WHERE id=?",
                reason, invalidatedSeq, id);
    }

    public List<ObjectNode> computedAll() {
        return queryObjects("SELECT * FROM computed ORDER BY id", this::computedRow);
    }

    public List<ObjectNode> computedFor(String clusterKey) {
        return queryObjects("SELECT * FROM computed WHERE cluster_key=? ORDER BY kind, id", this::computedRow, clusterKey);
    }

    private ObjectNode computedRow(ResultSet rs) throws Exception {
        ObjectNode n = Json.M.createObjectNode();
        n.put("id", rs.getString("id"));
        n.put("kind", rs.getString("kind"));
        String ck = rs.getString("cluster_key");
        if (ck == null) n.putNull("clusterKey"); else n.put("clusterKey", ck);
        double ws = rs.getDouble("window_start_ms");
        if (rs.wasNull()) n.putNull("windowStartMs"); else n.put("windowStartMs", ws);
        double we = rs.getDouble("window_end_ms");
        if (rs.wasNull()) n.putNull("windowEndMs"); else n.put("windowEndMs", we);
        n.put("inputDigest", rs.getString("input_digest"));
        n.put("paramVersion", rs.getString("param_version"));
        n.set("parents", Json.read(rs.getString("parents_json")));
        n.set("payload", Json.read(rs.getString("payload_json")));
        n.put("status", rs.getString("status"));
        String ir = rs.getString("invalidation_reason");
        if (ir == null) n.putNull("invalidationReason"); else n.put("invalidationReason", ir);
        long is = rs.getLong("invalidated_seq");
        if (rs.wasNull()) n.putNull("invalidatedSeq"); else n.put("invalidatedSeq", is);
        n.put("seq", rs.getLong("seq"));
        return n;
    }

    // ---- diagnostics ---------------------------------------------------------------

    public void addDiagnostic(Diagnostic d) {
        exec("INSERT INTO diagnostics(stage,position,severity,message) VALUES(?,?,?,?)",
                d.stage(), d.position(), d.severity(), d.message());
    }

    public List<ObjectNode> diagnostics() {
        return queryObjects("SELECT * FROM diagnostics ORDER BY seq", rs -> {
            ObjectNode n = Json.M.createObjectNode();
            n.put("seq", rs.getLong("seq"));
            n.put("stage", rs.getString("stage"));
            n.put("position", rs.getString("position"));
            n.put("severity", rs.getString("severity"));
            n.put("message", rs.getString("message"));
            return n;
        });
    }

    // ---- counts -----------------------------------------------------------------------

    public long count(String table) {
        try (PreparedStatement ps = db.conn().prepareStatement("SELECT COUNT(*) FROM " + table)) {
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    private interface RowMapper {
        ObjectNode map(ResultSet rs) throws Exception;
    }

    private List<ObjectNode> queryObjects(String sql, RowMapper mapper, Object... params) {
        try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
            bind(ps, params);
            ResultSet rs = ps.executeQuery();
            List<ObjectNode> out = new ArrayList<>();
            while (rs.next()) out.add(mapper.map(rs));
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void exec(String sql, Object... params) {
        try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws Exception {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p == null) ps.setObject(i + 1, null);
            else if (p instanceof String s) ps.setString(i + 1, s);
            else if (p instanceof Integer n) ps.setInt(i + 1, n);
            else if (p instanceof Long n) ps.setLong(i + 1, n);
            else if (p instanceof Double n) ps.setDouble(i + 1, n);
            else throw new IllegalArgumentException("unsupported param " + p.getClass());
        }
    }

    public ArrayNode toArray(List<ObjectNode> rows) {
        ArrayNode a = Json.M.createArrayNode();
        rows.forEach(a::add);
        return a;
    }
}
