package org.neurospike.domain;

public record FeatureObservation(
        String id,
        String spikeId,
        String featureVersionId,
        double x,
        double y,
        String coordinateBasis,
        String unit,
        String rawRepresentation,
        String status,
        String diagnostic) {
}
