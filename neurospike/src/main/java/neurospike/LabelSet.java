package neurospike;

import java.util.Map;

/**
 * Immutable clustering-label material (output of a spike sorter run). Cluster
 * numbers are explicitly NOT stable identities; a stable identity is derived
 * from event membership lineage in the engine.
 */
public record LabelSet(String labelVersion, String source,
                       Map<String, Integer> detectionCluster) {
}
