package org.neuro.spike.model;

public record FeaturePoint(String uid, String featureVersion, String eventUid, double x, double y, int seq) {
}
