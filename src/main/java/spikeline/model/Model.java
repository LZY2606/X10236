package spikeline.model;

import java.util.List;
import java.util.Map;

/**
 * Domain payload types for the spike-line review workspace.
 *
 * Layering rule enforced across the codebase:
 *  - materials (probe geometry / waveform refs / detections / thresholds /
 *    feature versions) are IMMUTABLE source records;
 *  - cluster labels belong to a parameterised clustering run and are never
 *    treated as stable identity;
 *  - stable unit identities are assigned separately and deterministically;
 *  - decisions are the only human-modifiable layer;
 *  - every derived row (features / assignments / evidence) records its input
 *    digest, rule version and parent dependencies.
 *
 * Every domain quantity that has a physical meaning travels as a
 * {@link PhysValue}: the original textual representation is always retained,
 * normalisation happens only in the derived layer, and values that cannot be
 * converted stay in place with a diagnostic instead of being dropped.
 */
public final class Model {

    private Model() {
    }

    /** A physical quantity with its original representation preserved on import. */
    public record PhysValue(String raw, Double value, String unit, String basis, String diagnostic) {
        public static PhysValue of(String raw, Double value, String unit, String basis) {
            return new PhysValue(raw, value, unit, basis, null);
        }

        public static PhysValue unconvertible(String raw, String basis, String diagnostic) {
            return new PhysValue(raw, null, null, basis, diagnostic);
        }

        public boolean valid() {
            return value != null && diagnostic == null;
        }
    }

    /** One site on the probe, with coordinates in micrometres relative to the probe origin. */
    public record ProbeSite(String siteId, String channel,
                            PhysValue xUm, PhysValue yUm, PhysValue zUm) {
    }

    /** Recording-level time base. All times are interpreted relative to this basis. */
    public record Recording(String recordingId, String name, String timeBasis,
                            PhysValue startRef, PhysValue sampleRateHz) {
    }

    /**
     * Immutable reference to a waveform snippet. Samples are stored in the imported
     * representation (microvolts); peak time/rate keep raw text for unit audits.
     */
    public record WaveformRef(String refId, String channel, String uri,
                              long peakSampleIndex, PhysValue peakTimeMs,
                              int preSamples, int postSamples,
                              PhysValue sampleRateHz,
                              double[] samplesUv, String rawJson) {
    }

    /** Immutable multi-channel detection event. */
    public record DetectionEvent(String eventId, String channel, String refId,
                                 long sampleIndex, PhysValue timeMs,
                                 PhysValue amplitudeUv, String rawJson) {
    }

    /** Detection threshold and refractory window per channel (rule material). */
    public record ThresholdRule(String thresholdId, String channel,
                                PhysValue thresholdUv, String method,
                                PhysValue refractoryMs, String rawJson) {
    }

    /** Versioned feature-extraction rule set. Params are canonicalised into the digest. */
    public record FeatureVersion(String featureVersion, String algorithm,
                                 Map<String, Object> params, String basis) {
    }

    /**
     * A clustering run. A run binds a feature version, a time window (with an
     * explicit endpoint convention), a rule version and the source detection set
     * it is based on. Runs are append-only; retuning creates a new run.
     */
    public record Run(String runId, String parentRunId, String branch, String stage,
                      int seq, String featureVersion,
                      PhysValue windowStartMs, PhysValue windowEndMs,
                      boolean endpointInclusive, String ruleVersion,
                      String sourceDigest, String basis) {
    }

    /** Cluster label inside one run. Cluster numbers are NOT stable across runs. */
    public record ClusterLabel(String runId, String eventId, int clusterNumber,
                               String rawLabel, String rawJson) {
    }

    /** Stable unit identity, first observed on a run/cluster pair, with a stable order. */
    public record UnitIdentity(String unitId, String firstRunId, int firstClusterNumber,
                               int stableOrder) {
    }

    /** Assignment of an event to a stable unit, derived from a run's labels. */
    public record Assignment(String assignmentId, String runId, String branch,
                             String eventId, String unitId, int clusterNumber,
                             boolean sharedAfterSplit, String inputDigest,
                             String parentsJson, String ruleVersion, int asOfSeq,
                             String txId) {
    }

    /** One derived feature vector for one event. */
    public record FeatureRow(String featureId, String runId, String eventId,
                             String featureVersion, double peakUv, double halfWidthMs,
                             double pc1, String rawDigest, String inputDigest,
                             String parentsJson, String ruleVersion, int asOfSeq,
                             String txId) {
    }

    public static final String KIND_REFRACTORY = "REFRACTORY_VIOLATION";
    public static final String KIND_AMPLITUDE_DRIFT = "AMPLITUDE_DRIFT";
    public static final String KIND_PROPAGATION = "CONCURRENT_PROPAGATION";
    public static final String KIND_SPLIT_SHARED = "SPLIT_SHARED_EVENT";
    public static final String KIND_LOCAL_FAILURE = "LOCAL_FAILURE";

    /** Evidence is advisory only: it never renames units by itself. */
    public record Evidence(String evidenceId, String runId, String branch, String unitId,
                           String kind, String severity, String detailJson,
                           PhysValue windowStartMs, PhysValue windowEndMs,
                           String inputDigest, String parentsJson, String ruleVersion,
                           int asOfSeq, String txId) {
    }

    public enum DecisionAction {
        ACCEPT_UNIT, REJECT_UNIT, RETUNE_PARAMS, SPLIT_CLUSTER, MERGE_UNITS, MARK_CONFLICT
    }

    public enum DecisionStatus {
        PROPOSED, ACCEPTED, REJECTED, CONFLICT, SUPERSEDED
    }

    /** Human adjudication. The only mutable layer; edits append new rows. */
    public record Decision(String decisionId, int seq, String branch, String stage,
                           String unitId, String runId, Integer clusterNumber,
                           String action, String status, String rationale,
                           String conflictsWith, String parentEventId,
                           Map<String, Object> payload, String inputDigest,
                           String parentsJson, String ruleVersion, String txId) {
    }

    /** Append-only lineage event: every input/rule/decision transition is recorded. */
    public record LogEvent(long seq, String branch, String stage, String type,
                           String payloadJson, String inputDigest,
                           String parentEventIds, String ruleVersion,
                           String txId) {
    }

    public record BranchInfo(String name, String parentBranch, long forkedFromSeq) {
    }

    /** Positioned diagnostic produced while importing materials. */
    public record Diagnostic(String path, String code, String message, String raw) {
    }

    /** A value shown in the UI, always carrying its stable identity and provenance. */
    public record DisplayedValue(String id, String label, String value, String unit,
                                 String basis, String validRange, String invalidReason,
                                 List<String> provenance) {
    }
}
