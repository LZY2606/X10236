package neuro.spike.model;

import java.util.List;
import java.util.Map;

/**
 * Immutable feature-extraction rule version. {@code params} is the exact parameter set
 * imported; the derived layer computes projections from {@link DetectionEvent} waveforms
 * using these parameters.
 */
public record FeatureVersion(String id, String version, String method,
                             Map<String, String> params,
                             List<String> appliedThresholdIds) {
}
