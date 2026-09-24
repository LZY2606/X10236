package eph.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the deterministic offline case pack
 * {@code spike-dossier-offline-case.json}. All timing is fixed (no wall clock calls) so that
 * equivalent inputs always produce the identical pack, ordering and ids.
 *
 * <p>Cast (tetrode probe, 4 sites, 30 kHz sampling, 10.0 s window):
 * <ul>
 *   <li><b>uA</b> stable unit — 12 spikes, large amplitude; spike at 5.000 s exactly on the
 *       window endpoint (endpoint test);</li>
 *   <li><b>uB</b> drift unit — 8 spikes whose amplitude decays across the session (amplitude
 *       drift evidence, never an identity change);</li>
 *   <li><b>uC</b> contaminant pair — 5 spikes; one pair sits inside the 1.0 ms refractory gap
 *       (refractory-violation evidence) and two events propagate across channels simultaneously
 *       (multi-channel propagation evidence);</li>
 *   <li>one event is imported with a broken amplitude literal ({@code "48uV@?"}) — it is kept
 *       raw with a positioned diagnostic (local failure, never dropped to make plots work);</li>
 *   <li>one detection threshold uses an unknown unit — retained raw with a diagnostic.</li>
 * </ul>
 */
public final class CasePackGenerator {

    public static final String PACK_ID = "spike-dossier-v1";

    private CasePackGenerator() {
    }

    public static CasePack build() {
        CasePack.RecordingSpec recording = new CasePack.RecordingSpec(
                "rec_001", "2026-09-20T08:00:00Z", "30000 Hz", "10.0 s");

        List<CasePack.SiteSpec> sites = List.of(
                new CasePack.SiteSpec("site_0", 0, "0 µm", "0 µm", "0 µm"),
                new CasePack.SiteSpec("site_1", 1, "25 µm", "0 µm", "0 µm"),
                new CasePack.SiteSpec("site_2", 2, "0 µm", "25 µm", "0 µm"),
                new CasePack.SiteSpec("site_3", 3, "25 µm", "25 µm", "0 µm"));
        CasePack.ProbeSpec probe = new CasePack.ProbeSpec(
                "probe_tetrode_a", "tetrode-4x25um", "probe-origin-metres", sites);

        List<CasePack.ThresholdSpec> thresholds = List.of(
                new CasePack.ThresholdSpec("det_run_v1", "params-detection-1", 0, "-4.5 σ", "robust MAD std"),
                new CasePack.ThresholdSpec("det_run_v1", "params-detection-1", 1, "-4.5 σ", "robust MAD std"),
                new CasePack.ThresholdSpec("det_run_v1", "params-detection-1", 2, "-4.5 σ", "robust MAD std"),
                new CasePack.ThresholdSpec("det_run_v1", "params-detection-1", 3, "-138 µV??", "manual override, unit garbled"));

        CasePack.WaveformSourceSpec wave = new CasePack.WaveformSourceSpec(
                "wav_ref_001", "bandpass-filtered-recording",
                "offline://casepack/spike-dossier-v1/rec_001.bin",
                "int16-le-30kHz-4ch", "µV", "ms", 32, "30000 Hz");

        List<CasePack.SpikeSpec> spikes = buildSpikes(wave.id());

        Map<String, String> v1Params = Map.of(
                "space", "pca", "components", "2", "windowPre", "0.8 ms",
                "windowPost", "1.2 ms", "normalization", "none");
        Map<String, String> v2Params = Map.of(
                "space", "pca", "components", "2", "windowPre", "0.8 ms",
                "windowPost", "1.2 ms", "normalization", "per-channel-zscore",
                "alignment", "negative-peak");
        CasePack.FeatureVersionSpec fv1 = new CasePack.FeatureVersionSpec(
                "feat_v1", "pca2-raw", "2 PCA components on raw snippet amplitudes", v1Params);
        CasePack.FeatureVersionSpec fv2 = new CasePack.FeatureVersionSpec(
                "feat_v2", "pca2-zscored-peaked",
                "2 PCA components on per-channel z-scored, negative-peak-aligned snippets", v2Params);

        CasePack.LabelBatchSpec batchV1 = new CasePack.LabelBatchSpec(
                "labels_init", "feat_v1", "params-cluster-1", "2026-09-20T08:30:00Z",
                initialAssignments(spikes));
        CasePack.LabelBatchSpec batchV2 = new CasePack.LabelBatchSpec(
                "labels_revised", "feat_v2", "params-cluster-2", "2026-09-20T09:15:00Z",
                revisedAssignments(spikes));
        CasePack.LabelBatchSpec batchPatched = new CasePack.LabelBatchSpec(
                "labels_patched", "feat_v2", "params-cluster-2", "2026-09-20T10:05:00Z",
                patchedAssignments(spikes));

        List<CasePack.StepSpec> steps = List.of(
                buildInitialStep(batchV1.id()),
                buildRevisionStep(batchV2.id()),
                buildPatchStep(batchPatched.id()),
                buildConflictStep());

        List<CasePack.ExpectedEvidenceSpec> evidence = List.of(
                new CasePack.ExpectedEvidenceSpec("CONCURRENT_SPIKES",
                        "two candidate events on distinct channels share the same sample instant",
                        "uC", List.of("spk_c1", "spk_c1p")),
                new CasePack.ExpectedEvidenceSpec("AMPLITUDE_DRIFT",
                        "uB amplitudes decay across the session; evidence of drift, not identity",
                        "uB", List.of("spk_b0", "spk_b7")),
                new CasePack.ExpectedEvidenceSpec("CLUSTER_NUMBER_CHANGE",
                        "uA keeps stable id while cluster number changes 1 -> 7 under feat_v2",
                        "uA", List.of()),
                new CasePack.ExpectedEvidenceSpec("SPLIT_SHARED_EVENT",
                        "the two children of split uC share one detected event",
                        "uC", List.of("spk_c3")),
                new CasePack.ExpectedEvidenceSpec("WINDOW_ENDPOINT",
                        "spike exactly at 5.000 s is included with [start,end] semantics",
                        "uA", List.of("spk_a_endpoint")),
                new CasePack.ExpectedEvidenceSpec("REFRACTORY_VIOLATION",
                        "two spikes of one unit 0.6 ms apart violate the 1.0 ms refractory gap",
                        "uC", List.of("spk_c0", "spk_c_refr")),
                new CasePack.ExpectedEvidenceSpec("LOCAL_FAILURE",
                        "unconvertible amplitude literal retained raw with a positioned diagnostic",
                        "uB", List.of("spk_b_badamp")),
                new CasePack.ExpectedEvidenceSpec("DECISION_CONFLICT",
                        "two adjudicators accept vs reject the same merge candidate; both kept",
                        "uC", List.of()));

        return new CasePack(PACK_ID,
                "Spike-train dossier: neural spike candidates, drift clusters and the human "
                        + "adjudication lineage",
                "casepack-1.0", recording, probe, thresholds, List.of(wave), spikes,
                List.of(fv1, fv2), List.of(batchV1, batchV2, batchPatched), steps, evidence);
    }

    private static List<CasePack.SpikeSpec> buildSpikes(String waveformId) {
        List<CasePack.SpikeSpec> spikes = new ArrayList<>();

        // uA: 12 stable, large spikes; last one exactly on the 5.000 s endpoint.
        double[] aTimes = {0.30, 0.81, 1.42, 1.95, 2.50, 3.05, 3.60, 4.11, 4.62, 4.88, 4.96, 5.00};
        for (int i = 0; i < aTimes.length; i++) {
            String id = i == aTimes.length - 1 ? "spk_a_endpoint" : "spk_a" + i;
            double amp = -150 + (i % 3) * 4;
            spikes.add(new CasePack.SpikeSpec(id, 0, fmt(aTimes[i]) + " s", amp,
                    fmt(amp) + " µV", snippet(150.0, i), waveformId, null,
                    i == aTimes.length - 1 ? "endpoint-exactly-5.000s" : null));
        }

        // uB: 8 spikes with decaying amplitude (drift). One carries a broken amplitude literal.
        double[] bTimes = {0.45, 1.10, 1.75, 2.40, 3.10, 3.80, 4.40, 4.90};
        for (int i = 0; i < bTimes.length; i++) {
            double amp = -120 + i * 12.0; // -120 -> -36 µV
            String id = i == 5 ? "spk_b_badamp" : "spk_b" + i;
            String ampText = i == 5 ? "48uV@?" : fmt(amp) + " µV";
            Double ampRaw = i == 5 ? null : amp;
            spikes.add(new CasePack.SpikeSpec(id, 1, fmt(bTimes[i]) + " s",
                    ampRaw == null ? Double.NaN : ampRaw, ampText, snippet(120.0 - i * 12.0, i + 30),
                    waveformId, null,
                    i == 5 ? "amplitude literal corrupt; must be retained raw" : null));
        }

        // uC: refractory pair (0.6 ms), propagation pair, split-shared event.
        spikes.add(new CasePack.SpikeSpec("spk_c0", 2, "0.620 s", -90, "-90 µV",
                snippet(90, 7), waveformId, null, null));
        spikes.add(new CasePack.SpikeSpec("spk_c_refr", 2, "0.6206 s", -88, "-88 µV",
                snippet(88, 8), waveformId, null,
                "0.6 ms after spk_c0 -> refractory violation evidence"));
        spikes.add(new CasePack.SpikeSpec("spk_c1", 2, "1.300 s", -95, "-95 µV",
                snippet(95, 9), waveformId, 3,
                "propagation master"));
        spikes.add(new CasePack.SpikeSpec("spk_c1p", 3, "1.300 s", -60, "-60 µV",
                snippet(60, 10), waveformId, 2,
                "same instant on channel 3 -> concurrent spike / multi-channel propagation"));
        spikes.add(new CasePack.SpikeSpec("spk_c3", 2, "3.330 s", -92, "-92 µV",
                snippet(92, 11), waveformId, null,
                "shared by both split children uC1/uC2"));

        return List.copyOf(spikes);
    }

    private static List<Double> snippet(double peakMicrovolts, int seed) {
        List<Double> out = new ArrayList<>(32);
        for (int n = 0; n < 32; n++) {
            double t = (n - 8) / 6.0;
            double shape = -Math.exp(-t * t) * peakMicrovolts;
            double noise = (((seed * 31 + n * 17) % 11) - 5) * 0.55;
            out.add(Math.round((shape + noise) * 100.0) / 100.0);
        }
        return out;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    private static List<CasePack.AssignmentSpec> initialAssignments(List<CasePack.SpikeSpec> spikes) {
        List<CasePack.AssignmentSpec> out = new ArrayList<>();
        for (CasePack.SpikeSpec s : spikes) {
            String unit = unitOf(s.id());
            String number = switch (unit) {
                case "uA" -> "1";
                case "uB" -> "2";
                default -> "3";
            };
            out.add(new CasePack.AssignmentSpec(s.id(), number, unit, null));
        }
        return List.copyOf(out);
    }

    private static List<CasePack.AssignmentSpec> revisedAssignments(List<CasePack.SpikeSpec> spikes) {
        // Feature v2 + new clustering parameters permute cluster NUMBERS; stable unit ids persist.
        // uC is split into uC1 and uC2; spk_c3 is shared by both children.
        List<CasePack.AssignmentSpec> out = new ArrayList<>();
        for (CasePack.SpikeSpec s : spikes) {
            String unit = unitOf(s.id());
            switch (unit) {
                case "uA" -> out.add(new CasePack.AssignmentSpec(s.id(), "7", "uA",
                        "number changed 1->7; stable identity retained"));
                case "uB" -> out.add(new CasePack.AssignmentSpec(s.id(), "4", "uB",
                        "number changed 2->4"));
                default -> {
                    String child = ("spk_c0".equals(s.id()) || "spk_c_refr".equals(s.id())
                            || "spk_c1".equals(s.id()) || "spk_c1p".equals(s.id()))
                            ? "uC1" : "uC2";
                    String note = "spk_c3".equals(s.id())
                            ? "split child uC2 owns the event; uC1 shares it (shared_event)"
                            : null;
                    out.add(new CasePack.AssignmentSpec(s.id(),
                            "uC1".equals(child) ? "12" : "13", child, note));
                    if ("spk_c3".equals(s.id())) {
                        out.add(new CasePack.AssignmentSpec("spk_c3", "12", "uC1",
                                "shared membership from split (shared_event)"));
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    private static List<CasePack.AssignmentSpec> patchedAssignments(List<CasePack.SpikeSpec> spikes) {
        // Source patch only touches one direct source (spk_a6 re-detected 30 µV higher);
        // the batch is rebuilt but every unrelated assignment keeps identical (spike,unit,number).
        return revisedAssignments(spikes);
    }

    private static String unitOf(String spikeId) {
        if (spikeId.startsWith("spk_a")) {
            return "uA";
        }
        if (spikeId.startsWith("spk_b")) {
            return "uB";
        }
        return "uC";
    }

    private static CasePack.StepSpec buildInitialStep(String batchId) {
        return new CasePack.StepSpec(0, "INITIAL_IMPORT",
                "Import raw waveform references, probe geometry, detection thresholds, feature "
                        + "versions, cluster labels and adjudication events as separate stores",
                "baseline", List.of(), Map.of("initial", batchId), List.of(),
                null, List.of("uA", "uB", "uC"));
    }

    private static CasePack.StepSpec buildRevisionStep(String batchId) {
        // Default lineage decisions recorded at revision time (still evidence, never identity).
        List<CasePack.DecisionSpec> decisions = List.of(
                new CasePack.DecisionSpec("2026-09-20T09:16:00Z", "SPLIT",
                        "uC", null,
                        "refractory violation + concurrent multi-channel propagation justify a "
                                + "split; both are evidence, cluster numbers are not identities",
                        "REFRACTORY_VIOLATION+CONCURRENT_SPIKES", true, null));
        return new CasePack.StepSpec(1, "PARAMETER_REVISION",
                "Feature version feat_v1 -> feat_v2 with z-scoring and peak alignment; cluster "
                        + "numbers permute and uC splits, while stable unit ids persist",
                "params-cluster-2", List.of(), Map.of("revised", batchId), decisions,
                null, List.of("uA", "uB", "uC", "uC1", "uC2"));
    }

    private static CasePack.StepSpec buildPatchStep(String batchId) {
        // One DIRECT SOURCE changes: a single re-detected spike amplitude. Only intersecting
        // clusters (uA) must recompute; uB/uC derived records keep the same parent version and
        // are not recomputed.
        CasePack.WindowSpec window = new CasePack.WindowSpec("3.55 s", "5.00 s", true, true);
        return new CasePack.StepSpec(2, "SOURCE_PATCH",
                "Re-detection raises spk_a6 amplitude from -150 µV to -120 µV (one direct source "
                        + "changed). Only the intersecting cluster uA recomputes.",
                "params-cluster-2", List.of("spk_a6"), Map.of("patched", batchId),
                List.of(), window, List.of("uA"));
    }

    private static CasePack.StepSpec buildConflictStep() {
        // Two humans disagree about the same candidate. Both adopted and rejected results persist;
        // the active adjudication is explicitly marked, nothing is overwritten.
        CasePack.DecisionSpec d1 = new CasePack.DecisionSpec(
                "2026-09-20T11:00:00Z", "MERGE_ACCEPT", "uC1", null,
                "reviewer A: propagation pair reflects one neuron across tetrode sites; accept merge",
                "CONCURRENT_SPIKES", true, "dec_reviewer_b");
        CasePack.DecisionSpec d2 = new CasePack.DecisionSpec(
                "2026-09-20T11:05:00Z", "MERGE_REJECT", "uC1", null,
                "reviewer B: refractory violation still present after merge; reject",
                "REFRACTORY_VIOLATION", false, "dec_reviewer_a");
        return new CasePack.StepSpec(3, "CONFLICTING_DECISIONS",
                "Two human decisions on the same merge candidate disagree; accepted and rejected "
                        + "results are both retained with conflict diagnostics",
                "params-cluster-2", List.of(), Map.of(), List.of(d1, d2),
                null, List.of("uC1"));
    }

    public static String toJson(CasePack pack) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            return mapper.writeValueAsString(pack);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, String> ordered(Map<String, String> in) {
        return new LinkedHashMap<>(in);
    }
}
