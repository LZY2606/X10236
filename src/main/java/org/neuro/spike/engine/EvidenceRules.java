package org.neuro.spike.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side evidence rules. Each rule returns structured findings only;
 * none of them reassigns cluster identity. A label renumber never changes
 * these findings because they key on stable event uids.
 */
public final class EvidenceRules {
    public static final double REFRACTORY_SEC = 0.0010;
    public static final double CONCURRENT_SEC = 0.0003;
    public static final double PROPAGATION_SEC = 0.0015;
    public static final double DRIFT_RATIO = 0.25;

    private EvidenceRules() {
    }

    public record Finding(String kind, String eventA, String eventB, double value, String detail) {
    }

    public static List<Finding> refractory(List<DetEvent> events) {
        List<Finding> out = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            for (int j = i + 1; j < events.size(); j++) {
                double dt = Math.abs(events.get(i).timeSec() - events.get(j).timeSec());
                if (dt > 0 && dt < REFRACTORY_SEC) {
                    out.add(new Finding("REFRACTORY_VIOLATION", events.get(i).uid(), events.get(j).uid(),
                            dt, "inter-spike interval below 1.0 ms refractory period"));
                }
            }
        }
        return out;
    }

    /** Near-simultaneous events on different channels, within or across clusters. */
    public static List<Finding> concurrent(List<DetEvent> events) {
        List<Finding> out = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            for (int j = i + 1; j < events.size(); j++) {
                DetEvent a = events.get(i);
                DetEvent b = events.get(j);
                double dt = Math.abs(a.timeSec() - b.timeSec());
                if (dt <= CONCURRENT_SEC && a.channel() != b.channel()) {
                    out.add(new Finding("CONCURRENT_SPIKE", a.uid(), b.uid(), dt,
                            "spikes within 0.3 ms on different channels"));
                }
            }
        }
        return out;
    }

    /** Multi-channel propagation: distinct channels with spikes within 1.5 ms. */
    public static List<Finding> propagation(List<DetEvent> events) {
        List<Finding> out = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            for (int j = i + 1; j < events.size(); j++) {
                DetEvent a = events.get(i);
                DetEvent b = events.get(j);
                double dt = Math.abs(a.timeSec() - b.timeSec());
                if (dt <= PROPAGATION_SEC && dt > CONCURRENT_SEC && a.channel() != b.channel()) {
                    out.add(new Finding("MULTI_CHANNEL_PROPAGATION", a.uid(), b.uid(), dt,
                            "near-simultaneous spikes across channels (multi-unit propagation evidence)"));
                }
            }
        }
        return out;
    }

    /**
     * Amplitude drift: mean amplitude first half vs second half by time.
     * Unconvertible amplitudes are counted in {@code skipped}; when every
     * amplitude is unconvertible the caller must emit INCOMPLETE, not a fake zero.
     */
    public static DriftResult amplitudeDrift(List<DetEvent> events) {
        List<DetEvent> sorted = events.stream()
                .filter(e -> e.amplitude() != null && e.amplitude().convertible())
                .sorted((a, b) -> Double.compare(a.timeSec(), b.timeSec()))
                .toList();
        int skipped = (int) events.stream()
                .filter(e -> e.amplitude() == null || !e.amplitude().convertible())
                .count();
        if (sorted.size() < 2) {
            return new DriftResult(false, Double.NaN, Double.NaN, Double.NaN, skipped,
                    sorted.isEmpty() ? "no convertible amplitudes; raw values retained at origin" : null);
        }
        int half = Math.max(1, sorted.size() / 2);
        double first = mean(sorted.subList(0, half));
        double second = mean(sorted.subList(half, sorted.size()));
        double ratio = Math.abs(second - first) / Math.max(Math.abs(first), 1e-12);
        return new DriftResult(ratio >= DRIFT_RATIO, first, second, ratio, skipped, null);
    }

    public record DriftResult(boolean drifted, double firstMean, double secondMean,
                              double ratio, int skipped, String inability) {
    }

    /** Per-0.5 s bin mean amplitude in microvolts, with invalid bins left null. */
    public static Map<Integer, Double> driftTimeline(List<DetEvent> events, double binSec, int bins) {
        Map<Integer, List<Double>> bucket = new LinkedHashMap<>();
        for (DetEvent e : events) {
            if (e.amplitude() == null || !e.amplitude().convertible()) {
                continue;
            }
            int bin = (int) Math.floor(e.timeSec() / binSec);
            if (bin < 0 || bin >= bins) {
                continue;
            }
            bucket.computeIfAbsent(bin, k -> new ArrayList<>()).add(e.amplitude().si().get() * 1e6);
        }
        Map<Integer, Double> out = new LinkedHashMap<>();
        bucket.forEach((k, v) -> out.put(k, v.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN)));
        return out;
    }

    private static double mean(List<DetEvent> list) {
        return list.stream().mapToDouble(e -> e.amplitude().si().get()).average().orElse(Double.NaN);
    }
}
