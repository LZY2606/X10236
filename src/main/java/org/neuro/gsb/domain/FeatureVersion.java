package org.neuro.gsb.domain;

import java.util.List;

/** Immutable versioned description of a feature-extraction rule set. */
public record FeatureVersion(String id, String label, String windowStart, String windowEnd,
                             double distanceThreshold, int refractoryUs, int propagationUs,
                             List<String> axes, String notes) {

    /** Half-open analysis window [start, end) in record-time seconds; null means whole record. */
    public double[] windowSeconds() {
        if (windowStart == null || windowEnd == null) return null;
        return new double[]{Double.parseDouble(windowStart), Double.parseDouble(windowEnd)};
    }
}
