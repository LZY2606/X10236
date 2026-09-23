package gsb.spikesort.model;

import java.util.List;
import java.util.Map;

/** Domain model for the pulse-cluster-spectrum review chain.
 *  Immutable materials (waveform refs, probe geometry, detection params,
 *  feature versions, cluster labels) are stored separately from mutable
 *  adjudication events. Cluster indices are never stable identities:
 *  stable identity lives on {@link Unit#id()}. */
public final class Model {
    private Model() {}

    // ---------- diagnostics ----------
    public enum Severity { ERROR, WARNING }

    /** A positioned diagnostic. Incomplete input must surface as one of these,
     *  never as a silently defaulted value. */
    public record Diagnostic(String id, String stage, String location,
                             Severity severity, String code, String message,
                             String rawValue) {}

    // ---------- ingest outcomes: three distinct failure kinds ----------
    public enum IngestStatus { OK, BUDGET_EXHAUSTED, FORMAT_CORRUPT, SEMANTIC_UNSOLVABLE }

    public record IngestResult(IngestStatus status, List<Diagnostic> diagnostics, int imported) {
        public boolean ok() { return status == IngestStatus.OK; }
    }

    // ---------- immutable materials ----------
    public record WaveformRef(String id, int channel, double tStartMs, double tEndMs,
                              double samplingRateHz, String amplitudeUnit, String coordFrame,
                              String sourceUri, String rawRepr, String digest) {}

    public record ProbeGeometry(String id, int version, String frame,
                                List<Site> sites, String digest) {
        public record Site(int channel, double xUm, double yUm) {}
    }

    public record DetectionParams(String id, int version, double thresholdUv,
                                  double refractoryMs, double windowStartMs,
                                  double windowEndMs, String digest) {}

    public record FeatureSet(String id, int version, String method,
                             String inputDigest, String digest) {}

    // ---------- stable identity vs mutable label ----------
    public record Unit(String id, String createdByEvent, String status) {}

    /** clusterIndex is a per-feature-set label only; it is NOT an identity. */
    public record ClusterLabel(String id, String featureSetId, int clusterIndex,
                               String unitId, String assignedEventId) {}

    // ---------- spikes ----------
    public record SpikeEvent(String id, double timeMs, int channel,
                             String amplitudeRaw, Double amplitudeUv,
                             String unitId, String waveformRefId,
                             List<String> candidateUnitIds) {}

    // ---------- adjudication (mutable decisions, append-only) ----------
    public record AdjudicationEvent(String id, long seq, String type, String branch,
                                    String payloadJson, String parentId,
                                    String status, String createdAt) {}

    // ---------- machine-readable evidence ----------
    public record EvidenceEvent(String id, String kind, String unitId, Integer channel,
                                Double timeMs, String basisJson, String adjudicationEventId) {}

    // ---------- derived artifacts with provenance ----------
    public record Artifact(String id, String kind, String unitId, String inputDigest,
                           int paramVersion, List<String> parents,
                           long validFromSeq, Long validToSeq,
                           String status, String invalidationReason,
                           String payloadJson) {}

    // ---------- export manifest ----------
    public record ExportManifest(String format, int formatVersion, String exportedAt,
                                 Map<String, Integer> tableCounts, String digest) {}
}
