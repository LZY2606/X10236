package neurospike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Application layer: append-only transition rules over State. Every transition
 * records input digest, parameter version and parent dependency; conflicting
 * human decisions stay UNRESOLVED_CONFLICT with all legal candidates.
 */
public final class AppService {

    private final State state;

    public AppService(State state) {
        this.state = state;
    }

    public State state() {
        return state;
    }

    public record Transition(State state, Event event, List<String> affectedClusterStableIds,
                             Evaluation before, Evaluation after, WindowSpec windowBefore,
                             WindowSpec windowAfter) {
    }

    public Transition applyScriptOp(JsonNode op) {
        return switch (op.path("op").asText()) {
            case "PARAM_REVISION" -> paramRevision(op);
            case "LABEL_REVISION" -> labelRevision(op);
            case "SOURCE_CHANGE" -> sourceChange(op);
            case "DECISION" -> {
                State before = state;
                String target = resolveTarget(state, op.path("targetId").asText(),
                        op.path("targetKind").asText("FINDING"), WindowSpec.record(state));
                yield new AppService(before).decision(
                        target,
                        op.path("targetKind").asText("FINDING"),
                        op.path("verdict").asText(),
                        op.path("rationale").asText(""),
                        op.path("reviewer").asText("script"),
                        textList(op.path("candidates")),
                        op.path("params"), null);
            }
            default -> throw new IllegalArgumentException("unknown script op: "
                    + op.path("op").asText());
        };
    }

    public Transition paramRevision(JsonNode op) {
        long seq = state.events().size() + 1;
        FeatureVersionSpec base = state.currentSpec();
        FeatureVersionSpec next = Json.MAPPER.convertValue(
                op.path("featureSpec"), FeatureVersionSpec.class);
        // direct source for a parameter revision is only the feature version
        FeatureVersionSpec merged = new FeatureVersionSpec(
                next.featureVersion() != null ? next.featureVersion()
                        : base.featureVersion() + ".rev" + seq,
                next.algorithm() != null ? next.algorithm() : base.algorithm(),
                next.components() != null ? next.components() : base.components(),
                next.waveformSamples() > 0 ? next.waveformSamples() : base.waveformSamples(),
                next.refractoryWindow() != null ? next.refractoryWindow() : base.refractoryWindow(),
                next.concurrenceWindow() != null ? next.concurrenceWindow() : base.concurrenceWindow(),
                next.propagationWindow() != null ? next.propagationWindow() : base.propagationWindow(),
                next.driftWindow() != null ? next.driftWindow() : base.driftWindow(),
                next.driftAmplitudeRatio() != null ? next.driftAmplitudeRatio() : base.driftAmplitudeRatio(),
                next.rateBinSeconds() != null ? next.rateBinSeconds() : base.rateBinSeconds(),
                next.autocorrHalfWindow() != null ? next.autocorrHalfWindow() : base.autocorrHalfWindow());
        Event event = append(Event.PARAM_REVISION, op, mergedDigest(merged));
        int version = state.featureSpecVersions().size() + 1;
        MaterialRev<FeatureVersionSpec> rev = new MaterialRev<>(
                "feature@" + version, "feature", version, event.eventId(),
                mergedDigest(merged), merged);
        State next2 = withState(s -> {}, List.of(), rev, null, null, event, null);
        return transitionResult(state, next2, event, WindowSpec.record(state),
                WindowSpec.record(next2), affectedAll(state, next2));
    }

    public Transition sourceChange(JsonNode op) {
        long seq = state.events().size() + 1;
        JsonNode changed = op.path("changes");
        List<MaterialRev<Detection>> newDets = new ArrayList<>();
        List<MaterialRev<WaveformRef>> newWfs = new ArrayList<>();
        Set<String> changedDetectionIds = new TreeSet<>();
        Event event = append(Event.SOURCE_CHANGE, op, Canonical.shortHash(
                Canonical.of(changed)));
        if (changed.has("detections")) {
            changed.path("detections").forEach(node -> {
                Detection d = Json.MAPPER.convertValue(node, Detection.class);
                MaterialRev<Detection> old = latestDetection(d.detectionId());
                int v = old == null ? 1 : old.version() + 1;
                MaterialRev<Detection> rev = new MaterialRev<>(
                        d.detectionId() + "@v" + v, d.detectionId(), v,
                        event.eventId(), Canonical.shortHash(Canonical.of(d, Json.MAPPER)), d);
                newDets.add(rev);
                changedDetectionIds.add(d.detectionId());
            });
        }
        if (changed.has("waveforms")) {
            changed.path("waveforms").forEach(node -> {
                WaveformRef w = Json.MAPPER.convertValue(node, WaveformRef.class);
                MaterialRev<WaveformRef> old = latestWaveform(w.waveformId());
                int v = old == null ? 1 : old.version() + 1;
                newWfs.add(new MaterialRev<>(w.waveformId() + "@v" + v, w.waveformId(), v,
                        event.eventId(),
                        Canonical.shortHash(Canonical.of(w, Json.MAPPER)), w));
            });
        }
        State next2 = withState(s -> {}, newWfs, null, newDets, null, event, null);
        List<String> affected = affectedClustersByDetections(next2, changedDetectionIds);
        return transitionResult(state, next2, event, WindowSpec.record(state),
                WindowSpec.record(next2), affected);
    }

    public Transition decision(String targetId, String targetKind, String verdict,
                               String rationale, String reviewer, List<String> candidates,
                               JsonNode params, String supersedesEventId) {
        long seq = state.events().size() + 1;
        ObjectNode payload = Json.NODE.objectNode()
                .put("targetId", targetId).put("targetKind", targetKind)
                .put("verdict", verdict).put("rationale", rationale)
                .put("reviewer", reviewer);
        if (candidates != null) {
            payload.set("candidates", Json.NODE.arrayNode()
                    .addAll(candidates.stream().map(Json.NODE::textNode).toList()));
        }
        if (params != null) {
            payload.set("params", params);
        }
        if (supersedesEventId != null) {
            payload.put("supersedesEventId", supersedesEventId);
        }

        // conflict detection: a new verdict against the same target that differs
        // from an existing, non-superseded decision is recorded, not resolved
        String conflict = detectConflict(targetId, verdict, supersedesEventId);
        Decision decision = new Decision(null, seq, targetId, targetKind, verdict,
                rationale, reviewer, candidates == null ? List.of() : List.copyOf(candidates),
                conflict, params, supersedesEventId);
        Event event = append(Event.DECISION, payload,
                Engine.digestAt(state, state.events().size()));
        decision = new Decision(event.eventId(), seq, targetId, targetKind, verdict,
                rationale, reviewer, decision.candidates(), conflict, params,
                supersedesEventId);
        State next2 = withState(s -> {}, List.of(), null, null, List.of(), event, decision);
        WindowSpec before = WindowSpec.record(state);
        WindowSpec after = before;
        if (Decision.PARAM_ADJUST.equals(verdict) && params != null
                && params.has("window")) {
            after = WindowSpec.fromNode(params.path("window"), before);
        }
        List<String> affected = Decision.PARAM_ADJUST.equals(verdict)
                ? clustersIntersectingWindow(next2, after)
                : affectedClustersByTarget(next2, targetId);
        return transitionResult(state, next2, event, before, after, affected);
    }

    private String detectConflict(String targetId, String verdict, String supersedes) {
        if (supersedes != null) {
            return Decision.RESOLVED;
        }
        for (Decision d : state.decisions()) {
            if (d.targetId().equals(targetId)
                    && d.supersedesEventId() == null
                    && !d.verdict().equals(verdict)
                    && !Decision.PARAM_ADJUST.equals(d.verdict())
                    && !Decision.SPLIT.equals(d.verdict())
                    && !Decision.MERGE.equals(d.verdict())) {
                return Decision.UNRESOLVED_CONFLICT;
            }
        }
        return Decision.RESOLVED;
    }

    private Event append(String kind, JsonNode payload, String inputDigest) {
        long seq = state.events().size() + 1;
        String parent = state.head().eventId();
        String paramVersion = "feature@" + state.featureSpecVersions().size();
        String causal = Canonical.of(Json.NODE.objectNode()
                .put("seq", seq).put("parent", parent).put("kind", kind)
                .put("inputDigest", inputDigest).put("paramVersion", paramVersion)
                .set("payload", payload));
        String eventId = "evt_" + Canonical.shortHash(causal);
        return new Event(eventId, seq, kind, parent, payload, inputDigest,
                paramVersion, "OK", null,
                Instant.now().toString());
    }

    private State withState(@SuppressWarnings("unused") Runnable unused,
                            List<MaterialRev<WaveformRef>> newWfs,
                            MaterialRev<FeatureVersionSpec> newSpec,
                            List<MaterialRev<Detection>> newDets,
                            List<MaterialRev<LabelSet>> newLabels,
                            Event event, Decision decision) {
        List<MaterialRev<WaveformRef>> wfs = new ArrayList<>(state.waveformVersions());
        if (newWfs != null) {
            wfs.addAll(newWfs);
        }
        List<MaterialRev<Detection>> dets = new ArrayList<>(state.detectionVersions());
        if (newDets != null) {
            dets.addAll(newDets);
        }
        List<MaterialRev<FeatureVersionSpec>> specs =
                new ArrayList<>(state.featureSpecVersions());
        if (newSpec != null) {
            specs.add(newSpec);
        }
        List<MaterialRev<LabelSet>> labels = new ArrayList<>(state.labelSetVersions());
        if (newLabels != null) {
            labels.addAll(newLabels);
        }
        List<Event> events = new ArrayList<>(state.events());
        events.add(event);
        List<Decision> decisions = new ArrayList<>(state.decisions());
        if (decision != null) {
            decisions.add(decision);
        }
        return new State(state.caseId(), state.title(), state.timeBasis(), state.probe(),
                state.thresholds(), wfs, dets, specs, labels,
                state.baselineAdjudications(), events, decisions, state.lineageEdges());
    }

    private MaterialRev<Detection> latestDetection(String key) {
        MaterialRev<Detection> result = null;
        for (MaterialRev<Detection> r : state.detectionVersions()) {
            if (r.key().equals(key)) {
                result = r;
            }
        }
        return result;
    }

    private MaterialRev<WaveformRef> latestWaveform(String key) {
        MaterialRev<WaveformRef> result = null;
        for (MaterialRev<WaveformRef> r : state.waveformVersions()) {
            if (r.key().equals(key)) {
                result = r;
            }
        }
        return result;
    }

    private String mergedDigest(FeatureVersionSpec spec) {
        return Canonical.shortHash(Canonical.of(spec, Json.MAPPER));
    }

    private Transition transitionResult(State before, State after, Event event,
                                        WindowSpec wb, WindowSpec wa,
                                        List<String> affected) {
        Evaluation beforeEval = Engine.head(before, wb);
        Evaluation afterEval = Engine.head(after, wa);
        ObjectNode note = Json.NODE.objectNode();
        note.set("affectedClusterStableIds", Json.NODE.arrayNode()
                .addAll(affected.stream().map(Json.NODE::textNode).toList()));
        note.put("windowBeforeSeconds", wb.startSeconds() + "-" + wb.endSeconds());
        note.put("windowAfterSeconds", wa.startSeconds() + "-" + wa.endSeconds());
        Event enriched = new Event(event.eventId(), event.seq(), event.kind(),
                event.parentEventId(),
                event.payload().deepCopy().set("effect", note),
                event.inputDigest(), event.paramVersion(), event.status(),
                event.diagnosticJson(), event.createdAt());
        List<Event> replaced = new ArrayList<>(after.events());
        replaced.set(replaced.size() - 1, enriched);
        State finalState = new State(after.caseId(), after.title(), after.timeBasis(),
                after.probe(), after.thresholds(), after.waveformVersions(),
                after.detectionVersions(), after.featureSpecVersions(),
                after.labelSetVersions(), after.baselineAdjudications(),
                List.copyOf(replaced), after.decisions(), after.lineageEdges());
        return new Transition(finalState, enriched, affected, beforeEval, afterEval,
                wb, wa);
    }

    /** Local source change: only clusters containing changed detections. */
    static List<String> affectedClustersByDetections(State state, Set<String> changed) {
        MembershipEngine.MembershipResult m = MembershipEngine.at(
                state, state.events().size());
        Set<String> out = new TreeSet<>();
        for (ClusterGen c : m.clusters()) {
            for (String id : c.detections()) {
                if (changed.contains(id)) {
                    out.add(c.stableId());
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    /** Window change: only clusters intersecting the new window. */
    static List<String> clustersIntersectingWindow(State state, WindowSpec window) {
        MembershipEngine.MembershipResult m = MembershipEngine.at(
                state, state.events().size());
        double hz = state.timeBasis().samplesPerSecond().value();
        Map<String, MaterialRev<Detection>> dets = state.detectionAtOrBefore(
                state.events().size());
        Set<String> out = new TreeSet<>();
        for (ClusterGen c : m.clusters()) {
            List<Detection> ds = new ArrayList<>();
            for (String id : c.detections()) {
                MaterialRev<Detection> r = dets.get(id);
                if (r != null) {
                    ds.add(r.material());
                }
            }
            if (window.intersects(ds, hz)) {
                out.add(c.stableId());
            }
        }
        return List.copyOf(out);
    }

    private static List<String> affectedClustersByTarget(State state, String targetId) {
        MembershipEngine.MembershipResult m = MembershipEngine.at(
                state, state.events().size());
        Set<String> out = new TreeSet<>();
        for (ClusterGen c : m.clusters()) {
            if (c.stableId().equals(targetId)) {
                out.add(c.stableId());
            }
            for (LineageEdge e : m.edges()) {
                if (e.toClusterStableId().equals(c.stableId())
                        && (e.fromClusterStableId().equals(targetId)
                        || c.stableId().equals(targetId))) {
                    out.add(c.stableId());
                }
            }
            if (c.detections().contains(targetId)) {
                out.add(c.stableId());
            }
        }
        // finding target -> its cluster
        for (Finding f : Engine.head(state, WindowSpec.record(state)).findings()) {
            if (f.findingId().equals(targetId) || f.type().equals(targetId)) {
                out.add(f.clusterStableId());
            }
        }
        return List.copyOf(out);
    }

    private static List<String> affectedAll(State before, State after) {
        MembershipEngine.MembershipResult m = MembershipEngine.at(
                after, after.events().size());
        Set<String> out = new TreeSet<>();
        for (ClusterGen c : m.clusters()) {
            out.add(c.stableId());
        }
        return List.copyOf(out);
    }

    private static List<String> textList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null) {
            node.forEach(n -> out.add(n.asText()));
        }
        return out;
    }
}
