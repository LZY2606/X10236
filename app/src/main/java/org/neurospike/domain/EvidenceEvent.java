package org.neurospike.domain;

public record EvidenceEvent(
        long sequence,
        String id,
        String atMs,
        String type,
        String severity,
        String subjectStableId,
        String ruleVersion,
        String basis,
        String payloadJson,
        String acceptedResultId) {
}
