package org.neuro.spikeledger.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.neuro.spikeledger.model.Entities.ClusterLabel;
import org.neuro.spikeledger.model.Entities.ClusteringRun;
import org.neuro.spikeledger.model.Entities.SpikeEvent;
import org.neuro.spikeledger.model.Entities.Unit;
import org.neuro.spikeledger.model.Evidence;
import org.neuro.spikeledger.model.Hashing;
import org.neuro.spikeledger.model.Ids;
import org.neuro.spikeledger.model.Physical;

/**
 * 确定性一维时间近邻聚类：相同输入摘要 + 相同参数版本，在任意机器上
 * 得到相同标签、相同稳定身份与相同顺序。聚类编号只是运行内整数。
 */
public final class Clustering {
    private Clustering() {}

    public record Params(double gapMs, double refractoryMs, double amplitudeDriftRatio,
                         double propagationTolMs) {
        public String version() {
            return "cluster-" + Hashing.sha12(gapMs + "|" + refractoryMs + "|"
                    + amplitudeDriftRatio + "|" + propagationTolMs);
        }
    }

    /** 三套参数版本，支撑“参数修订”叙事。 */
    public static final Params P1 = new Params(8.0, 1.5, 0.35, 0.8);
    public static final Params P2 = new Params(5.0, 1.5, 0.30, 0.8);
    public static final Params P3 = new Params(5.0, 2.0, 0.25, 0.6);

    public static String inputDigest(String featureVersionId, String thresholdId,
                                     String geometryId, List<SpikeEvent> spikes) {
        StringBuilder sb = new StringBuilder(featureVersionId).append('|')
                .append(thresholdId).append('|').append(geometryId).append('|');
        for (SpikeEvent s : spikes) {
            sb.append(s.id()).append('@').append(fmt(s.timeMs())).append(':')
                    .append(s.channel()).append(';');
        }
        return Hashing.sha256Hex(sb.toString());
    }

    public static String runId(String digest, String paramsVersion, double start, double end,
                               boolean endInclusive) {
        return Ids.derived("run", digest + "|" + paramsVersion + "|" + fmt(start) + "|"
                + fmt(end) + "|" + endInclusive);
    }

    /**
     * 执行聚类。事件排序键固定（time, id）保证稳定；时间窗端点按
     * {@code start<=t<end}（endInclusive=false）或 {@code start<=t<=end} 处理。
     */
    public static Result run(ClusteringRun meta, Params params, List<SpikeEvent> spikes) {
        List<SpikeEvent> in = spikes.stream()
                .filter(s -> inWindow(s.timeMs(), meta.windowStartMs(), meta.windowEndMs(),
                        meta.endInclusive()))
                .sorted(Comparator.comparingDouble(SpikeEvent::timeMs)
                        .thenComparing(SpikeEvent::id))
                .toList();

        List<List<SpikeEvent>> groups = new ArrayList<>();
        List<SpikeEvent> current = new ArrayList<>();
        for (SpikeEvent s : in) {
            if (current.isEmpty()) {
                current.add(s);
                continue;
            }
            SpikeEvent last = current.get(current.size() - 1);
            if (s.timeMs() - last.timeMs() <= params.gapMs()) {
                current.add(s);
            } else {
                groups.add(current);
                current = new ArrayList<>();
                current.add(s);
            }
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        groups.sort(Comparator
                .comparingDouble((List<SpikeEvent> g) -> g.get(0).timeMs())
                .thenComparing(g -> g.get(0).id()));

        List<ClusterLabel> labels = new ArrayList<>();
        List<Unit> units = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        for (int li = 0; li < groups.size(); li++) {
            List<SpikeEvent> g = groups.get(li);
            List<String> eventIds = new ArrayList<>();
            for (SpikeEvent s : g) {
                labels.add(new ClusterLabel(meta.id(), s.id(), li, 1.0));
                eventIds.add(s.id());
            }
            String uid = Ids.derived("u", meta.id() + "|" + li + "|"
                    + String.join(",", eventIds));
            units.add(new Unit(uid, null, meta.id(), li, eventIds,
                    meta.windowStartMs(), meta.windowEndMs()));
            evidence.addAll(evidenceFor(uid, g, params));
        }
        return new Result(labels, units, evidence);
    }

    public static boolean inWindow(double t, double start, double end, boolean endInclusive) {
        boolean afterStart = t >= start;
        boolean beforeEnd = endInclusive ? t <= end : t < end;
        return afterStart && beforeEnd;
    }

    /**
     * 证据（不是结论）：
     * 不应期违例、波形振幅漂移、同时多通道传播；不据此自动改身份。
     */
    public static List<Evidence> evidenceFor(String unitId, List<SpikeEvent> g, Params p) {
        List<Evidence> out = new ArrayList<>();
        for (int i = 1; i < g.size(); i++) {
            double dt = g.get(i).timeMs() - g.get(i - 1).timeMs();
            if (dt < p.refractoryMs()) {
                out.add(new Evidence(unitId, Evidence.REFRACTORY_VIOLATION,
                        p.refractoryMs() - dt,
                        List.of(g.get(i - 1).id(), g.get(i).id()),
                        "interval " + fmt(dt) + " ms < refractory " + fmt(p.refractoryMs()) + " ms",
                        "timeBasis=" + g.get(i).timeBasis() + ";unit=ms"));
            }
        }
        List<Double> amps = new ArrayList<>();
        for (SpikeEvent s : g) {
            Double a = amplitudeInMv(s.amplitude());
            if (a != null) {
                amps.add(Math.abs(a));
            }
        }
        if (amps.size() >= 2) {
            double min = amps.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double max = amps.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            double ratio = min == 0 ? 1.0 : (max - min) / max;
            if (ratio > p.amplitudeDriftRatio()) {
                out.add(new Evidence(unitId, Evidence.AMPLITUDE_DRIFT, ratio,
                        g.stream().map(SpikeEvent::id).toList(),
                        "|amplitude| range drift " + Math.round(ratio * 100) + "% of max",
                        "canonicalUnit=mV;rawUnitsPreserved"));
            }
        }
        Map<Long, List<SpikeEvent>> byGroup = new LinkedHashMap<>();
        for (SpikeEvent s : g) {
            if (s.concurrencyGroup() != null) {
                byGroup.computeIfAbsent(s.concurrencyGroup(), k -> new ArrayList<>()).add(s);
            }
        }
        for (Map.Entry<Long, List<SpikeEvent>> e : byGroup.entrySet()) {
            long distinctChannels = e.getValue().stream().map(SpikeEvent::channel).distinct().count();
            double span = span(e.getValue());
            if (distinctChannels >= 2 && span <= p.propagationTolMs()) {
                out.add(new Evidence(unitId, Evidence.MULTICHANNEL_PROPAGATION, span,
                        e.getValue().stream().map(SpikeEvent::id).toList(),
                        distinctChannels + " channels within " + fmt(span)
                                + " ms (concurrency group " + e.getKey() + ")",
                        "coordBasis=probe geometry;timeBasis=recording-0"));
            }
        }
        return out;
    }

    private static Double amplitudeInMv(Physical p) {
        if (p.canonical() != null) {
            return p.canonical();
        }
        return Physical.convert(p.value(), p.unit());
    }

    private static double span(List<SpikeEvent> events) {
        double min = events.stream().mapToDouble(SpikeEvent::timeMs).min().orElse(0);
        double max = events.stream().mapToDouble(SpikeEvent::timeMs).max().orElse(0);
        return max - min;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.6g", v);
    }

    public record Result(List<ClusterLabel> labels, List<Unit> units,
                         List<Evidence> evidence) {}
}
