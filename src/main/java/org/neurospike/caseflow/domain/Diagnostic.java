package org.neurospike.caseflow.domain;

public record Diagnostic(
        String code,
        String path,
        String message,
        String materialId,
        String unit,
        String coordinateBasis
) {
}
