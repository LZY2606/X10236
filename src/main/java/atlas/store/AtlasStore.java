package atlas.store;

import atlas.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.*;

/** 存储层：不可变材料、可修改决定、派生结果分表存放；所有查询支持 as-of 序列号。 */
public final class AtlasStore {
    public final Database db;

    public AtlasStore(Database db) { this.db = db; }

    // ---------- 基础 ----------

    public long nextSeq() {
        long s = seq() + 1;
        exec("INSERT INTO meta(k,v) VALUES('seq',?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", Long.toString(s));
        return s;
    }

    public long seq() {
        ObjectNode r = one("SELECT v FROM meta WHERE k='seq'");
        return r == null ? 0L : Long.parseLong(r.get("v").asText());
    }

    public String meta(String k, String def) {
        ObjectNode r = one("SELECT v FROM meta WHERE k=?", k);
        return r == null ? def : r.get("v").asText();
    }

    public void meta(String k, String v) {
        exec("INSERT INTO meta(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", k, v);
    }

    public List<ObjectNode> query(String sql, Object... args) {
        List<ObjectNode> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                while (rs.next()) {
                    ObjectNode row = Json.obj();
                    for (int i = 1; i <= n; i++) {
                        String name = md.getColumnLabel(i);
                        Object v = rs.getObject(i);
                        if (v == null) row.putNull(name);
                        else if (v instanceof Integer || v instanceof Long) row.put(name, ((Number) v).longValue());
                        else if (v instanceof Number) row.put(name, ((Number) v).doubleValue());
                        else row.put(name, v.toString());
                    }
                    out.add(row);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
        return out;
    }

    public ObjectNode one(String sql, Object... args) {
        List<ObjectNode> r = query(sql, args);
        return r.isEmpty() ? null : r.get(0);
    }

    public int exec(String sql, Object... args) {
        try (PreparedStatement ps = db.conn.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("exec failed: " + sql, e);
        }
    }

    private static void bind(PreparedStatement ps, Object[] args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) ps.setObject(i + 1, null);
            else if (a instanceof Long l) ps.setLong(i + 1, l);
            else if (a instanceof Integer n) ps.setInt(i + 1, n);
            else if (a instanceof Double d) ps.setDouble(i + 1, d);
            else ps.setString(i + 1, a.toString());
        }
    }

    // ---------- 材料层（不可变，追加式） ----------

    public void insertWaveformRef(String eventId, long version, int channel, double tMs, double tEndMs,
                                  String rawAmplitude, String rawUnit, Double ampUv, String unitDiag,
                                  String snippetJson, double sampleRateKhz, String sourceRef,
                                  String timeBasis, long seq) {
        exec("INSERT OR REPLACE INTO waveform_refs(event_id,version,channel,t_ms,t_end_ms,raw_amplitude,raw_unit,amp_uv,unit_diag,snippet,sample_rate_khz,source_ref,time_basis,import_seq) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            eventId, version, channel, tMs, tEndMs, rawAmplitude, rawUnit, ampUv, unitDiag,
            snippetJson, sampleRateKhz, sourceRef, timeBasis, seq);
    }

    public void insertProbeGeometry(String version, int channel, double x, double y, String basis, long seq) {
        exec("INSERT OR REPLACE INTO probe_geometry(version,channel,x_um,y_um,coord_basis,import_seq) VALUES(?,?,?,?,?,?)",
            version, channel, x, y, basis, seq);
    }

    public void insertDetectionParams(String version, String rawThreshold, Double thresholdUv,
                                      double refractoryMs, double windowMs, long seq) {
        exec("INSERT OR REPLACE INTO detection_params(version,raw_threshold,threshold_uv,refractory_ms,window_ms,created_seq) VALUES(?,?,?,?,?,?)",
            version, rawThreshold, thresholdUv, refractoryMs, windowMs, seq);
    }

    public void insertFeatureSet(String version, String algo, long seq) {
        exec("INSERT OR REPLACE INTO feature_sets(version,algo,created_seq) VALUES(?,?,?)", version, algo, seq);
    }

    public void insertClusterLabel(String featureVersion, int clusterNo, String eventId, long seq) {
        exec("INSERT OR IGNORE INTO cluster_labels(feature_version,cluster_no,event_id,import_seq) VALUES(?,?,?,?)",
            featureVersion, clusterNo, eventId, seq);
    }

    /** 每个事件取 as-of 时刻的最新版本。 */
    public List<ObjectNode> eventsView(long asof) {
        return query("""
            SELECT w.* FROM waveform_refs w
            WHERE w.import_seq <= ?
              AND w.version = (SELECT MAX(version) FROM waveform_refs x
                               WHERE x.event_id = w.event_id AND x.import_seq <= ?)
            ORDER BY w.t_ms, w.channel, w.event_id
            """, asof, asof);
    }

    // ---------- 单元身份 ----------

    public void upsertUnit(String uid, String origin, long seq) {
        exec("INSERT OR IGNORE INTO units(unit_uid,origin,created_seq) VALUES(?,?,?)", uid, origin, seq);
    }

    public void setUnitStatus(String uid, String status, long seq) {
        exec("INSERT OR REPLACE INTO unit_status_history(unit_uid,seq,status) VALUES(?,?,?)", uid, status, seq);
    }

    public String unitStatus(String uid, long asof) {
        ObjectNode r = one("SELECT status FROM unit_status_history WHERE unit_uid=? AND seq<=? ORDER BY seq DESC LIMIT 1", uid, asof);
        return r == null ? "ACTIVE" : r.get("status").asText();
    }

    public void mapCluster(String featureVersion, int clusterNo, String uid, double confidence,
                           String candidatesJson, long seq) {
        exec("INSERT OR REPLACE INTO unit_cluster_map(feature_version,cluster_no,unit_uid,confidence,candidates,created_seq) VALUES(?,?,?,?,?,?)",
            featureVersion, clusterNo, uid, confidence, candidatesJson, seq);
    }

    public ObjectNode clusterMap(String featureVersion, int clusterNo) {
        return one("SELECT * FROM unit_cluster_map WHERE feature_version=? AND cluster_no=?",
            featureVersion, clusterNo);
    }

    public void addUnitEvent(String uid, String eventId, long seq) {
        exec("INSERT OR IGNORE INTO unit_events(unit_uid,event_id,seq) VALUES(?,?,?)", uid, eventId, seq);
    }

    /** 成员关系：显式 unit_events 优先；否则用当前特征版本的聚类映射。 */
    public Map<String, List<String>> membership(long asof) {
        Map<String, List<String>> out = new TreeMap<>();
        for (ObjectNode r : query("SELECT unit_uid,event_id FROM unit_events WHERE seq<=? ORDER BY event_id", asof))
            out.computeIfAbsent(r.get("unit_uid").asText(), k -> new ArrayList<>()).add(r.get("event_id").asText());
        String fv = meta("feature_version", null);
        if (fv != null) {
            Map<String, String> clusterToUnit = new HashMap<>();
            for (ObjectNode r : query("SELECT cluster_no,unit_uid FROM unit_cluster_map WHERE feature_version=? AND created_seq<=?", fv, asof))
                clusterToUnit.put(r.get("cluster_no").asText(), r.get("unit_uid").asText());
            for (ObjectNode r : query("SELECT cluster_no,event_id FROM cluster_labels WHERE feature_version=? AND import_seq<=? ORDER BY event_id", fv, asof)) {
                String uid = clusterToUnit.get(r.get("cluster_no").asText());
                if (uid == null || out.containsKey(uid)) continue;
                out.computeIfAbsent(uid, k -> new ArrayList<>()).add(r.get("event_id").asText());
            }
        }
        return out;
    }

    public String nextUid() {
        long n = Long.parseLong(meta("uid_counter", "0")) + 1;
        meta("uid_counter", Long.toString(n));
        return "U-" + String.format("%04d", n);
    }

    // ---------- 裁定事件 ----------

    public String insertAdjudication(String branch, String kind, String target, String payloadJson,
                                     String rationale, String conflictWith, String ts, long seq) {
        long n = Long.parseLong(meta("adj_counter", "0")) + 1;
        meta("adj_counter", Long.toString(n));
        String adjId = "ADJ-" + String.format("%04d", n);
        exec("INSERT INTO adjudications(adj_id,seq,branch,kind,target,payload,rationale,conflict_with,ts) VALUES(?,?,?,?,?,?,?,?,?)",
            adjId, seq, branch, kind, target, payloadJson, rationale, conflictWith, ts);
        return adjId;
    }

    public void insertAdjStatus(String adjId, String status, long seq) {
        exec("INSERT OR REPLACE INTO adj_status_history(adj_id,seq,status) VALUES(?,?,?)", adjId, status, seq);
    }

    public String adjStatus(String adjId, long asof) {
        ObjectNode r = one("SELECT status FROM adj_status_history WHERE adj_id=? AND seq<=? ORDER BY seq DESC LIMIT 1", adjId, asof);
        return r == null ? null : r.get("status").asText();
    }

    public List<ObjectNode> adjudications(long asof) {
        return query("SELECT * FROM adjudications WHERE seq<=? ORDER BY seq", asof);
    }

    // ---------- 证据（机器可读取） ----------

    public void insertEvidence(String evId, String kind, String unitUid, double value, String unit,
                               String verdict, String detailJson, long seq) {
        exec("INSERT OR REPLACE INTO evidence(ev_id,kind,unit_uid,value,unit,verdict,detail,seq) VALUES(?,?,?,?,?,?,?,?)",
            evId, kind, unitUid, value, unit, verdict, detailJson, seq);
    }

    // ---------- 派生层 ----------

    public void putDerived(String id, String kind, String unitUid, String windowKey, String paramVersion,
                           String inputDigest, String parentsJson, String payloadJson,
                           String validRangeJson, String basis, long seq) {
        exec("INSERT OR REPLACE INTO derived(id,kind,unit_uid,window_key,param_version,input_digest,parents,payload,valid_range,basis,created_seq,invalidated_seq,invalidated_reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,NULL,NULL)",
            id, kind, unitUid, windowKey, paramVersion, inputDigest, parentsJson, payloadJson, validRangeJson, basis, seq);
    }

    public List<ObjectNode> derivedOf(String unitUid) {
        return query("SELECT * FROM derived WHERE unit_uid=? AND invalidated_seq IS NULL", unitUid);
    }

    public int invalidateDerived(String unitUid, String reason, long seq) {
        return exec("UPDATE derived SET invalidated_seq=?, invalidated_reason=? WHERE unit_uid=? AND invalidated_seq IS NULL",
            seq, reason, unitUid);
    }

    public List<ObjectNode> derivedView(long asof) {
        return query("SELECT * FROM derived WHERE created_seq<=? AND (invalidated_seq IS NULL OR invalidated_seq>?) ORDER BY unit_uid,kind",
            asof, asof);
    }

    // ---------- 诊断 ----------

    public void diagnostic(String layer, String path, String message, long seq) {
        exec("INSERT INTO diagnostics(layer,path,message,seq) VALUES(?,?,?,?)", layer, path, message, seq);
    }

    // ---------- 日志与半写恢复 ----------

    public void beginBatch(String batchId, String payloadJson) {
        exec("INSERT OR REPLACE INTO journal(batch_id,state,payload,diag) VALUES(?,?,?,NULL)", batchId, "BEGIN", payloadJson);
    }

    /** 应用批次中的操作（幂等），供正常提交与恢复共用。 */
    public void applyBatch(String batchId) {
        ObjectNode j = one("SELECT payload FROM journal WHERE batch_id=?", batchId);
        if (j == null) throw new IllegalStateException("unknown batch " + batchId);
        JsonNode payload = Json.read(j.get("payload").asText());
        for (JsonNode op : payload.withArray("ops")) {
            String table = op.get("table").asText();
            ObjectNode row = (ObjectNode) op.get("row");
            StringJoiner cols = new StringJoiner(",");
            StringJoiner marks = new StringJoiner(",");
            List<Object> vals = new ArrayList<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = row.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> f = it.next();
                cols.add(f.getKey());
                marks.add("?");
                JsonNode v = f.getValue();
                vals.add(v.isNull() ? null : v.isIntegralNumber() ? v.longValue()
                    : v.isFloatingPointNumber() ? v.doubleValue() : v.asText());
            }
            exec("INSERT OR REPLACE INTO " + table + "(" + cols + ") VALUES(" + marks + ")", vals.toArray());
        }
    }

    public void markApplied(String batchId) {
        exec("UPDATE journal SET state='APPLIED' WHERE batch_id=?", batchId);
    }

    /** 启动恢复：BEGIN 状态的批次幂等前滚；损坏的批次隔离并给出 CORRUPT_FORMAT 诊断。 */
    public List<String> recover() {
        List<String> notes = new ArrayList<>();
        for (ObjectNode j : query("SELECT batch_id,payload FROM journal WHERE state='BEGIN'")) {
            String id = j.get("batch_id").asText();
            JsonNode payload = Json.tryRead(j.get("payload").asText());
            if (payload == null || !payload.has("ops")) {
                exec("UPDATE journal SET state='QUARANTINED', diag=? WHERE batch_id=?",
                    "CORRUPT_FORMAT: journal payload unparsable", id);
                diagnostic("recovery", "journal/" + id, "CORRUPT_FORMAT: 批次载荷损坏，已隔离", nextSeq());
                notes.add("quarantined " + id);
                continue;
            }
            applyBatch(id);
            markApplied(id);
            diagnostic("recovery", "journal/" + id, "半写批次已前滚完成", nextSeq());
            notes.add("rolled-forward " + id);
        }
        return notes;
    }

    // ---------- 回放日志 ----------

    public void logReplay(String step, String status, String detail, long seq) {
        exec("INSERT OR REPLACE INTO replay_log(step,seq,status,detail) VALUES(?,?,?,?)", step, seq, status, detail);
    }

    public void wipeAll() {
        for (String t : List.of("waveform_refs","probe_geometry","detection_params","feature_sets",
                "cluster_labels","units","unit_status_history","unit_cluster_map","unit_events",
                "adjudications","adj_status_history","evidence","derived","diagnostics","journal",
                "replay_log","meta"))
            exec("DELETE FROM " + t);
    }
}
