package ephys;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Application service: separated immutable materials vs mutable decisions,
 *  deterministic clustering with local invalidation, evidence-only rules,
 *  conflict-preserving adjudication and sequence-based replay. */
public final class EphysService {

    private final Db db;

    public EphysService(Db db) {
        this.db = db;
    }

    // ---------------- meta ----------------

    public String metaGet(String k) {
        try (PreparedStatement ps = db.prepare("SELECT v FROM meta WHERE k=?")) {
            ps.setString(1, k);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void metaPut(String k, String v) {
        try (PreparedStatement ps = db.prepare(
                "INSERT INTO meta(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
            ps.setString(1, k);
            ps.setString(2, v);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------- materials ----------------

    public void putMaterial(String kind, String id, String version, JsonNode payload) {
        String hash = Json.shortHash(Json.canonical(payload));
        long seq = db.headSeq();
        try (PreparedStatement ps = db.prepare(
                "INSERT OR IGNORE INTO materials(kind,id,version,payload,hash,seq) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, kind);
            ps.setString(2, id);
            ps.setString(3, version);
            ps.setString(4, Json.canonical(payload));
            ps.setString(5, hash);
            ps.setLong(6, seq);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public JsonNode materialPayload(String kind, String id) {
        try (PreparedStatement ps = db.prepare(
                "SELECT payload FROM materials WHERE kind=? AND id=? ORDER BY seq DESC LIMIT 1")) {
            ps.setString(1, kind);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Json.parse(rs.getString(1)) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public ArrayNode materialsJson() {
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        try (PreparedStatement ps = db.prepare(
                "SELECT kind,id,version,payload,hash,seq FROM materials ORDER BY kind,id,version")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectNode m = arr.addObject();
                    m.put("kind", rs.getString(1));
                    m.put("id", rs.getString(2));
                    m.put("version", rs.getString(3));
                    m.set("payload", Json.parse(rs.getString(4)));
                    m.put("hash", rs.getString(5));
                    m.put("seq", rs.getLong(6));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return arr;
    }

    // ---------------- event log ----------------

    public long appendEvent(String kind, String status, ObjectNode payload, String paramVersion,
                            List<Long> parents) {
        return appendEventAt(db.nextSeq(), kind, status, payload, paramVersion, parents);
    }

    long appendEventAt(long seq, String kind, String status, ObjectNode payload, String paramVersion,
                       List<Long> parents) {
        String inputHash = Json.shortHash(Json.canonical(payload));
        ArrayNode p = JsonNodeFactory.instance.arrayNode();
        parents.stream().sorted().forEachOrdered(p::add);
        try (PreparedStatement ps = db.prepare(
                "INSERT INTO events(seq,kind,status,payload,input_hash,param_version,parents,created)"
                    + " VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, seq);
            ps.setString(2, kind);
            ps.setString(3, status);
            ps.setString(4, Json.canonical(payload));
            ps.setString(5, inputHash);
            ps.setString(6, paramVersion);
            ps.setString(7, Json.write(p));
            ps.setString(8, "seq-" + seq);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return seq;
    }

    public List<JsonNode> events(long upto) {
        List<JsonNode> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepare(
                "SELECT seq,kind,status,payload,input_hash,param_version,parents,created"
                    + " FROM events WHERE seq<=? ORDER BY seq")) {
            ps.setLong(1, upto);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectNode ev = JsonNodeFactory.instance.objectNode();
                    ev.put("seq", rs.getLong(1));
                    ev.put("kind", rs.getString(2));
                    ev.put("status", rs.getString(3));
                    ev.set("payload", Json.parse(rs.getString(4)));
                    ev.put("input_hash", rs.getString(5));
                    if (rs.getString(6) != null) ev.put("param_version", rs.getString(6));
                    ev.set("parents", Json.parse(rs.getString(7)));
                    ev.put("created", rs.getString(8));
                    out.add(ev);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    // ---------------- spikes ----------------

    public List<Spike> spikes(long upto) {
        List<Spike> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepare(
                "SELECT id,channel,t_ms,t_raw,amp_uv,amp_raw,wave_ref,ord FROM spikes"
                    + " WHERE imported_seq<=? ORDER BY ord,id")) {
            ps.setLong(1, upto);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Double t = (Double) rs.getObject(3);
                    Double a = (Double) rs.getObject(5);
                    out.add(new Spike(rs.getString(1), rs.getString(2), t, a,
                        rs.getString(4), rs.getString(6), (String) rs.getObject(7), rs.getInt(8)));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public Map<String, Spike> spikeMap(long upto) {
        Map<String, Spike> m = new LinkedHashMap<>();
        for (Spike s : spikes(upto)) m.put(s.id(), s);
        return m;
    }

    // ---------------- probe geometry & feature params ----------------

    public Map<String, Integer> channelIndex() {
        Map<String, Integer> idx = new LinkedHashMap<>();
        JsonNode g = materialPayload("probe_geometry", "probe-01");
        if (g == null) return idx;
        int i = 0;
        for (JsonNode c : g.withArray("channels")) idx.put(c.get("id").asText(), i++);
        return idx;
    }

    public Map<String, double[]> channelPos() {
        Map<String, double[]> pos = new LinkedHashMap<>();
        JsonNode g = materialPayload("probe_geometry", "probe-01");
        if (g == null) return pos;
        for (JsonNode c : g.withArray("channels")) {
            List<Diag> d = new ArrayList<>();
            Double x = Quantity.from(c.get("x"), "length", d, "/x").value();
            Double y = Quantity.from(c.get("y"), "length", d, "/y").value();
            if (x != null && y != null) pos.put(c.get("id").asText(), new double[] {x, y});
        }
        return pos;
    }

    public Clusterer.Params featureParams(String fv) {
        JsonNode m = materialPayload("feature_set", fv);
        if (m == null) return null;
        return Clusterer.Params.fromJson(m.get("params"));
    }

    static void setDouble(PreparedStatement ps, int i, Double v) throws Exception {
        if (v == null) ps.setNull(i, java.sql.Types.REAL); else ps.setDouble(i, v);
    }

    static void partition(Diag d, List<Diag> errors, List<Diag> warnings) {
        if ("error".equals(d.severity())) errors.add(d); else warnings.add(d);
    }

    // ---------------- import ----------------

    public Outcome<ObjectNode> importBatch(JsonNode batch) {
        if (batch == null || !batch.isObject()) {
            return new Outcome.CorruptFormat<>(
                List.of(Diag.error("import", "/", "batch must be a JSON object")));
        }
        List<Diag> errors = new ArrayList<>();
        List<Diag> warnings = new ArrayList<>();
        JsonNode spikesNode = batch.get("spikes");
        if (spikesNode == null || !spikesNode.isArray()) {
            return new Outcome.CorruptFormat<>(
                List.of(Diag.error("import", "/spikes", "missing or non-array spikes section")));
        }
        Set<String> refs = new LinkedHashSet<>();
        if (batch.hasNonNull("waveform_refs") && batch.get("waveform_refs").isArray()) {
            for (JsonNode r : batch.get("waveform_refs")) refs.add(r.get("id").asText());
        }
        List<Spike> parsed = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < spikesNode.size(); i++) {
            JsonNode n = spikesNode.get(i);
            String path = "/spikes/" + i;
            if (!n.isObject()) {
                errors.add(Diag.error("import", path, "spike entry must be an object"));
                continue;
            }
            String id = n.hasNonNull("id") ? n.get("id").asText() : null;
            String channel = n.hasNonNull("channel") ? n.get("channel").asText() : null;
            if (id == null || id.isBlank()) {
                errors.add(Diag.error("import", path + "/id", "missing spike id"));
            } else if (!seen.add(id)) {
                errors.add(Diag.error("import", path + "/id", "duplicate spike id '" + id + "'"));
            }
            if (channel == null || channel.isBlank()) {
                errors.add(Diag.error("import", path + "/channel", "missing channel"));
            }
            List<Diag> td = new ArrayList<>();
            List<Diag> ad = new ArrayList<>();
            Quantity t = Quantity.from(n.get("time"), "time", td, path + "/time");
            Quantity a = Quantity.from(n.get("amplitude"), "voltage", ad, path + "/amplitude");
            for (Diag d : td) partition(d, errors, warnings);
            for (Diag d : ad) partition(d, errors, warnings);
            String waveRef = n.hasNonNull("wave_ref") ? n.get("wave_ref").asText() : null;
            if (waveRef != null && !refs.isEmpty() && !refs.contains(waveRef)) {
                errors.add(Diag.error("import", path + "/wave_ref",
                    "waveform reference '" + waveRef + "' is not declared in waveform_refs"));
            }
            if (id != null && !id.isBlank()) {
                parsed.add(new Spike(id, channel, t.value(), a.value(), t.raw(), a.raw(), waveRef, i));
            }
        }
        if (!errors.isEmpty()) {
            List<Diag> all = new ArrayList<>(errors);
            all.addAll(warnings);
            return new Outcome.CorruptFormat<>(all);
        }

        long head = db.headSeq();
        long seq = head + 1;
        try {
            db.txRun(() -> {
                insertGuard(seq);
                writeOptionalMaterials(batch, "waveform_refs", "waveform_ref");
                writeOptionalMaterials(batch, "thresholds", "detection_threshold");
                if (batch.hasNonNull("probe")) {
                    putMaterial("probe_geometry", batch.get("probe").get("id").asText(), "1",
                        batch.get("probe"));
                }
                if (batch.hasNonNull("recording")) {
                    putMaterial("recording_time", batch.get("recording").get("session_id").asText(),
                        "1", batch.get("recording"));
                }
                insertSpikes(parsed, seq);
                ObjectNode payload = JsonNodeFactory.instance.objectNode();
                payload.put("batch_id",
                    batch.hasNonNull("batch_id") ? batch.get("batch_id").asText() : "batch-" + seq);
                payload.put("count", parsed.size());
                ArrayNode ids = payload.putArray("spike_ids");
                for (Spike s : parsed) ids.add(s.id());
                payload.putArray("materials");
                ArrayNode diagArr = payload.putArray("diagnostics");
                for (Diag d : warnings) diagArr.add(d.toJson());
                payload.putArray("touches");
                appendEventAt(seq, "import", "ok", payload, null, List.of(head));
                completeGuard(seq);
            });
        } catch (RuntimeException e) {
            return new Outcome.CorruptFormat<>(List.of(Diag.error("import", "/",
                "import aborted and rolled back: " + e.getMessage())));
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("event_seq", seq);
        result.put("imported", parsed.size());
        ArrayNode diagArr = result.putArray("diagnostics");
        for (Diag d : warnings) diagArr.add(d.toJson());
        return new Outcome.Ok<>(result, warnings);
    }

    void insertGuard(long seq) {
        try (PreparedStatement g = db.prepare(
                "INSERT INTO import_guard(id,started,status) VALUES(?,?,?)")) {
            g.setLong(1, seq);
            g.setString(2, "seq-" + seq);
            g.setString(3, "running");
            g.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    void completeGuard(long seq) {
        try (PreparedStatement g = db.prepare("UPDATE import_guard SET status='done' WHERE id=?")) {
            g.setLong(1, seq);
            g.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    void writeOptionalMaterials(JsonNode batch, String section, String kind) {
        if (!batch.hasNonNull(section) || !batch.get(section).isArray()) return;
        for (JsonNode r : batch.get(section)) {
            putMaterial(kind, r.get("id").asText(),
                r.hasNonNull("version") ? r.get("version").asText() : "1", r);
        }
    }

    void insertSpikes(List<Spike> parsed, long seq) {
        try (PreparedStatement ps = db.prepare(
                "INSERT INTO spikes(id,channel,t_ms,t_raw,amp_uv,amp_raw,wave_ref,ord,imported_seq)"
                    + " VALUES(?,?,?,?,?,?,?,?,?)")) {
            for (Spike s : parsed) {
                ps.setString(1, s.id());
                ps.setString(2, s.channel());
                setDouble(ps, 3, s.tMs());
                ps.setString(4, s.tRaw());
                setDouble(ps, 5, s.ampUv());
                ps.setString(6, s.ampRaw());
                ps.setString(7, s.waveRef());
                ps.setInt(8, s.ord());
                ps.setLong(9, seq);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------- clustering ----------------

    public Outcome<ObjectNode> runClustering(String fv) {
        Clusterer.Params params = featureParams(fv);
        if (params == null) {
            return new Outcome.SemanticUnsolvable<>(List.of(Diag.error("cluster", "/feature_version",
                "unknown feature version '" + fv + "'")));
        }
        State st = head();
        List<Spike> all = spikes(db.headSeq());
        List<Diag> diags = new ArrayList<>();
        Outcome<Clusterer.Result> r =
            new Clusterer().cluster(all, params, channelIndex(), diags);
        if (!(r instanceof Outcome.Ok<Clusterer.Result> ok)) {
            return castFail(r);
        }
        List<List<String>> clusters = ok.value().clusters();
        List<State.Unit> active = st.activeUnits();
        List<String> matched = matchClusters(clusters, active);

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.putNull("window");
        payload.put("param_version", fv);
        payload.set("params", params.toJson());
        ArrayNode inputIds = payload.putArray("input_spike_ids");
        all.stream().map(Spike::id).sorted().forEach(inputIds::add);
        ArrayNode carr = payload.putArray("clusters");
        ArrayNode touches = payload.putArray("touches");
        Set<String> matchedUids = new LinkedHashSet<>();
        for (int i = 0; i < clusters.size(); i++) {
            List<String> members = clusters.get(i);
            String uid = matched.get(i);
            String continued = uid;
            if (uid == null) {
                uid = newUid("cluster", fv, members);
                continued = null;
            } else {
                matchedUids.add(uid);
            }
            ObjectNode c = carr.addObject();
            c.put("index", i);
            c.put("label", fv + "#" + i);
            c.put("uid", uid);
            if (continued != null) c.put("continued_from", continued); else c.putNull("continued_from");
            ArrayNode m = c.putArray("members");
            for (String id : members) m.add(id);
            touches.add(uid);
        }
        ArrayNode retired = payload.putArray("retired");
        for (State.Unit u : active) {
            if (!matchedUids.contains(u.uid)) {
                ObjectNode rr = retired.addObject();
                rr.put("uid", u.uid);
                rr.put("reason", "no_matching_cluster");
                touches.add(u.uid);
            }
        }
        long seq = appendEvent("cluster", "ok", payload, fv, List.of(db.headSeq()));
        evidencePass(uidsOf(head().activeUnits()), fv, seq);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("event_seq", seq);
        result.set("cluster", payload);
        return new Outcome.Ok<>(result, diags);
    }

    public Outcome<ObjectNode> recomputeWindow(double t0, double t1, String reason) {
        if (!(t1 > t0)) {
            return new Outcome.SemanticUnsolvable<>(List.of(Diag.error("window", "/window",
                "empty or inverted window [" + t0 + ", " + t1 + ") ms")));
        }
        State st = head();
        String fv = st.featureVersion;
        if (fv == null) {
            return new Outcome.SemanticUnsolvable<>(List.of(Diag.error("window", "/feature_version",
                "no clustering has been computed yet")));
        }
        Clusterer.Params params = featureParams(fv);
        Map<String, Spike> byId = spikeMap(db.headSeq());
        List<State.Unit> affected = new ArrayList<>();
        List<String> unaffected = new ArrayList<>();
        for (State.Unit u : st.activeUnits()) {
            boolean hit = false;
            for (String id : u.members) {
                Spike s = byId.get(id);
                if (s != null && s.tMs() != null && s.tMs() >= t0 && s.tMs() < t1) {
                    hit = true;
                    break;
                }
            }
            if (hit) affected.add(u); else unaffected.add(u.uid);
        }
        Set<String> affectedSpikes = new LinkedHashSet<>();
        for (State.Unit u : affected) affectedSpikes.addAll(u.members);
        List<Spike> recluster = new ArrayList<>();
        for (String id : affectedSpikes) {
            Spike s = byId.get(id);
            if (s != null) recluster.add(s);
        }
        List<Diag> diags = new ArrayList<>();
        Outcome<Clusterer.Result> r =
            new Clusterer().cluster(recluster, params, channelIndex(), diags);
        if (!(r instanceof Outcome.Ok<Clusterer.Result> ok)) {
            return castFail(r);
        }
        List<List<String>> clusters = ok.value().clusters();
        List<String> matched = matchClusters(clusters, affected);

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        ObjectNode w = payload.putObject("window");
        w.put("t0", t0);
        w.put("t1", t1);
        w.put("unit", "ms");
        w.put("half_open", true);
        payload.put("param_version", fv);
        payload.set("params", params.toJson());
        ArrayNode inputIds = payload.putArray("input_spike_ids");
        affectedSpikes.stream().sorted().forEach(inputIds::add);
        ArrayNode carr = payload.putArray("clusters");
        ArrayNode touches = payload.putArray("touches");
        Set<String> matchedUids = new LinkedHashSet<>();
        for (int i = 0; i < clusters.size(); i++) {
            List<String> members = clusters.get(i);
            String uid = matched.get(i);
            String continued = uid;
            if (uid == null) {
                uid = newUid("cluster", fv, members);
                continued = null;
            } else {
                matchedUids.add(uid);
            }
            ObjectNode c = carr.addObject();
            c.put("index", i);
            c.put("label", fv + "#" + i);
            c.put("uid", uid);
            if (continued != null) c.put("continued_from", continued); else c.putNull("continued_from");
            ArrayNode m = c.putArray("members");
            for (String id : members) m.add(id);
            touches.add(uid);
        }
        ArrayNode retired = payload.putArray("retired");
        for (State.Unit u : affected) {
            if (!matchedUids.contains(u.uid)) {
                ObjectNode rr = retired.addObject();
                rr.put("uid", u.uid);
                rr.put("reason", "no_matching_cluster_in_window");
                touches.add(u.uid);
            }
        }
        long seq = appendEvent("cluster", "ok", payload, fv, List.of(db.headSeq()));

        ObjectNode inv = JsonNodeFactory.instance.objectNode();
        inv.set("window", w);
        inv.put("reason", reason);
        inv.put("param_version", fv);
        ArrayNode aff = inv.putArray("affected_uids");
        for (State.Unit u : affected) aff.add(u.uid);
        ArrayNode unaff = inv.putArray("unaffected_uids");
        for (String u : unaffected) unaff.add(u);
        ArrayNode invTouches = inv.putArray("touches");
        for (State.Unit u : affected) invTouches.add(u.uid);
        long invSeq = appendEvent("invalidate", "ok", inv, fv, List.of(seq));

        evidencePass(uidsOf(head().activeUnits()), fv, invSeq);

        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("event_seq", seq);
        result.put("invalidate_seq", invSeq);
        ArrayNode ra = result.putArray("affected_uids");
        for (State.Unit u : affected) ra.add(u.uid);
        ArrayNode ru = result.putArray("unaffected_uids");
        for (String u : unaffected) ru.add(u);
        return new Outcome.Ok<>(result, diags);
    }

    @SuppressWarnings("unchecked")
    private static <T> Outcome<T> castFail(Outcome<?> r) {
        if (r instanceof Outcome.BudgetExhausted<?> b) {
            return (Outcome<T>) new Outcome.BudgetExhausted<>(b.detail(), b.diags());
        }
        if (r instanceof Outcome.CorruptFormat<?> c) {
            return (Outcome<T>) new Outcome.CorruptFormat<>(c.diags());
        }
        return (Outcome<T>) new Outcome.SemanticUnsolvable<>(r.diags());
    }

    static List<String> uidsOf(Collection<State.Unit> units) {
        List<String> out = new ArrayList<>();
        for (State.Unit u : units) out.add(u.uid);
        return out;
    }

    static String newUid(String kind, String fv, List<String> members) {
        List<String> sorted = new ArrayList<>(members);
        sorted.sort(String::compareTo);
        return "u-" + Json.shortHash(kind + "|" + fv + "|" + String.join(",", sorted));
    }

    /** Match clusters to existing units by Jaccard overlap >= 0.5; ties break
     *  to the lexicographically smaller uid so equivalent input is stable. */
    static List<String> matchClusters(List<List<String>> clusters, List<State.Unit> units) {
        List<String> matched = new ArrayList<>(clusters.size());
        for (int i = 0; i < clusters.size(); i++) matched.add(null);
        record Cand(int cluster, String uid, double jaccard) {}
        List<Cand> cands = new ArrayList<>();
        for (int i = 0; i < clusters.size(); i++) {
            Set<String> members = new LinkedHashSet<>(clusters.get(i));
            for (State.Unit u : units) {
                Set<String> inter = new LinkedHashSet<>(members);
                inter.retainAll(u.members);
                if (inter.isEmpty()) continue;
                Set<String> union = new LinkedHashSet<>(members);
                union.addAll(u.members);
                double j = (double) inter.size() / union.size();
                if (j >= 0.5) cands.add(new Cand(i, u.uid, j));
            }
        }
        cands.sort(Comparator.comparingDouble(Cand::jaccard).reversed()
            .thenComparing(Cand::uid).thenComparingInt(Cand::cluster));
        Set<String> usedUnits = new LinkedHashSet<>();
        Set<Integer> usedClusters = new LinkedHashSet<>();
        for (Cand c : cands) {
            if (usedUnits.contains(c.uid()) || usedClusters.contains(c.cluster())) continue;
            usedUnits.add(c.uid());
            usedClusters.add(c.cluster());
            matched.set(c.cluster(), c.uid());
        }
        return matched;
    }

    // ---------------- evidence ----------------

    void evidencePass(Collection<String> uids, String fv, long parentSeq) {
        State st = head();
        Clusterer.Params params = featureParams(fv != null ? fv : st.featureVersion);
        if (params == null) return;
        Map<String, Spike> byId = spikeMap(db.headSeq());
        Map<String, List<Spike>> membersByUid = new LinkedHashMap<>();
        Map<String, String> spikeChannel = new LinkedHashMap<>();
        for (State.Unit u : st.activeUnits()) {
            List<Spike> ms = new ArrayList<>();
            for (String id : u.members) {
                Spike s = byId.get(id);
                if (s != null && s.usable()) {
                    ms.add(s);
                    spikeChannel.put(s.id(), s.channel());
                }
            }
            membersByUid.put(u.uid, ms);
        }
        Set<String> existing = st.evidenceIds();
        List<com.fasterxml.jackson.databind.node.ObjectNode> items = new ArrayList<>();
        for (String uid : uids) {
            List<Spike> ms = membersByUid.get(uid);
            if (ms == null) continue;
            for (var item : Evidence.refractoryViolations(uid, ms, params.refractoryMs())) {
                if (!existing.contains(item.get("id").asText())) items.add(item);
            }
            for (var item : Evidence.amplitudeDrift(uid, ms, params.driftSlopeUvPerS())) {
                if (!existing.contains(item.get("id").asText())) items.add(item);
            }
        }
        for (var item : Evidence.propagation(uidsOf(st.activeUnits()), membersByUid, channelPos(),
                spikeChannel, params.coincidenceMs(), params.propagationDistUm())) {
            if (!existing.contains(item.get("id").asText())) items.add(item);
        }
        if (items.isEmpty()) return;
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        ArrayNode arr = payload.putArray("items");
        ArrayNode touches = payload.putArray("touches");
        for (var item : items) {
            arr.add(item);
            if (item.hasNonNull("unit_uid")) touches.add(item.get("unit_uid").asText());
            for (JsonNode u : item.withArray("unit_uids")) touches.add(u.asText());
        }
        appendEvent("evidence", "ok", payload, st.featureVersion, List.of(parentSeq));
    }

    // ---------------- adjudication ----------------

    public Outcome<ObjectNode> adjudicate(JsonNode req) {
        if (req == null || !req.isObject() || !req.hasNonNull("op")) {
            return new Outcome.CorruptFormat<>(
                List.of(Diag.error("adjudication", "/op", "missing op")));
        }
        String op = req.get("op").asText();
        long headSeq = db.headSeq();
        long baseSeq = req.hasNonNull("base_seq") ? req.get("base_seq").asLong() : headSeq;
        if (baseSeq > headSeq) {
            return new Outcome.SemanticUnsolvable<>(List.of(Diag.error("adjudication", "/base_seq",
                "base_seq " + baseSeq + " is ahead of head " + headSeq)));
        }
        List<String> targets = new ArrayList<>();
        if (req.hasNonNull("targets")) for (JsonNode t : req.get("targets")) targets.add(t.asText());
        if (targets.isEmpty() && req.hasNonNull("target")) targets.add(req.get("target").asText());
        State st = stateAt(headSeq);
        List<Diag> errors = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            if (st.unit(targets.get(i)) == null) {
                errors.add(Diag.error("adjudication", "/targets/" + i,
                    "unknown unit uid '" + targets.get(i) + "' at seq " + headSeq));
            }
        }
        if (!errors.isEmpty()) return new Outcome.SemanticUnsolvable<>(errors);

        if ("resolve".equals(op)) {
            return resolveConflict(req, baseSeq, headSeq);
        }

        List<Long> diverged = new ArrayList<>();
        for (JsonNode ev : events(headSeq)) {
            if (ev.get("seq").asLong() <= baseSeq) continue;
            for (JsonNode t : ev.get("payload").withArray("touches")) {
                if (targets.contains(t.asText())) {
                    diverged.add(ev.get("seq").asLong());
                    break;
                }
            }
        }

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("op", op);
        payload.put("base_seq", baseSeq);
        ArrayNode tarr = payload.putArray("targets");
        targets.forEach(tarr::add);
        if (req.hasNonNull("rationale")) payload.put("rationale", req.get("rationale").asText());
        ArrayNode touches = payload.putArray("touches");
        targets.forEach(touches::add);
        payload.set("candidates", legalCandidates(op, targets, st));

        if (!diverged.isEmpty()) {
            ObjectNode conflict = payload.putObject("conflict");
            conflict.put("message", "targets changed since base_seq; decision kept but not applied");
            ArrayNode ds = conflict.putArray("diverged_by_seqs");
            diverged.forEach(ds::add);
            payload.putNull("result");
            long seq = appendEvent("adjudicate", "conflicted", payload, st.featureVersion,
                List.of(baseSeq));
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            result.put("event_seq", seq);
            result.put("status", "conflicted");
            result.set("event", payload);
            return new Outcome.Ok<>(result, List.of());
        }

        Outcome<ObjectNode> applied = applyDecision(op, targets, req, st, payload);
        if (!(applied instanceof Outcome.Ok<ObjectNode>)) return applied;
        payload.set("result", applied.value());
        for (JsonNode u : applied.value().withArray("touches")) touches.add(u.asText());
        long seq = appendEvent("adjudicate", "ok", payload, st.featureVersion, List.of(baseSeq));
        evidencePass(uidsOf(head().activeUnits()), st.featureVersion, seq);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("event_seq", seq);
        result.put("status", "ok");
        result.set("affected", applied.value().withArray("touches").deepCopy());
        result.set("event", payload);
        return new Outcome.Ok<>(result, List.of());
    }

    private Outcome<ObjectNode> resolveConflict(JsonNode req, long baseSeq, long headSeq) {
        if (!req.hasNonNull("conflict_seq")) {
            return new Outcome.SemanticUnsolvable<>(List.of(Diag.error("adjudication",
                "/conflict_seq", "resolve requires conflict_seq")));
        }
        long cseq = req.get("conflict_seq").asLong();
        JsonNode conflictEvent = null;
        for (JsonNode ev : events(headSeq)) {
            if (ev.get("seq").asLong() == cseq) conflictEvent = ev;
        }
        if (conflictEvent == null || !"conflicted".equals(conflictEvent.get("status").asText())) {
            return new Outcome.SemanticUnsolvable<>(List.of(Diag.error("adjudication",
                "/conflict_seq", "seq " + cseq + " is not a conflicted adjudication")));
        }
        String decision = req.hasNonNull("decision") ? req.get("decision").asText() : "discard";
        JsonNode p = conflictEvent.get("payload");
        long newSeq = -1;
        if ("apply".equals(decision)) {
            ObjectNode retry = JsonNodeFactory.instance.objectNode();
            retry.put("op", p.get("op").asText());
            retry.set("targets", p.get("targets").deepCopy());
            retry.put("base_seq", headSeq);
            retry.put("rationale", "re-apply of conflicted decision #" + cseq);
            Outcome<ObjectNode> again = adjudicate(retry);
            if (!(again instanceof Outcome.Ok<ObjectNode> ok)) return again;
            newSeq = ok.value().get("event_seq").asLong();
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("op", "resolve");
        result.put("conflict_seq", cseq);
        result.put("decision", decision);
        if (newSeq > 0) result.put("applied_as_seq", newSeq);
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("op", "resolve");
        payload.put("base_seq", headSeq);
        payload.putArray("targets");
        payload.set("result", result);
        payload.putArray("touches");
        payload.putArray("candidates");
        long seq = appendEvent("adjudicate", "ok", payload, head().featureVersion, List.of(cseq));
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("event_seq", seq);
        out.put("status", "ok");
        out.set("affected", result);
        return new Outcome.Ok<>(out, List.of());
    }

    private Outcome<ObjectNode> applyDecision(String op, List<String> targets, JsonNode req, State st,
                                              ObjectNode payload) {
        Map<String, Spike> byId = spikeMap(db.headSeq());
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ArrayNode touches = result.putArray("touches");
        switch (op) {
            case "merge" -> {
                if (targets.size() < 2) {
                    return new Outcome.SemanticUnsolvable<>(List.of(Diag.error("adjudication",
                        "/targets", "merge needs at least two targets")));
                }
                for (String t : targets) {
                    if (!"active".equals(st.unit(t).status)) {
                        return new Outcome.SemanticUnsolvable<>(List.of(Diag.error("adjudication",
                            "/targets", "unit '" + t + "' is not active")));
                    }
                }
                List<String> members = new ArrayList<>();
                for (String t : targets) members.addAll(st.unit(t).members);
                members.sort(Comparator.comparingDouble((String id) -> {
                    Spike s = byId.get(id);
                    return s != null && s.tMs() != null ? s.tMs() : Double.MAX_VALUE;
                }).thenComparing(id -> id));
                List<String> parents = new ArrayList<>(targets);
                parents.sort(String::compareTo);
                String uid = "u-" + Json.shortHash("merge|" + String.join(",", parents)
                    + "|" + String.join(",", members));
                result.put("op", "merge");
                result.put("new_uid", uid);
                ArrayNode m = result.putArray("members");
                members.forEach(m::add);
                ArrayNode retired = result.putArray("retired");
                targets.forEach(retired::add);
                touches.add(uid);
            }
            case "split" -> {
                if (targets.size() != 1) {
                    return new Outcome.SemanticUnsolvable<>(List.of(Diag.error("adjudication",
                        "/targets", "split needs exactly one target")));
                }
                String parent = targets.get(0);
                State.Unit u = st.unit(parent);
                if (!"active".equals(u.status)) {
                    return new Outcome.SemanticUnsolvable<>(List.of(Diag.error("adjudication",
                        "/targets", "unit '" + parent + "' is not active")));
                }
                List<List<String>> groups;
                if (req.hasNonNull("groups") && req.get("groups").isArray()) {
                    groups = new ArrayList<>();
                    for (JsonNode g : req.get("groups")) {
                        List<String> ids = new ArrayList<>();
                        g.forEach(x -> ids.add(x.asText()));
                        groups.add(ids);
                    }
                    Set<String> covered = new LinkedHashSet<>();
                    groups.forEach(covered::addAll);
                    if (groups.size() < 2 || !covered.equals(u.members)) {
                        return new Outcome.SemanticUnsolvable<>(List.of(Diag.error("adjudication",
                            "/groups", "groups must be a partition of the parent members into >= 2")));
                    }
                } else {
                    Outcome<List<List<String>>> auto = autoSplitCandidates(u, byId, st);
                    if (!(auto instanceof Outcome.Ok<List<List<String>>> ok)) return castFail(auto);
                    groups = ok.value();
                }
                result.put("op", "split");
                result.put("parent", parent);
                ArrayNode children = result.putArray("children");
                for (List<String> group : groups) {
                    String child = "u-" + Json.shortHash(
                        "split|" + parent + "|" + String.join(",", new ArrayList<>(group)));
                    ObjectNode c = children.addObject();
                    c.put("uid", child);
                    ArrayNode m = c.putArray("members");
                    group.stream().sorted().forEach(m::add);
                    touches.add(child);
                }
                ArrayNode shared = result.putArray("shared_evidence");
                for (JsonNode ev : st.evidence) {
                    boolean refs = ev.hasNonNull("unit_uid")
                        && parent.equals(ev.get("unit_uid").asText());
                    if (!refs) {
                        for (JsonNode x : ev.withArray("unit_uids")) {
                            if (parent.equals(x.asText())) refs = true;
                        }
                    }
                    if (refs) shared.add(ev.get("id").asText());
                }
            }
            case "reject", "accept" -> {
                if (targets.size() != 1) {
                    return new Outcome.SemanticUnsolvable<>(List.of(Diag.error("adjudication",
                        "/targets", op + " needs exactly one target")));
                }
                result.put("op", op);
                result.put("uid", targets.get(0));
                touches.add(targets.get(0));
            }
            default -> {
                return new Outcome.SemanticUnsolvable<>(List.of(Diag.error("adjudication", "/op",
                    "unknown op '" + op + "'")));
            }
        }
        return new Outcome.Ok<>(result, List.of());
    }

    private Outcome<List<List<String>>> autoSplitCandidates(State.Unit u, Map<String, Spike> byId,
                                                            State st) {
        List<Spike> ms = new ArrayList<>();
        for (String id : u.members) {
            Spike s = byId.get(id);
            if (s != null && s.usable()) ms.add(s);
        }
        ms.sort(Comparator.comparingDouble(Spike::tMs));
        if (ms.size() < 4) {
            return new Outcome.SemanticUnsolvable<>(List.of(Diag.error("adjudication", "/groups",
                "too few timed spikes to derive split candidates for '" + u.uid + "'")));
        }
        List<List<String>> groups = new ArrayList<>();
        int cut = ms.size() / 2;
        groups.add(ms.subList(0, cut).stream().map(Spike::id).sorted().toList());
        groups.add(ms.subList(cut, ms.size()).stream().map(Spike::id).sorted().toList());
        return new Outcome.Ok<>(groups, List.of());
    }

    private ArrayNode legalCandidates(String op, List<String> targets, State st) {
        ArrayNode cands = JsonNodeFactory.instance.arrayNode();
        if ("split".equals(op) && targets.size() == 1) {
            State.Unit u = st.unit(targets.get(0));
            if (u != null && u.members.size() >= 4) {
                Map<String, Spike> byId = spikeMap(db.headSeq());
                List<Spike> ms = new ArrayList<>();
                for (String id : u.members) {
                    Spike s = byId.get(id);
                    if (s != null && s.usable()) ms.add(s);
                }
                ms.sort(Comparator.comparingDouble(Spike::tMs));
                int cut = ms.size() / 2;
                ObjectNode c0 = cands.addObject();
                c0.put("candidate_id", "c0");
                c0.put("rule", "temporal_halves");
                c0.put("adopted", true);
                ArrayNode g0 = c0.putArray("groups");
                g0.add(ms.subList(0, cut).stream().map(Spike::id).sorted()
                    .collect(JsonNodeFactory.instance::arrayNode, ArrayNode::add, ArrayNode::addAll));
                g0.add(ms.subList(cut, ms.size()).stream().map(Spike::id).sorted()
                    .collect(JsonNodeFactory.instance::arrayNode, ArrayNode::add, ArrayNode::addAll));
                ObjectNode c1 = cands.addObject();
                c1.put("candidate_id", "c1");
                c1.put("rule", "no_split_single_unit");
                c1.put("adopted", false);
                ArrayNode g1 = c1.putArray("groups");
                g1.add(u.members.stream().sorted()
                    .collect(JsonNodeFactory.instance::arrayNode, ArrayNode::add, ArrayNode::addAll));
            }
        }
        return cands;
    }

    // ---------------- state replay & views ----------------

    public State stateAt(long seq) {
        return State.replay(events(seq), seq);
    }

    public State head() {
        return stateAt(db.headSeq());
    }

    public ObjectNode stateJson(Long viewSeq) {
        long view = viewSeq != null ? viewSeq : db.headSeq();
        State st = stateAt(view);
        Map<String, Spike> byId = spikeMap(view);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("head_seq", db.headSeq());
        out.put("view_seq", view);
        out.put("feature_version", st.featureVersion);
        out.put("time_basis", "session-relative");
        out.put("time_unit", "ms");
        out.put("coordinate_basis", "probe-frame");
        out.put("coordinate_unit", "um");

        ArrayNode units = out.putArray("units");
        List<State.Unit> sorted = new ArrayList<>(st.units.values());
        sorted.sort(Comparator.comparingLong((State.Unit u) -> u.bornSeq)
            .thenComparing(u -> firstTime(u, byId))
            .thenComparing(u -> u.uid));
        for (State.Unit u : sorted) {
            ObjectNode un = units.addObject();
            un.put("uid", u.uid);
            un.put("stable_identity", true);
            if (u.label != null) un.put("label", u.label);
            un.put("status", u.status);
            un.put("origin", u.origin);
            un.put("born_seq", u.bornSeq);
            if (u.retiredSeq != null) un.put("retired_seq", u.retiredSeq);
            List<Spike> ms = new ArrayList<>();
            for (String id : u.members) {
                Spike s = byId.get(id);
                if (s != null) ms.add(s);
            }
            ms.sort(Comparator.comparing((Spike s) -> s.tMs() == null ? Double.MAX_VALUE : s.tMs())
                .thenComparing(Spike::id));
            un.put("n_spikes", ms.size());
            ArrayNode members = un.putArray("members");
            double t0 = Double.NaN, t1 = Double.NaN, ampSum = 0;
            int ampN = 0;
            for (Spike s : ms) {
                members.add(s.id());
                if (s.tMs() != null) {
                    t0 = Double.isNaN(t0) ? s.tMs() : Math.min(t0, s.tMs());
                    t1 = Double.isNaN(t1) ? s.tMs() : Math.max(t1, s.tMs());
                }
                if (s.ampUv() != null) {
                    ampSum += s.ampUv();
                    ampN++;
                }
            }
            if (!Double.isNaN(t0)) {
                ObjectNode vi = un.putObject("valid_interval");
                vi.put("t0", t0);
                vi.put("t1", t1);
                vi.put("unit", "ms");
                vi.put("half_open", true);
            }
            if (ampN > 0) {
                ObjectNode ma = un.putObject("mean_amplitude");
                ma.put("value", ampSum / ampN);
                ma.put("unit", "uV");
            }
            ArrayNode inv = un.putArray("invalidations");
            for (JsonNode i : st.invalidations) {
                for (JsonNode a : i.withArray("affected_uids")) {
                    if (a.asText().equals(u.uid)) {
                        ObjectNode x = inv.addObject();
                        x.set("window", i.get("window"));
                        x.put("reason", i.get("reason").asText());
                    }
                }
            }
        }

        ArrayNode ev = out.putArray("evidence");
        st.evidence.forEach(ev::add);
        ArrayNode inv = out.putArray("invalidations");
        st.invalidations.forEach(inv::add);
        ArrayNode conflicts = out.putArray("conflicts");
        st.conflicts.forEach(conflicts::add);
        ArrayNode cands = out.putArray("candidates");
        st.candidates.forEach(cands::add);
        ObjectNode cm = out.putObject("cluster_map");
        st.clusterToUnit.forEach(cm::put);

        ArrayNode diags = out.putArray("diagnostics");
        for (JsonNode e : events(view)) {
            if (!"import".equals(e.get("kind").asText())) continue;
            for (JsonNode d : e.get("payload").withArray("diagnostics")) diags.add(d);
        }
        return out;
    }

    private static double firstTime(State.Unit u, Map<String, Spike> byId) {
        double best = Double.MAX_VALUE;
        for (String id : u.members) {
            Spike s = byId.get(id);
            if (s != null && s.tMs() != null) best = Math.min(best, s.tMs());
        }
        return best;
    }

    /** Provenance: which materials, rule versions and decisions touched a unit. */
    public ArrayNode trace(String uid) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (JsonNode ev : events(db.headSeq())) {
            boolean hit = false;
            for (JsonNode t : ev.get("payload").withArray("touches")) {
                if (uid.equals(t.asText())) hit = true;
            }
            if (!hit && ev.get("payload").toString().contains(uid)) hit = true;
            if (!hit) continue;
            ObjectNode n = out.addObject();
            n.put("seq", ev.get("seq").asLong());
            n.put("kind", ev.get("kind").asText());
            n.put("status", ev.get("status").asText());
            if (ev.hasNonNull("param_version")) n.put("param_version", ev.get("param_version").asText());
            n.put("input_hash", ev.get("input_hash").asText());
            n.set("parents", ev.get("parents"));
        }
        return out;
    }
}
