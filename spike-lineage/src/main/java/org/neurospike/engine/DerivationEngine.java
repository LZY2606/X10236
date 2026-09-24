package org.neurospike.engine;

import org.neurospike.model.Derived;
import org.neurospike.model.Material;
import org.neurospike.store.ProjectStore;
import org.neurospike.store.StoreLabels;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 派生编排：给定参数版本上下文，重建全部可见派生节点并维护谱系边。
 * 局部性：阈值修订只影响检测失败的事件子树；时间窗修改只重算相交簇；
 * 源数据修订只影响读取该事件的节点。内容寻址 nodeId 保证等价输入等价身份。
 */
public final class DerivationEngine {
    private final ProjectStore store;
    private final EvidenceEngine evidence = new EvidenceEngine();

    public DerivationEngine(ProjectStore store) { this.store = store; }

    public EngineContext currentContext() throws Exception {
        String labelSet = store.decision().meta("active_label_set");
        int thr = Integer.parseInt(store.decision().meta("active_threshold_version"));
        double ws = Double.parseDouble(store.decision().meta("window_start"));
        double we = Double.parseDouble(store.decision().meta("window_end"));
        double tol = Double.parseDouble(store.decision().meta("concurrency_tol_seconds"));
        double ref = Double.parseDouble(store.decision().meta("refractory_seconds"));
        String patch = store.decision().meta("source_patch");
        return new EngineContext(labelSet, thr, ws, we, tol, ref, patch);
    }

    public Result recompute(long birthSeq, Set<String> onlyClusterKeys) {
        try {
            EngineContext ctx = currentContext();
            Derived.TimeWindow window = new Derived.TimeWindow(ctx.windowStart(), ctx.windowEnd());
            List<Material.SpikeEvent> all = store.material().events();
            List<Material.Threshold> thresholds = store.material().thresholds(ctx.thresholdVersion());
            Map<Integer, Double> thrByChannel = new LinkedHashMap<>();
            for (Material.Threshold t : thresholds) {
                if (t.value().siValue() != null) thrByChannel.put(t.channelLocalIndex(), t.value().siValue());
            }

            // 检测层：阈值是“低于负值才检测到”的负阈值 -> 幅度(负) <= 阈值
            List<Material.SpikeEvent> detected = new ArrayList<>();
            for (Material.SpikeEvent e : all) {
                Double lim = thrByChannel.get(e.channelLocalIndex());
                boolean passes = !e.valid() || (lim != null && e.amplitude().siValue() != null
                        && e.amplitude().siValue() <= lim + 1e-9);
                if (passes) detected.add(e);
            }

            List<Material.SpikeEvent> windowed = detected.stream()
                    .filter(e -> window.contains(e.timeSeconds())).toList();

            // 全局并发/多通道证据（依赖窗口内全部事件；时间窗变化时重算）
            List<Derived.Node> concurrent = evidence.concurrentGroups(store, ctx, windowed, birthSeq);

            Map<String, StoreLabels.Cluster> clusters = store.labels().clusters(ctx.labelSetId());
            Map<String, Material.SpikeEvent> byId = new LinkedHashMap<>();
            all.forEach(e -> byId.put(e.eventId(), e));

            List<Derived.Node> produced = new ArrayList<>(concurrent);
            List<String> affected = new ArrayList<>();
            for (StoreLabels.Cluster cluster : clusters.values()) {
                if (onlyClusterKeys != null && !onlyClusterKeys.contains(cluster.clusterKey())) continue;
                List<Material.SpikeEvent> members = new ArrayList<>();
                List<String> invalidReasons = new ArrayList<>();
                for (String eid : cluster.eventIds()) {
                    Material.SpikeEvent e = byId.get(eid);
                    if (e == null) { invalidReasons.add("event-missing:" + eid); continue; }
                    if (!e.valid()) { invalidReasons.add(eid + ":" + e.invalidReason()); continue; }
                    if (!detected.contains(e)) { invalidReasons.add(eid + ":below-active-threshold"); continue; }
                    if (!window.contains(e.timeSeconds())) continue;
                    members.add(e);
                }
                // 时间窗相交判断：不相交的簇完全跳过重算（保持旧节点身份）
                double minT = cluster.eventIds().stream().map(byId::get)
                        .filter(java.util.Objects::nonNull)
                        .mapToDouble(Material.SpikeEvent::timeSeconds).min().orElse(Double.POSITIVE_INFINITY);
                double maxT = cluster.eventIds().stream().map(byId::get)
                        .filter(java.util.Objects::nonNull)
                        .mapToDouble(Material.SpikeEvent::timeSeconds).max().orElse(Double.NEGATIVE_INFINITY);
                if (onlyClusterKeys == null || window.intersectsCluster(minT, maxT)
                        || onlyClusterKeys.contains(cluster.clusterKey())) {
                    produced.addAll(clusterViews(ctx, cluster, members, invalidReasons, birthSeq));
                }
                affected.add(cluster.clusterKey());
            }

            // 局部失效节点（无效事件不被静默丢弃：为每条生成 LOCAL_FAILURE）
            for (Material.SpikeEvent e : all) {
                if (e.valid()) continue;
                produced.add(localFailure(ctx, e, birthSeq));
            }
            return new Result(ctx, produced, affected);
        } catch (Exception e) {
            throw new RuntimeException("derivation failed", e);
        }
    }

    private List<Derived.Node> clusterViews(EngineContext ctx, StoreLabels.Cluster cluster,
                                            List<Material.SpikeEvent> members,
                                            List<String> invalidReasons, long birthSeq) {
        List<Derived.Node> out = new ArrayList<>();
        String membershipDigest = cluster.clusterKey() + "|n=" + members.size()
                + "|invalid=" + String.join(",", invalidReasons.stream().sorted().toList());

        // 簇视图（身份）
        var summary = new LinkedHashMap<String, Object>();
        summary.put("clusterKey", cluster.clusterKey());
        summary.put("displayNumber", cluster.displayNumber());
        summary.put("eventCount", members.size());
        summary.put("channels", members.stream().map(Material.SpikeEvent::channelLocalIndex)
                .sorted().distinct().toList());
        summary.put("timeWindow", List.of(ctx.windowStart(), ctx.windowEnd()));
        summary.put("windowUnit", "seconds since t0");
        summary.put("invalidReasons", invalidReasons);
        Derived.Node view = EngineSupport.node(store, Derived.NodeKind.CLUSTER_VIEW,
                invalidReasons.isEmpty() ? "OK" : "LOCAL_FAILURE",
                membershipDigest, ctx, summary, List.of(), birthSeq);
        if (!store.derived().nodeExists(view.nodeId())) store.derived().putNode(view);
        out.add(view);

        // 自相关（ISI 直方图，bin 0.5ms，±10ms），父节点=簇视图
        if (!members.isEmpty()) {
            out.add(histogram(ctx, cluster, members, Derived.NodeKind.AUTOCORRELATION,
                    view, birthSeq));
            out.add(rateView(ctx, cluster, members, view, birthSeq));
            out.add(projection(ctx, cluster, members, view, birthSeq));
            out.addAll(evidence.refractoryViolations(store, ctx, cluster, members, birthSeq));
            Derived.Node drift = evidence.amplitudeDrift(store, ctx, cluster, members, birthSeq);
            if (drift != null) out.add(drift);
        }
        return out;
    }

    private Derived.Node histogram(EngineContext ctx, StoreLabels.Cluster cluster,
                                   List<Material.SpikeEvent> members,
                                   Derived.NodeKind kind, Derived.Node parent, long birthSeq) {
        double binMs = 0.5, halfMs = 10;
        int bins = (int) (2 * halfMs / binMs);
        long[] counts = new long[bins];
        var sorted = members.stream()
                .sorted(java.util.Comparator.comparingDouble(Material.SpikeEvent::timeSeconds)).toList();
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                double dtMs = (sorted.get(j).timeSeconds() - sorted.get(i).timeSeconds()) * 1000;
                if (dtMs >= halfMs) break;
                int idx = (int) ((dtMs + halfMs) / binMs);
                if (idx >= 0 && idx < bins) { counts[idx]++; counts[bins - 1 - idx]++; }
            }
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("clusterKey", cluster.clusterKey());
        payload.put("binMs", binMs); payload.put("rangeMs", List.of(-halfMs, halfMs));
        payload.put("unit", "ms");
        payload.put("counts", counts);
        String digest = cluster.clusterKey() + "|ac|" + members.size();
        Derived.Node n = EngineSupport.node(store, kind, "OK", digest, ctx, payload,
                List.of(parent), birthSeq);
        if (!store.derived().nodeExists(n.nodeId())) store.derived().putNode(n);
        return n;
    }

    private Derived.Node rateView(EngineContext ctx, StoreLabels.Cluster cluster,
                                  List<Material.SpikeEvent> members,
                                  Derived.Node parent, long birthSeq) {
        double binSec = 1.0;
        int bins = (int) Math.max(1, Math.ceil(ctx.windowEnd() == Double.POSITIVE_INFINITY
                ? 30 : (ctx.windowEnd() - ctx.windowStart())));
        long[] counts = new long[bins];
        for (Material.SpikeEvent e : members) {
            int idx = (int) ((e.timeSeconds() - ctx.windowStart()) / binSec);
            if (idx >= 0 && idx < bins) counts[idx]++;
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("clusterKey", cluster.clusterKey());
        payload.put("binSeconds", binSec);
        payload.put("ratesHz", java.util.Arrays.stream(counts).asDoubleStream()
                .map(v -> v / binSec).boxed().toList());
        payload.put("unit", "Hz");
        String digest = cluster.clusterKey() + "|rate|" + members.size();
        Derived.Node n = EngineSupport.node(store, kind, "OK", digest, ctx, payload,
                List.of(parent), birthSeq);
        if (!store.derived().nodeExists(n.nodeId())) store.derived().putNode(n);
        return n;
    }

    private Derived.Node projection(EngineContext ctx, StoreLabels.Cluster cluster,
                                    List<Material.SpikeEvent> members,
                                    Derived.Node parent, long birthSeq) {
        List<List<Double>> points = members.stream()
                .map(e -> e.features().size() >= 2 ? e.features() : List.of(0.0, 0.0))
                .toList();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("clusterKey", cluster.clusterKey());
        payload.put("axes", List.of("log10(amplitude uV)", "channel projection"));
        payload.put("featureSet", members.isEmpty() ? null : members.get(0).featureSetId());
        payload.put("points", points);
        String digest = cluster.clusterKey() + "|proj|" + points;
        Derived.Node n = EngineSupport.node(store, kind, "OK", digest, ctx, payload,
                List.of(parent), birthSeq);
        if (!store.derived().nodeExists(n.nodeId())) store.derived().putNode(n);
        return n;
    }

    private Derived.Node localFailure(EngineContext ctx, Material.SpikeEvent e, long birthSeq) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("eventId", e.eventId());
        payload.put("reason", e.invalidReason());
        payload.put("rawLine", e.rawLine());
        payload.put("retainedInPlace", true);
        String digest = e.eventId() + "|fail|" + e.invalidReason();
        Derived.Node n = EngineSupport.node(store, Derived.NodeKind.LOCAL_FAILURE,
                "LOCAL_FAILURE", digest, ctx, payload, List.of(), birthSeq);
        if (!store.derived().nodeExists(n.nodeId())) store.derived().putNode(n);
        return n;
    }

    public record Result(EngineContext context, List<Derived.Node> nodes, List<String> affectedClusters) {}
}
