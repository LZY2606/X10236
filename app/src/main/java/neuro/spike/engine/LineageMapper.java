package neuro.spike.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import neuro.spike.model.ClusterLabel;

/**
 * Maps the cluster numbers of a new label version to stable unit ids using detection-set
 * overlap with the previous label version. Cluster numbers are deliberately treated as
 * non-identifying: a pure renumbering still resolves to the same unit ids, while genuine
 * splits/merges fan in/out.
 *
 * When a new cluster is a split child whose detections are shared with its sibling (the
 * mandated split-shared-event case) and overlap alone cannot assign the shared detection
 * uniquely, both candidate units are retained and the ambiguity is reported for the
 * decision layer rather than guessed.
 */
public final class LineageMapper {

    public record NewCluster(int clusterNumber, Set<String> detections, Map<String, Double> weights) {
    }

    public record Mapping(String unitId, int clusterNumber, double jaccard, boolean ambiguous) {
    }

    /**
     * @param previous detectionId -> unit ids (one or more when already shared)
     * @param label    incoming label version
     * @return ordered mapping for every new cluster
     */
    public List<Mapping> map(Map<String, List<String>> previous, ClusterLabel label) {
        Map<Integer, NewCluster> incoming = new LinkedHashMap<>();
        for (ClusterLabel.Assignment a : label.assignments()) {
            NewCluster nc = incoming.computeIfAbsent(a.clusterNumber(),
                    k -> new NewCluster(k, new HashSet<>(), new LinkedHashMap<>()));
            nc.detections.add(a.detectionId());
            nc.weights.put(a.detectionId(), a.weight());
        }

        Map<String, Set<String>> unitDetections = new LinkedHashMap<>();
        previous.forEach((det, units) -> {
            for (String unit : units) {
                unitDetections.computeIfAbsent(unit, k -> new HashSet<>()).add(det);
            }
        });

        List<Mapping> result = new ArrayList<>();
        for (NewCluster nc : incoming.values()) {
            String bestUnit = null;
            double bestScore = -1.0;
            for (Map.Entry<String, Set<String>> e : unitDetections.entrySet()) {
                double score = jaccard(nc.detections(), e.getValue());
                if (score > bestScore) {
                    bestScore = score;
                    bestUnit = e.getKey();
                }
            }
            if (bestUnit == null || bestScore == 0.0) {
                bestUnit = "unit-" + stableNumber(label, nc.clusterNumber());
            }
            boolean ambiguous = isSplitShared(nc, incoming);
            result.add(new Mapping(bestUnit, nc.clusterNumber(), bestScore, ambiguous));
        }
        return result;
    }

    /**
     * A cluster is split-ambiguous when at least one of its detections is also assigned
     * to a sibling cluster with non-trivial weight on both sides.
     */
    private boolean isSplitShared(NewCluster nc, Map<Integer, NewCluster> all) {
        for (String det : nc.detections()) {
            for (NewCluster other : all.values()) {
                if (other.clusterNumber() == nc.clusterNumber()) {
                    continue;
                }
                if (other.detections().contains(det)) {
                    double w1 = nc.weights().getOrDefault(det, 0.0);
                    double w2 = other.weights().getOrDefault(det, 0.0);
                    if (w1 > 0.0 && w2 > 0.0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    /** Deterministic fresh unit number: derived from pack content hash ordering upstream. */
    private String stableNumber(ClusterLabel label, int clusterNumber) {
        return Integer.toHexString((label.id() + "#" + clusterNumber).hashCode());
    }
}
