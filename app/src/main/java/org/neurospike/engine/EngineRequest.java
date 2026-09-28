package org.neurospike.engine;

import org.neurospike.domain.AnalysisWindow;
import org.neurospike.domain.FeatureObservation;
import org.neurospike.domain.Material;
import org.neurospike.domain.SpikeDetection;

import java.util.List;

public record EngineRequest(
        String runId,
        String ruleVersion,
        String clusterVersion,
        String featureVersion,
        AnalysisWindow window,
        List<SpikeDetection> spikes,
        List<FeatureObservation> features,
        List<Material> clusterMaterials,
        long startSequence) {
    public EngineRequest {
        spikes = List.copyOf(spikes);
        features = List.copyOf(features);
        clusterMaterials = List.copyOf(clusterMaterials);
    }
}
