package gsb.spikesort;

import gsb.spikesort.domain.AdjudicationService;
import gsb.spikesort.domain.CasePackLoader;
import gsb.spikesort.domain.IngestService;
import gsb.spikesort.model.Json;
import gsb.spikesort.model.Model.*;
import gsb.spikesort.store.Database;
import gsb.spikesort.store.Store;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Re-entering any old event must restore the dependency versions and
 *  visible conclusions of that moment — history is never overwritten by
 *  the latest computation. */
class TimeTravelTests {

    @Test
    void oldEventRestoresContemporaryArtifacts() {
        Store store = new Store(Database.inMemory());
        new CasePackLoader(store).load();
        List<AdjudicationEvent> events = store.events();
        AdjudicationEvent windowChange = events.stream()
                .filter(e -> e.type().equals("WINDOW_CHANGE")).findFirst().orElseThrow();

        // before the window change, unit-B artifacts existed with the old param version
        List<Artifact> before = store.artifactsAt(windowChange.seq() - 1).stream()
                .filter(a -> "unit-B".equals(a.unitId())).toList();
        List<Artifact> after = store.artifactsAt(windowChange.seq()).stream()
                .filter(a -> "unit-B".equals(a.unitId())).toList();
        assertFalse(before.isEmpty());
        assertFalse(after.isEmpty());
        assertNotEquals(before.stream().map(Artifact::id).toList(),
                after.stream().map(Artifact::id).toList(),
                "window change recomputed unit-B; old versions remain addressable");
        // old artifacts carry their invalidation reason instead of being deleted
        assertTrue(store.artifacts().stream().anyMatch(a ->
                "unit-B".equals(a.unitId()) && a.invalidationReason() != null));
    }

    @Test
    void eventLogIsAppendOnlyAndOrdered() {
        Store store = new Store(Database.inMemory());
        new CasePackLoader(store).load();
        List<AdjudicationEvent> events = store.events();
        for (int i = 1; i < events.size(); i++) {
            assertEquals(events.get(i - 1).seq() + 1, events.get(i).seq());
            assertEquals(events.get(i - 1).id(), events.get(i).parentId(),
                    "each event chains to its parent");
        }
    }

    @Test
    void decisionReturnsAffectedDownstreamRecords() {
        Store store = new Store(Database.inMemory());
        IngestService ingest = new IngestService(store);
        // minimal two-unit dataset
        String doc = """
        {"probeGeometry":{"sites":[{"channel":0,"xUm":0,"yUm":0},{"channel":1,"xUm":0,"yUm":40}]},
         "detectionParams":{"thresholdUv":50,"refractoryMs":1,"windowStartMs":0,"windowEndMs":10000},
         "featureSet":{"version":1},
         "units":[{"id":"u1"},{"id":"u2"}],
         "clusterLabels":[{"id":"l1","featureSetId":"feat-v1","clusterIndex":1,"unitId":"u1"}],
         "waveformRefs":[{"id":"w1","channel":0,"tStartMs":0,"tEndMs":1}],
         "spikes":[
           {"id":"s1","timeMs":100,"channel":0,"amplitude":"80 µV","unitId":"u1","waveformRefId":"w1"},
           {"id":"s2","timeMs":200,"channel":1,"amplitude":"90 µV","unitId":"u2"}]}
        """;
        assertTrue(ingest.importMaterials(doc).ok());
        AdjudicationService adj = new AdjudicationService(store);
        adj.bootstrapArtifacts();
        var result = adj.decide("WINDOW_CHANGE",
                Json.read("{\"windowStartMs\":150,\"windowEndMs\":10000}"));
        assertNotNull(result.event());
        // only u1 has a spike inside the removed edge [0,150)
        assertEquals(List.of("u1"), result.affectedUnitIds());
        assertFalse(result.affectedArtifactIds().isEmpty(),
                "adjudication must surface affected downstream records");
    }
}
