package eph.archive;

import java.util.List;
import java.util.Map;

/**
 * Offline domain case pack: "Spike-train dossier — neural spike candidates, drift clusters and
 * the human adjudication lineage". It is electrophysiology material (probe geometry, detection
 * events, waveform snippets, feature versions, cluster labels, decisions), never a generic
 * spreadsheet.
 *
 * <p>The {@code steps} replay three history-changing operations:
 * <ol>
 *   <li>PARAMETER_REVISION — feature version v1 -> v2 (cluster numbers permute, stable unit ids
 *       persist),</li>
 *   <li>SOURCE_PATCH — one direct source changed; only intersecting clusters recompute,</li>
 *   <li>CONFLICTING_DECISIONS — two human decisions disagree; both adopted and rejected results
 *       are kept.</li>
 * </ol>
 */
public record CasePack(String packId, String title, String schemaVersion,
                       RecordingSpec recording, ProbeSpec probe,
                       List<ThresholdSpec> thresholds,
                       List<WaveformSourceSpec> waveformSources,
                       List<SpikeSpec> spikes,
                       List<FeatureVersionSpec> featureVersions,
                       List<LabelBatchSpec> labelBatches,
                       List<StepSpec> steps,
                       List<ExpectedEvidenceSpec> expectedEvidence) {

    public record RecordingSpec(String id, String startWallClockUtc,
                                String sampleRate, String duration) {
    }

    public record ProbeSpec(String id, String type, String coordinateFrame,
                            List<SiteSpec> sites) {
    }

    public record SiteSpec(String id, int channel, String x, String y, String z) {
    }

    public record ThresholdSpec(String detectionRunId, String parameterVersion, int channel,
                                String value, String basis) {
    }

    public record WaveformSourceSpec(String id, String kind, String uri, String encoding,
                                     String amplitudeUnit, String timeUnit,
                                     int samplesPerSnippet, String sampleRate) {
    }

    public record SpikeSpec(String id, int channel, String time, double amplitudeRaw,
                            String amplitude, List<Double> snippet,
                            String sourceWaveformId, Integer pairedChannel,
                            String note) {
    }

    public record FeatureVersionSpec(String id, String name, String description,
                                     Map<String, String> parameters) {
    }

    public record LabelBatchSpec(String id, String featureVersionId, String parameterVersion,
                                 String generatedAt, List<AssignmentSpec> assignments) {
    }

    public record AssignmentSpec(String spikeId, String clusterNumber, String unitId,
                                 String note) {
    }

    public record StepSpec(int seq, String type, String description,
                           String parameterVersion,
                           List<String> changedSpikeIds,
                           Map<String, String> labelBatchById,
                           List<DecisionSpec> decisions,
                           WindowSpec window,
                           List<String> expectedAffectedUnitIds) {
    }

    public record DecisionSpec(String atTime, String action, String unitId, String spikeId,
                               String rationale, String evidenceKind, boolean adopted,
                               String conflictsWith) {
    }

    public record WindowSpec(String start, String end, boolean closedStart, boolean closedEnd) {
    }

    public record ExpectedEvidenceSpec(String code, String description, String unitId,
                                       List<String> spikeIds) {
    }
}
