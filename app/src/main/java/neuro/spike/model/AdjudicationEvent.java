package neuro.spike.model;

import java.util.List;
import java.util.Map;

/**
 * Immutable human decision. Kept strictly separate from material inputs: accept / reject
 * / parameter-adjust are all new events and never overwrite prior materials or decisions.
 */
public record AdjudicationEvent(String id, long seq, String decision,
                               String targetUnitId, String rationale,
                               Map<String, String> parameterAdjustments,
                               List<String> conflictsWithDecisionIds,
                               String parentDecisionId) {
}
