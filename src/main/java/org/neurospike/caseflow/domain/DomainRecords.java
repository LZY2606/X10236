package org.neurospike.caseflow.domain;

import java.util.List;
import java.util.Set;

public final class DomainRecords {
    private DomainRecords() {
    }

    public record RecordingContext(
            String recordingId,
            String timeBasis,
            long sampleRateHz,
            String sampleUnit,
            String amplitudeUnit,
            String coordinateBasis) {
    }

    public record ChannelGeometry(int channel, double x, double y, String unit) {
    }

    public record ProbeGeometry(String probeId, String coordinateBasis, List<ChannelGeometry> channels) {
    }

    public record DetectionThreshold(String thresholdId, int channel, double value, String unit, String comparator) {
    }

    public record WaveformFragment(
            String waveformId,
            String detectionId,
            int channel,
            long startSample,
            String sampleUnit,
            String amplitudeUnit,
            List<Double> samples) {
    }

    public record DetectionEvent(
            String detectionId,
            long sampleIndex,
            int channel,
            double amplitude,
            String amplitudeUnit,
            String waveformId) {
    }

    public record FeatureVersion(
            String featureVersionId,
            String extractor,
            String normalization,
            List<String> components) {
    }

    public record FeaturePoint(
            String featurePointId,
            String detectionId,
            String featureVersionId,
            List<Double> coordinates,
            String coordinateBasis) {
    }

    public record LabelAssignment(
            String labelVersionId,
            String clusterLabel,
            String stableUnitId,
            List<String> detectionIds) {
    }

    public record AdjudicationEvent(
            String adjudicationId,
            String decisionType,
            String actor,
            String rationale,
            List<String> targetStableUnitIds,
            String outcome) {
    }

    public record RuleVersion(
            String ruleVersionId,
            double refractoryMs,
            double coincidenceMs,
            double driftRatioThreshold,
            double endpointEpsilonMs,
            int budget) {
    }

    public record SourceRevision(
            String revisionId,
            String targetType,
            String targetId,
            String replacementId,
            String reason) {
    }

    public record EvidenceRecord(
            String evidenceId,
            String code,
            String severity,
            String message,
            List<String> subjectIds,
            String unit,
            String coordinateBasis) {
    }

    public record Candidate(
            String candidateId,
            String status,
            String rationale,
            List<String> stableUnitIds) {
    }

    public record WindowComputation(
            String computationId,
            String ruleVersionId,
            String featureVersionId,
            String labelVersionId,
            long startSample,
            long endSample,
            String status,
            String inputDigest,
            List<String> parentIds,
            List<EvidenceRecord> evidence,
            List<Candidate> candidates,
            List<Diagnostic> diagnostics,
            Set<String> affectedStableUnitIds,
            boolean adopted) {
        public WindowComputation withAdopted(boolean value) {
            return new WindowComputation(computationId, ruleVersionId, featureVersionId, labelVersionId,
                    startSample, endSample, status, inputDigest, parentIds, evidence, candidates, diagnostics,
                    affectedStableUnitIds, value);
        }
    }

    public record CaseEvent(
            String eventId,
            long seq,
            String eventType,
            String summary,
            String computationId,
            String ruleVersionId,
            String sourceRevisionId,
            String adjudicationId) {
    }

    public record DecisionRecord(
            String decisionId,
            String adjudicationId,
            String candidateId,
            String status,
            String rationale) {
    }
}
