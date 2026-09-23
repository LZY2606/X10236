package org.neuro.gsb.domain;

import java.util.List;

/**
 * A human decision kept separate from raw material and machine results.
 * Actions: MERGE, SPLIT, ACCEPT, REJECT, PARAM_ADJUST.
 * Every decision names the machine result(s) it acted on; conflicting decisions
 * remain recorded and are flagged rather than overwritten.
 */
public record AdjudicationEvent(String id, String reviewer, String action, String rationale,
                                List<String> clusterLabelIds, List<String> lineageUnitIds,
                                String parameterId, String supersedesId) {
}
