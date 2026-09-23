package org.neuro.gsb.domain;

import java.util.List;

/** Feature-space point for one detection event under one feature version. */
public record FeatureVector(String eventId, String featureVersionId, List<Double> coords) {
}
