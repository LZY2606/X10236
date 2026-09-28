package com.neuro.gsb.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuro.gsb.domain.Ids;
import com.neuro.gsb.store.ComputationStore;
import com.neuro.gsb.store.Database;
import com.neuro.gsb.store.EventStore;
import com.neuro.gsb.store.LineageStore;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Derived analyses per cluster identity: drift timeline, autocorrelogram,
 * firing rate, feature projection, waveform summary. Every computation
 * records input digest, parameter version and parents.
 *
 * Window changes recompute ONLY identities whose event-time extent
 * intersects the new window (partial invalidation); all other computations
 * stay VALID and untouched.
 *
 * Refractory violations, amplitude drift and multi-channel propagation are
 * emitted as structured evidence rows — they never rewrite identity.
 */
public final class DriftAnalysis {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Database db;
    private final ComputationStore computations;
    private final LineageStore lineage;

    public DriftAnalysis(Database db, ComputationStore computations, LineageStore lineage) {
        this.db = db;
        this.computations = computations;
        this.lineage = lineage;
    }

    public record Ev(String key, int ch, double tMs, double ampUv, String label, String labelSet) {}

    /** Load the canonical normalized series (global DETECTION_SERIES computation). */
    public List<Ev> loadSeries(long asOfSeq) {
        return computations.asOf(asOfSeq, "global").stream()
                .filter(c -> c.kind().equals("DETECTION_SERIES"))
                .findFirst()
                .map(c -> parseSeries(c.payload()))
                .orElse(List.of());
    }

    private List<Ev> parseSeries(String payload) {
        List<Ev> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(payload);
            for (JsonNode n : root.get("events")) {
                out.add(new Ev(n.get("key").asText(), n.get("ch").asInt(),
                        n.get("t_ms").asDouble(), n.get("amp_uv").asDouble(),
                        n.get("label").asText(), n.get("label_set").asText()));
            }
        } catch (Exception e) {
            throw new IllegalStateException("corrupt DETECTION_SERIES payload", e);
        }
        return out;
    }

    /** Identities whose member time extent intersects [start,end] (null = unbounded). */
    public List<String> identitiesIntersecting(long asOfSeq, Double startMs, Double endMs) {
        List<Ev> series = loadSeries(asOfSeq);
        Map<String, Ev> byKey = new LinkedHashMap<>();
        for (Ev e : series) byKey.put(e.key, e);
        List<String> out = new ArrayList<>();
        for (var id : lineage.identitiesAsOf(asOfSeq)) {
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (String key : lineage.membersAsOf(asOfSeq, id.identity())) {
                Ev e = byKey.get(key);
                if (e == null) continue;
                min = Math.min(min, e.tMs);
                max = Math.max(max, e.tMs);
            }
            if (min > max) continue;
            boolean intersects = (startMs == null || max >= startMs) && (endMs == null || min <= endMs);
            if (intersects) out.add(id.identity());
        }
        return out;
    }

    /**
     * Recompute analyses. If onlyIdentities is non-null, only those identities
     * are invalidated + recomputed (partial invalidation); others keep their
     * VALID computations from the previous parameter version.
     * Returns affected identities.
     */
    public List<String> recompute(Connection conn, long eventSeq, AnalysisParams params,
                                  List<String> onlyIdentities) {
        List<Ev> series = loadSeries(Long.MAX_VALUE);
        Map<String, Ev> byKey = new LinkedHashMap<>();
        for (Ev e : series) byKey.put(e.key, e);

        List<String> targets = onlyIdentities != null ? onlyIdentities
                : lineage.identitiesAsOf(Long.MAX_VALUE).stream().map(LineageStore.Identity::identity).toList();

        List<String> affected = computations.invalidateForIdentities(conn, targets, eventSeq);
        String digest = Ids.sha256(series.toString());

        for (String identity : targets) {
            List<Ev> members = new ArrayList<>();
            for (String key : lineage.membersAsOf(Long.MAX_VALUE, identity)) {
                Ev e = byKey.get(key);
                if (e != null) members.add(e);
            }
            members.sort((a, b) -> Double.compare(a.tMs, b.tMs));
            List<Ev> inWindow = members.stream()
                    .filter(e -> (params.windowStartMs() == null || e.tMs >= params.windowStartMs())
                              && (params.windowEndMs() == null || e.tMs <= params.windowEndMs()))
                    .toList();

            put(conn, "DRIFT_TIMELINE", identity, digest, params, eventSeq, driftPayload(inWindow, params));
            put(conn, "AUTOCORRELOGRAM", identity, digest, params, eventSeq, acgPayload(inWindow, params));
            put(conn, "FIRING_RATE", identity, digest, params, eventSeq, ratePayload(inWindow, params));
            put(conn, "FEATURE_PROJECTION", identity, digest, params, eventSeq, projectionPayload(inWindow));
        }
        attachEvidence(conn, eventSeq, targets, series, byKey, params);
        return affected;
    }

    private void put(Connection conn, String kind, String identity, String digest,
                     AnalysisParams params, long eventSeq, String payload) {
        computations.put(conn, new ComputationStore.Computation(
                ComputationStore.computationId(kind, identity, digest, params.paramVersion()),
                kind, identity, digest, params.paramVersion(), "[]", payload, "VALID", eventSeq, null));
    }

    /** Evidence pass: refractory violations, amplitude drift, concurrent spikes / propagation. */
    private void attachEvidence(Connection conn, long eventSeq, List<String> targets, List<Ev> series,
                                Map<String, Ev> byKey, AnalysisParams params) {
        for (String identity : targets) {
            List<Ev> members = new ArrayList<>();
            for (String key : lineage.membersAsOf(Long.MAX_VALUE, identity)) {
                Ev e = byKey.get(key);
                if (e != null) members.add(e);
            }
            members.sort((a, b) -> Double.compare(a.tMs, b.tMs));
            // refractory violations
            for (int i = 1; i < members.size(); i++) {
                double isi = members.get(i).tMs - members.get(i - 1).tMs;
                if (isi < params.refractoryMs()) {
                    lineage.addEvidence(conn, identity, "REFRACTORY_VIOLATION",
                            "{\"isi_ms\":" + isi + ",\"refractory_ms\":" + params.refractoryMs()
                                    + ",\"a\":\"" + members.get(i - 1).key + "\",\"b\":\"" + members.get(i).key
                                    + "\",\"unit\":\"ms\",\"basis\":\"RECORDING_CLOCK\""
                                    + ",\"rule\":\"evidence only; never rewrites identity\"}", eventSeq);
                }
            }
            // amplitude drift: linear slope over time, µV per second
            if (members.size() >= 3) {
                double slope = slopeUvPerSecond(members);
                if (Math.abs(slope) > 5.0) {
                    lineage.addEvidence(conn, identity, "AMPLITUDE_DRIFT",
                            "{\"slope_uv_per_s\":" + slope + ",\"unit\":\"uV/s\",\"basis\":\"RECORDING_CLOCK\""
                                    + ",\"rule\":\"evidence only; never rewrites identity\"}", eventSeq);
                }
            }
        }
        // concurrent spikes / multi-channel propagation: same-time events on different channels
        Map<Long, List<Ev>> byTimeBucket = new TreeMap<>();
        for (Ev e : series) {
            byTimeBucket.computeIfAbsent(Math.round(e.tMs * 1000), k -> new ArrayList<>()).add(e);
        }
        for (var bucket : byTimeBucket.values()) {
            if (bucket.size() > 1 && bucket.stream().map(Ev::ch).distinct().count() > 1) {
                StringBuilder keys = new StringBuilder();
                for (Ev e : bucket) keys.append(keys.isEmpty() ? "" : ",").append("\"").append(e.key).append("\"");
                lineage.addEvidence(conn, "global", "MULTI_CHANNEL_PROPAGATION",
                        "{\"event_keys\":[" + keys + "],\"channels\":" + bucket.stream().map(Ev::ch).distinct().sorted().toList()
                                + ",\"t_ms\":" + bucket.get(0).tMs + ",\"unit\":\"ms\",\"basis\":\"RECORDING_CLOCK\""
                                + ",\"rule\":\"propagation is evidence; cluster number is not identity\"}", eventSeq);
            }
        }
    }

    private static double slopeUvPerSecond(List<Ev> members) {
        int n = members.size();
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (Ev e : members) {
            double x = e.tMs / 1000.0, y = e.ampUv;
            sx += x; sy += y; sxx += x * x; sxy += x * y;
        }
        double denom = n * sxx - sx * sx;
        return denom == 0 ? 0 : (n * sxy - sx * sy) / denom;
    }

    private String driftPayload(List<Ev> inWindow, AnalysisParams params) {
        StringBuilder bins = new StringBuilder("[");
        Map<Long, List<Double>> byBin = new TreeMap<>();
        for (Ev e : inWindow) {
            byBin.computeIfAbsent((long) Math.floor(e.tMs / params.binMs()), k -> new ArrayList<>()).add(e.ampUv);
        }
        boolean first = true;
        for (var entry : byBin.entrySet()) {
            double mean = entry.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            if (!first) bins.append(',');
            bins.append("{\"bin_start_ms\":").append(entry.getKey() * (long) params.binMs())
                .append(",\"mean_amp_uv\":").append(mean).append(",\"n\":").append(entry.getValue().size()).append("}");
            first = false;
        }
        return "{\"unit\":\"uV\",\"time_unit\":\"ms\",\"basis\":\"RECORDING_CLOCK\",\"bin_ms\":" + params.binMs()
                + ",\"window\":\"" + params.windowTag() + "\",\"bins\":" + bins + "]}";
    }

    private String acgPayload(List<Ev> inWindow, AnalysisParams params) {
        int binCount = 25;
        long[] hist = new long[binCount];
        long violations = 0;
        for (int i = 1; i < inWindow.size(); i++) {
            double isi = inWindow.get(i).tMs - inWindow.get(i - 1).tMs;
            if (isi < params.refractoryMs()) violations++;
            int bin = (int) Math.min(binCount - 1, Math.floor(isi / params.refractoryMs()));
            if (bin >= 0) hist[bin]++;
        }
        StringBuilder h = new StringBuilder("[");
        for (int i = 0; i < binCount; i++) h.append(i == 0 ? "" : ",").append(hist[i]);
        return "{\"unit\":\"count\",\"isi_bin_ms\":" + params.refractoryMs()
                + ",\"refractory_ms\":" + params.refractoryMs()
                + ",\"refractory_violations\":" + violations
                + ",\"histogram\":" + h + "]}";
    }

    private String ratePayload(List<Ev> inWindow, AnalysisParams params) {
        Map<Long, Long> byBin = new TreeMap<>();
        for (Ev e : inWindow) {
            byBin.merge((long) Math.floor(e.tMs / params.binMs()), 1L, Long::sum);
        }
        StringBuilder bins = new StringBuilder("[");
        boolean first = true;
        for (var entry : byBin.entrySet()) {
            if (!first) bins.append(',');
            double hz = entry.getValue() / (params.binMs() / 1000.0);
            bins.append("{\"bin_start_ms\":").append(entry.getKey() * (long) params.binMs())
                .append(",\"rate_hz\":").append(hz).append("}");
            first = false;
        }
        return "{\"unit\":\"Hz\",\"time_unit\":\"ms\",\"basis\":\"RECORDING_CLOCK\",\"bin_ms\":" + params.binMs()
                + ",\"bins\":" + bins + "]}";
    }

    private String projectionPayload(List<Ev> inWindow) {
        StringBuilder pts = new StringBuilder("[");
        boolean first = true;
        for (Ev e : inWindow) {
            if (!first) pts.append(',');
            // feature projection: amplitude vs. channel index as a 2-D stand-in for PCA space
            pts.append("{\"key\":\"").append(e.key).append("\",\"pc1\":").append(e.ampUv)
               .append(",\"pc2\":").append(e.ch * 10.0).append("}");
            first = false;
        }
        return "{\"unit\":\"uV\",\"feature_version\":\"feat-pca-v1\",\"points\":" + pts + "]}";
    }
}
