package com.neurogsb.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.neurogsb.db.Database;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Repository over the layered schema. Materials are immutable; decisions and
 * evidence are append-only events; derived rows are never updated in place,
 * they are invalidated and superseded so any historical event can be
 * re-visited with the dependency versions and conclusions visible at that
 * time.
 */
public final class Store {
    private final Database db;

    public Store(Database db) {
        this.db = db;
    }

    public Database db() {
        return db;
    }

    // ---------- materials (immutable) ----------

    public String addMaterial(String materialId, String kind, JsonNode content) {
        String canonical = Json.write(content);
        db.update("INSERT INTO materials(material_id,kind,content,content_hash,created_seq) VALUES(?,?,?,?,?)",
                materialId, kind, canonical, Json.sha256(canonical), db.nextSeq());
        return materialId;
    }

    public JsonNode material(String materialId) {
        Map<String, Object> row = db.one("SELECT content FROM materials WHERE material_id=?", materialId);
        if (row == null) throw new IllegalArgumentException("unknown material " + materialId);
        return Json.read((String) row.get("content"));
    }

    public List<Map<String, Object>> materials() {
        return db.query("SELECT material_id,kind,content_hash,created_seq FROM materials ORDER BY created_seq");
    }

    public String latestMaterialId(String kind) {
        Map<String, Object> row = db.one(
                "SELECT material_id FROM materials WHERE kind=? ORDER BY created_seq DESC LIMIT 1", kind);
        return row == null ? null : (String) row.get("material_id");
    }

    // ---------- units (stable identity) ----------

    public void addUnit(String unitUid, JsonNode origin) {
        db.update("INSERT INTO units(unit_uid,origin,created_seq) VALUES(?,?,?)",
                unitUid, Json.write(origin), db.nextSeq());
    }

    public boolean hasUnit(String unitUid) {
        return db.one("SELECT unit_uid FROM units WHERE unit_uid=?", unitUid) != null;
    }

    public List<String> unitUids() {
        List<Map<String, Object>> rows = db.query("SELECT unit_uid FROM units ORDER BY created_seq, unit_uid");
        List<String> out = new ArrayList<>();
        rows.forEach(r -> out.add((String) r.get("unit_uid")));
        return out;
    }

    /** Cluster numbers are per-label-set labels, never identity. */
    public void attachLabels(String labelSetId, Map<String, Integer> labels) {
        for (Map.Entry<String, Integer> e : labels.entrySet()) {
            db.update("INSERT OR REPLACE INTO unit_labels(label_set_id,unit_uid,cluster_no) VALUES(?,?,?)",
                    labelSetId, e.getKey(), e.getValue());
        }
    }

    public Integer clusterNo(String labelSetId, String unitUid) {
        Map<String, Object> row = db.one(
                "SELECT cluster_no FROM unit_labels WHERE label_set_id=? AND unit_uid=?", labelSetId, unitUid);
        return row == null ? null : ((Number) row.get("cluster_no")).intValue();
    }

    // ---------- events ----------

    public String addEvent(String kind, JsonNode payload, List<String> parents) {
        long seq = db.nextSeq();
        String eventId = "ev-" + String.format("%06d", seq);
        db.update("INSERT INTO events(event_id,seq,kind,payload,parents,created_at) VALUES(?,?,?,?,?,?)",
                eventId, seq, kind, Json.write(payload), Json.write(parents), Instant.now().toString());
        return eventId;
    }

    public long eventSeq(String eventId) {
        Map<String, Object> row = db.one("SELECT seq FROM events WHERE event_id=?", eventId);
        if (row == null) throw new IllegalArgumentException("unknown event " + eventId);
        return ((Number) row.get("seq")).longValue();
    }

    public List<Map<String, Object>> events() {
        return db.query("SELECT event_id,seq,kind,payload,parents,created_at FROM events ORDER BY seq");
    }

    // ---------- evidence ----------

    public void addEvidence(String unitUid, String kind, JsonNode payload, String eventId) {
        long seq = eventSeq(eventId);
        String evidenceId = "evd-" + String.format("%06d", seq) + "-" + kind.toLowerCase().replace('_', '-');
        db.update("INSERT INTO evidence(evidence_id,unit_uid,kind,payload,event_id,created_seq) VALUES(?,?,?,?,?,?)",
                evidenceId, unitUid, kind, Json.write(payload), eventId, seq);
    }

    public List<Map<String, Object>> evidenceFor(String unitUid) {
        return db.query("SELECT evidence_id,kind,payload,event_id FROM evidence WHERE unit_uid=? ORDER BY created_seq", unitUid);
    }

    // ---------- derived (append-only) ----------

    public String addDerived(String unitUid, String metric, String inputDigest, String paramVersion,
                             List<String> parents, double validFrom, double validTo, JsonNode value, long seq) {
        String derivedId = "drv-" + unitUid + "-" + metric.toLowerCase().replace('_', '-') + "-" + seq;
        db.update("""
                INSERT INTO derived(derived_id,unit_uid,metric,input_digest,param_version,parents,
                                    valid_from_s,valid_to_s,status,invalidation_reason,value,created_seq,invalidated_seq)
                VALUES(?,?,?,?,?,?,?,?, 'VALID', NULL, ?, ?, NULL)""",
                derivedId, unitUid, metric, inputDigest, paramVersion, Json.write(parents),
                validFrom, validTo, Json.write(value), seq);
        return derivedId;
    }

    public void invalidateDerived(String derivedId, long seq, String reason) {
        db.update("UPDATE derived SET status='INVALIDATED', invalidation_reason=?, invalidated_seq=? WHERE derived_id=?",
                reason, seq, derivedId);
    }

    public List<Map<String, Object>> derivedFor(String unitUid) {
        return db.query("SELECT * FROM derived WHERE unit_uid=? ORDER BY created_seq", unitUid);
    }

    public List<Map<String, Object>> derivedValidNow() {
        return db.query("SELECT * FROM derived WHERE status='VALID' ORDER BY unit_uid, metric, created_seq");
    }

    /** Conclusions visible at a historical event sequence. */
    public List<Map<String, Object>> derivedValidAt(long seq) {
        return db.query("""
                SELECT * FROM derived
                WHERE created_seq<=? AND (invalidated_seq IS NULL OR invalidated_seq>?)
                ORDER BY unit_uid, metric, created_seq""", seq, seq);
    }

    // ---------- adjudications ----------

    public String addAdjudication(String targetUid, int round, String decision, String rationale, String eventId) {
        long seq = eventSeq(eventId);
        String adjId = "adj-" + targetUid + "-r" + round + "-" + seq;
        List<Map<String, Object>> existing = db.query(
                "SELECT adj_id,decision FROM adjudications WHERE target_unit_uid=? AND round=? AND status='APPLIED'",
                targetUid, round);
        String status = "APPLIED";
        if (!existing.isEmpty()) {
            String prior = (String) existing.get(0).get("decision");
            if (!prior.equals(decision)) {
                status = "CONTESTED";
                db.update("UPDATE adjudications SET status='CONTESTED' WHERE target_unit_uid=? AND round=?",
                        targetUid, round);
            }
        }
        db.update("""
                INSERT INTO adjudications(adj_id,target_unit_uid,round,decision,rationale,event_id,status,created_seq)
                VALUES(?,?,?,?,?,?,?,?)""",
                adjId, targetUid, round, decision, rationale, eventId, status, seq);
        return adjId;
    }

    public void addCandidate(String adjId, JsonNode candidate) {
        db.update("INSERT OR IGNORE INTO candidates(adj_id,candidate) VALUES(?,?)", adjId, Json.write(candidate));
    }

    public List<Map<String, Object>> adjudications() {
        return db.query("SELECT * FROM adjudications ORDER BY created_seq");
    }

    public List<Map<String, Object>> candidatesFor(String adjId) {
        return db.query("SELECT candidate FROM candidates WHERE adj_id=? ORDER BY candidate", adjId);
    }
}
