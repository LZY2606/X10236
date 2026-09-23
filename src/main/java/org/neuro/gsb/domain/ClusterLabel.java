package org.neuro.gsb.domain;

import java.util.List;

/**
 * A machine-generated clustering assignment under a specific feature version
 * and analysis window. The cluster serial {@link #clusterSerial()} is explicitly
 * NOT a stable identity: reruns renumber. Stable identity is the lineage node
 * produced by adjudication decisions.
 */
public record ClusterLabel(String id, String featureVersionId, int clusterSerial,
                           List<String> eventIds, String windowStart, String windowEnd) {

    public String clusterRef() {
        return featureVersionId + "#" + clusterSerial;
    }

    public double[] windowSeconds() {
        if (windowStart == null || windowEnd == null) return null;
        return new double[]{Double.parseDouble(windowStart), Double.parseDouble(windowEnd)};
    }
}
