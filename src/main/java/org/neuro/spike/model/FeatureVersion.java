package org.neuro.spike.model;

/** Named version of the feature extraction rules (peak/PCA projection etc.). */
public record FeatureVersion(String uid, String name, int revision, String specJson, int seq) {
    public String versionId() {
        return name + "@v" + revision;
    }
}
