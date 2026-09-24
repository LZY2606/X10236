package neurospike;

import java.util.List;
import java.util.Map;

/**
 * Full derived evaluation of a State at one event. Deterministic: same state +
 * same rule params + same parent dependencies yields identical ordering, ids
 * and values.
 */
public record Evaluation(String stateEventId, String inputDigest, String paramVersion,
                         List<ClusterGen> clusters, List<Finding> findings,
                         List<LineageEdge> newEdges, Map<String, double[]> features,
                         List<FeaturePoint> projection, List<RateBin> rateBins,
                         List<AutocorrBin> autocorrBins, List<String> ordering,
                         List<Diagnostic> diagnostics) {

    public ClusterGen cluster(String stableId) {
        return clusters.stream().filter(c -> c.stableId().equals(stableId))
                .findFirst().orElse(null);
    }
}
