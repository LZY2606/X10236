package org.neuro.spike.model;

/**
 * Machine-readable domain event (evidence raised, recompute scopes,
 * conflicts, parameter revisions...). Every judgement basis required by the
 * archive has an event the UI and tests can consume structurally.
 */
public record EventLog(
        String uid,
        String type,
        String subjectUid,
        String payloadJson,
        String basis,
        String derivationId,
        int seq) {
}
