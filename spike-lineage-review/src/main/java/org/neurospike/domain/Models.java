package org.neurospike.domain;

import java.util.List;
import java.util.Map;

public final class Models {
    private Models() {
    }

    /** A physical or logical quantity carrying its original representation and reference frame. */
    public record Quant(String raw, Double value, String unit, String reference, boolean convertible,
                        String diagnostic) {
    }

    /** Immutable layer: original waveform reference (never copied/normalized here). */
    public record WaveformRef(String refId, String uri, String checksum, String format,
                              String timeOrigin, String timeUnit, double sampleRateHz,
                              int sampleCount, long sampleIndex, int sampleLength,
                              List<Quant> channelGains) {
    }

    /** Immutable layer: probe geometry in a named coordinate frame. */
    public record Site(int channel, Quant x, Quant y, Quant z, String frame) {
    }

    /** Immutable layer: detection threshold per channel, stored verbatim. */
    public record Threshold(String thresholdId, int channel, Quant value, String rule,
                            String featureVersion) {
    }

    /** Immutable layer: detected spike event with time + waveform reference window. */
    public record SpikeEvent(long eventId, Quant time, int primaryChannel, String waveformRefId,
                             long sampleIndex, int sampleLength, Map<String, Quant> measurements,
                             List<Integer> channels, boolean convertibleTime) {
    }

    /** Immutable layer: extracted feature with named feature version. */
    public record FeatureSet(String featureId, String version, String basis, Map<String, Quant> values,
                             String diagnostic) {
    }

    /** Mutable/derived layer: a clustering run (parameters + parent dependencies). */
    public record ClusterVersion(String clusterId, String version, int seq,
                                 String featureVersion, String thresholdId,
                                 String timeWindowStart, String timeWindowEnd,
                                 Map<String, Object> params, String inputDigest,
                                 List<String> parents, String createdAt) {
    }

    /** Assignment of an immutable spike event to a stable unit under a clustering version. */
    public record Assignment(long eventId, String unitId, String clusterId, int label, double score) {
    }

    /** Stable unit identity. The numeric cluster label is NOT the identity. */
    public record Unit(String unitId, String firstSeenCluster, int firstLabel, String status,
                       String note) {
    }

    /** Append-only human decision (accept / reject / merge / split / parameter change). */
    public record DecisionEvent(String decisionId, long seq, String type, String actor,
                                String atEvent, Map<String, Object> payload,
                                String basisRuleId, String parentClusterId,
                                String adopted, String createdAt) {
    }

    /** Any derived computation output: input digest, parameter version, parent dependencies. */
    public record DerivedResult(String resultId, String kind, String scope, String clusterId,
                                String unitId, String windowStart, String windowEnd,
                                String inputDigest, String paramVersion, List<String> parents,
                                Map<String, Object> content, String createdAt) {
    }

    /** Directed provenance edge between stable identities. */
    public record LineageEdge(String edgeId, String fromId, String toId, String relation,
                              long sharedEvents, String viaDecisionId) {
    }

    /** One row of the counter-example archive (machine-readable judgment event). */
    public record ArchiveCheck(String checkId, String scenario, String stableIdentity,
                               String claim, String verdict, String basis,
                               Map<String, Object> evidence, String adoptedResultId,
                               String rejectedResultId, String createdAt) {
    }

    /** One replayed scenario step with inputs, decision state and affected objects. */
    public record ReplayStep(int seq, String stage, String scenario,
                             Map<String, Object> inputFragment, String decisionState,
                             List<String> affectedObjectIds, String resultId) {
    }

    /** Outcome categories required by the task: success and three distinct failures. */
    public enum Outcome {
        OK,
        CORRUPT_FORMAT,
        SEMANTIC_UNRESOLVABLE,
        BUDGET_EXHAUSTED
    }

    public record StageResult(Outcome outcome, String stage, List<Diagnostic> diagnostics,
                              int processed, int retainedUnconvertible) {
        public boolean ok() {
            return outcome == Outcome.OK;
        }
    }
}
