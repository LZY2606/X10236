package gsb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Material and decision storage. Raw representations are preserved on import;
 * only the derived layer normalizes. Unconvertible values stay in place and
 * produce positioned diagnostics instead of null/default substitutes.
 */
public final class Store {
    public final Database db;

    public Store(Database db) {
        this.db = db;
    }

    // ---------- import of immutable materials ----------

    public String addWaveformRef(JsonNode n, String position) {
        String id = n.get("id").asText();
        Quantity time = Quantity.of(n.get("time").get("value").asText(),
                n.get("time").get("unit").asText(), n.get("time").path("basis").asText("recording"));
        Quantity amp = Quantity.of(n.get("amplitude").get("value").asText(),
                n.get("amplitude").get("unit").asText(), null);
        Quantity rate = Quantity.of(n.get("sampleRate").get("value").asText(),
                n.get("sampleRate").get("unit").asText(), null);
        if (!time.convertible()) addDiagnostic("waveform_refs", position + ".time", "ERROR",
                "unconvertible time kept raw: " + time.problem());
        if (!amp.convertible()) addDiagnostic("waveform_refs", position + ".amplitude", "ERROR",
                "unconvertible amplitude kept raw: " + amp.problem());
        if (!rate.convertible()) addDiagnostic("waveform_refs", position + ".sampleRate", "ERROR",
                "unconvertible sample rate kept raw: " + rate.problem());
        long seq = db.nextSeq();
        db.execute("INSERT INTO waveform_refs(id,seq,channel,time_value,time_unit,time_basis,"
                        + "amplitude_value,amplitude_unit,samples_json,sample_rate_value,sample_rate_unit,digest)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id, seq, n.get("channel").asInt(),
                time.rawValue, time.rawUnit, time.basis,
                amp.rawValue, amp.rawUnit,
                Json.write(n.get("samples")), rate.rawValue, rate.rawUnit,
                Json.digestOf(n));
        return id;
    }

    public String addProbeGeometry(JsonNode n) {
        String id = n.get("id").asText();
        long seq = db.nextSeq();
        db.execute("INSERT INTO probe_geometries(id,seq,frame,sites_json,digest) VALUES(?,?,?,?,?)",
                id, seq, n.get("frame").asText(), Json.write(n.get("sites")), Json.digestOf(n));
        return id;
    }

    public String addThreshold(JsonNode n, String position) {
        String id = n.get("id").asText();
        Quantity q = Quantity.of(n.get("threshold").get("value").asText(), n.get("threshold").get("unit").asText(), null);
        if (!q.convertible()) addDiagnostic("detection_thresholds", position + ".threshold", "ERROR",
                "unconvertible threshold kept raw: " + q.problem());
        long seq = db.nextSeq();
        db.execute("INSERT INTO detection_thresholds(id,seq,channel,value,unit,version,digest) VALUES(?,?,?,?,?,?,?)",
                id, seq, n.get("channel").asInt(), q.rawValue, q.rawUnit,
                n.get("version").asInt(), Json.digestOf(n));
        return id;
    }

    public String addFeatureVersion(JsonNode n) {
        String id = n.get("id").asText();
        long seq = db.nextSeq();
        db.execute("INSERT INTO feature_versions(id,seq,name,params_json,digest) VALUES(?,?,?,?,?)",
                id, seq, n.get("name").asText(), Json.write(n.get("params")), Json.digestOf(n));
        return id;
    }

    public String addClusterLabel(JsonNode n) {
        String id = n.get("id").asText();
        long seq = db.nextSeq();
        db.execute("INSERT INTO cluster_labels(id,seq,label_set_version,cluster_no,unit_id,spike_ids_json,digest)"
                        + " VALUES(?,?,?,?,?,?,?)",
                id, seq, n.get("labelSetVersion").asInt(), n.get("clusterNo").asInt(),
                n.get("unitId").asText(), Json.write(sortedCopy(n.get("spikeIds"))), Json.digestOf(n));
        return id;
    }

    /** Equivalent input yields stable ordering: spike id lists are stored sorted. */
    private static JsonNode sortedCopy(JsonNode arr) {
        List<String> ids = new ArrayList<>();
        arr.forEach(x -> ids.add(x.asText()));
        ids.sort(Comparator.naturalOrder());
        ArrayNode out = Json.MAPPER.createArrayNode();
        ids.forEach(out::add);
        return out;
    }

    public String addUnit(String origin) {
        String id = db.newId("U");
        long seq = db.nextSeq();
        db.execute("INSERT INTO units(id,seq,origin,status) VALUES(?,?,?,?)", id, seq, origin, "ACTIVE");
        return id;
    }

    public void addDiagnostic(String target, String position, String severity, String message) {
        String id = db.newId("D");
        long seq = db.nextSeq();
        db.execute("INSERT INTO diagnostics(id,seq,target,position,severity,message) VALUES(?,?,?,?,?,?)",
                id, seq, target, position, severity, message);
    }

    // ---------- queries ----------

    public List<JsonNode> waveforms() {
        return db.query("SELECT * FROM waveform_refs ORDER BY seq", Store::waveformToJson);
    }

    public JsonNode waveform(String id) {
        List<JsonNode> r = db.query("SELECT * FROM waveform_refs WHERE id=?", Store::waveformToJson, id);
        return r.isEmpty() ? null : r.get(0);
    }

    private static JsonNode waveformToJson(java.sql.ResultSet rs) throws java.sql.SQLException {
        ObjectNode n = Json.obj();
        n.put("id", rs.getString("id"));
        n.put("seq", rs.getLong("seq"));
        n.put("channel", rs.getInt("channel"));
        ObjectNode time = n.putObject("time");
        time.put("value", rs.getString("time_value"));
        time.put("unit", rs.getString("time_unit"));
        time.put("basis", rs.getString("time_basis"));
        ObjectNode amp = n.putObject("amplitude");
        amp.put("value", rs.getString("amplitude_value"));
        amp.put("unit", rs.getString("amplitude_unit"));
        n.set("samples", Json.parse(rs.getString("samples_json")));
        ObjectNode rate = n.putObject("sampleRate");
        rate.put("value", rs.getString("sample_rate_value"));
        rate.put("unit", rs.getString("sample_rate_unit"));
        n.put("digest", rs.getString("digest"));
        return n;
    }

    public List<JsonNode> clusterLabels() {
        return db.query("SELECT * FROM cluster_labels ORDER BY seq", rs -> {
            ObjectNode n = Json.obj();
            n.put("id", rs.getString("id"));
            n.put("seq", rs.getLong("seq"));
            n.put("labelSetVersion", rs.getInt("label_set_version"));
            n.put("clusterNo", rs.getInt("cluster_no"));
            n.put("unitId", rs.getString("unit_id"));
            n.set("spikeIds", Json.parse(rs.getString("spike_ids_json")));
            n.put("digest", rs.getString("digest"));
            return n;
        });
    }

    /** Latest label set version visible at the given seq. */
    public int latestLabelVersion(long asOfSeq) {
        List<Integer> v = db.query(
                "SELECT MAX(label_set_version) FROM cluster_labels WHERE seq<=?",
                rs -> rs.getInt(1), asOfSeq);
        return v.isEmpty() ? 0 : v.get(0);
    }

    /** All label rows of one specific label set version. */
    public List<JsonNode> labelsOfVersion(int version) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode l : clusterLabels()) {
            if (l.get("labelSetVersion").asInt() == version) out.add(l);
        }
        out.sort(Comparator.comparing(l -> l.get("id").asText()));
        return out;
    }

    /** Labels of the newest version at asOfSeq. Cluster number is a label, never an identity. */
    public List<JsonNode> currentLabels(long asOfSeq) {
        int version = latestLabelVersion(asOfSeq);
        List<JsonNode> all = clusterLabels();
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode l : all) {
            if (l.get("labelSetVersion").asInt() == version && l.get("seq").asLong() <= asOfSeq) out.add(l);
        }
        out.sort(Comparator.comparing(l -> l.get("id").asText()));
        return out;
    }

    /** Spikes belonging to a unit under the label version visible at asOfSeq. */
    public List<JsonNode> spikesForUnit(String unitId, long asOfSeq) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode label : currentLabels(asOfSeq)) {
            if (label.get("unitId").asText().equals(unitId)) {
                label.get("spikeIds").forEach(s -> ids.add(s.asText()));
            }
        }
        List<JsonNode> out = new ArrayList<>();
        for (String id : ids) {
            JsonNode w = waveform(id);
            if (w != null) out.add(w);
        }
        // stable ordering: by (time, channel, id) using raw values
        out.sort(Comparator
                .comparing((JsonNode w) -> w.get("time").get("value").asText())
                .thenComparing(w -> w.get("channel").asInt())
                .thenComparing(w -> w.get("id").asText()));
        return out;
    }

    public List<JsonNode> units() {
        return db.query("SELECT * FROM units ORDER BY seq", rs -> {
            ObjectNode n = Json.obj();
            n.put("id", rs.getString("id"));
            n.put("seq", rs.getLong("seq"));
            n.put("origin", rs.getString("origin"));
            n.put("status", rs.getString("status"));
            return n;
        });
    }

    public void setUnitStatus(String unitId, String status) {
        db.execute("UPDATE units SET status=? WHERE id=?", status, unitId);
    }

    public List<JsonNode> diagnostics() {
        return db.query("SELECT * FROM diagnostics ORDER BY seq", rs -> {
            ObjectNode n = Json.obj();
            n.put("id", rs.getString("id"));
            n.put("seq", rs.getLong("seq"));
            n.put("target", rs.getString("target"));
            n.put("position", rs.getString("position"));
            n.put("severity", rs.getString("severity"));
            n.put("message", rs.getString("message"));
            return n;
        });
    }

    public List<JsonNode> thresholds() {
        return db.query("SELECT * FROM detection_thresholds ORDER BY seq", rs -> {
            ObjectNode n = Json.obj();
            n.put("id", rs.getString("id"));
            n.put("seq", rs.getLong("seq"));
            n.put("channel", rs.getInt("channel"));
            ObjectNode t = n.putObject("threshold");
            t.put("value", rs.getString("value"));
            t.put("unit", rs.getString("unit"));
            n.put("version", rs.getInt("version"));
            n.put("digest", rs.getString("digest"));
            return n;
        });
    }

    public List<JsonNode> featureVersions() {
        return db.query("SELECT * FROM feature_versions ORDER BY seq", rs -> {
            ObjectNode n = Json.obj();
            n.put("id", rs.getString("id"));
            n.put("seq", rs.getLong("seq"));
            n.put("name", rs.getString("name"));
            n.set("params", Json.parse(rs.getString("params_json")));
            n.put("digest", rs.getString("digest"));
            return n;
        });
    }

    public List<JsonNode> probeGeometries() {
        return db.query("SELECT * FROM probe_geometries ORDER BY seq", rs -> {
            ObjectNode n = Json.obj();
            n.put("id", rs.getString("id"));
            n.put("seq", rs.getLong("seq"));
            n.put("frame", rs.getString("frame"));
            n.set("sites", Json.parse(rs.getString("sites_json")));
            n.put("digest", rs.getString("digest"));
            return n;
        });
    }
}
