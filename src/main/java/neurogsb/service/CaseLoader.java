package neurogsb.service;

import com.fasterxml.jackson.databind.JsonNode;
import neurogsb.db.Database;
import neurogsb.model.OpResult;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** 载入离线案例包“脉冲簇谱”，按步骤重放：基线 → 参数修订 → 局部源改动 → 冲突裁定。 */
public final class CaseLoader {
    public static final long DEFAULT_BUDGET = 100_000;

    private final Database db;
    private final Store store;
    private final IngestService ingest;
    private final EvidenceService evidence;
    private final AdjudicationService adjudication;

    public CaseLoader(Database db) {
        this.db = db;
        this.store = new Store(db);
        this.ingest = new IngestService(db, store);
        this.evidence = new EvidenceService(db, store);
        this.adjudication = new AdjudicationService(db, store, evidence);
    }

    public boolean isEmpty() { return store.events(null).isEmpty() && store.materials("probe_geometry").isEmpty(); }

    public List<String> load() {
        List<String> log = new ArrayList<>();
        JsonNode v1 = resource("v1_base.json");
        var r = ingest.importPack(v1);
        if (!r.ok()) throw new IllegalStateException("casepack v1 import failed: " + r);
        log.add("v1_base imported: " + ((OpResult.Ok) r).summary());

        // 初始计算（锚定 import 事件）
        String opId = Store.newId("op");
        db.beginOp(opId, "initial compute");
        var importEv = store.insertEvent("import", "system", null,
                Store.M.createObjectNode().put("case_id", v1.get("case_id").asText()), List.of(), "main", opId);
        db.commitOp(opId);
        for (String u : store.allSpikes().stream().map(s -> s.unitId()).distinct().toList()) {
            var cr = evidence.computeUnit(u, importEv.seq(), DEFAULT_BUDGET);
            if (!cr.ok()) throw new IllegalStateException("initial compute failed: " + cr);
        }
        log.add("initial evidence computed");

        for (String step : List.of("v2_param_revision.json", "v3_local_source_change.json", "v4_conflicting_decisions.json")) {
            JsonNode stage = resource(step);
            for (JsonNode op : stage.get("ops")) {
                log.add(apply(op).toString());
            }
        }
        return log;
    }

    private OpResult apply(JsonNode op) {
        return switch (op.get("op").asText()) {
            case "renumber" -> adjudication.renumber(op.get("unit_id").asText(),
                    op.get("new_cluster_no").asInt(), op.get("actor").asText());
            case "split" -> adjudication.split(op.get("unit_id").asText(),
                    op.get("t_split_ms").asDouble(), op.get("actor").asText());
            case "window_change" -> adjudication.changeWindow(op.get("t0_ms").asDouble(),
                    op.get("t1_ms").asDouble(), op.get("actor").asText(), DEFAULT_BUDGET);
            case "param_revision" -> adjudication.paramRevision(op.get("sigma").asDouble(),
                    op.get("actor").asText(), op.get("note").asText());
            case "amend_waveform_ref" -> adjudication.amendWaveformRef(
                    op.get("waveform_ref_id").asText(), op.get("adds_spikes"),
                    op.get("actor").asText(), op.get("reason").asText(), DEFAULT_BUDGET);
            case "decide" -> {
                // 找到该单元最新的对应证据派生结果作为裁定对象
                String unitId = op.get("unit_id").asText();
                String kind = op.get("subject_kind").asText();
                var derived = store.derivedFor(unitId, null).stream()
                        .filter(d -> d.kind().equals(kind) && d.status().equals("ok"))
                        .reduce((a, b) -> b);
                if (derived.isEmpty()) yield new OpResult.SemanticUnsolvable("decide.subject",
                        "单元 " + unitId + " 没有可裁定的 " + kind);
                yield adjudication.decide(derived.get().id(), kind,
                        "accept".equals(op.get("verdict").asText()),
                        op.get("actor").asText(), op.get("rationale").asText());
            }
            default -> new OpResult.FormatCorrupt("op", "未知操作 " + op.get("op").asText());
        };
    }

    static JsonNode resource(String name) {
        try (InputStream in = CaseLoader.class.getResourceAsStream("/casepack/" + name)) {
            if (in == null) throw new IllegalStateException("missing casepack/" + name);
            return Store.M.readTree(in);
        } catch (Exception e) {
            throw new RuntimeException("load casepack/" + name + ": " + e.getMessage(), e);
        }
    }
}
