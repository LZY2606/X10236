package gsb.domain;

import java.util.List;

/**
 * Immutable domain model and read DTOs.
 *
 * <p>The domain deliberately keeps the six source kinds separate: waveform references, probe
 * geometry, detection thresholds, feature versions, cluster labels and adjudication events never
 * share a table and never overwrite each other.
 */
public final class Model {
    private Model() {}

    /** Event types recorded on the append only ledger. */
    public enum EventType {
        CASE_BASELINE,
        PARAM_REVISION,
        SOURCE_AMENDMENT,
        WINDOW_ADJUST,
        ADJUDICATION,
        CONFLICTED_DECISION,
        LOCAL_FAILURE
    }

    /** Outcome of a decision or proposal; rejected/unaccepted results are retained. */
    public enum Verdict {
        PENDING,
        ADOPTED,
        REJECTED,
        CONFLICTED
    }

    /** Kinds of evidence. All are evidence only, never a stable unit identity. */
    public enum EvidenceKind {
        REFRACTORY_VIOLATION,
        AMPLITUDE_DRIFT,
        CONCURRENT_PROPAGATION
    }

    public record Site(String id, String label, double xUm, double yUm, int version,
                       String supersedes, boolean active) {}

    public record WaveRef(String id, String uri, String format, String channelLabel,
                          long sampleIndexFrom, long sampleIndexTo, double sampleRateHz) {}

    public record Threshold(String id, String channelLabel, String rawExpression,
                            String units, double value, int version, boolean active) {}

    public record FeatureVersion(String id, String name, int version, String paramsJson,
                                 String activeFromEventId, boolean active) {}

    public record Detection(String id, String channelLabel, long sampleIndex,
                            String rawTimeMs, double timeMs, String rawAmplitudeUv,
                            Double amplitudeUv, double wavePeakUv, String waveRefId,
                            List<Double> snippetUv, String featureVersionId, int featureVersion,
                            String status, List<String> diagnosticIds) {}

    public record Diagnostic(String id, String location, String code, String message,
                             String rawValue, String severity) {}

    public record ClusterLabel(String id, int featureVersion, String clusterNo,
                               String derivedFromProposalId, boolean active) {}

    public record ClusterMember(String detectionId, String clusterId) {}

    /** Append only ledger entry. */
    public record LedgerEvent(String id, long seq, EventType type, String parentEventId,
                              String ruleVersion, String actor, String summary,
                              String payloadJson, String inputDigest, long createdAtMs,
                              String status, List<String> affectedArtifactIds) {}

    public record RefPair(String a, String b, double intervalMs) {}
    public record PropPair(String a, String b, double delayMs, double distanceUm) {}
    public record Drift(String clusterId, double slopeUvPerS, double firstUv,
                        double lastUv, int members) {}

    public record Evidence(String id, EvidenceKind kind, String detectionId,
                           String clusterId, String detail, double numeric,
                           String units) {}

    public record Proposal(String id, String kind, String clusterId,
                           List<String> candidateClusterIds, String evidenceSummary,
                           Verdict verdict, String decisionEventId, String note) {}

    public record UnitUnit(String id, String bornFromEventId, String label,
                           List<String> detectionIds, List<String> lineageProposalIds) {}

    /** A value shown in the UI always carries its stable identity and provenance. */
    public record ValueView(String id, String label, String raw, String normalized,
                            String unitsOrBasis, String validRange, String invalidReason,
                            String sourceArtifactId) {}

    public record WaveformView(String detectionId, String waveRefId, List<Double> samplesUv,
                               double sampleRateHz, String peakId, String channelLabel,
                               String timeBasis, String validRange, String invalidReason) {}

    public record FeaturePoint(String detectionId, double x, double y, String clusterId,
                               String unitId, boolean invalid) {}

    public record FeatureView(String featureVersionId, int version, List<FeaturePoint> points,
                              String basis, String invalidReason) {}

    public record AutoCorrBin(double lagMs, int count) {}
    public record AutoCorrView(String clusterId, String unitId, double binMs,
                               List<AutoCorrBin> bins, String refractoryNote) {}

    public record RateBin(double fromMs, double toMs, int count, double rateHz) {}
    public record RateView(String clusterId, String unitId, List<RateBin> bins,
                           String windowFromMs, String windowToMs, String endpointPolicy) {}

    public record DriftPoint(String detectionId, double timeMs, double amplitudeUv,
                             boolean interpolated) {}
    public record DriftView(String clusterId, String unitId, List<DriftPoint> points,
                            Double slopeUvPerS, String units, String invalidReason) {}

    /** Full computed state at one ledger event; historical frames are never overwritten. */
    public record Frame(String eventId, long seq, String ruleVersion,
                        String activeFeatureVersionId, String windowFromMs, String windowToMs,
                        String windowEndpointPolicy,
                        List<ValueView> values,
                        List<WaveformView> waveforms,
                        List<FeatureView> features,
                        List<AutoCorrView> autocorrs,
                        List<RateView> rates,
                        List<DriftView> drifts,
                        List<Evidence> evidence,
                        List<Proposal> proposals,
                        List<UnitUnit> units,
                        List<Diagnostic> diagnostics,
                        List<String> recomputedArtifactIds,
                        String inputDigest) {}
}
