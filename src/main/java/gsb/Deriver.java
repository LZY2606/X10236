package gsb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Derived layer. Every result records input digest, parameter version and parent
 * dependencies. Evidence (refractory violations, amplitude drift, multi-channel
 * propagation) is stored as machine-readable events; none of it renames units.
 * Window changes invalidate and recompute only units whose spikes intersect the
 * new window.
 */
public final class Deriver {
    /** Distinct outcomes: success, budget exhausted, semantic failure. */
    public enum Outcome { OK, BUDGET_EXHAUSTED, SEMANTIC_UNSOLVABLE }

    public static final class Result {
        public final Outcome outcome;
        public final String derivedId;
        public final String message;
        Result(Outcome o, String id, String msg) { outcome = o; derivedId = id; message = msg; }
    }

    private final Database db;
    private final Store store;
    private final long opBudget;

    public Deriver(Database db, Store store, long opBudget) {
        this.db = db;
        this.store = store;
        this.opBudget = opBudget;
    }

    /** Current analysis window, stored in meta. */
    public double[] currentWindow() {
        String s = db.getMeta("window.start");
        String e = db.getMeta("window.end");
        return new double[]{s == null ? 0.0 : Double.parseDouble(s), e == null ? 0.0 : Double.parseDouble(e)};
    }

    public void setWindow(double start, double end) {
        db.setMeta("window.start", Double.toString(start));
        db.setMeta("window.end", Double.toString(end));
    }

    private static double timeSeconds(JsonNode spike) {
        return Quantity.of(spike.get("time").get("value").asText(), spike.get("time").get("unit").asText(), null)
                .normalized();
    }

    private static Double ampMicrovolts(JsonNode spike) {
        Quantity q = Quantity.of(spike.get("amplitude").get("value").asText(),
                spike.get("amplitude").get("unit").asText(), null);
        return q.convertible() ? q.normalized() : null;
    }

    /**
     * Compute unit evidence inside [windowStart, windowEnd] under a parameter version.
     * Parents: label rows + threshold/feature versions used. Input digest covers the
     * exact spike rows consumed, so any source change is detectable.
     */
    public Result computeUnitEvidence(String unitId, double windowStart, double windowEnd,
                                      String paramVersion, long asOfSeq) {
        db.setMeta("paramVersion", paramVersion);
        List<JsonNode> spikes = store.spikesForUnit(unitId, asOfSeq);
        List<JsonNode> inWindow = new ArrayList<>();
        for (JsonNode s : spikes) {
            double t = timeSeconds(s);
            // window endpoints are inclusive: a spike exactly on the boundary belongs to the window
            if (t >= windowStart && t <= windowEnd) inWindow.add(s);
        }
        long ops = 0;
        ArrayNode inputIds = Json.MAPPER.createArrayNode();
        for (JsonNode s : inWindow) {
            inputIds.add(s.get("id").asText());
            if (++ops > opBudget) return new Result(Outcome.BUDGET_EXHAUSTED, null,
                    "op budget exceeded while listing inputs for " + unitId);
        }
        if (inWindow.isEmpty()) {
            return new Result(Outcome.SEMANTIC_UNSOLVABLE, null,
                    "no spikes of " + unitId + " intersect window [" + windowStart + ", " + windowEnd + "] s");
        }

        // --- refractory violations: ISI below refractoryMs ---
        double refractoryMs = param(paramVersion, "refractoryMs", 1.5);
        List<Double> times = new ArrayList<>();
        for (JsonNode s : inWindow) times.add(timeSeconds(s));
        times.sort(Comparator.naturalOrder());
        int violations = 0;
        for (int i = 1; i < times.size(); i++) {
            if (++ops > opBudget) return new Result(Outcome.BUDGET_EXHAUSTED, null, "op budget exceeded in ISI scan");
            if ((times.get(i) - times.get(i - 1)) * 1000.0 < refractoryMs) violations++;
        }
        double violationRate = times.size() > 1 ? (double) violations / (times.size() - 1) : 0.0;

        // --- amplitude drift: least-squares slope over time (uV/s) ---
        double slope = 0.0;
        int ampCount = 0;
        {
            double sx = 0, sy = 0, sxx = 0, sxy = 0;
            for (JsonNode s : inWindow) {
                Double a = ampMicrovolts(s);
                if (a == null) continue; // unconvertible values stay raw and are excluded with diagnostics
                if (++ops > opBudget) return new Result(Outcome.BUDGET_EXHAUSTED, null, "op budget exceeded in drift fit");
                double t = timeSeconds(s);
                sx += t; sy += a; sxx += t * t; sxy += t * a;
                ampCount++;
            }
            if (ampCount >= 2) {
                double denom = ampCount * sxx - sx * sx;
                if (denom != 0) slope = (ampCount * sxy - sx * sy) / denom;
            }
        }

        // --- multi-channel propagation: spikes on >=2 channels within coincidenceMs ---
        double coincidenceMs = param(paramVersion, "coincidenceMs", 0.3);
        int propagated = 0;
        List<JsonNode> all = store.waveforms();
        for (JsonNode s : inWindow) {
            if (++ops > opBudget) return new Result(Outcome.BUDGET_EXHAUSTED, null, "op budget exceeded in coincidence scan");
            double t0 = timeSeconds(s);
            int ch0 = s.get("channel").asInt();
            boolean coincident = false;
            for (JsonNode o : all) {
                if (o.get("channel").asInt() != ch0
                        && Math.abs(timeSeconds(o) - t0) * 1000.0 <= coincidenceMs) {
                    coincident = true;
                    break;
                }
            }
            if (coincident) propagated++;
        }

        // parents: current labels for this unit + active parameter version
        ArrayNode parents = Json.MAPPER.createArrayNode();
        for (JsonNode l : store.currentLabels(asOfSeq)) {
            if (l.get("unitId").asText().equals(unitId)) parents.add(l.get("id").asText());
        }
        parents.add(paramVersion);

        ObjectNode payload = Json.obj();
        payload.put("unitId", unitId);
        payload.put("spikeCount", inWindow.size());
        payload.put("refractoryViolationRate", violationRate);
        payload.put("refractoryViolationCount", violations);
        payload.put("amplitudeDriftSlopeUvPerS", slope);
        payload.put("amplitudeSamplesUsed", ampCount);
        payload.put("propagatedSpikeCount", propagated);
        payload.put("windowStartS", windowStart);
        payload.put("windowEndS", windowEnd);
        payload.put("timeUnit", "s");
        payload.put("timeBasis", "recording");
        payload.put("amplitudeUnit", "uV");

        String inputDigest = Json.sha256(Json.canonical(inputIds));
        String id = db.newId("R");
        long seq = db.nextSeq();
        db.execute("INSERT INTO derived_results(id,seq,kind,unit_id,input_digest,param_version,parents_json,"
                        + "window_start,window_end,status,invalidation_reason,payload_json)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id, seq, "unit_evidence", unitId, inputDigest, paramVersion, Json.write(parents),
                windowStart, windowEnd, "VALID", null, Json.write(payload));

        // machine-readable evidence events: these are evidence, never identity
        addEvidence(unitId, "REFRACTORY_VIOLATION", violationRate,
                Json.obj().put("violations", violations).put("isiPairs", Math.max(0, times.size() - 1))
                        .put("refractoryMs", refractoryMs));
        addEvidence(unitId, "AMPLITUDE_DRIFT", slope,
                Json.obj().put("slopeUnit", "uV/s").put("samplesUsed", ampCount));
        addEvidence(unitId, "MULTI_CHANNEL_PROPAGATION", propagated,
                Json.obj().put("coincidenceMs", coincidenceMs).put("spikesInWindow", inWindow.size()));

        return new Result(Outcome.OK, id, "ok");
    }

    private void addEvidence(String unitId, String kind, double magnitude, ObjectNode detail) {
        String id = db.newId("E");
        long seq = db.nextSeq();
        db.execute("INSERT INTO evidence_events(id,seq,unit_id,kind,magnitude,detail_json) VALUES(?,?,?,?,?,?)",
                id, seq, unitId, kind, magnitude, Json.write(detail));
    }

    private double param(String paramVersion, String key, double dflt) {
        for (JsonNode fv : store.featureVersions()) {
            if (fv.get("id").asText().equals(paramVersion) && fv.get("params").has(key)) {
                return fv.get("params").get(key).asDouble();
            }
        }
        return dflt;
    }

    /**
     * Change the analysis window. Only units whose spikes intersect the new window
     * are invalidated and recomputed; everything else keeps its derived results.
     * Returns the ids of affected units (stable order).
     */
    public List<String> applyWindowChange(double newStart, double newEnd, String paramVersion) {
        long asOf = db.currentSeq();
        List<String> affected = new ArrayList<>();
        for (JsonNode unit : store.units()) {
            String unitId = unit.get("id").asText();
            for (JsonNode s : store.spikesForUnit(unitId, asOf)) {
                double t = timeSeconds(s);
                if (t >= newStart && t <= newEnd) {
                    affected.add(unitId);
                    break;
                }
            }
        }
        affected.sort(Comparator.naturalOrder());
        setWindow(newStart, newEnd);
        for (String unitId : affected) {
            invalidateDerived(unitId, "WINDOW_CHANGED");
            computeUnitEvidence(unitId, newStart, newEnd, paramVersion, db.currentSeq());
        }
        return affected;
    }


    /** Digest of the exact spike ids a unit's evidence would consume in a window. */
    public String inputDigestFor(String unitId, double windowStart, double windowEnd, long asOfSeq) {
        ArrayNode ids = Json.MAPPER.createArrayNode();
        for (JsonNode s : store.spikesForUnit(unitId, asOfSeq)) {
            double t = timeSeconds(s);
            if (t >= windowStart && t <= windowEnd) ids.add(s.get("id").asText());
        }
        return Json.sha256(Json.canonical(ids));
    }

    /**
     * Recompute only units whose current inputs differ from their latest VALID
     * derived result. Returns affected unit ids in stable order.
     */
    public List<String> recomputeStaleUnits(String paramVersion, String reason) {
        double[] w = currentWindow();
        long asOf = db.currentSeq();
        List<String> affected = new ArrayList<>();
        for (JsonNode unit : store.units()) {
            String unitId = unit.get("id").asText();
            String status = unit.get("status").asText();
            if (status.equals("SPLIT") || status.equals("MERGED")) continue;
            String digest = inputDigestFor(unitId, w[0], w[1], asOf);
            JsonNode latest = null;
            for (JsonNode d : currentDerived(asOf)) {
                if (d.get("unitId").asText().equals(unitId) && d.get("status").asText().equals("VALID")) {
                    latest = d;
                }
            }
            boolean stale = latest == null || !latest.get("inputDigest").asText().equals(digest)
                    || !latest.get("paramVersion").asText().equals(paramVersion)
                    || latest.get("windowStart").asDouble() != w[0]
                    || latest.get("windowEnd").asDouble() != w[1];
            if (stale) {
                invalidateDerived(unitId, reason);
                computeUnitEvidence(unitId, w[0], w[1], paramVersion, db.currentSeq());
                affected.add(unitId);
            }
        }
        affected.sort(Comparator.naturalOrder());
        return affected;
    }

    /** Mark current VALID derived rows of a unit as INVALIDATED with a reason (append-only). */
    public void invalidateDerived(String unitId, String reason) {
        List<String[]> valid = db.query(
                "SELECT id, payload_json FROM derived_results WHERE unit_id=? AND status='VALID'",
                rs -> new String[]{rs.getString(1), rs.getString(2)}, unitId);
        for (String[] row : valid) {
            String id = db.newId("R");
            long seq = db.nextSeq();
            JsonNode old = findDerived(row[0]);
            db.execute("INSERT INTO derived_results(id,seq,kind,unit_id,input_digest,param_version,parents_json,"
                            + "window_start,window_end,status,invalidation_reason,payload_json)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, seq, old.get("kind").asText(), unitId,
                    old.get("inputDigest").asText(), old.get("paramVersion").asText(),
                    Json.write(old.get("parents")),
                    old.hasNonNull("windowStart") ? old.get("windowStart").asDouble() : null,
                    old.hasNonNull("windowEnd") ? old.get("windowEnd").asDouble() : null,
                    "INVALIDATED", reason, Json.write(old.get("payload")));
        }
    }

    public JsonNode findDerived(String id) {
        List<JsonNode> r = db.query("SELECT * FROM derived_results WHERE id=?", Deriver::derivedToJson, id);
        return r.isEmpty() ? null : r.get(0);
    }

    /** Latest derived row per (kind, unit) visible at asOfSeq. */
    public List<JsonNode> currentDerived(long asOfSeq) {
        List<JsonNode> all = db.query("SELECT * FROM derived_results WHERE seq<=? ORDER BY seq",
                Deriver::derivedToJson, asOfSeq);
        java.util.Map<String, JsonNode> latest = new java.util.TreeMap<>();
        for (JsonNode d : all) {
            latest.put(d.get("kind").asText() + "|" + d.get("unitId").asText(), d);
        }
        return new ArrayList<>(latest.values());
    }

    public List<JsonNode> evidenceFor(String unitId, long asOfSeq) {
        return db.query("SELECT * FROM evidence_events WHERE unit_id=? AND seq<=? ORDER BY seq",
                rs -> {
                    ObjectNode n = Json.obj();
                    n.put("id", rs.getString("id"));
                    n.put("seq", rs.getLong("seq"));
                    n.put("unitId", rs.getString("unit_id"));
                    n.put("kind", rs.getString("kind"));
                    n.put("magnitude", rs.getDouble("magnitude"));
                    n.set("detail", Json.parse(rs.getString("detail_json")));
                    return n;
                }, unitId, asOfSeq);
    }

    static JsonNode derivedToJson(java.sql.ResultSet rs) throws java.sql.SQLException {
        ObjectNode n = Json.obj();
        n.put("id", rs.getString("id"));
        n.put("seq", rs.getLong("seq"));
        n.put("kind", rs.getString("kind"));
        n.put("unitId", rs.getString("unit_id"));
        n.put("inputDigest", rs.getString("input_digest"));
        n.put("paramVersion", rs.getString("param_version"));
        n.set("parents", Json.parse(rs.getString("parents_json")));
        double ws = rs.getDouble("window_start");
        if (rs.wasNull()) n.putNull("windowStart"); else n.put("windowStart", ws);
        double we = rs.getDouble("window_end");
        if (rs.wasNull()) n.putNull("windowEnd"); else n.put("windowEnd", we);
        n.put("status", rs.getString("status"));
        String reason = rs.getString("invalidation_reason");
        if (reason == null) n.putNull("invalidationReason"); else n.put("invalidationReason", reason);
        n.set("payload", Json.parse(rs.getString("payload_json")));
        return n;
    }
}
