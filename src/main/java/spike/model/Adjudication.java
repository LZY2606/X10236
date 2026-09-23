package spike.model;

import java.util.List;
import spike.model.Enums.DecisionType;
import spike.model.Enums.EventStatus;

/**
 * A mutable human decision, stored separately from immutable material. Decisions are append-only
 * and versioned: a later conflicting decision never deletes the earlier one, it supersedes it.
 * Both adopted and rejected outcomes remain in the record.
 */
public record Adjudication(String id, DecisionType decision, EventStatus status,
                           List<String> clusterIds, List<String> spikeIds,
                           List<String> evidenceIds, String rationale, String reviewer,
                           String parameterVersion, String timeBasis, long seq,
                           long supersededBySeq, String conflictGroup) {
}
