package org.neuro.spikeledger.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.neuro.spikeledger.model.Entities.SpikeEvent;

/**
 * 确定性特征投影（两轴：峰幅、通道指数），以及自相关与发放率统计。
 * 全部在服务端可调用，便于直接单测。无法转换幅度时该点标记 invalid，
 * 不为画图而丢弃。
 */
public final class Features {
    private Features() {}

    public record Point(String eventId, double x, double y, String xUnit, String yUnit,
                        boolean amplitudeValid, String rawAmplitude) {}

    public static List<Point> project(List<SpikeEvent> spikes) {
        List<Point> out = new ArrayList<>();
        for (SpikeEvent s : spikes) {
            Double amp = s.amplitude().canonical();
            boolean valid = amp != null;
            double y = valid ? Math.abs(amp) : 0.0;
            out.add(new Point(s.id(), s.channel(), y, "channel-index",
                    "mV", valid, s.amplitude().raw()));
        }
        return out;
    }

    /** 自相关直方图，bin 固定 1 ms，范围 [-maxLag,maxLag]。 */
    public static int[] autocorrelogram(List<Double> timesMs, double maxLagMs) {
        int bins = (int) Math.round(maxLagMs * 2);
        int[] hist = new int[bins];
        List<Double> sorted = timesMs.stream().sorted().toList();
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                double d = sorted.get(j) - sorted.get(i);
                if (d > maxLagMs) {
                    break;
                }
                int pos = (int) Math.round(d + maxLagMs) - bins / 2 + bins / 2;
                int idx = (int) Math.round(d + maxLagMs) - 1;
                if (idx >= 0 && idx < bins) {
                    hist[idx]++;
                    int mirror = bins - 1 - idx;
                    if (mirror >= 0 && mirror < bins) {
                        hist[mirror]++;
                    }
                }
            }
        }
        return hist;
    }

    /** 发放率（Hz），固定 bin 宽度；时间基准统一 ms。 */
    public static List<Map<String, Object>> firingRate(List<Double> timesMs, double startMs,
                                                       double endMs, double binMs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (double t = startMs; t < endMs; t += binMs) {
            double lo = t;
            double hi = Math.min(t + binMs, endMs);
            long n = timesMs.stream().filter(x -> x >= lo && x < hi).count();
            double rateHz = n / ((hi - lo) / 1000.0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t0_ms", lo);
            m.put("t1_ms", hi);
            m.put("rate_hz", rateHz);
            out.add(m);
        }
        return out;
    }

    /** 漂移时间线：每个时间 bin 的中位 |幅度|（mV）；无效值单独返回诊断点。 */
    public static List<Map<String, Object>> amplitudeTimeline(List<SpikeEvent> spikes,
                                                              double binMs) {
        List<SpikeEvent> valid = spikes.stream()
                .filter(s -> s.amplitude().canonical() != null)
                .sorted(java.util.Comparator.comparingDouble(SpikeEvent::timeMs))
                .toList();
        if (valid.isEmpty()) {
            return List.of();
        }
        double start = valid.get(0).timeMs();
        double end = valid.get(valid.size() - 1).timeMs() + 0.0001;
        List<Map<String, Object>> out = new ArrayList<>();
        for (double t = start; t < end; t += binMs) {
            double hi = t + binMs;
            List<Double> binVals = valid.stream()
                    .filter(s -> s.timeMs() >= t && s.timeMs() < hi)
                    .map(s -> Math.abs(s.amplitude().canonical()))
                    .sorted()
                    .toList();
            if (binVals.isEmpty()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t0_ms", t);
            m.put("median_amp_mv", binVals.get(binVals.size() / 2));
            m.put("n", binVals.size());
            out.add(m);
        }
        return out;
    }
}
