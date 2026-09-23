package org.neuro.spike.model;

import java.util.List;

/**
 * Content-addressed computed output. The digest binds the input summary,
 * parameter version and parent dependencies, so re-running on equivalent
 * inputs yields the same id and a stable ordering.
 */
public record DerivedResult(
        String uid,
        String kind,
        String targetUid,
        String inputDigest,
        String paramVersion,
        String featureVersion,
        List<String> parents,
        ResultStatus status,
        String outputJson,
        List<Diagnostic> diagnostics,
        int budgetLimit,
        int budgetUsed,
        int tStart,
        int tEnd,
        String windowKey,
        int seq) {
}
