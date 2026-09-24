package neurogsb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Layered store. Materials are append-only; decisions are events; derived
 * records carry input digest, parameter version, parents and temporal
 * validity (seq_from/seq_to) so historical states can be restored.
 */
public final class Store {
    public final Connection conn;
    private int ordCounter = -1;

    public Store(Connection conn) { this.conn = conn; }

    public synchronized int nextOrd() {
        if (ordCounter < 0) {
            ordCounter = queryInt("SELECT COALESCE(MAX(v),0) FROM (" +
                "SELECT MAX(ord) v FROM waveform_refs UNION ALL SELECT MAX(ord) FROM probe_geometry" +
                " UNION ALL SELECT MAX(ord) FROM detection_thresholds UNION ALL SELECT MAX(ord) FROM feature_versions" +
                " UNION ALL SELECT MAX(ord) FROM cluster_labels UNION ALL SELECT MAX(ord) FROM spike_events" +
                " UNION ALL SELECT MAX(ord) FROM adjudication_events UNION ALL SELECT MAX(ord) FROM supersessions" +
                " UNION ALL SELECT MAX(ord) FROM evidence_events UNION ALL SELECT MAX(ord) FROM derived_records" +
                " UNION ALL SELECT MAX(ord) FROM diagnostics UNION ALL SELECT MAX(ord) FROM manifests)", 0);
        }
        return ++ordCounter;
    }

    public int nextSeq() {
        return queryInt("SELECT COALESCE(MAX(seq),0) FROM adjudication_events", 0) + 1;
    }

    public int paramVersion() {
        return queryInt("SELECT COALESCE(MAX(version),0) FROM detection_thresholds", 0);
    }

    // ---------------- generic helpers ----------------

    public int queryInt(String sql, int fallback) {
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : fallback;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public List<JsonNode> query(String sql, Object... args) {
        List<JsonNode> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                if (a == null) ps.setObject(i + 1, null);
                else if (a instanceof Double d) ps.setDouble(i + 1, d);
                else if (a instanceof Integer n) ps.setInt(i + 1, n);
                else ps.setString(i + 1, a.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    ObjectNode row = Json.obj();
                    for (int i = 1; i <= n; i++) {
                        String col = rs.getMetaData().getColumnLabel(i);
                        Object v = rs.getObject(i);
                        if (v == null) row.putNull(col);
                        else if (v instanceof Number num) {
                            if (v instanceof Double || v instanceof Float) row.put(col, num.doubleValue());
                            else row.put(col, num.longValue());
                        } else row.put(col, v.toString());
                    }
                    out.add(row);
                }
            }
        } catch (Exception e) { throw new RuntimeException(e); }
        return out;
    }

    public JsonNode queryOne(String sql, Object... args) {
        List<JsonNode> r = query(sql, args);
        return r.isEmpty() ? null : r.get(0);
    }

    public void exec(String sql, Object... args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                if (a == null) ps.setObject(i + 1, null);
                else if (a instanceof Double d) ps.setDouble(i + 1, d);
                else if (a instanceof Integer n) ps.setInt(i + 1, n);
                else ps.setString(i + 1, a.toString());
            }
            ps.executeUpdate();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ---------------- material inserts ----------------

    public void addWaveformRef(String id, String unitUid, int channel, String tRaw, String tUnit,
                               Double tMs, String sampleRef, String snippetJson, double fsHz, JsonNode raw) {
        exec("INSERT INTO waveform_refs VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            id, unitUid, channel, tRaw, tUnit, tMs, sampleRef, snippetJson, fsHz,
            Json.canonical(raw), Json.digest(raw), nextOrd());
    }

    public void addProbeSite(String id, String siteId, int channel, String xRaw, String yRaw,
                             String coordUnit, String coordFrame, Double xUm, Double yUm, JsonNode raw) {
        exec("INSERT INTO probe_geometry VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            id, siteId, channel, xRaw, yRaw, coordUnit, coordFrame, xUm, yUm,
            Json.canonical(raw), Json.digest(raw), nextOrd());
    }

    public void addThreshold(String id, int channel, String valueRaw, String unit, Double valueUv,
                             Double fromMs, Double toMs, int version, JsonNode raw) {
        exec("INSERT INTO detection_thresholds VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            id, channel, valueRaw, unit, valueUv, fromMs, toMs, version,
            Json.canonical(raw), Json.digest(raw), nextOrd());
    }

    public void addFeatureVersion(String id, String name, int version, JsonNode params, JsonNode raw) {
        exec("INSERT INTO feature_versions VALUES(?,?,?,?,?,?,?)",
            id, name, version, Json.canonical(params), Json.canonical(raw), Json.digest(raw), nextOrd());
    }

    public void addClusterLabel(String id, String unitUid, int labelNo, int seq, String event, JsonNode raw) {
        exec("INSERT INTO cluster_labels VALUES(?,?,?,?,?,?,?,?)",
            id, unitUid, labelNo, seq, event, Json.canonical(raw), Json.digest(raw), nextOrd());
    }

    public void addSpike(String id, String unitUid, int channel, String tRaw, String tUnit, Double tMs,
                         String ampRaw, String ampUnit, Double ampUv, String sharedGroup, JsonNode raw) {
        exec("INSERT INTO spike_events VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, unitUid, channel, tRaw, tUnit, tMs, ampRaw, ampUnit, ampUv, sharedGroup,
            Json.canonical(raw), Json.digest(raw), nextOrd());
    }

    public void addDiagnostic(String id, String source, String position, String code,
                              String severity, String message, JsonNode raw) {
        exec("INSERT INTO diagnostics VALUES(?,?,?,?,?,?,?,?)",
            id, source, position, code, severity, message,
            raw == null ? null : Json.canonical(raw), nextOrd());
    }

    public void addEvidence(String id, int seq, String kind, JsonNode subjects, JsonNode metrics,
                            Double wFrom, Double wTo, String statement, JsonNode raw) {
        exec("INSERT INTO evidence_events VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            id, seq, kind, Json.canonical(subjects), Json.canonical(metrics), wFrom, wTo,
            "evidence", statement, Json.canonical(raw), Json.digest(raw), nextOrd());
    }

    public void addAdjudication(String id, int seq, String branch, String kind, JsonNode targets,
                                String outcome, String rationale, JsonNode evidence, int paramVersion,
                                Double wFrom, Double wTo, String status, String supersedes,
                                String conflictWith, JsonNode affected, JsonNode raw) {
        exec("INSERT INTO adjudication_events VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, seq, branch, kind, Json.canonical(targets), outcome, rationale,
            evidence == null ? null : Json.canonical(evidence), paramVersion, wFrom, wTo,
            status, supersedes, conflictWith, affected == null ? null : Json.canonical(affected),
            Json.canonical(raw), Json.digest(raw), nextOrd());
    }

    public void addSupersession(String id, int seq, String eventId, String newStatus) {
        exec("INSERT INTO supersessions VALUES(?,?,?,?,?)", id, seq, eventId, newStatus, nextOrd());
    }

    public void addDerived(String id, String kind, String unitUid, String inputDigest, int paramVersion,
                           JsonNode parents, JsonNode payload, int seqFrom,
                           Double validFromMs, Double validToMs, String manifestId) {
        exec("INSERT INTO derived_records VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, kind, unitUid, inputDigest, paramVersion, Json.canonical(parents),
            Json.canonical(payload), seqFrom, null, "valid", null, validFromMs, validToMs,
            manifestId, nextOrd());
    }

    // ---------------- manifests / half-write recovery ----------------

    public String beginManifest(String description) {
        String id = "mf-" + Json.sha256(description + ":" + nextOrd());
        exec("INSERT INTO manifests VALUES(?,?,?,?)", id, "pending", description, nextOrd());
        return id;
    }

    public void commitManifest(String id) {
        exec("UPDATE manifests SET status='committed' WHERE id=?", id);
    }

    /**
     * Recovery after crash: pending manifests are rolled back (their derived
     * rows removed); derived rows pointing at unknown manifests are marked
     * invalidated rather than silently trusted.
     */
    public JsonNode recover() {
        ArrayNode report = Json.arr();
        for (JsonNode m : query("SELECT * FROM manifests WHERE status='pending'")) {
            String mid = m.get("id").asText();
            exec("DELETE FROM derived_records WHERE manifest_id=?", mid);
            exec("UPDATE manifests SET status='aborted' WHERE id=?", mid);
            addDiagnostic("dg-rec-" + mid, "recovery", "manifest:" + mid,
                "RECOVERY", "warning", "half-written transaction rolled back: " + mid, null);
            ObjectNode r = Json.obj();
            r.put("action", "rollback").put("manifest", mid);
            report.add(r);
        }
        for (JsonNode d : query(
                "SELECT * FROM derived_records WHERE manifest_id IS NOT NULL" +
                " AND manifest_id NOT IN (SELECT id FROM manifests) AND status='valid'")) {
            String did = d.get("id").asText();
            exec("UPDATE derived_records SET status='invalidated', invalidation_reason=? WHERE id=?",
                "half_write_recovered", did);
            addDiagnostic("dg-torn-" + did, "recovery", "derived:" + did,
                "RECOVERY", "warning", "torn derived record invalidated: " + did, null);
            ObjectNode r = Json.obj();
            r.put("action", "invalidate_torn").put("derived", did);
            report.add(r);
        }
        return report;
    }

    // ---------------- temporal / status queries ----------------

    /** Status of an adjudication event as of a given sequence (append-only supersessions). */
    public String adjudicationStatusAsOf(String eventId, int seq) {
        JsonNode base = queryOne("SELECT status FROM adjudication_events WHERE id=?", eventId);
        if (base == null) return null;
        String status = base.get("status").asText();
        for (JsonNode s : query(
                "SELECT new_status FROM supersessions WHERE event_id=? AND seq<=? ORDER BY seq",
                eventId, seq)) {
            status = s.get("new_status").asText();
        }
        return status;
    }

    public String adjudicationStatusNow(String eventId) {
        return adjudicationStatusAsOf(eventId, Integer.MAX_VALUE);
    }

    /** Derived records visible as of seq (temporal validity). */
    public List<JsonNode> derivedAsOf(String kind, String unitUid, int seq) {
        return query("SELECT * FROM derived_records WHERE kind=? AND unit_uid=?" +
            " AND seq_from<=? AND (seq_to IS NULL OR seq_to>?) ORDER BY ord",
            kind, unitUid, seq, seq);
    }

    public List<JsonNode> derivedCurrent(String kind, String unitUid) {
        return query("SELECT * FROM derived_records WHERE kind=? AND unit_uid=? AND status='valid'" +
            " AND seq_to IS NULL ORDER BY ord", kind, unitUid);
    }

    public void supersedeDerived(String id, int seqTo, String reason) {
        exec("UPDATE derived_records SET seq_to=?, status='superseded', invalidation_reason=? WHERE id=?",
            seqTo, reason, id);
    }

    public void invalidateDerived(String id, String reason) {
        exec("UPDATE derived_records SET status='invalidated', invalidation_reason=? WHERE id=?",
            reason, id);
    }

    /** Canonical dump of all tables: used for export and roundtrip comparison. */
    public ObjectNode canonicalDump() {
        ObjectNode dump = Json.obj();
        String[] tables = {"waveform_refs", "probe_geometry", "detection_thresholds",
            "feature_versions", "cluster_labels", "spike_events", "adjudication_events",
            "supersessions", "evidence_events", "derived_records", "diagnostics", "manifests"};
        for (String t : tables) {
            ArrayNode rows = Json.arr();
            for (JsonNode r : query("SELECT * FROM " + t + " ORDER BY ord")) rows.add(r);
            dump.set(t, rows);
        }
        return dump;
    }

    public boolean isEmpty() {
        return queryInt("SELECT COUNT(*) FROM waveform_refs", 0) == 0
            && queryInt("SELECT COUNT(*) FROM spike_events", 0) == 0;
    }
}
