package org.neuro.spike.engine;

import com.fasterxml.jackson.databind.JsonNode;
import org.neuro.spike.model.Cluster;
import org.neuro.spike.model.Diagnostic;
import org.neuro.spike.model.FeaturePoint;
import org.neuro.spike.model.FeatureVersion;
import org.neuro.spike.model.Material;
import org.neuro.spike.model.MaterialType;
import org.neuro.spike.store.SpikeStore;
import org.neuro.spike.util.Json;
import org.neuro.spike.util.Quantities;
import org.neuro.spike.model.Quantity;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the store at a sequence point and builds the domain view. Going back
 * to an old event simply uses its sequence: no row is ever overwritten by a
 * newer computation, so historical conclusions are reproducible.
 */
public final class ViewBuilder {
    private ViewBuilder() {
    }

    public record Snapshot(
            List<Material> materials,
            List<FeatureVersion> featureVersions,
            List<FeaturePoint> featurePoints,
            List<Cluster> clusters,
            List<DetEvent> events,
            DomainData.Geometry geometry,
            double durationSec,
            double thresholdUv,
            String thresholdParamVersion,
            double windowStart,
            double windowEnd,
            List<String> activeWindowBatches) {
    }

    public static Snapshot build(SpikeStore store, int atSeq) {
        List<Material> materials = store.materialsAt(atSeq);
        List<DetEvent> events = new ArrayList<>();
        DomainData.Geometry geometry = new DomainData.Geometry("probe-origin-um", List.of());
        double durationSec = 0;
        double thresholdUv = -1;
        String thresholdVersion = null;
        double windowStart = 0;
        double windowEnd = Double.POSITIVE_INFINITY;
        List<String> windowBatches = new ArrayList<>();

        List<DomainData.Channel> channels = new ArrayList<>();
        for (Material m : materials) {
            JsonNode node = Json.read(m.payloadJson());
            switch (m.type()) {
                case RECORDING_BASE -> durationSec = node.path("duration_s").asDouble();
                case PROBE_GEOMETRY -> {
                    for (JsonNode ch : node.path("channels")) {
                        String yRaw = ch.path("y").asText();
                        channels.add(new DomainData.Channel(
                                ch.path("channel").asInt(),
                                Quantities.coordinate(yRaw).si().orElse(Double.NaN) * 1e6,
                                ch.path("x").asText("0 um"), yRaw));
                    }
                }
                case DETECTION_EVENT -> events.add(toEvent(m, node));
                case DETECTION_THRESHOLD -> {
                    thresholdUv = node.path("threshold_uV").asDouble(-1);
                    thresholdVersion = node.path("param_version").asText(null);
                }
                case TIME_WINDOW -> {
                    windowStart = node.path("start_s").asDouble(0);
                    windowEnd = node.path("end_s").asDouble(Double.POSITIVE_INFINITY);
                    windowBatches.clear();
                    for (JsonNode b : node.path("recompute_batches")) {
                        windowBatches.add(b.asText());
                    }
                }
                default -> {
                }
            }
        }
        geometry = new DomainData.Geometry("probe-origin-um", channels);

        List<FeatureVersion> versions = store.featureVersionsAt(atSeq);
        String fv = versions.isEmpty() ? null : versions.get(versions.size() - 1).uid();
        List<FeaturePoint> points = fv == null ? List.of() : store.featurePointsAt(fv, atSeq);

        List<Cluster> clusters = store.clustersAt(atSeq);
        return new Snapshot(materials, versions, points, clusters, events, geometry,
                durationSec, thresholdUv, thresholdVersion, windowStart, windowEnd, windowBatches);
    }

    private static DetEvent toEvent(Material m, JsonNode n) {
        Quantity amplitude;
        if (n.hasNonNull("amplitude_raw")) {
            amplitude = Quantities.amplitude(n.get("amplitude_raw").asText());
        } else {
            amplitude = Quantity.unconvertible("", "amplitude missing");
        }
        boolean corrupt = n.path("corrupt").asBoolean(false);
        boolean excluded = n.path("excluded").asBoolean(false);
        List<Diagnostic> diags = m.diagnostics();
        String reason = null;
        if (corrupt && diags != null && !diags.isEmpty()) {
            reason = diags.get(0).message();
        }
        return new DetEvent(
                m.uid(),
                n.path("time_s").asDouble(),
                n.path("time_raw").asText(),
                n.path("channel").asInt(),
                amplitude,
                n.path("waveform_ref").asText(),
                corrupt,
                corrupt ? (reason != null ? reason : "corrupt waveform segment") : null,
                excluded,
                excluded ? "inside corrupt recording segment" : null);
    }
}
