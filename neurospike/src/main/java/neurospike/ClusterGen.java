package neurospike;

import java.util.List;

/**
 * One generation of one cluster. {@code clusterNumber} is the (unstable) number
 * from a label set; {@code stableId} is derived from the sorted event
 * membership plus label version and is the identity that survives renumber,
 * merge and split.
 */
public record ClusterGen(String stableId, int clusterNumber, String labelVersion,
                         List<String> detections, String originEventId) {
}
