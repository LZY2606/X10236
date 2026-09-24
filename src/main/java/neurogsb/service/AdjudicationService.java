package neurogsb.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import neurogsb.db.Database;
import neurogsb.model.OpResult;
import neurogsb.model.Records.*;
import neurogsb.model.Records.Candidate;

import java.util.ArrayList;
import java.util.List;

/** 裁定与变更：每个动作生成新事件；历史事件不被覆盖；局部失效只波及依赖闭包。 */
public final class AdjudicationService {
    private final Database db;
    private final Store store;
    private final EvidenceService evidence;

    public AdjudicationService(Database db, Store store, EvidenceService evidence) {
        this.db = db; this.store = store; this.evidence = evidence;
    }

    /** 聚类编号变化：cluster_no 只是标签，unit_id 稳定。 */
    public OpResult renumber(String unitId, int newClusterNo, String actor) {
        var labels = store.materials("cluster_label").stream()
                .filter(m -> m.payload().get("unit_id").asText().equals(unitId)).toList();
        if (labels.isEmpty()) return new OpResult.SemanticUnsolvable("renumber.unit_id", "未知单元 " + unitId);
        String opId = Store.newId("op");
        db.beginOp(opId, "renumber " + unitId);
        var latest = labels.get(labels.size() - 1);
        ObjectNode payload = Store.M.createObjectNode();
        payload.put("unit_id", unitId);
        payload.put("cluster_no", newClusterNo);
        payload.put("note", "cluster_no 是标签，不是稳定身份");
        var mat = new Material(Store.newId("cl"), "cluster_label", latest.version() + 1,
                payload, Store.digest(payload.toString()), db.nextSeq());
        store.insertMaterial(mat, opId);
        store.supersedeMaterial(latest.id(), mat.id());
        ObjectNode ev = Store.M.createObjectNode();
        ev.put("unit_id", unitId);
        ev.put("old_cluster_no", latest.payload().get("cluster_no").asInt());
        ev.put("new_cluster_no", newClusterNo);
        var e = store.insertEvent("renumber", actor, unitId, ev, List.of(latest.id(), mat.id()), "main", opId);
        db.commitOp(opId);
        return new OpResult.Ok("renumbered " + unitId + " -> " + newClusterNo, List.of(e.id()));
    }

    /** 拆分：±shared_margin 内的棘波成为共享事件，保留全部合法候选直到裁定。 */
    public OpResult split(String unitId, double tSplit, String actor) {
        var spikes = store.spikes(unitId).stream().filter(s -> s.tMs() != null).toList();
        var before = spikes.stream().filter(s -> s.tMs() < tSplit).toList();
        var after = spikes.stream().filter(s -> s.tMs() >= tSplit).toList();
        if (before.isEmpty() || after.isEmpty()) {
            return new OpResult.SemanticUnsolvable("split.t_split_ms",
                    "拆分点 " + tSplit + " ms 不落在单元 " + unitId + " 的发放范围内");
        }
        var params = evidence.currentParams();
        String unitA = Store.newId("u"), unitB = Store.newId("u");
        String opId = Store.newId("op");
        db.beginOp(opId, "split " + unitId);
        // 新单元的标签材料（不可变层）
        for (var entry : List.of(new String[]{unitA, "a"}, new String[]{unitB, "b"})) {
            ObjectNode p = Store.M.createObjectNode();
            p.put("unit_id", entry[0]);
            p.put("cluster_no", -1);
            p.put("note", "split child " + entry[1] + " of " + unitId);
            store.insertMaterial(new Material(Store.newId("cl"), "cluster_label", 1, p,
                    Store.digest(p.toString()), db.nextSeq()), opId);
        }
        // 共享事件：拆分点附近 ±margin 的棘波归属不唯一 → 候选全部保留
        var shared = spikes.stream()
                .filter(s -> Math.abs(s.tMs() - tSplit) <= params.sharedMarginMs()).toList();
        for (var s : shared) {
            for (var target : List.of(unitA, unitB)) {
                ObjectNode p = Store.M.createObjectNode();
                p.put("spike_id", s.id());
                p.put("t_ms", s.tMs());
                p.put("assign_to", target);
                p.put("reason", "拆分后共享事件：|t - t_split| <= " + params.sharedMarginMs() + " ms");
                store.insertCandidate(new Candidate(Store.newId("cand"), s.id(), "shared_spike_assignment", p, "open"));
            }
        }
        store.reassignSpikes(unitId, unitA, Double.NEGATIVE_INFINITY, tSplit, opId);
        store.reassignSpikes(unitId, unitB, tSplit, Double.POSITIVE_INFINITY, opId);
        ObjectNode ev = Store.M.createObjectNode();
        ev.put("parent_unit", unitId);
        ev.put("child_a", unitA);
        ev.put("child_b", unitB);
        ev.put("t_split_ms", tSplit);
        ev.put("shared_spike_count", shared.size());
        var e = store.insertEvent("split", actor, unitId, ev, List.of(unitId), "main", opId);
        db.commitOp(opId);
        return new OpResult.Ok("split " + unitId + " at " + tSplit + "ms",
                List.of(e.id(), unitA, unitB));
    }

    /** 修改时间窗：只重算与新窗口相交的簇。半开区间 [t0, t1)。 */
    public OpResult changeWindow(double t0, double t1, String actor, long budget) {
        if (!(t0 < t1)) {
            return new OpResult.SemanticUnsolvable("window", "时间窗端点非法：t0=" + t0 + " 必须小于 t1=" + t1);
        }
        String opId = Store.newId("op");
        db.beginOp(opId, "window change");
        ObjectNode w = Store.M.createObjectNode();
        w.put("t0", t0); w.put("t1", t1); w.put("unit", "ms"); w.put("interval", "half-open [t0,t1)");
        store.kvPut("window", w.toString());
        ObjectNode ev = Store.M.createObjectNode();
        ev.put("t0_ms", t0); ev.put("t1_ms", t1);
        var e = store.insertEvent("window_change", actor, null, ev, List.of(), "main", opId);
        // 只重算相交簇
        var window = new EvidenceService.Window(t0, t1);
        List<String> affected = new ArrayList<>();
        var units = store.allSpikes().stream().map(Spike::unitId).distinct().toList();
        for (String u : units) {
            boolean intersects = store.spikes(u).stream()
                    .anyMatch(s -> s.tMs() != null && window.contains(s.tMs()));
            if (intersects) {
                var r = evidence.computeUnit(u, e.seq(), budget);
                if (!r.ok()) return r;
                affected.add(u);
            }
        }
        db.commitOp(opId);
        return new OpResult.Ok("window [" + t0 + "," + t1 + ") recomputed " + affected.size() + " intersecting clusters", affected);
    }

    /** 参数修订：新阈值版本使依赖旧参数版本的派生结果失效（记录原因）。 */
    public OpResult paramRevision(double sigma, String actor, String note) {
        String opId = Store.newId("op");
        db.beginOp(opId, "param revision");
        var thresholds = store.materials("detection_threshold");
        var prev = thresholds.get(thresholds.size() - 1);
        ObjectNode p = Store.M.createObjectNode();
        p.put("sigma", sigma);
        p.put("unit", "σ (noise RMS)");
        p.put("refractory_ms", prev.payload().get("refractory_ms").asDouble());
        p.put("coprop_window_ms", prev.payload().get("coprop_window_ms").asDouble());
        p.put("shared_margin_ms", prev.payload().get("shared_margin_ms").asDouble());
        p.put("drift_slope_threshold_uv_per_ms", prev.payload().get("drift_slope_threshold_uv_per_ms").asDouble());
        p.put("note", note);
        var mat = new Material(Store.newId("th"), "detection_threshold", prev.version() + 1, p,
                Store.digest(p.toString()), db.nextSeq());
        store.insertMaterial(mat, opId);
        store.supersedeMaterial(prev.id(), mat.id());
        ObjectNode ev = Store.M.createObjectNode();
        ev.put("old", prev.id()); ev.put("new", mat.id()); ev.put("sigma", sigma);
        var e = store.insertEvent("param_revision", actor, null, ev, List.of(prev.id(), mat.id()), "main", opId);
        // 失效：所有依赖旧参数版本的派生结果
        List<String> affected = new ArrayList<>();
        for (var d : store.allDerived()) {
            if (d.paramVersion().equals(prev.id() + "@v" + prev.version()) && d.status().equals("ok")) {
                store.invalidate(d.id(), "param_revision:" + mat.id(), d.unitId(), e.seq());
                affected.add(d.id());
            }
        }
        db.commitOp(opId);
        return new OpResult.Ok("param revision v" + mat.version() + " invalidated " + affected.size(), affected);
    }

    /** 局部源数据改动：只让依赖被改材料的派生结果失效，只重算受影响单元。 */
    public OpResult amendWaveformRef(String waveformRefId, com.fasterxml.jackson.databind.JsonNode addSpikes,
                                     String actor, String reason, long budget) {
        var old = store.material(waveformRefId);
        if (old.isEmpty()) return new OpResult.SemanticUnsolvable("waveform_ref_id", "未知波形引用 " + waveformRefId);
        String opId = Store.newId("op");
        db.beginOp(opId, "amend " + waveformRefId);
        ObjectNode p = old.get().payload().deepCopy();
        p.put("amended", true);
        p.put("amend_reason", reason);
        var mat = new Material(Store.newId("wr"), "waveform_ref", old.get().version() + 1, p,
                Store.digest(p.toString()), db.nextSeq());
        store.insertMaterial(mat, opId);
        store.supersedeMaterial(old.get().id(), mat.id());
        // 新增棘波（保持原始表示）
        List<String> touchedUnits = new ArrayList<>();
        var ingest = new IngestService(db, store);
        int i = 0;
        for (var sn : addSpikes) {
            String unitId = sn.get("unit_id").asText();
            ingest.insertSpike(sn, unitId, "amend[" + i + "]", mat.id(), opId);
            if (!touchedUnits.contains(unitId)) touchedUnits.add(unitId);
            i++;
        }
        ObjectNode ev = Store.M.createObjectNode();
        ev.put("old_ref", old.get().id()); ev.put("new_ref", mat.id()); ev.put("reason", reason);
        var e = store.insertEvent("source_amend", actor, null, ev, List.of(old.get().id(), mat.id()), "main", opId);
        // 局部失效：仅 parents 含旧材料的派生结果
        List<String> affected = new ArrayList<>();
        for (var d : store.allDerived()) {
            if (d.status().equals("ok") && d.parents().contains(old.get().id())) {
                store.invalidate(d.id(), "source_amended:" + mat.id(), d.unitId(), e.seq());
                affected.add(d.id());
            }
        }
        for (String u : touchedUnits) {
            var r = evidence.computeUnit(u, e.seq(), budget);
            if (!r.ok()) return r;
        }
        db.commitOp(opId);
        return new OpResult.Ok("amended " + waveformRefId + " -> local invalidation of " + affected.size(), affected);
    }

    /** 裁定：接受/否定。冲突时双方候选都保留，状态置为 conflicted。 */
    public OpResult decide(String subjectId, String subjectKind, boolean accept, String actor, String rationale) {
        String opId = Store.newId("op");
        db.beginOp(opId, "decide " + subjectId);
        ObjectNode p = Store.M.createObjectNode();
        p.put("subject_id", subjectId);
        p.put("subject_kind", subjectKind);
        p.put("verdict", accept ? "accept" : "reject");
        p.put("rationale", rationale);
        var e = store.insertEvent(accept ? "accept" : "reject", actor, null, p, List.of(subjectId), "main", opId);
        // 冲突检测：同一 subject 上存在相反裁定
        var decisions = store.events(null).stream()
                .filter(ev -> (ev.kind().equals("accept") || ev.kind().equals("reject"))
                        && ev.payload().get("subject_id") != null
                        && ev.payload().get("subject_id").asText().equals(subjectId))
                .toList();
        List<String> affected = new ArrayList<>(List.of(e.id()));
        boolean conflict = decisions.stream().map(ev -> ev.kind()).distinct().count() > 1;
        if (conflict) {
            // 保留全部合法候选
            for (var ev : decisions) {
                ObjectNode cp = Store.M.createObjectNode();
                cp.put("event_id", ev.id());
                cp.put("verdict", ev.kind());
                cp.put("actor", ev.actor());
                store.insertCandidate(new Candidate(Store.newId("cand"), subjectId,
                        "conflicting_decision", cp, "open"));
            }
            ObjectNode ce = Store.M.createObjectNode();
            ce.put("subject_id", subjectId);
            ce.put("status", "CONFLICTED");
            ce.put("note", "裁定冲突：全部合法候选已保留，等待 resolve");
            var conflictEv = store.insertEvent("conflict", "system", null, ce,
                    decisions.stream().map(Event::id).toList(), "main", opId);
            affected.add(conflictEv.id());
        }
        db.commitOp(opId);
        return new OpResult.Ok((accept ? "accepted " : "rejected ") + subjectId
                + (conflict ? " (CONFLICT)" : ""), affected);
    }

    /** 解决冲突：选定一个候选，其余标记 dropped。 */
    public OpResult resolve(String subjectId, String chosenCandidateId, String actor) {
        var cands = store.candidates(subjectId).stream()
                .filter(c -> c.kind().equals("conflicting_decision")).toList();
        if (cands.stream().noneMatch(c -> c.id().equals(chosenCandidateId))) {
            return new OpResult.SemanticUnsolvable("resolve.candidate", "候选 " + chosenCandidateId + " 不属于 " + subjectId);
        }
        String opId = Store.newId("op");
        db.beginOp(opId, "resolve " + subjectId);
        for (var c : cands) store.setCandidateStatus(c.id(), c.id().equals(chosenCandidateId) ? "chosen" : "dropped");
        ObjectNode ev = Store.M.createObjectNode();
        ev.put("subject_id", subjectId);
        ev.put("chosen", chosenCandidateId);
        var e = store.insertEvent("resolve", actor, null, ev,
                cands.stream().map(Candidate::id).toList(), "main", opId);
        db.commitOp(opId);
        return new OpResult.Ok("resolved " + subjectId, List.of(e.id()));
    }
}
