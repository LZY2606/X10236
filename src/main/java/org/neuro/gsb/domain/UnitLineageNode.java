package org.neuro.gsb.domain;

import java.util.List;

/**
 * Stable, auditable identity for a candidate unit across renumbering, drift,
 * merges and splits. Parent links are earlier lineage node ids. A split yields
 * children; a merge yields a single child with multiple parents.
 */
public record UnitLineageNode(String unitId, String bornFromAdjudicationId, List<String> parentUnitIds,
                              List<String> clusterLabelIds, List<String> eventIds, String status,
                              String note) {

    public static final String ACTIVE = "ACTIVE";
    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String CONFLICTED = "CONFLICTED";
}
