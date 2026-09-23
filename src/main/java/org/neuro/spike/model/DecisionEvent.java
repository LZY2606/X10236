package org.neuro.spike.model;

/**
 * A mutable human/program adjudication. Decisions are append-only: a conflict
 * is resolved by a new decision, never by erasing the rejected one.
 */
public record DecisionEvent(
        String uid,
        String action,
        String targetProposal,
        String subjectCluster,
        String payloadJson,
        String decisionVersion,
        String intentId,
        int seq) {
}
