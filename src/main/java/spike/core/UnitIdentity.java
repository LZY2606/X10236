package spike.core;

import java.util.List;

/**
 * Stable unit identity. Cluster numbers ({@code code}) are per-run labels and
 * are never treated as identity; merges/splits connect unit ids via lineage.
 */
public record UnitIdentity(
        String unitId,
        String fingerprint,
        String currentCode,
        String runId,
        List<String> lineageParentUnitIds,
        String relation,
        long firstSeenSeq
) {
    public static final String RELATION_SEED = "seed";
    public static final String RELATION_MERGE = "merge";
    public static final String RELATION_SPLIT = "split";
    public static final String RELATION_RENUMBER = "renumber";
}
