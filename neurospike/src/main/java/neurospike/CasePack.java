package neurospike;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The offline domain case pack: "spike train provenance - neural spike
 * candidates, drift clusters and human adjudication lineage". The six layers
 * are stored separately. {@code script} is the replay script (raw nodes so the
 * loader can attach pointer diagnostics and so unknown op shapes fail explicitly).
 */
public record CasePack(String caseId, String title, String domain,
                       TimeBasis timeBasis, Probe probe, List<Threshold> thresholds,
                       List<WaveformRef> waveforms, List<Detection> detections,
                       FeatureVersionSpec featureSpec, List<LabelSet> labelSets,
                       List<AdjudicationSeed> adjudicationEvents,
                       JsonNode script) {

    public static final String DOMAIN = "neuro-electrophysiology/spike-cluster-provenance/v1";
}
