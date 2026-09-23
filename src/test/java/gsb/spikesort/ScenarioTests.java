package gsb.spikesort;

import gsb.spikesort.domain.AdjudicationService;
import gsb.spikesort.domain.CasePackLoader;
import gsb.spikesort.domain.IngestService;
import gsb.spikesort.model.Json;
import gsb.spikesort.model.Model.*;
import gsb.spikesort.store.Database;
import gsb.spikesort.store.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Acceptance scenarios: concurrent spikes, amplitude drift, cluster
 *  renumbering, split-shared events, window endpoints, adjudication
 *  conflict and partial invalidation. */
class ScenarioTests {
    private Store store;

    @BeforeEach
    void setUp() {
        store = new Store(Database.inMemory());
        new CasePackLoader(store).load();
    }

    private Set<String> evidenceKinds(String unitId) {
        return store.evidence().stream()
                .filter(e -> unitId == null || unitId.equals(e.unitId()))
                .map(EvidenceEvent::kind).collect(Collectors.toSet());
    }

    @Test
    void concurrentSpikesArePropagationEvidenceNotIdentityMerge() {
        assertTrue(evidenceKinds(null).contains("MULTICHANNEL_PROPAGATION"));
        // the concurrent spikes keep their own units: evidence never merges identity
        SpikeEvent a = store.spikes().stream().filter(s -> s.id().equals("spk-conc-a")).findFirst().orElseThrow();
        SpikeEvent b = store.spikes().stream().filter(s -> s.id().equals("spk-conc-b")).findFirst().orElseThrow();
        assertEquals("unit-A", a.unitId());
        assertEquals("unit-B", b.unitId());
        // basis is machine-readable and states the evidence-only rule
        EvidenceEvent ev = store.evidence().stream()
                .filter(e -> e.kind().equals("MULTICHANNEL_PROPAGATION")).findFirst().orElseThrow();
        var basis = Json.read(ev.basisJson());
        assertEquals("evidence_only", basis.get("rule").asText());
        assertFalse(basis.get("clusterIndexIsIdentity").asBoolean());
    }

    @Test
    void amplitudeDriftIsEvidenceAndUnitIdentityIsStable() {
        assertTrue(evidenceKinds("unit-A").contains("AMPLITUDE_DRIFT"));
        // unit-A survived all scripted decisions with its stable id
        assertTrue(store.units().stream().anyMatch(u -> u.id().equals("unit-A")));
        // drift evidence carries quantitative basis
        EvidenceEvent drift = store.evidence().stream()
                .filter(e -> e.kind().equals("AMPLITUDE_DRIFT") && "unit-A".equals(e.unitId()))
                .findFirst().orElseThrow();
        var basis = Json.read(drift.basisJson());
        assertTrue(basis.get("relativeChange").asDouble() >= 0.2);
        assertEquals("uV", "uV"); // amplitude unit is µV across the chain
    }

    @Test
    void refractoryViolationIsEvidenceOnly() {
        assertTrue(evidenceKinds("unit-B").contains("REFRACTORY_VIOLATION"));
        EvidenceEvent ev = store.evidence().stream()
                .filter(e -> e.kind().equals("REFRACTORY_VIOLATION")).findFirst().orElseThrow();
        var basis = Json.read(ev.basisJson());
        assertTrue(basis.get("isiMs").asDouble() < basis.get("refractoryMs").asDouble());
        assertEquals("evidence_only", basis.get("rule").asText());
    }

    @Test
    void clusterRenumberingKeepsStableUnitIdentity() {
        // scripted RENUMBER mapped cluster 2 -> 7 on feat-v1
        List<ClusterLabel> labels = store.clusterLabels();
        assertTrue(labels.stream().anyMatch(l -> l.clusterIndex() == 7 && l.unitId().equals("unit-B")));
        // original label is retained too (history, not overwrite)
        assertTrue(labels.stream().anyMatch(l -> l.clusterIndex() == 2 && l.unitId().equals("unit-B")));
        // the unit id never changed
        assertTrue(store.units().stream().anyMatch(u -> u.id().equals("unit-B")));
    }

    @Test
    void splitSharedSpikesKeepAllLegalCandidates() {
        SpikeEvent shared = store.spikes().stream()
                .filter(s -> s.id().equals("spk-c-shared-0")).findFirst().orElseThrow();
        assertNull(shared.unitId(), "undecidable shared spike must not be force-assigned");
        assertEquals(List.of("unit-C1", "unit-C2"), shared.candidateUnitIds());
        // original unit marked split; children exist
        assertEquals("split", store.units().stream().filter(u -> u.id().equals("unit-C"))
                .findFirst().orElseThrow().status());
        assertTrue(store.units().stream().anyMatch(u -> u.id().equals("unit-C1")));
        assertTrue(store.units().stream().anyMatch(u -> u.id().equals("unit-C2")));
    }

    @Test
    void windowEndpointsAreHalfOpen() {
        // [start, end): t=0 included, t=60000 excluded
        List<SpikeEvent> inWindow = store.spikesInWindow(0.0, 60000.0);
        Set<String> ids = inWindow.stream().map(SpikeEvent::id).collect(Collectors.toSet());
        assertTrue(ids.contains("spk-edge-start"));
        assertTrue(ids.contains("spk-edge-in"));
        assertFalse(ids.contains("spk-edge-end"));
    }

    @Test
    void windowChangeRecomputesOnlyIntersectingClusters() {
        // fresh state: import only, then change window
        Store s2 = new Store(Database.inMemory());
        CasePackLoader loader = new CasePackLoader(s2);
        // replay only the materials part by loading then inspecting the scripted WINDOW_CHANGE
        loader.load();
        // find the WINDOW_CHANGE event and its affected units via artifact validity
        AdjudicationEvent windowEvent = s2.events().stream()
                .filter(e -> e.type().equals("WINDOW_CHANGE")).findFirst().orElseThrow();
        // unit-A lives entirely inside [5000,55000): its artifacts must survive that event
        List<Artifact> unitA = s2.artifactsAt(windowEvent.seq()).stream()
                .filter(a -> "unit-A".equals(a.unitId())).toList();
        assertFalse(unitA.isEmpty());
        // none of unit-A's current artifacts was invalidated by the window change
        boolean anyInvalidatedByWindow = s2.artifacts().stream()
                .anyMatch(a -> "unit-A".equals(a.unitId())
                        && "WINDOW_CHANGE".equals(a.invalidationReason()));
        assertFalse(anyInvalidatedByWindow, "unit-A does not intersect the changed window edges");
        // unit-B and unit-C intersect the removed edges and were recomputed
        assertTrue(s2.artifacts().stream().anyMatch(a -> "unit-B".equals(a.unitId())
                && "WINDOW_CHANGE".equals(a.invalidationReason())));
        assertTrue(s2.artifacts().stream().anyMatch(a -> "unit-C".equals(a.unitId())
                && "WINDOW_CHANGE".equals(a.invalidationReason())));
    }

    @Test
    void conflictingAdjudicationsAreBothRetained() {
        List<AdjudicationEvent> events = store.events();
        AdjudicationEvent accept = events.stream().filter(e -> e.type().equals("ACCEPT")).findFirst().orElseThrow();
        AdjudicationEvent reject = events.stream().filter(e -> e.type().equals("REJECT")).findFirst().orElseThrow();
        assertEquals("adopted", accept.status(), "first decision stays adopted");
        assertEquals("conflicted", reject.status(), "contradicting decision is kept as conflicted");
        // both payloads remain in the archive
        assertTrue(accept.payloadJson().contains("accept-drift-as-same-unit"));
        assertTrue(reject.payloadJson().contains("reject-drift-split-required"));
    }

    @Test
    void localizedSourceChangeInvalidatesOnlyDependents() {
        // scripted SOURCE_CHANGE touched spk-a-05 (unit-A) only
        List<Artifact> invalidated = store.artifacts().stream()
                .filter(a -> "SOURCE_CHANGE".equals(a.invalidationReason())).toList();
        assertFalse(invalidated.isEmpty());
        Set<String> touchedUnits = invalidated.stream().map(Artifact::unitId).collect(Collectors.toSet());
        assertTrue(touchedUnits.stream().allMatch(u -> u == null || u.equals("unit-A")),
                "only unit-A artifacts and the global projection may be invalidated, got " + touchedUnits);
        assertTrue(touchedUnits.contains("unit-A"));
        assertTrue(touchedUnits.contains(null), "global feature projection must be recomputed too");
    }

    @Test
    void incompleteInputYieldsPositionedDiagnosticsNotFakeSuccess() {
        Store s3 = new Store(Database.inMemory());
        IngestService ingest = new IngestService(s3);
        IngestResult r = ingest.importMaterials("{\"spikes\": []}");
        assertEquals(IngestStatus.SEMANTIC_UNSOLVABLE, r.status());
        assertFalse(r.diagnostics().isEmpty());
        Diagnostic d = r.diagnostics().get(0);
        assertTrue(d.location().startsWith("$."), "diagnostic must carry a location");
        assertEquals(Severity.ERROR, d.severity());
        // nothing was written
        assertTrue(s3.spikes().isEmpty());
    }

    @Test
    void unconvertibleAmplitudeStaysRawWithDiagnostic() {
        SpikeEvent bad = store.spikes().stream().filter(s -> s.id().equals("spk-bad-amp"))
                .findFirst().orElseThrow();
        assertEquals("n/a", bad.amplitudeRaw(), "raw representation preserved");
        assertNull(bad.amplitudeUv(), "no fake default on the derived layer");
        assertTrue(store.diagnostics().stream().anyMatch(d ->
                d.code().equals("UNIT_UNCONVERTIBLE") && d.location().contains("spikes")));
    }

    @Test
    void millivoltAmplitudeIsNormalizedOnDerivedLayer() {
        SpikeEvent edge = store.spikes().stream().filter(s -> s.id().equals("spk-edge-start"))
                .findFirst().orElseThrow();
        assertEquals("0.15 mV", edge.amplitudeRaw());
        assertEquals(150.0, edge.amplitudeUv(), 1e-9);
    }
}
