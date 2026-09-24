package org.neurospike.web;

import com.fasterxml.jackson.databind.JsonNode;
import org.neurospike.model.Material;
import org.neurospike.store.ProjectStore;
import org.neurospike.store.StoreLabels;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 面向审阅页的只读状态聚合；每个显示值带稳定身份、单位、有效区间与失效原因。 */
public final class AppState {
    private final ProjectStore store;

    public AppState(ProjectStore store) { this.store = store; }

    public Map<String, Object> full() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            String probeId = store.decision().meta("probe_id");
            out.put("basis", Map.of(
                    "coordinateUnit", "um", "coordinateOrigin", "probe-tip",
                    "timeBasisT0", store.decision().meta("time_basis"),
                    "amplitudeUnit", "uV",
                    "windowSeconds", List.of(Double.parseDouble(store.decision().meta("window_start")),
                            Double.parseDouble(store.decision().meta("window_end"))),
                    "windowIsHalfOpen", "[start,end)"));
            out.put("params", Map.of(
                    "activeLabelSet", store.decision().meta("active_label_set"),
                    "activeThresholdVersion", Integer.parseInt(
                            store.decision().meta("active_threshold_version")),
                    "concurrencyTolSeconds", Double.parseDouble(
                            store.decision().meta("concurrency_tol_seconds")),
                    "refractorySeconds", Double.parseDouble(
                            store.decision().meta("refractory_seconds")),
                    "sourcePatch", nz(store.decision().meta("source_patch"))));

            List<Map<String, Object>> channels = new ArrayList<>();
            for (var ch : store.material().channels(probeId)) {
                channels.add(mapOf("stableId", ch.stableId(), "label", ch.label(),
                        "x", ch.x(), "y", ch.y(), "coordUnit", ch.coordUnit()));
            }
            out.put("channels", channels);

            List<Map<String, Object>> thresholds = new ArrayList<>();
            for (int v = 1; v <= 2; v++) {
                for (var t : store.material().thresholds(v)) {
                    thresholds.add(mapOf("thresholdId", t.thresholdId(), "version", t.version(),
                            "channel", t.channelLocalIndex(), "operator", t.operator(),
                            "raw", t.value().raw() + " " + t.value().rawUnit(),
                            "siValue", nz(t.value().siValue()), "siUnit", nz(t.value().siUnit()),
                            "convertible", t.value().convertible(), "basis", t.basis()));
                }
            }
            out.put("thresholds", thresholds);

            List<Map<String, Object>> featureSets = new ArrayList<>();
            for (var ls : store.labels().labelSets()) { /* drain */ }
            featureSets.add(featureJson("fs-v1"));
            featureSets.add(featureJson("fs-v2"));
            out.put("featureSets", featureSets);

            List<Map<String, Object>> events = new ArrayList<>();
            for (Material.SpikeEvent e : store.material().events()) {
                events.add(mapOf(
                        "eventId", e.eventId(), "timeSeconds", e.timeSeconds(),
                        "channel", e.channelLocalIndex(), "waveformId", e.waveformId(),
                        "amplitudeRaw", e.amplitude().raw() + " " + e.amplitude().rawUnit(),
                        "amplitudeSiUv", nz(e.amplitude().siValue()),
                        "featureSet", e.featureSetId(),
                        "features", e.features(), "valid", e.valid(),
                        "invalidReason", nz(e.invalidReason()),
                        "supersedesOrder", nz(e.supersedesOrder())));
            }
            out.put("events", events);

            String activeLabels = store.decision().meta("active_label_set");
            List<Map<String, Object>> labelSets = new ArrayList<>();
            for (var ls : store.labels().labelSets()) {
                labelSets.add(mapOf("labelSetId", ls.labelSetId(), "version", ls.version(),
                        "parent", nz(ls.parentLabelSetId()), "note", ls.note(),
                        "active", ls.labelSetId().equals(activeLabels)));
            }
            out.put("labelSets", labelSets);

            List<Map<String, Object>> clusters = new ArrayList<>();
            for (StoreLabels.Cluster c : store.labels().clusters(activeLabels).values()) {
                clusters.add(mapOf("clusterKey", c.clusterKey(),
                        "displayNumber", c.displayNumber(), "eventIds", c.eventIds(),
                        "candidates", candidates(activeLabels, c.clusterKey())));
            }
            out.put("clusters", clusters);

            List<Map<String, Object>> nodes = new ArrayList<>();
            for (var n : store.derived().allNodes()) {
                JsonNode payload = store.json().readTree(n.payloadJson());
                nodes.add(mapOf("nodeId", n.nodeId(), "kind", n.kind().name(),
                        "status", n.status(), "paramVersion", n.paramVersion(),
                        "parents", n.parentIds(), "payload", payload));
            }
            out.put("derivedNodes", nodes);
            out.put("lineage", store.derived().lineageEdges());

            List<Map<String, Object>> history = new ArrayList<>();
            for (Material.Adjudication a : store.decision().history("main")) {
                history.add(mapOf("seq", a.seq(), "adjudicationId", a.adjudicationId(),
                        "kind", a.kind(), "nodeId", nz(a.nodeId()),
                        "payload", store.json().readTree(a.payload()), "at", a.createdAt()));
            }
            out.put("history", history);
            return out;
        } catch (Exception e) {
            throw new RuntimeException("state build failed", e);
        }
    }

    private List<Map<String, Object>> candidates(String labelSetId, String clusterKey) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (var ps = store.raw().prepareStatement(
                "SELECT event_id,reason FROM cluster_candidate WHERE label_set_id=? AND cluster_key=?")) {
            ps.setString(1, labelSetId); ps.setString(2, clusterKey);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapOf("eventId", rs.getString(1), "reason", rs.getString(2)));
            }
        } catch (Exception ignored) { }
        return out;
    }

    private Map<String, Object> featureJson(String id) {
        try {
            var fv = store.material().featureVersion(id);
            return fv == null ? Map.of() : mapOf("featureSetId", fv.featureSetId(),
                    "version", fv.version(), "algorithm", fv.algorithm(),
                    "paramsCanonical", fv.paramsCanonical());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Object nz(Object o) { return o == null ? "" : o; }
    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
