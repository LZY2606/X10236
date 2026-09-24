package neurospike;

import java.util.List;

/**
 * Immutable feature-extraction version: algorithm + components + rule parameters.
 * Changing this is a parameter revision, not a raw-material change.
 */
public record FeatureVersionSpec(String featureVersion, String algorithm,
                                 List<String> components, int waveformSamples,
                                 Quant refractoryWindow, Quant concurrenceWindow,
                                 Quant propagationWindow, Quant driftWindow,
                                 Quant driftAmplitudeRatio, Quant rateBinSeconds,
                                 Quant autocorrHalfWindow) {
}
