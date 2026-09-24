package neuro.spike.model;

import java.util.List;

/** Feature coordinates for one spike under one FeatureVersion. */
public record FeatureVector(String eventId, String featureVersionId, List<Double> coords) {
    public FeatureVector {
        coords = coords == null ? List.of() : List.copyOf(coords);
    }
}
