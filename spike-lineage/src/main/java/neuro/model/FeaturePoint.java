package neuro.model;

import java.util.List;

/**
 * A feature projection point for one spike under a specific feature version. Coordinates are kept in
 * their projected basis; downstream rendering never silently rescales them.
 */
public record FeaturePoint(
        String spikeId,
        String featureVersion,
        List<Double> coords,
        String basis) {
}
