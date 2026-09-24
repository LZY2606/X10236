package org.neurospike.adjudicate;

import org.neurospike.engine.DerivationEngine;
import org.neurospike.engine.LineageEngine;
import org.neurospike.model.Diagnostic;
import org.neurospike.model.FailureResult;
import org.neurospike.model.FailureType;
import org.neurospike.model.Ids;
import org.neurospike.model.Material;
import org.neurospike.store.ProjectStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 裁定工作流（服务端业务规则，可直接测试）。
 * 每个动作 = 一条只追加的 adjudication 事件 + 一次（局部）派生重算。
 * 历史不被覆盖：回到旧 seq 时按当时参数版本与决定恢复视图。
 */
public final class AdjudicationService {
    private final ProjectStore store;
    private final DerivationEngine engine;
    private final LineageEngine lineage;

    public AdjudicationService(ProjectStore store) {
        this.store = store;
        this.engine = new DerivationEngine(store);
        this.lineage = new LineageEngine(store);
    }

    public sealed interface Outcome {
        record Ok(long seq, String adjudicationId, String state,
                 Set<String> affectedClusters, List<Diagnostic> notes) implements Outcome {}
        record Failed(FailureResult failure) implements Outcome {}
    }

    /** 初始导入后的基线重算。 */
    public Outcome baseline() {
        return run("baseline", null, payload("kind", "baseline"), null, false);
    }

    /** 接受/否定一个证据节点。 */
    public Outcome decide(String verdict, String nodeId, String comment) {
        if (!verdict.equals("accept") && !verdict.equals("reject")) {
            return fail("verdict-must-be-accept-or-reject");
        }
        try {
            if (store.derived().node(nodeId) == null) {
                return fail("unknown-node:" + nodeId);
            }
        } catch (Exception e) { return fail(e.getMessage()); }
        Map<String, Object> p = payload("verdict", verdict);
        p.put("nodeId", nodeId); p.put("comment", comment);
        return run("decide", nodeId, p, null, true);
    }

    /** 参数调整：阈值版本。只重算与检测结果发生变化的事件相交的簇（局部）。 */
    public Outcome setThresholdVersion(int version) {
        int before;
        try {
            before = Integer.parseInt(store.decision().meta("active_threshold_version"));
            var exist = store.material().thresholds(version);
            if (exist.isEmpty()) return fail("unknown-threshold-version:" + version);
        } catch (Exception e) { return fail(e.getMessage()); }
        Map<String, Object> p = payload("param", "active_threshold_version");
        p.put("from", before); p.put("to", version);
        return run("param-threshold", null, p, () -> {
            store.decision().putMeta("active_threshold_version", String.valueOf(version));
        }, false);
    }

    /** 参数调整：时间窗（半开区间）。只重算相交簇。 */
    public Outcome setWindow(double start, double end) {
        if (!(end > start)) return fail("window-end-must-be-greater-than-start");
        Map<String, Object> p = payload("param", "window");
        p.put("from", List.of(store.decision().meta("window_start"),
                store.decision().meta("window_end")));
        p.put("to", List.of(start, end));
        return run("param-window", null, p, () -> {
            store.decision().putMeta("window_start", String.valueOf(start));
            store.decision().putMeta("window_end", String.valueOf(end));
        }, false);
    }

    /** 参数调整：激活标签集（重编号/拆分）。构建谱系边。 */
    public Outcome activateLabelSet(String labelSetId) {
        String before;
        int parentVer, childVer;
        try {
            before = store.decision().meta("active_label_set");
            var ls = store.labels().labelSet(labelSetId);
            if (ls == null) return fail("unknown-label-set:" + labelSetId);
            var prev = store.labels().labelSet(before);
            parentVer = prev == null ? ls.version() - 1 : prev.version();
            childVer = ls.version();
        } catch (Exception e) { return fail(e.getMessage()); }
        Map<String, Object> p = payload("param", "active_label_set");
        p.put("from", before); p.put("to", labelSetId);
        Outcome out = run("param-labels", null, p, () -> {
            store.decision().putMeta("active_label_set", labelSetId);
            if (labelSetId.equals("ls-v2")) lineage.buildEdges("ls-v1", "ls-v2", 1, 2, null);
            if (labelSetId.equals("ls-v3")) lineage.buildEdges("ls-v2", "ls-v3", 2, 3, null);
        }, false);
        return out;
    }

    /**
     * 只影响局部依赖的源数据改动：修订单个事件幅度。原始行不可变，
     * 新值以新版本事件（同 eventId，supersedes_order+1）追加；
     * 只有读取该事件的派生节点因 inputDigest 改变而获得新身份。
     */
    public Outcome patchEventAmplitude(String eventId, String newRaw, String newUnit) {
        try {
            Material.SpikeEvent old = store.material().event(eventId);
            if (old == null) return fail("unknown-event:" + eventId);
            var pr = org.neurospike.model.Quantity.parse(newRaw, newUnit, "uV");
            if (!pr.ok()) {
                return new Outcome.Failed(FailureResult.of(FailureType.SEMANTIC_UNRESOLVABLE,
                        List.of(Diagnostic.of("patch-unconvertible",
                                eventId + "#amplitude", pr.diagnostic()))));
            }
            Integer order = old.supersedesOrder() == null ? 1 : old.supersedesOrder() + 1;
            Material.SpikeEvent revised = new Material.SpikeEvent(old.eventId(), old.timeSeconds(),
                    old.channelLocalIndex(), old.waveformId(), pr.quantity(),
                    old.featureSetId(), old.features(),
                    old.rawLine() + " [PATCHED " + newRaw + " " + newUnit + "]",
                    true, null, order);
            String patchKey = eventId + "#" + order + "=" + newRaw + newUnit;
            Map<String, Object> p = payload("source-patch", eventId);
            p.put("supersedesOrder", order); p.put("newAmplitude", newRaw + " " + newUnit);
            return run("source-patch", null, p, () -> {
                store.material().putEvent(revised, 0);
                store.decision().putMeta("source_patch", patchKey);
            }, false);
        } catch (Exception e) { return fail(e.getMessage()); }
    }

    /**
     * 对一个事件做归属裁定。对同一目标的两个不同决定 = CONFLICT，
     * 但两者都保留（被采纳与未采纳结果并存）。
     */
    public Outcome assignEvent(String eventId, String clusterKey, String labelSetId) {
        try {
            var clusters = store.labels().clusters(labelSetId);
            if (!clusters.containsKey(clusterKey)) return fail("unknown-cluster:" + clusterKey);
            if (store.material().event(eventId) == null) return fail("unknown-event:" + eventId);
            List<Material.Adjudication> prior = store.decision().history(null);
            String conflictWith = null;
            for (Material.Adjudication a : prior) {
                if (a.kind().equals("assign-event") && a.payload().contains("\"eventId\":\"" + eventId + "\"")) {
                    if (!a.payload().contains("\"clusterKey\":\"" + clusterKey + "\"")) conflictWith = a.adjudicationId();
                }
            }
            Map<String, Object> p = payload("assign-event", eventId);
            p.put("clusterKey", clusterKey); p.put("labelSetId", labelSetId);
            if (conflictWith != null) p.put("conflictsWith", conflictWith);
            String kind = conflictWith != null ? "assign-conflict" : "assign-event";
            return run(kind, null, p, null, true);
        } catch (Exception e) { return fail(e.getMessage()); }
    }

    private Outcome fail(String msg) {
        return new Outcome.Failed(FailureResult.of(FailureType.SEMANTIC_UNRESOLVABLE,
                List.of(Diagnostic.of("semantic", "adjudication", msg))));
    }

    private Map<String, Object> payload(String key, Object value) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put(key, value); p.put("at", Instant.now().toString());
        return p;
    }

    private interface SideEffect { void run() throws Exception; }

    private Outcome run(String kind, String nodeId, Map<String, Object> payload,
                        SideEffect effect, boolean markAdoption) {
        String txid = "tx_" + System.nanoTime();
        final long[] seqHolder = new long[1];
        try {
            store.transaction(txid, journal -> {
                try {
                    journal.record(kind, store.json().writeValueAsString(payload));
                    if (effect != null) effect.run();
                    DerivationEngine.Result result = engine.recompute(0L, null);
                    journal.record("recompute", store.json().writeValueAsString(Map.of(
                            "nodes", result.nodes().size(),
                            "affectedClusters", result.affectedClusters())));
                    String adjId = Ids.contentId("adj",
                            kind + "|" + store.json().writeValueAsString(payload));
                    long seq = store.decision().appendAdjudication(adjId, "main", kind, nodeId,
                            store.json().writeValueAsString(payload), Instant.now().toString());
                    seqHolder[0] = seq;
                    if (markAdoption && nodeId != null) {
                        try (var ps = store.raw().prepareStatement(
                                "INSERT INTO node_adoption(decision_seq,node_id,verdict) VALUES(?,?,?)")) {
                            ps.setLong(1, seq); ps.setString(2, nodeId);
                            ps.setString(3, String.valueOf(payload.get("verdict")));
                            ps.executeUpdate();
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            // 裁定后：受影响下游记录（冲突/归属时聚焦事件的下游簇）
            Set<String> affected = new LinkedHashSet<>();
            String focusEvent = (String) payload.get("assign-event");
            if (focusEvent == null) focusEvent = (String) payload.get("source-patch");
            if (focusEvent != null) {
                affected.addAll(lineage.downstreamClusters(focusEvent,
                        store.decision().meta("active_label_set")));
            } else {
                DerivationEngine.Result r = engine.recompute(seqHolder[0], null);
                affected.addAll(r.affectedClusters());
            }
            String state = kind.equals("assign-conflict") ? "CONFLICT"
                    : kind.startsWith("decide") ? "RECORDED" : "OK";
            return new Outcome.Ok(seqHolder[0], null, state, affected, List.of());
        } catch (RuntimeException e) {
            return new Outcome.Failed(FailureResult.of(FailureType.SEMANTIC_UNRESOLVABLE,
                    List.of(Diagnostic.of("adjudication-failed", kind,
                            e.getCause() != null ? e.getCause().getMessage() : e.getMessage()))));
        }
    }
}
