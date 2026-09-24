package neuro.spike.model;

/**
 * Immutable material: a specific version of the feature extractor. Results
 * record this version so a projection can always be tied to the rule version.
 */
public record FeatureVersion(String materialId,
                             String name,
                             String version,
                             int dimensions,
                             String basis) {
    public String ruleId() { return Ids.ruleId(name + "_features", version); }
}
