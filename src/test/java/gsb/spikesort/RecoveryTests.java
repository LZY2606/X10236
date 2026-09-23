package gsb.spikesort;

import gsb.spikesort.domain.CasePackLoader;
import gsb.spikesort.domain.ExportImportService;
import gsb.spikesort.domain.IngestService;
import gsb.spikesort.model.Json;
import gsb.spikesort.model.Model.*;
import gsb.spikesort.store.Database;
import gsb.spikesort.store.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Half-write recovery and the three distinct failure results:
 *  BUDGET_EXHAUSTED, FORMAT_CORRUPT, SEMANTIC_UNSOLVABLE. */
class RecoveryTests {

    @TempDir Path tmp;

    private Store loadedStore() {
        Store s = new Store(Database.inMemory());
        new CasePackLoader(s).load();
        return s;
    }

    @Test
    void truncatedImportLeavesOriginalDatabaseIntact() {
        Store store = loadedStore();
        String exported = new ExportImportService(store).exportJson();
        int spikeCount = store.spikes().size();
        int eventCount = store.events().size();

        // simulate a half-written import body (truncated mid-stream)
        String truncated = exported.substring(0, exported.length() / 2);
        IngestResult r = new ExportImportService(store).importJson(truncated);
        assertEquals(IngestStatus.FORMAT_CORRUPT, r.status());
        assertEquals(spikeCount, store.spikes().size(), "original data untouched");
        assertEquals(eventCount, store.events().size());
    }

    @Test
    void tamperedDigestIsRejectedBeforeWriting() {
        Store store = loadedStore();
        String exported = new ExportImportService(store).exportJson();
        var root = Json.read(exported);
        // flip a spike id inside tables without updating the digest
        var spikes = (com.fasterxml.jackson.databind.node.ArrayNode) root.path("tables").path("spike_events");
        ((com.fasterxml.jackson.databind.node.ObjectNode) spikes.get(0)).put("id", "tampered");
        IngestResult r = new ExportImportService(store).importJson(Json.write(root));
        assertEquals(IngestStatus.FORMAT_CORRUPT, r.status());
        assertTrue(store.spikes().stream().noneMatch(s -> s.id().equals("tampered")));
    }

    @Test
    void unsupportedVersionIsSemanticFailureNotFormatFailure() {
        Store store = loadedStore();
        String exported = new ExportImportService(store).exportJson();
        var root = Json.read(exported);
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("manifest")).put("formatVersion", 99);
        IngestResult r = new ExportImportService(store).importJson(Json.write(root));
        assertEquals(IngestStatus.SEMANTIC_UNSOLVABLE, r.status());
    }

    @Test
    void budgetExhaustedIsDistinctFromCorruptAndUnsolvable() {
        Store store = new Store(Database.inMemory());
        IngestService ingest = new IngestService(store, 2); // tiny budget
        String doc = """
        {"probeGeometry":{"sites":[{"channel":0,"xUm":0,"yUm":0}]},
         "detectionParams":{"thresholdUv":50,"refractoryMs":1,"windowStartMs":0,"windowEndMs":100},
         "featureSet":{"version":1},
         "spikes":[
           {"id":"s1","timeMs":1,"channel":0,"amplitude":"10 µV"},
           {"id":"s2","timeMs":2,"channel":0,"amplitude":"11 µV"},
           {"id":"s3","timeMs":3,"channel":0,"amplitude":"12 µV"}]}
        """;
        IngestResult r = ingest.importMaterials(doc);
        assertEquals(IngestStatus.BUDGET_EXHAUSTED, r.status());
        assertTrue(store.spikes().isEmpty(), "budget failure writes nothing");
    }

    @Test
    void corruptFormatIsDistinctResult() {
        Store store = new Store(Database.inMemory());
        IngestResult r = new IngestService(store).importMaterials("{not json");
        assertEquals(IngestStatus.FORMAT_CORRUPT, r.status());
    }

    @Test
    void invertedWindowIsSemanticFailure() {
        Store store = new Store(Database.inMemory());
        String doc = """
        {"probeGeometry":{"sites":[{"channel":0,"xUm":0,"yUm":0}]},
         "detectionParams":{"thresholdUv":50,"refractoryMs":1,"windowStartMs":100,"windowEndMs":0},
         "featureSet":{"version":1}, "spikes":[]}
        """;
        IngestResult r = new IngestService(store).importMaterials(doc);
        assertEquals(IngestStatus.SEMANTIC_UNSOLVABLE, r.status());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("WINDOW_INVERTED")));
    }

    @Test
    void atomicExportSurvivesCrashMidWrite() throws Exception {
        Store store = loadedStore();
        Path target = tmp.resolve("export.json");
        // simulate a crashed previous export: stale tmp file present
        Files.writeString(tmp.resolve("export.json.tmp"), "{\"partial\":");
        new ExportImportService(store).exportToFile(target);
        // stale tmp must not clobber the good export
        String content = Files.readString(target);
        assertEquals("pulse-cluster-spectrum-export",
                Json.read(content).path("manifest").path("format").asText());
        // and the exported file imports cleanly
        Store target2 = new Store(Database.inMemory());
        IngestResult r = new ExportImportService(target2).importJson(content);
        assertEquals(IngestStatus.OK, r.status());
        assertEquals(store.spikes().size(), target2.spikes().size());
    }

    private static int spikeCount(Store s) { return s.spikes().size(); }

    @Test
    void failedImportRollsBackWholeTransaction() {
        Store store = loadedStore();
        int unitsBefore = store.units().size();
        String exported = new ExportImportService(store).exportJson();
        var root = Json.read(exported);
        // corrupt one row semantically (duplicate primary key) but keep digest honest
        var spikes = (com.fasterxml.jackson.databind.node.ArrayNode) root.path("tables").path("spike_events");
        String dupId = spikes.get(0).path("id").asText();
        ((com.fasterxml.jackson.databind.node.ObjectNode) spikes.get(1)).put("id", dupId);
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("manifest"))
                .put("digest", Json.digestOf(Json.canonical(root.path("tables"))));
        IngestResult r = new ExportImportService(store).importJson(Json.write(root));
        assertEquals(IngestStatus.SEMANTIC_UNSOLVABLE, r.status());
        assertEquals(unitsBefore, store.units().size(), "rollback keeps original rows");
        assertEquals(spikeCount(store), store.spikes().size(), "rollback keeps original rows");
    }
}
