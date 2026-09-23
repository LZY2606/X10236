package org.neuro.spike.model;

import java.util.List;

/**
 * A clustering snapshot label. The {@code label} is a display numbering
 * (1,2,3...) that is allowed to change; {@code uid} is the stable identity
 * referenced everywhere downstream.
 */
public record Cluster(
        String uid,
        int label,
        String paramVersion,
        String featureVersion,
        double tStart,
        double tEnd,
        List<String> members,
        int seq) {
}
