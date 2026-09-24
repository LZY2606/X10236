package neurogsb.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import neurogsb.db.Database;
import neurogsb.model.OpResult;
import neurogsb.model.Records.*;
import neurogsb.model.Records.Derived;

import java.util.ArrayList;
import java.util.List;

/**
 * 证据计算：不应期违例、幅度漂移、同时多通道传播都只是证据，
 * 判定依据以机器可读事件写入 payload.judgment_basis。
 * 所有派生结果记录输入摘要、参数版本与父级依赖。
 */
public final class EvidenceService {
    public static final String BASIS =
        "evidence_only: refractory_violation/amplitude_drift/co_propagation are evidence, "
        + "cluster_no is not a stable identity; window changes recompute only intersecting clusters";

    private final Database db;
    private final Store store;

    public EvidenceService(Database db, Store store) { this.db = db; this.store = store; }

    public record Params(double refractoryMs, double copropWindowMs, double sharedMarginMs,
                         double driftSlopeThreshold, String paramVersion) {}

    public Params currentParams() {
        var thresholds = store.materials("detection_threshold");
        if (thresholds.isEmpty()) return new Params(1.5, 0.05, 0.5, 0.02, "none");
        var latest = thresholds.get(thresholds.size() - 1);
        var p = latest.payload();
        return new Params(p.get("refractory_ms").asDouble(), p.get("coprop_window_ms").asDouble(),
                p.get("shared_margin_ms").asDouble(), p.get("drift_slope_threshold_uv_per_ms").asDouble(),
                latest.id() + "@v" + latest.version());
    }

    public record Window(double t0, double t1) {
        public boolean contains(double t) { return t >= t0 && t < t1; } // 半开区间 [t0, t1)
    }

    public Window currentWindow() {
        return store.kvGet("window").map(w -> {
            var n = Store.json(w);
            return new Window(n.get("t0").asDouble(), n.get("t1").asDouble());
        }).orElse(new Window(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY));
    }

    /** 计算一个单元的全部派生结果。预算耗尽时返回 BudgetExhausted，不写任何部分结果。 */
    public OpResult computeUnit(String unitId, long eventSeq, long budget) {
        var params = currentParams();
        var window = currentWindow();
        List<Spike> all = store.spikes(unitId);
        List<Spike> spikes = all.stream().filter(s -> s.tMs() != null && window.contains(s.tMs())).toList();
        if (spikes.size() > budget) {
            return new OpResult.BudgetExhausted(spikes.size(), budget, "computeUnit:" + unitId);
        }
        String opId = Store.newId("op");
        db.beginOp(opId, "compute " + unitId);
        try {
            List<String> parents = new ArrayList<>();
            parents.add(params.paramVersion());
            store.materials("feature_version").stream().reduce((a, b) -> b)
                    .ifPresent(f -> parents.add(f.id()));
            spikes.stream().map(Spike::waveformRefId).distinct().filter(java.util.Objects::nonNull)
                    .forEach(parents::add);
            String inputDigest = Store.digest(unitId + "|" + params.paramVersion() + "|"
                    + window.t0 + "|" + window.t1 + "|"
                    + spikes.stream().map(Spike::id).sorted().reduce("", (a, b) -> a + b + ","));

            insertDerived(unitId, "evidence.refractory", refractory(spikes, params), inputDigest, params, parents, eventSeq, opId);
            insertDerived(unitId, "evidence.drift", drift(spikes, params), inputDigest, params, parents, eventSeq, opId);
            insertDerived(unitId, "evidence.coprop", coprop(unitId, spikes, params), inputDigest, params, parents, eventSeq, opId);
            insertDerived(unitId, "acg", acg(spikes), inputDigest, params, parents, eventSeq, opId);
            insertDerived(unitId, "rate", rate(spikes, window), inputDigest, params, parents, eventSeq, opId);
            insertDerived(unitId, "projection", projection(spikes), inputDigest, params, parents, eventSeq, opId);
            db.commitOp(opId);
            return new OpResult.Ok("computed " + unitId + " spikes=" + spikes.size(), List.of(unitId));
        } catch (RuntimeException e) {
            db.recover();
            throw e;
        }
    }

    private void insertDerived(String unitId, String kind, ObjectNode payload, String digest,
                               Params params, List<String> parents, long eventSeq, String opId) {
        payload.put("judgment_basis", BASIS);
        payload.put("time_base", "ms since recording start");
        payload.put("amplitude_unit", "µV");
        store.insertDerived(new Derived(Store.newId("der"), kind, unitId, digest,
                params.paramVersion(), parents, "ok", payload, eventSeq), opId);
    }

    /** 不应期违例：ISI < refractory_ms 的对数（证据，不是身份）。 */
    ObjectNode refractory(List<Spike> spikes, Params p) {
        ObjectNode out = Store.M.createObjectNode();
        ArrayNode pairs = out.putArray("pairs");
        int violations = 0;
        for (int i = 1; i < spikes.size(); i++) {
            double isi = spikes.get(i).tMs() - spikes.get(i - 1).tMs();
            if (isi < p.refractoryMs()) {
                violations++;
                ObjectNode pair = pairs.addObject();
                pair.put("a", spikes.get(i - 1).id());
                pair.put("b", spikes.get(i).id());
                pair.put("isi_ms", isi);
                pair.put("unit", "ms");
            }
        }
        out.put("kind", "REFRACTORY_VIOLATION");
        out.put("violations", violations);
        out.put("refractory_ms", p.refractoryMs());
        return out;
    }

    /** 幅度漂移：振幅对时间的最小二乘斜率 (µV/ms)。 */
    ObjectNode drift(List<Spike> spikes, Params p) {
        ObjectNode out = Store.M.createObjectNode();
        out.put("kind", "AMPLITUDE_DRIFT");
        var pts = spikes.stream().filter(s -> s.ampUv() != null).toList();
        if (pts.size() < 3) {
            out.put("slope_uv_per_ms", 0.0);
            out.put("detected", false);
            out.put("note", "样本不足");
            return out;
        }
        double n = pts.size();
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (var s : pts) { sx += s.tMs(); sy += s.ampUv(); sxx += s.tMs() * s.tMs(); sxy += s.tMs() * s.ampUv(); }
        double denom = n * sxx - sx * sx;
        double slope = denom == 0 ? 0 : (n * sxy - sx * sy) / denom;
        double mean = sy / n;
        out.put("slope_uv_per_ms", slope);
        out.put("mean_uv", mean);
        out.put("detected", Math.abs(slope) > p.driftSlopeThreshold());
        out.put("threshold_uv_per_ms", p.driftSlopeThreshold());
        return out;
    }

    /** 同时多通道传播：同一单元在 ±coprop_window 内出现在多个通道（证据）。 */
    ObjectNode coprop(String unitId, List<Spike> spikes, Params p) {
        ObjectNode out = Store.M.createObjectNode();
        out.put("kind", "CO_PROPAGATION");
        ArrayNode groups = out.putArray("groups");
        // 并发棘波：与其他单元在 ±window 内同时发放
        var others = store.allSpikes().stream()
                .filter(s -> !s.unitId().equals(unitId) && s.tMs() != null).toList();
        int concurrent = 0;
        for (var s : spikes) {
            List<String> channels = new ArrayList<>();
            List<String> partners = new ArrayList<>();
            if (s.channel() != null) channels.add(String.valueOf(s.channel()));
            for (var o : others) {
                if (Math.abs(o.tMs() - s.tMs()) <= p.copropWindowMs()) {
                    partners.add(o.unitId() + "@" + o.id());
                    if (o.channel() != null) channels.add(String.valueOf(o.channel()));
                }
            }
            if (channels.stream().distinct().count() > 1 || !partners.isEmpty()) {
                concurrent++;
                ObjectNode g = groups.addObject();
                g.put("spike", s.id());
                g.put("t_ms", s.tMs());
                g.put("unit", "ms");
                g.put("channels", String.join(",", channels.stream().distinct().sorted().toList()));
                g.put("concurrent_with", String.join(",", partners));
            }
        }
        out.put("concurrent_events", concurrent);
        out.put("coprop_window_ms", p.copropWindowMs());
        return out;
    }

    /** 自相关直方图：1ms bin，至 50ms。 */
    ObjectNode acg(List<Spike> spikes) {
        ObjectNode out = Store.M.createObjectNode();
        int bins = 50;
        int[] hist = new int[bins];
        for (int i = 0; i < spikes.size(); i++)
            for (int j = i + 1; j < spikes.size(); j++) {
                int d = (int) Math.round(spikes.get(j).tMs() - spikes.get(i).tMs());
                if (d >= bins) break;
                if (d >= 0) hist[d]++;
            }
        ArrayNode arr = out.putArray("bins");
        for (int h : hist) arr.add(h);
        out.put("bin_ms", 1.0);
        out.put("unit", "ms");
        return out;
    }

    /** 发放率：1000ms bin，spikes/s。 */
    ObjectNode rate(List<Spike> spikes, Window window) {
        ObjectNode out = Store.M.createObjectNode();
        double t0 = spikes.isEmpty() ? 0 : spikes.get(0).tMs();
        double t1 = spikes.isEmpty() ? 0 : spikes.get(spikes.size() - 1).tMs();
        if (Double.isFinite(window.t0)) t0 = Math.max(t0, window.t0);
        if (Double.isFinite(window.t1)) t1 = Math.min(t1, window.t1);
        int nBins = Math.max(1, (int) Math.ceil((t1 - t0) / 1000.0));
        int[] hist = new int[nBins];
        for (var s : spikes) {
            int b = (int) ((s.tMs() - t0) / 1000.0);
            if (b >= 0 && b < nBins) hist[b]++;
        }
        ArrayNode arr = out.putArray("bins");
        for (int h : hist) arr.add(h); // spikes/s（bin=1s）
        out.put("bin_ms", 1000.0);
        out.put("t0_ms", t0);
        out.put("unit", "spikes/s");
        return out;
    }

    /** 特征投影：f1/f2 来自特征版本物材定义的空间。 */
    ObjectNode projection(List<Spike> spikes) {
        ObjectNode out = Store.M.createObjectNode();
        ArrayNode pts = out.putArray("points");
        for (var s : spikes) {
            ArrayNode pt = pts.addArray();
            pt.add(s.f1()); pt.add(s.f2()); pt.add(s.id());
        }
        out.put("dims", "f1,f2");
        return out;
    }
}
