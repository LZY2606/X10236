package org.neurospike.engine;

import org.neurospike.domain.DecisionRecord;
import org.neurospike.domain.DerivedResult;
import org.neurospike.domain.EvidenceEvent;
import org.neurospike.io.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DecisionRules {
    public DecisionEvaluation evaluate(List<DecisionRecord> prior, DecisionRecord candidate,
                                       List<DerivedResult> affectedResults, long sequence) {
        List<String> candidates = new ArrayList<>();
        DecisionRecord conflict = prior.stream()
                .filter(decision -> decision.scopeId().equals(candidate.scopeId()))
                .filter(decision -> incompatible(decision.action(), candidate.action()))
                .findFirst()
                .orElse(null);
        String status = conflict == null ? candidate.status() : "CONFLICTED";
        List<DerivedResult> downstream = affectedResults.stream()
                .sorted(java.util.Comparator.comparing(DerivedResult::sequence).thenComparing(DerivedResult::id))
                .toList();
        downstream.forEach(result -> candidates.add(result.stableSubjectId()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionId", candidate.id());
        payload.put("scopeId", candidate.scopeId());
        payload.put("requestedAction", candidate.action());
        payload.put("status", status);
        payload.put("legalStableCandidates", candidates.stream().distinct().sorted().toList());
        payload.put("affectedResultIds", downstream.stream().map(DerivedResult::id).toList());
        payload.put("identityPolicy", "cluster_number_is_not_stable_identity");
        if (conflict != null) {
            payload.put("conflictsWithDecisionId", conflict.id());
            payload.put("resolution", "semantic_unresolvable_keep_all_legal_candidates");
        }
        EvidenceEvent event = new EvidenceEvent(sequence, "ev-decision-" + candidate.id(),
                candidate.atMs(), conflict == null ? "ADJUDICATION_" + candidate.action() : "ADJUDICATION_CONFLICT",
                conflict == null ? "INFO" : "WARNING", candidate.scopeId(), "decision-rules-v1",
                "human adjudication and downstream dependency graph", Json.write(payload),
                downstream.isEmpty() ? null : downstream.get(0).id());
        return new DecisionEvaluation(status, conflict == null ? candidate : withConflict(candidate, conflict.id()),
                event, downstream);
    }

    private boolean incompatible(String left, String right) {
        if (left.equals(right)) {
            return false;
        }
        return left.equals("ACCEPT") || right.equals("ACCEPT" ) || left.equals("REJECT") || right.equals("REJECT");
    }

    private DecisionRecord withConflict(DecisionRecord record, String conflictId) {
        return new DecisionRecord(record.id(), record.revisionId(), record.scopeId(), record.action(),
                record.rationale(), record.author(), record.atMs(), "CONFLICTED", conflictId, record.payloadJson());
    }

    public record DecisionEvaluation(String status, DecisionRecord record, EvidenceEvent event,
                                     List<DerivedResult> affectedResults) {
    }
}
