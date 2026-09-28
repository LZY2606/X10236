package org.neurospike.domain;

public record DecisionRecord(
        String id,
        String revisionId,
        String scopeId,
        String action,
        String rationale,
        String author,
        String atMs,
        String status,
        String conflictsWithId,
        String payloadJson) {
}
