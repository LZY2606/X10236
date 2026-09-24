package neuro.spike.model;

import java.util.List;

/**
 * Immutable cluster-label version. Labels are assignments of detections to cluster
 * numbers for a particular feature version; cluster numbers are explicitly NOT stable
 * identity across versions.
 */
public record ClusterLabel(String id, String labelVersion, String featureVersionId,
                           List<Assignment> assignments) {
    /**
     * clusterNumber may be negative for noise / unassigned, and a detection may appear
     * in several clusters within the same version (split-shared membership); ambiguity
     * is modelled by multiple Assignment rows with the same detectionId.
     */
    public record Assignment(String detectionId, int clusterNumber, double weight) {
    }
}
