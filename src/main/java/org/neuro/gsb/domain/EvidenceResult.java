package org.neuro.gsb.domain;

import java.util.List;

/**
 * Immutable derived evidence record. Captures the input summary, the parameter
 * version (rule hash) and the parent dependencies so every number on screen can
 * be traced back. Evidence kinds:
 * REFRACTORY_VIOLATION, AMPLITUDE_DRIFT, MULTICHANNEL_PROPAGATION,
 * AUTOCORRELATION, FIRING_RATE, PROJECTION, CONCURRENT_SPIKE.
 * Evidence is never itself an identity decision.
 */
public record EvidenceResult(String id, String kind, String subjectClusterLabelId,
                             String featureVersionId, String windowStart, String windowEnd,
                             String inputSummary, String ruleVersion, List<String> parentDependencyIds,
                             String status, String invalidReason, List<double[]> series) {

    public double numericOrZero() {
        return 0;
    }
}
