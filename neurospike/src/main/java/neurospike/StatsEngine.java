package neurospike;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Firing-rate bins and autocorrelation, with units and the time basis exposed. */
public final class StatsEngine {

    private StatsEngine() {
    }

    public static List<RateBin> rate(State state, MembershipEngine.MembershipResult m,
                                     WindowSpec window, long seq) {
        FeatureVersionSpec spec = state.specAtOrBefore(seq).material();
        double hz = state.timeBasis().samplesPerSecond().value();
        double binS = spec.rateBinSeconds().valueIn("s");
        Map<String, MaterialRev<Detection>> dets = state.detectionAtOrBefore(seq);
        List<RateBin> out = new ArrayList<>();
        for (ClusterGen c : m.clusters()) {
            List<Detection> ds = EvidenceEngine.inWindow(state, dets, c.detections(), window, hz);
            for (double start = window.startSeconds(); start < window.endSeconds();
                 start += binS) {
                double end = Math.min(window.endSeconds(), round(start + binS));
                int count = 0;
                boolean atStart = false;
                boolean atEnd = false;
                for (Detection d : ds) {
                    double t = d.sample().value() / hz;
                    if (t >= start && t < end) {
                        count++;
                    }
                    if (Math.abs(t - start) < 1e-12) {
                        atStart = true;
                    }
                    if (Math.abs(t - end) < 1e-12) {
                        atEnd = true;
                    }
                }
                out.add(new RateBin(c.stableId(), round(start), round(end), count,
                        round(count / (end - start)), atStart, atEnd, "Hz"));
            }
        }
        return out;
    }

    public static List<AutocorrBin> autocorr(State state,
                                             MembershipEngine.MembershipResult m,
                                             WindowSpec window, long seq) {
        FeatureVersionSpec spec = state.specAtOrBefore(seq).material();
        double hz = state.timeBasis().samplesPerSecond().value();
        double half = spec.autocorrHalfWindow().valueIn("s");
        double bin = 0.001;
        Map<String, MaterialRev<Detection>> dets = state.detectionAtOrBefore(seq);
        List<AutocorrBin> out = new ArrayList<>();
        for (ClusterGen c : m.clusters()) {
            List<Detection> ds = EvidenceEngine.inWindow(state, dets, c.detections(), window, hz);
            int bins = (int) Math.round(half / bin);
            int[] counts = new int[2 * bins + 1];
            for (int i = 0; i < ds.size(); i++) {
                for (int j = i + 1; j < ds.size(); j++) {
                    double lag = (ds.get(j).sample().value() - ds.get(i).sample().value()) / hz;
                    if (lag > half) {
                        break;
                    }
                    int idx = (int) Math.round(lag / bin);
                    counts[bins + idx]++;
                    counts[bins - idx]++;
                }
            }
            for (int k = 1; k <= bins; k++) {
                double lag = round(k * bin);
                out.add(new AutocorrBin(c.stableId(), lag, counts[bins + k], "s"));
                out.add(new AutocorrBin(c.stableId(), -lag, counts[bins - k], "s"));
            }
        }
        out.sort((a, b) -> {
            int byCluster = a.clusterStableId().compareTo(b.clusterStableId());
            return byCluster != 0 ? byCluster
                    : Double.compare(a.lagSeconds(), b.lagSeconds());
        });
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 1e9) / 1e9;
    }
}
