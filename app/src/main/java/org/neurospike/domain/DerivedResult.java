package org.neurospike.domain;

public record DerivedResult(
        String id,
        String kind,
        String stableSubjectId,
        String inputDigest,
        String parameterVersion,
        String parentDependenciesJson,
        String windowId,
        String status,
        String validityStartMs,
        String validityEndMs,
        String invalidReason,
        String payloadJson,
        long sequence) {
}
