package gsb.spikesort.domain;

import com.fasterxml.jackson.databind.JsonNode;
import gsb.spikesort.model.Json;
import gsb.spikesort.model.Model.*;
import gsb.spikesort.store.Store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Append-only adjudication log + partial invalidation engine.
 *
 *  Rules encoded here:
 *  - refractory violations, amplitude drift and multi-channel propagation are
 *    evidence only; cluster indices are never treated as stable identity;
 *  - changing the time window recomputes only the intersecting clusters;
 *  - when a split cannot uniquely assign a spike, all legal candidate units
 *    are kept on the spike;
 *  - conflicting human decisions are both retained, one adopted, one
 *    conflicted — never silently overwritten. */
public final class AdjudicationService {
    private final Store store;
    private final ArtifactComputer computer;
    private final EvidenceEngine evidence;

    public AdjudicationService(Store store) {
        this.store = store;
        this.computer = new ArtifactComputer(store);
        this.evidence = new EvidenceEngine(store);
    }

    public record DecisionResult(AdjudicationEvent event,
                                 List<String> affectedArtifactIds,
                                 List<String> affectedUnitIds,
                                 List<Diagnostic> diagnostics) {}

    // ------------------------------------------------------------------
    public DecisionResult decide(String type, JsonNode payload) {
        return decide(type, payload, "main");
    }

    public DecisionResult decide(String type, JsonNode payload, String branch) {
        long seq = store.nextEventSeq();
        String id = "evt-" + Json.digestOf(seq + "|" + type + "|" + Json.canonical(payload)).substring(0, 16);
        String parentId = store.events().isEmpty() ? null
                : store.events().get(store.events().size() - 1).id();
        AdjudicationEvent event = new AdjudicationEvent(id, seq, type, branch,
                Json.write(payload), parentId, "adopted", Instant.now().toString());

        List<Diagnostic> diags = new ArrayList<>();
        Set<String> affectedUnits = new LinkedHashSet<>();
        Set<String> affectedArtifacts = new LinkedHashSet<>();

        store.begin();
        try {
            switch (type) {
                case "MERGE" -> applyMerge(payload, seq, id, affectedUnits);
                case "SPLIT" -> applySplit(payload, seq, id, affectedUnits);
                case "RENUMBER" -> applyRenumber(payload, id);
                case "WINDOW_CHANGE" -> applyWindowChange(payload, seq, affectedUnits);
                case "SOURCE_CHANGE" -> applySourceChange(payload, seq, affectedUnits);
                case "PARAM_REVISION" -> applyParamRevision(payload, seq, affectedUnits);
                case "ACCEPT", "REJECT", "NOTE" -> { /* decision recorded; no recompute */ }
                case "CONFLICT" -> applyConflict(payload, id);
                default -> throw new IllegalArgumentException("unknown decision type: " + type);
            }

            // conflict detection: a decision carrying a conflictKey that
            // contradicts an existing adopted decision is kept but conflicted
            String status = resolveConflictStatus(event, payload);
            event = new AdjudicationEvent(id, seq, type, branch, Json.write(payload),
                    parentId, status, event.createdAt());
            store.insertEvent(event);

            // invalidate + recompute only what the decision touched
            if (!affectedUnits.isEmpty()) {
                affectedArtifacts.addAll(invalidateAndRecompute(affectedUnits, seq, type));
            }
            // evidence is re-evaluated against the new state and stored as
            // machine-readable events tied to this decision
            for (EvidenceEvent ev : evidence.evaluate(id)) store.insertEvidence(ev);

            store.commit();
        } catch (Exception e) {
            store.rollbackQuietly();
            diags.add(new Diagnostic("diag-decision", "adjudicate", "$." + type,
                    Severity.ERROR, "SEMANTIC_UNSOLVABLE", e.getMessage(), Json.write(payload)));
            return new DecisionResult(null, List.of(), List.of(), diags);
        }
        return new DecisionResult(event, List.copyOf(affectedArtifacts),
                List.copyOf(affectedUnits), diags);
    }

    // ------------------------------------------------------------------
    private void applyMerge(JsonNode p, long seq, String eventId, Set<String> affected) {
        String toUnit = p.get("toUnitId").asText();
        store.insertUnit(new Unit(toUnit, eventId, "active"));
        for (JsonNode u : p.get("fromUnitIds")) {
            String from = u.asText();
            for (SpikeEvent s : store.spikesOfUnit(from)) {
                store.updateSpikeUnit(s.id(), toUnit, List.of(toUnit));
            }
            store.updateUnitStatus(from, "merged");
            affected.add(from);
        }
        affected.add(toUnit);
    }

    private void applySplit(JsonNode p, long seq, String eventId, Set<String> affected) {
        String from = p.get("unitId").asText();
        List<String> into = new ArrayList<>();
        for (JsonNode u : p.get("intoUnitIds")) {
            into.add(u.asText());
            store.insertUnit(new Unit(u.asText(), eventId, "active"));
        }
        Set<String> shared = new TreeSet<>();
        for (JsonNode s : p.path("sharedSpikeIds")) shared.add(s.asText());
        // explicit assignments: spikeId -> unitId
        JsonNode assign = p.path("assignments");
        for (SpikeEvent s : store.spikesOfUnit(from)) {
            if (shared.contains(s.id())) {
                // not uniquely decidable: keep ALL legal candidates, no owner
                store.updateSpikeUnit(s.id(), null, into);
            } else if (assign.has(s.id())) {
                String to = assign.get(s.id()).asText();
                store.updateSpikeUnit(s.id(), to, List.of(to));
            } else {
                // default stays with first target only when explicitly allowed
                store.updateSpikeUnit(s.id(), null, into);
            }
        }
        store.updateUnitStatus(from, "split");
        affected.add(from);
        affected.addAll(into);
    }

    private void applyRenumber(JsonNode p, String eventId) {
        // cluster indices change; unit ids (stable identity) do not
        String fsId = p.get("featureSetId").asText();
        JsonNode mapping = p.get("mapping"); // {"2": 5, ...} clusterIndex -> newIndex
        for (ClusterLabel l : store.clusterLabels()) {
            if (!l.featureSetId().equals(fsId)) continue;
            JsonNode newIdx = mapping.get(String.valueOf(l.clusterIndex()));
            if (newIdx == null) continue;
            store.insertClusterLabel(new ClusterLabel(
                    l.id() + "-rn" + newIdx.asInt(), fsId, newIdx.asInt(),
                    l.unitId(), eventId));
        }
    }

    private void applyWindowChange(JsonNode p, long seq, Set<String> affected) {
        DetectionParams old = store.latestDetectionParams().orElseThrow();
        double ns = p.get("windowStartMs").asDouble();
        double ne = p.get("windowEndMs").asDouble();
        if (!(ne > ns)) throw new IllegalArgumentException("window end must be after start");
        store.insertDetectionParams(new DetectionParams(
                "detparam-v" + (old.version() + 1), old.version() + 1,
                old.thresholdUv(), old.refractoryMs(), ns, ne,
                Json.digestOf("window|" + ns + "|" + ne + "|" + old.digest())));
        // only clusters intersecting the changed edge regions are recomputed
        double os = old.windowStartMs(), oe = old.windowEndMs();
        Set<String> touched = new TreeSet<>();
        for (SpikeEvent s : store.spikes()) {
            boolean inOld = s.timeMs() >= os && s.timeMs() < oe;
            boolean inNew = s.timeMs() >= ns && s.timeMs() < ne;
            if (inOld != inNew && s.unitId() != null) touched.add(s.unitId());
        }
        affected.addAll(touched);
    }

    private void applySourceChange(JsonNode p, long seq, Set<String> affected) {
        // localized source-data change: only units whose spikes changed are affected
        for (JsonNode upd : p.get("spikeUpdates")) {
            String spikeId = upd.get("id").asText();
            SpikeEvent before = store.spikes().stream()
                    .filter(s -> s.id().equals(spikeId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown spike " + spikeId));
            String ampRaw = upd.has("amplitude") ? upd.get("amplitude").asText() : before.amplitudeRaw();
            Double ampUv = IngestService.normalizeAmplitudeToUv(ampRaw);
            store.updateSpikeAmplitude(spikeId, ampRaw, ampUv);
            if (before.unitId() != null) affected.add(before.unitId());
        }
    }

    private void applyParamRevision(JsonNode p, long seq, Set<String> affected) {
        DetectionParams old = store.latestDetectionParams().orElseThrow();
        store.insertDetectionParams(new DetectionParams(
                "detparam-v" + (old.version() + 1), old.version() + 1,
                p.path("thresholdUv").asDouble(old.thresholdUv()),
                p.path("refractoryMs").asDouble(old.refractoryMs()),
                old.windowStartMs(), old.windowEndMs(),
                Json.digestOf("params|" + p + "|" + old.digest())));
        // parameter revision changes detection semantics globally
        store.units().forEach(u -> affected.add(u.id()));
    }

    private void applyConflict(JsonNode p, String eventId) {
        String target = p.get("conflictWithEventId").asText();
        store.updateEventStatus(target, "conflicted");
    }

    /** Both conflicting decisions are retained: the earlier adopted one keeps
     *  its status; the later contradicting one is stored as "conflicted"
     *  (the non-adopted branch of the archive). */
    private String resolveConflictStatus(AdjudicationEvent event, JsonNode payload) {
        if (!payload.hasNonNull("conflictKey")) return "adopted";
        String key = payload.get("conflictKey").asText();
        for (AdjudicationEvent e : store.events()) {
            JsonNode pl = Json.read(e.payloadJson());
            if (pl.hasNonNull("conflictKey") && pl.get("conflictKey").asText().equals(key)
                    && !pl.equals(payload) && "adopted".equals(e.status())) {
                return "conflicted";
            }
        }
        return "adopted";
    }

    /** Invalidate current artifacts of the affected units (+ the global
     *  feature projection) and recompute exactly those. */
    private Set<String> invalidateAndRecompute(Set<String> unitIds, long seq, String reason) {
        Set<String> invalidated = new LinkedHashSet<>();
        for (Artifact a : store.currentArtifacts()) {
            boolean globalProjection = a.unitId() == null;
            boolean owned = a.unitId() != null && unitIds.contains(a.unitId());
            if (globalProjection || owned) {
                store.invalidateArtifact(a.id(), seq, reason);
                invalidated.add(a.id());
            }
        }
        store.insertArtifact(computer.featureProjection(seq));
        for (String u : unitIds) {
            boolean exists = store.units().stream().anyMatch(x -> x.id().equals(u)
                    && "active".equals(x.status()));
            if (!exists) continue;
            for (Artifact a : computer.computeForUnit(u, seq)) store.insertArtifact(a);
        }
        return invalidated;
    }

    /** Initial computation right after import. */
    public List<Artifact> bootstrapArtifacts() {
        long seq = store.nextEventSeq();
        List<Artifact> all = computer.computeAll(store.units(), seq);
        store.begin();
        try {
            for (Artifact a : all) store.insertArtifact(a);
            store.commit();
        } catch (Exception e) {
            store.rollbackQuietly();
            throw e;
        }
        return all;
    }
}
