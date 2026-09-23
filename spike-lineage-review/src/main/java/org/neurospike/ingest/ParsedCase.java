package org.neurospike.ingest;

import org.neurospike.domain.Models.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parse result of the offline case pack (materials grouped by file). */
public record ParsedCase(
        Map<String, Object> manifest,
        Map<String, WaveformRef> waveforms,
        Map<String, List<Site>> sitesByRef,
        List<Threshold> thresholds,
        List<SpikeEvent> spikes,
        Map<String, FeatureRow> featuresById,
        List<ClusterFile> clusterFiles,
        List<DecisionRow> decisions,
        Map<String, Object> rules,
        List<Map<String, Object>> scenarios) {

    public record FeatureRow(String featureId, long eventId, String version, String basis,
                             Map<String, Quant> values, String diagnostic) {
    }

    public record ClusterFile(Header header, List<AssignRow> rows) {
    }

    public record Header(String clusterId, String version, int seq, String featureVersion,
                         String thresholdId, String windowStart, String windowEnd,
                         Map<String, Object> params) {
    }

    public record AssignRow(long eventId, String unitId, int label, double score) {
    }

    public record DecisionRow(String decisionId, long seq, String type, String actor, String atEvent,
                              String basisRuleId, String parentClusterId, String adopted,
                              Map<String, Object> payload) {
    }

    public static ParsedCase empty() {
        return new ParsedCase(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(),
                List.of(), List.of(), new LinkedHashMap<>(), List.of(), List.of(),
                new LinkedHashMap<>(), List.of());
    }
}
