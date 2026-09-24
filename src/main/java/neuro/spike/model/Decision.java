package neuro.spike.model;

import java.util.List;

/**
 * Immutable decision event. Adjudication is stored separately from raw
 * materials and from cluster labels. A decision references the evidence it
 * acted upon and declares affected downstream subjects. Conflicting decisions
 * are both retained; the engine exposes the conflict rather than overwriting.
 */
public record Decision(String decisionId,
                       long seq,
                       DecisionType type,
                       List<String> subjectIds,
                       List<String> evidenceIds,
                       String targetUnitKey,
                       List<String> childUnitKeys,
                       String paramsJson,
                       String rationale,
                       double atSec,
                       String basis) {
    public Decision {
        subjectIds = subjectIds == null ? List.of() : List.copyOf(subjectIds);
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        childUnitKeys = childUnitKeys == null ? List.of() : List.copyOf(childUnitKeys);
    }
}
