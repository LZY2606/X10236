package org.neurospike.domain;

public record Material(
        String id,
        MaterialKind kind,
        String version,
        String supersedesId,
        String directSourceId,
        String rawRepresentation,
        String normalizedJson,
        String coordinateBasis,
        String unit,
        String status,
        String diagnostic,
        String createdAt) {
}
