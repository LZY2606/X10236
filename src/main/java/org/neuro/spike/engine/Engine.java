package org.neuro.spike.engine;

import com.fasterxml.jackson.databind.JsonNode;
import org.neuro.spike.model.Cluster;
import org.neuro.spike.model.DecisionEvent;
import org.neuro.spike.model.Diagnostic;
import org.neuro.spike.model.Evidence;
import org.neuro.spike.model.EvidenceKind;
import org.neuro.spike.model.LineageLink;
import org.neuro.spike.model.LineageRelation;
import org.neuro.spike.model.Material;
import org.neuro.spike.model.MaterialType;
import org.neuro.spike.model.Proposal;
import org.neuro.spike.model.ResultStatus;
import org.neuro.spike.store.SpikeStore;
import org.neuro.spike.util.Ids;
import org.neuro.spike.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Business-rule engine. Every rule exercised by the review UI is also
 * callable directly here, which is what the storage/rule tests use.
 */
public final class Engine {
    private final SpikeStore store;
    private int budgetLimit = Integer.MAX_VALUE;
    private int budgetUsed = 0;

    public Engine(SpikeStore store) {
        this.store = store;
    }

    public void setBudget(int limit) {
        this.budgetLimit = limit;
        this.budgetUsed = 0;
    }

    public int budgetUsed() {
        return budgetUsed;
    }

    public int budgetLimit() {
        return budgetLimit;
    }

    public SpikeStore store() {
        return store;
    }

    // ---- raw event helpers -------------------------------------------

    public List<DetEvent> effectiveEvents(int atSeq) {
        ViewBuilder.Snapshot snap = ViewBuilder.build(store, atSeq);
        Map<String, DetEvent> byUid = new LinkedHashMap<>();
        String supersededKey = null;
        Map<String, String> supersedes = new LinkedHashMap<>();
        for (DetEvent e : snap.events()) {
            Material m = snap.materials().stream()
                    .filter(x -> x.uid().equals(e.uid())).findFirst().orElseThrow();
            JsonNode node = Json.read(m.payloadJson());
            if (node.hasNonNull("supersedes")) {
                supersedes.put(node.get("supersedes").asText(), e.uid());
            }
        }
        Set<String> replaced = new LinkedHashSet<>(supersedes.keySet());
        List<DetEvent> out = new ArrayList<>();
        for (DetEvent e : snap.events()) {
            if (replaced.contains(e.uid())) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    public Material addDetectionEvent(String key, double timeSec, String timeRaw, int channel,
                                      String amplitudeRaw, String waveformRef) {
        int seq = store.nextSeq();
        String uid = Ids.mat("det", key);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("time_s", timeSec);
        payload.put("time_raw", timeRaw);
        payload.put("channel", channel);
        payload.put("amplitude_raw", amplitudeRaw);
        payload.put("waveform_ref", waveformRef);
        Material m = new Material(uid, MaterialType.DETECTION_EVENT, Json.write(payload),
                "uV", "recording-clock:s", List.of(), seq);
        store.insertMaterial(m);
        return m;
    }

    /**
     * Local source change: one direct source is revised. The original event
     * material remains stored (immutable); a new material supersedes it and
     * only dependent clusters are recomputed.
     */
    public Material reviseDetectionEvent(String key, String supersedesUid, double timeSec,
                                         String timeRaw, int channel, String amplitudeRaw,
                                         String waveformRef) {
        int seq = store.nextSeq();
        String uid = Ids.mat("det", key);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("time_s", timeSec);
        payload.put("time_raw", timeRaw);
        payload.put("channel", channel);
        payload.put("amplitude_raw", amplitudeRaw);
        payload.put("waveform_ref", waveformRef);
        payload.put("supersedes", supersedesUid);
        Material m = new Material(uid, MaterialType.DETECTION_EVENT, Json.write(payload),
                "uV", "recording-clock:s", List.of(), seq);
        store.insertMaterial(m);
        logEvent("SOURCE_REVISED", uid, Map.of(
                "supersedes", supersedesUid,
                "scope", "dependents-only",
                "rule", "changing one direct source invalidates only dependent clusters"),
                "single direct source changed; invalidation restricted to dependent cluster graph",
                null);
        return m;
    }

    public Material reviseThreshold(String key, double thresholdUv, String paramVersion) {
        int seq = store.nextSeq();
        String uid = Ids.mat("thr", key);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("threshold_uV", thresholdUv);
        payload.put("param_version", paramVersion);
        Material m = new Material(uid, MaterialType.DETECTION_THRESHOLD, Json.write(payload),
                "uV", "feature-space", List.of(), seq);
        store.insertMaterial(m);
        logEvent("PARAM_REVISED", uid, Map.of(
                "param_version", paramVersion,
                "threshold_uV", thresholdUv),
                "parameter revision creates a new parameter version; history keeps prior versions", null);
        return m;
    }

    /**
     * Window change. Only clusters intersecting the window are marked for
     * recomputation; outside clusters keep their existing derived records.
     */
    public Material changeWindow(String key, double startSec, double endSec, List<String> recomputeBatches) {
        int seq = store.nextSeq();
        String uid = Ids.mat("win", key);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("start_s", startSec);
        payload.put("end_s", endSec);
        payload.put("recompute_batches", recomputeBatches);
        Material m = new Material(uid, MaterialType.TIME_WINDOW, Json.write(payload),
                "s", "recording-clock:s", List.of(), seq);
        store.insertMaterial(m);

        List<String> intersecting = intersectingClusters(startSec, endSec, store.headSeq());
        logEvent("WINDOW_CHANGED", uid, Map.of(
                "window_start_s", startSec,
                "window_end_s", endSec,
                "recompute_scope", intersecting,
                "rule", "only intersecting clusters are recomputed"),
                "modifying the analysis window recomputes intersecting clusters only",
                null);
        return m;
    }

    public List<String> intersectingClusters(double startSec, double endSec, int atSeq) {
        List<DetEvent> events = effectiveEvents(atSeq);
        Map<String, DetEvent> byUid = new LinkedHashMap<>();
        events.forEach(e -> byUid.put(e.uid(), e));
        List<String> hit = new ArrayList<>();
        for (Cluster c : activeClusters(atSeq)) {
            boolean intersects = c.members().stream().map(byUid::get).filter(java.util.Objects::nonNull)
                    .anyMatch(e -> e.timeSec() >= startSec && e.timeSec() <= endSec);
            if (intersects) {
                hit.add(c.uid());
            }
        }
        return hit;
    }
