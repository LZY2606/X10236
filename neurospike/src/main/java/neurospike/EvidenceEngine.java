package neurospike;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Structured evidence. Everything here is evidence only: refractory violation,
 * amplitude drift and simultaneous multi-channel propagation must never be
 * treated as a stable identity (cluster numbers are explicitly not identities).
 * Window changes recompute only intersecting clusters.
 */
public final class EvidenceEngine {

    private EvidenceEngine() {
    }

    public record EvidenceResult(List<Finding> findings, List<Diagnostic> diagnostics) {
    }

    public static EvidenceResult evaluate(State state, MembershipEngine.MembershipResult membership,
                                          long seq, WindowSpec window) {
        List<Finding> out = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        FeatureVersionSpec spec = state.specAtOrBefore(seq).material();
        Map<String, MaterialRev<Detection>> dets = state.detectionAtOrBefore(seq);
        double hz = state.timeBasis().samplesPerSecond().value();
        String eventId = state.eventsAtOrBefore(seq).get(
                state.eventsAtOrBefore(seq).size() - 1).eventId();

        for (ClusterGen c : membership.clusters()) {
            List<Detection> ds = inWindow(state, dets, c.detections(), window, hz);
            if (ds.isEmpty()) {
                continue;
            }
            refractory(spec, c, ds, hz, eventId, out);
            concurrence(spec, c, ds, dets, eventId, out);
            propagation(spec, c, ds, dets, eventId, out);
            amplitudeDrift(state, spec, c, ds, eventId, out, diagnostics);
            splitShared(c, membership, seq, eventId, out);
            windowEndpoints(c, ds, window, hz, eventId, out);
        }
        return new EvidenceResult(out, diagnostics);
    }

    public static List<Detection> inWindow(State state,
                                           Map<String, MaterialRev<Detection>> dets,
                                           List<String> ids, WindowSpec window,
                                           double hz) {
        List<Detection> out = new ArrayList<>();
        for (String id : ids) {
            MaterialRev<Detection> rev = dets.get(id);
            if (rev == null || !rev.material().active()) {
                continue;
            }
            double t = rev.material().sample().value() / hz;
            if (window.contains(t)) {
                out.add(rev.material());
            }
        }
        out.sort((a, b) -> Double.compare(a.sample().value(), b.sample().value()));
        return out;
    }

    private static void refractory(FeatureVersionSpec spec, ClusterGen c, List<Detection> ds,
                                   double hz, String eventId, List<Finding> out) {
        double window = spec.refractoryWindow().valueIn("s");
        List<String> pair = null;
        double minGap = Double.MAX_VALUE;
        for (int i = 1; i < ds.size(); i++) {
            double gap = (ds.get(i).sample().value() - ds.get(i - 1).sample().value()) / hz;
            if (gap < window && gap < minGap) {
                minGap = gap;
                pair = List.of(ds.get(i - 1).detectionId(), ds.get(i).detectionId());
            }
        }
        if (pair != null) {
            ObjectNode detail = Json.NODE.objectNode()
                    .put("ruleWindow", round(window)).put("ruleWindowUnit", "s");
            out.add(finding(Finding.REFRACTORY_VIOLATION, c, pair, round(minGap), "s",
                    "recording time basis", Finding.VALID, null, eventId,
                    Canonical.json(detail)));
        }
    }

    private static void concurrence(FeatureVersionSpec spec, ClusterGen c,
                                    List<Detection> ds,
                                    Map<String, MaterialRev<Detection>> all,
                                    String eventId, List<Finding> out) {
        double hzS = 0;
        double window = spec.concurrenceWindow().valueIn("s");
        Set<String> emitted = new TreeSet<>();
        for (Detection d : ds) {
            List<String> ids = new ArrayList<>();
            ids.add(d.detectionId());
            if (d.concurrentWith() != null) {
                for (String other : d.concurrentWith()) {
                    MaterialRev<Detection> or = all.get(other);
                    if (or == null) {
                        continue;
                    }
                    ids.add(other);
                }
            }
            if (ids.size() > 1) {
                java.util.Collections.sort(ids);
                String key = String.join("|", ids);
                if (emitted.add(key)) {
                    ObjectNode detail = Json.NODE.objectNode()
                            .put("ruleWindow", round(window)).put("ruleWindowUnit", "s");
                    detail.set("detections", Json.NODE.arrayNode()
                            .addAll(ids.stream().map(Json.NODE::textNode).toList()));
                    out.add(finding(Finding.CONCURRENT_SPIKE, c, List.copyOf(ids),
                            ids.size() - 1, "channels", "simultaneous across channels",
                            Finding.VALID, null, eventId, Canonical.json(detail)));
                }
            }
        }
    }

    private static void propagation(FeatureVersionSpec spec, ClusterGen c,
                                    List<Detection> ds,
                                    Map<String, MaterialRev<Detection>> all,
                                    String eventId, List<Finding> out) {
        double window = spec.propagationWindow().valueIn("s");
        Set<String> emitted = new TreeSet<>();
        for (Detection d : ds) {
            if (d.propagatedTo() == null || d.propagatedTo().isEmpty()) {
                continue;
            }
            List<String> ids = new ArrayList<>();
            ids.add(d.detectionId());
            for (String other : d.propagatedTo()) {
                MaterialRev<Detection> or = all.get(other);
                if (or == null) {
                    continue;
                }
                ids.add(other);
            }
            java.util.Collections.sort(ids);
            String key = String.join("|", ids);
            if (ids.size() > 1 && emitted.add(key)) {
                ObjectNode detail = Json.NODE.objectNode()
                        .put("ruleWindow", round(window)).put("ruleWindowUnit", "s");
                detail.set("channels", Json.NODE.arrayNode()
                        .addAll(ids.stream().map(Json.NODE::textNode).toList()));
                out.add(finding(Finding.MULTICHANNEL_PROPAGATION, c, List.copyOf(ids),
                        ids.size(), "channels", "probe geometry ordering",
                        Finding.VALID, null, eventId, Canonical.json(detail)));
            }
        }
    }

    private static void amplitudeDrift(State state, FeatureVersionSpec spec, ClusterGen c,
                                       List<Detection> ds, String eventId,
                                       List<Finding> out, List<Diagnostic> diagnostics) {
        double window = spec.driftWindow().valueIn("s");
        double threshold = spec.driftAmplitudeRatio().value();
        double hz = state.timeBasis().samplesPerSecond().value();
        double[] first = null;
        double[] last = null;
        Detection firstD = null;
        Detection lastD = null;
        for (Detection d : ds) {
            Double a = d.peakAmplitude() == null ? null : d.peakAmplitude().valueIn("uV");
            if (a == null) {
                diagnostics.add(new Diagnostic(Diagnostic.LOCAL_VALUE_INVALID,
                        "/detections/" + d.detectionId() + "/peakAmplitude",
                        "amplitude unconvertible; excluded from drift but retained",
                        d.peakAmplitude() == null ? null : d.peakAmplitude().originalText()));
                out.add(finding(Finding.LOCAL_VALUE_INVALID, c, List.of(d.detectionId()),
                        0.0, "uV", "amplitude unit basis", Finding.LOCAL_INVALID,
                        "amplitude cannot be converted to uV: "
                                + (d.peakAmplitude() == null ? "missing"
                                : d.peakAmplitude().originalText()),
                        eventId, "{}"));
                continue;
            }
            double t = d.sample().value() / hz;
            if (first == null) {
                first = new double[]{t, a};
                firstD = d;
            }
            last = new double[]{t, a};
            lastD = d;
        }
        if (first != null && last != null && (last[0] - first[0]) >= window
                && Math.abs(first[1]) > 1e-9) {
            double ratio = last[1] / first[1];
            if (Math.abs(ratio - 1.0) >= threshold) {
                ObjectNode detail = Json.NODE.objectNode()
                        .put("firstAmplitudeUv", round(first[1]))
                        .put("lastAmplitudeUv", round(last[1]))
                        .put("ratio", round(ratio))
                        .put("spanSeconds", round(last[0] - first[0]));
                out.add(finding(Finding.AMPLITUDE_DRIFT, c,
                        List.of(firstD.detectionId(), lastD.detectionId()),
                        round(ratio), "ratio", "amplitude normalized to uV",
                        Finding.VALID, null, eventId, Canonical.json(detail)));
            }
        }
    }

    private static void splitShared(ClusterGen c, MembershipEngine.MembershipResult membership,
                                    long seq, String eventId, List<Finding> out) {
        for (LineageEdge edge : membership.edges()) {
            if (!LineageEdge.SPLIT.equals(edge.edgeType()) || edge.seq() > seq) {
                continue;
            }
            if (!edge.toClusterStableId().equals(c.stableId())) {
                continue;
            }
            Set<String> siblings = new TreeSet<>();
            for (LineageEdge other : membership.edges()) {
                if (LineageEdge.SPLIT.equals(other.edgeType())
                        && other.fromClusterStableId().equals(edge.fromClusterStableId())
                        && !other.toClusterStableId().equals(c.stableId())) {
                    ClusterGen sib = membership.clusters().stream()
                            .filter(g -> g.stableId().equals(other.toClusterStableId()))
                            .findFirst().orElse(null);
                    if (sib != null) {
                        siblings.addAll(sib.detections());
                    }
                }
            }
            Set<String> shared = new TreeSet<>(c.detections());
            shared.retainAll(siblings);
            if (!shared.isEmpty()) {
                ObjectNode detail = Json.NODE.objectNode()
                        .put("parentStableId", edge.fromClusterStableId());
                out.add(finding(Finding.SPLIT_SHARED_EVENT, c, List.copyOf(shared),
                        shared.size(), "events", "shared after split (both candidates legal)",
                        Finding.VALID, null, eventId, Canonical.json(detail)));
            }
        }
    }

    private static void windowEndpoints(ClusterGen c, List<Detection> ds,
                                        WindowSpec window, double hz, String eventId,
                                        List<Finding> out) {
        List<String> atStart = new ArrayList<>();
        List<String> atEnd = new ArrayList<>();
        for (Detection d : ds) {
            double t = d.sample().value() / hz;
            if (Math.abs(t - window.startSeconds()) < 1e-9) {
                atStart.add(d.detectionId());
            }
            if (Math.abs(t - window.endSeconds()) < 1e-9) {
                atEnd.add(d.detectionId());
            }
        }
        if (!atStart.isEmpty() || !atEnd.isEmpty()) {
            ObjectNode detail = Json.NODE.objectNode()
                    .put("convention", "half-open [start,end)")
                    .put("startSeconds", window.startSeconds())
                    .put("endSeconds", window.endSeconds());
            detail.set("atStart", Json.NODE.arrayNode()
                    .addAll(atStart.stream().map(Json.NODE::textNode).toList()));
            detail.set("atEnd", Json.NODE.arrayNode()
                    .addAll(atEnd.stream().map(Json.NODE::textNode).toList()));
            List<String> both = new ArrayList<>(atStart);
            both.addAll(atEnd);
            out.add(finding(Finding.WINDOW_ENDPOINT, c, both,
                    both.size(), "events", "half-open [start,end)", Finding.VALID,
                    atEnd.isEmpty() ? null
                            : "events exactly at end are excluded by half-open convention",
                    eventId, Canonical.json(detail)));
        }
    }

    private static Finding finding(String type, ClusterGen c, List<String> ids,
                                   double value, String unit, String basis, String status,
                                   String invalidReason, String eventId, String detail) {
        String id = "f_" + Canonical.shortHash(Canonical.of(Json.NODE.objectNode()
                .put("type", type).put("cluster", c.stableId())
                .set("detections", Json.NODE.arrayNode()
                        .addAll(ids.stream().sorted().map(Json.NODE::textNode).toList()))));
        return new Finding(id, type, c.stableId(), ids,
                Double.toString(value), unit, basis, status, invalidReason,
                eventId, eventId, null, detail, type);
    }

    private static double round(double v) {
        return Math.round(v * 1e9) / 1e9;
    }
}
