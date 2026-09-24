package neuro.spike.model;

import java.util.List;

/**
 * Immutable material: a cluster assignment from ONE clustering run/label
 * version. The run-local ordinal is explicitly NOT an identity: the same
 * ordinal in a later run can refer to different spikes. Stable identity lives
 * in {@link #unitKey} and in the spike membership.
 */
public record ClusterLabel(String labelId,
                           String clusteringId,
                           String labelVersion,
                           int runOrdinal,
                           String unitKey,
                           List<String> eventIds) {
    public ClusterLabel {
        eventIds = eventIds == null ? List.of() : List.copyOf(eventIds);
        if (unitKey == null || unitKey.isBlank()) throw new IllegalArgumentException("unitKey required");
    }
}
