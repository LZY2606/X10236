package gsb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and replays the offline case pack "脉冲簇谱：神经棘波候选、漂移簇与人工裁定谱系".
 * Materials are imported first (each layer stored separately), then the scripted
 * events are replayed through the same code paths the HTTP API uses.
 */
public final class CasePack {
    public static final String NAME = "脉冲簇谱：神经棘波候选、漂移簇与人工裁定谱系";

    private final Database db;
    private final Store store;
    private final Deriver deriver;
    private final Adjudicator adjudicator;

    public CasePack(Database db, Store store, Deriver deriver, Adjudicator adjudicator) {
        this.db = db;
        this.store = store;
        this.deriver = deriver;
        this.adjudicator = adjudicator;
    }

    private static String readResource(String path) {
        try (InputStream in = CasePack.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void load() {
        JsonNode manifest = Json.parse(readResource("/casepack/manifest.json"));
        db.txRun(d -> {
            for (JsonNode file : manifest.withArray("materials")) {
                apply(Json.parse(readResource("/casepack/" + file.asText())));
            }
        });
        for (JsonNode file : manifest.withArray("events")) {
            JsonNode event = Json.parse(readResource("/casepack/" + file.asText()));
            db.txRun(d -> apply(event));
        }
        db.setMeta("casepack", NAME);
        db.setMeta("casepack.version", manifest.get("version").asText());
    }

    private void apply(JsonNode node) {
        String op = node.get("op").asText();
        switch (op) {
            case "probe" -> store.addProbeGeometry(node.get("geometry"));
            case "thresholds" -> {
                int i = 0;
                for (JsonNode t : node.get("thresholds")) store.addThreshold(t, "thresholds[" + i++ + "]");
            }
            case "features" -> store.addFeatureVersion(node.get("featureVersion"));
            case "waveforms" -> {
                int i = 0;
                for (JsonNode w : node.get("waveforms")) store.addWaveformRef(w, "waveforms[" + i++ + "]");
            }
            case "labels" -> {
                for (JsonNode u : node.get("units")) addUnitWithId(u.get("id").asText(), u.get("origin").asText());
                for (JsonNode l : node.get("labels")) store.addClusterLabel(l);
            }
            case "computeAll" -> {
                JsonNode w = node.get("window");
                deriver.setWindow(w.get(0).asDouble(), w.get(1).asDouble());
                String pv = node.get("paramVersion").asText();
                for (JsonNode u : store.units()) {
                    deriver.computeUnitEvidence(u.get("id").asText(),
                            w.get(0).asDouble(), w.get(1).asDouble(), pv, db.currentSeq());
                }
            }
            case "addThreshold" -> {
                List<String> parents = new ArrayList<>();
                int channel = node.get("threshold").get("channel").asInt();
                for (JsonNode t : store.thresholds()) {
                    if (t.get("channel").asInt() == channel) parents.add(t.get("id").asText());
                }
                String id = store.addThreshold(node.get("threshold"), "events.threshold");
                ObjectNode payload = Json.obj();
                payload.put("thresholdId", id);
                payload.put("channel", channel);
                payload.put("version", node.get("threshold").get("version").asInt());
                adjudicator.recordEvent("PARAM_CHANGE", payload, parents, "APPLIED");
            }
            case "replaceWaveform" -> {
                String oldId = node.get("id").asText();
                JsonNode oldW = store.waveform(oldId);
                if (oldW == null) throw new IllegalStateException("replaceWaveform: unknown " + oldId);
                String newId = store.addWaveformRef(node.get("with"), "events.replaceWaveform");
                // new label version where oldId is swapped for newId; other clusters carried forward
                int version = store.latestLabelVersion(db.currentSeq()) + 1;
                List<String> parents = new ArrayList<>();
                parents.add(oldId);
                for (JsonNode l : store.labelsOfVersion(version - 1)) {
                    parents.add(l.get("id").asText());
                    ObjectNode nl = Json.obj();
                    nl.put("id", db.newId("L"));
                    nl.put("labelSetVersion", version);
                    nl.put("clusterNo", l.get("clusterNo").asInt());
                    nl.put("unitId", l.get("unitId").asText());
                    ArrayNode ids = nl.putArray("spikeIds");
                    l.get("spikeIds").forEach(s -> ids.add(s.asText().equals(oldId) ? newId : s.asText()));
                    store.addClusterLabel(nl);
                }
                List<String> affected = deriver.recomputeStaleUnits(
                        node.get("paramVersion").asText(), "SOURCE_CHANGED");
                ObjectNode payload = Json.obj();
                payload.put("oldWaveformId", oldId);
                payload.put("newWaveformId", newId);
                payload.put("oldDigest", oldW.get("digest").asText());
                ArrayNode aff = payload.putArray("affectedUnits");
                affected.forEach(aff::add);
                adjudicator.recordEvent("SOURCE_CHANGE", payload, parents, "APPLIED");
            }
            case "renumber" -> {
                Map<Integer, Integer> mapping = new LinkedHashMap<>();
                node.get("mapping").fields().forEachRemaining(e ->
                        mapping.put(Integer.parseInt(e.getKey()), e.getValue().asInt()));
                Adjudicator.Result r = adjudicator.renumber(mapping);
                if (r.outcome != Adjudicator.Outcome.OK) throw new IllegalStateException(r.message);
            }
            case "window" -> {
                List<String> affected = deriver.applyWindowChange(
                        node.get("start").asDouble(), node.get("end").asDouble(),
                        node.get("paramVersion").asText());
                ObjectNode payload = Json.obj();
                payload.put("windowStartS", node.get("start").asDouble());
                payload.put("windowEndS", node.get("end").asDouble());
                ArrayNode aff = payload.putArray("affectedUnits");
                affected.forEach(aff::add);
                adjudicator.recordEvent("WINDOW_CHANGE", payload, List.of(), "APPLIED");
            }
            case "split" -> {
                String unitId = unitForCluster(node.get("clusterNo").asInt());
                List<List<String>> children = new ArrayList<>();
                for (JsonNode child : node.withArray("children")) {
                    List<String> ids = new ArrayList<>();
                    child.forEach(s -> ids.add(s.asText()));
                    children.add(ids);
                }
                Adjudicator.Result r = adjudicator.split(unitId, children);
                if (r.outcome != Adjudicator.Outcome.OK) throw new IllegalStateException(r.message);
            }
            case "verdict" -> {
                String unitId = unitForCluster(node.get("clusterNo").asInt());
                adjudicator.verdict(unitId, node.get("accept").asBoolean(),
                        node.path("rationale").asText(""));
            }
            case "resolve" -> {
                String unitId = unitForCluster(node.get("clusterNo").asInt());
                String conflictId = null;
                for (JsonNode e : adjudicator.events(db.currentSeq())) {
                    if (e.get("status").asText().equals("CONFLICTED")
                            && e.get("payload").path("unitId").asText().equals(unitId)) {
                        conflictId = e.get("id").asText();
                    }
                }
                if (conflictId == null) throw new IllegalStateException("no open conflict for " + unitId);
                Adjudicator.Result r = adjudicator.resolve(conflictId, node.get("chosen").asText());
                if (r.outcome != Adjudicator.Outcome.OK) throw new IllegalStateException(r.message);
            }
            default -> throw new IllegalStateException("unknown casepack op " + op);
        }
    }

    private String unitForCluster(int clusterNo) {
        for (JsonNode l : store.currentLabels(db.currentSeq())) {
            if (l.get("clusterNo").asInt() == clusterNo) return l.get("unitId").asText();
        }
        throw new IllegalStateException("no unit for cluster " + clusterNo);
    }

    private void addUnitWithId(String id, String origin) {
        long seq = db.nextSeq();
        db.execute("INSERT INTO units(id,seq,origin,status) VALUES(?,?,?,?)", id, seq, origin, "ACTIVE");
        if (id.matches("U\\d+")) {
            long n = Long.parseLong(id.substring(1));
            if (n > db.getMetaLong("counter:U", 0)) db.setMeta("counter:U", Long.toString(n));
        }
    }
}
