package org.neurospike.engine;

import org.neurospike.model.Derived;
import org.neurospike.model.Material;
import org.neurospike.store.ProjectStore;
import org.neurospike.store.StoreLabels;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 证据引擎：并发棘波、不应期违例、幅度漂移、多通道同时传播。
 * 结论一律 status=EVIDENCE —— 它们是证据，不会自动改变簇身份。
 */
public final class EvidenceEngine {

    /** 并发对：|dt| <= tol。时间窗内事件。 */
    public List<Derived.Node> concurrentGroups(ProjectStore store, EngineContext ctx,
                                               List<Material.SpikeEvent> windowed,
                                               long birthSeq) {
        List<Derived.ConcurrentPair> pairs = new ArrayList<>();
        List<Material.SpikeEvent> sorted = windowed.stream()
                .filter(Material.SpikeEvent::valid)
                .sorted(java.util.Comparator.comparingDouble(Material.SpikeEvent::timeSeconds)
                        .thenComparing(Material.SpikeEvent::eventId))
                .toList();
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                double dt = sorted.get(j).timeSeconds() - sorted.get(i).timeSeconds();
                if (dt > ctx.concurrencyTolSeconds()) break;
                Material.SpikeEvent a = sorted.get(i), b = sorted.get(j);
                pairs.add(new Derived.ConcurrentPair(a.eventId(), b.eventId(),
                        round(dt), a.channelLocalIndex(), b.channelLocalIndex()));
            }
        }
        // 并查集成组（只按并发边连）
        Map<String, String> parent = new LinkedHashMap<>();
        for (Material.SpikeEvent e : sorted) parent.put(e.eventId(), e.eventId());
        for (Derived.ConcurrentPair p : pairs) union(parent, p.eventIdA(), p.eventIdB());
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Material.SpikeEvent e : sorted)
            groups.computeIfAbsent(find(parent, e.eventId()), k -> new ArrayList<>()).add(e.eventId());

        List<Derived.Node> out = new ArrayList<>();
        List<List<String>> multi = new ArrayList<>();
        for (List<String> members : groups.values()) {
            if (members.size() < 2) continue;
            Map<String, Material.SpikeEvent> byId = new LinkedHashMap<>();
            sorted.forEach(e -> byId.put(e.eventId(), e));
            int channels = (int) members.stream().map(id -> byId.get(id).channelLocalIndex())
                    .distinct().count();
            double span = members.stream().mapToDouble(id -> byId.get(id).timeSeconds()).max().orElse(0)
                    - members.stream().mapToDouble(id -> byId.get(id).timeSeconds()).min().orElse(0);
            String gid = "grp_" + String.join("_", members);
            String digest = gid + "|tol=" + ctx.concurrencyTolSeconds();
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("groupId", gid);
            payload.put("members", members);
            payload.put("channelCount", channels);
            payload.put("spanSeconds", round(span));
            payload.put("interpretation", "evidence-only: concurrency does not merge units");
            String status = channels >= 2 ? "EVIDENCE" : "EVIDENCE";
            Derived.NodeKind kind = channels >= 2
                    ? Derived.NodeKind.MULTICHANNEL_PROPAGATION
                    : Derived.NodeKind.CONCURRENT_GROUP;
            Derived.Node n = EngineSupport.node(store, kind, status, digest, ctx, payload,
                    List.of(), birthSeq);
            if (!store.derived().nodeExists(n.nodeId())) store.derived().putNode(n);
            out.add(n);
            if (channels >= 2) multi.add(members);
        }
        return out;
    }

    /** 不应期违例（同簇相邻事件 ISI < refractory），只形成证据，不重编号。 */
    public List<Derived.Node> refractoryViolations(ProjectStore store, EngineContext ctx,
            StoreLabels.Cluster cluster, List<Material.SpikeEvent> members, long birthSeq) {
        List<Derived.RefractoryViolation> violations = new ArrayList<>();
        List<Material.SpikeEvent> sorted = members.stream()
                .sorted(java.util.Comparator.comparingDouble(Material.SpikeEvent::timeSeconds)
                        .thenComparing(Material.SpikeEvent::eventId)).toList();
        for (int i = 1; i < sorted.size(); i++) {
            double isi = sorted.get(i).timeSeconds() - sorted.get(i - 1).timeSeconds();
            if (isi < ctx.refractorySeconds()) {
                violations.add(new Derived.RefractoryViolation(cluster.clusterKey(),
                        sorted.get(i - 1).eventId(), sorted.get(i).eventId(),
                        round(isi), ctx.refractorySeconds()));
            }
        }
        if (violations.isEmpty()) return List.of();
        String digest = cluster.clusterKey() + "|" + violations;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("clusterKey", cluster.clusterKey());
        payload.put("violations", violations);
        payload.put("interpretation",
                "evidence-only: refractory violation suggests contamination; cluster number is not identity");
        Derived.Node n = EngineSupport.node(store, Derived.NodeKind.REFRACTORY_EVIDENCE,
                "EVIDENCE", digest, ctx, payload, List.of(), birthSeq);
        if (!store.derived().nodeExists(n.nodeId())) store.derived().putNode(n);
        return List.of(n);
    }

    /** 幅度漂移：前/后两半中位幅度差与斜率（uV/min）。证据，不自动拆分。 */
    public Derived.Node amplitudeDrift(ProjectStore store, EngineContext ctx,
            StoreLabels.Cluster cluster, List<Material.SpikeEvent> members, long birthSeq) {
        List<Double> amps = members.stream().filter(Material.SpikeEvent::valid)
                .map(e -> e.amplitude().siValue()).filter(java.util.Objects::nonNull)
                .sorted().toList();
        List<Material.SpikeEvent> sorted = members.stream()
                .sorted(java.util.Comparator.comparingDouble(Material.SpikeEvent::timeSeconds)).toList();
        if (amps.size() < 4) return null;
        double early = median(sorted.subList(0, sorted.size() / 2).stream()
                .map(e -> e.amplitude().siValue()).toList());
        double late = median(sorted.subList(sorted.size() / 2, sorted.size()).stream()
                .map(e -> e.amplitude().siValue()).toList());
        double t0 = sorted.get(0).timeSeconds(), t1 = sorted.get(sorted.size() - 1).timeSeconds();
        double slopeUvPerMin = t1 > t0 ? (Math.abs(late) - Math.abs(early)) / ((t1 - t0) / 60.0) : 0;
        double significance = Math.abs(Math.abs(late) - Math.abs(early))
                / (Math.abs(early) == 0 ? 1 : Math.abs(early));
        boolean drift = significance >= 0.20;
        Derived.AmplitudeDrift d = new Derived.AmplitudeDrift(cluster.clusterKey(),
                round(early), round(late), round(slopeUvPerMin), round(significance));
        var payload = new LinkedHashMap<String, Object>();
        payload.put("drift", d);
        payload.put("unit", "uV");
        payload.put("timeUnit", "seconds since t0");
        payload.put("interpretation",
                "evidence-only: amplitude drift does not authorize split/merge by itself");
        String digest = cluster.clusterKey() + "|" + d;
        Derived.Node n = EngineSupport.node(store, Derived.NodeKind.AMPLITUDE_DRIFT,
                "EVIDENCE", digest, ctx, payload, List.of(), birthSeq);
        if (!store.derived().nodeExists(n.nodeId())) store.derived().putNode(n);
        return drift ? n : null;
    }

    static double median(List<Double> v) {
        List<Double> s = v.stream().sorted().toList();
        int n = s.size();
        if (n == 0) return 0;
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    private static void union(Map<String, String> p, String a, String b) {
        p.put(find(p, a), find(p, b));
    }
    private static String find(Map<String, String> p, String x) {
        String r = x;
        while (!p.get(r).equals(r)) r = p.get(r);
        return r;
    }
    private static double round(double v) { return Math.round(v * 1e7) / 1e7; }
}
