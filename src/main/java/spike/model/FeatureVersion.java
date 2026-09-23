package spike.model;

import java.util.List;

/** Immutable definition of a feature extractor. Dimensions are part of the contract. */
public record FeatureVersion(String id, String name, String version, int dimensions,
                            List<String> componentNames, String amplitudeUnit, String timeBasis) {
}
