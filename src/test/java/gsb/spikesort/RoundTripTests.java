package gsb.spikesort;

import gsb.spikesort.domain.CasePackLoader;
import gsb.spikesort.domain.ExportImportService;
import gsb.spikesort.model.Json;
import gsb.spikesort.model.Model.*;
import gsb.spikesort.store.Database;
import gsb.spikesort.store.Store;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Export → delete local database → import must preserve identity, order
 *  and provenance relations. */
class RoundTripTests {

    @Test
    void exportImportRoundTripPreservesIdentityOrderAndProvenance() {
        Store source = new Store(Database.inMemory());
        new CasePackLoader(source).load();
        ExportImportService exporter = new ExportImportService(source);
        String exported = exporter.exportJson();

        // "delete the local database": a brand-new empty store
        Store target = new Store(Database.inMemory());
        IngestResult r = new ExportImportService(target).importJson(exported);
        assertEquals(IngestStatus.OK, r.status());

        // identity: units, events, artifacts keep their ids
        assertEquals(source.units().stream().map(Unit::id).toList(),
                target.units().stream().map(Unit::id).toList());
        assertEquals(source.events().stream().map(AdjudicationEvent::id).toList(),
                target.events().stream().map(AdjudicationEvent::id).toList());
        assertEquals(source.artifacts().stream().map(Artifact::id).toList(),
                target.artifacts().stream().map(Artifact::id).toList());

        // order: spike ordering is deterministic and identical
        assertEquals(source.spikes().stream().map(SpikeEvent::id).toList(),
                target.spikes().stream().map(SpikeEvent::id).toList());

        // provenance: parents, digests, validity ranges survive
        Artifact srcArt = source.artifacts().get(0);
        Artifact dstArt = target.artifacts().get(0);
        assertEquals(srcArt.inputDigest(), dstArt.inputDigest());
        assertEquals(srcArt.parents(), dstArt.parents());
        assertEquals(srcArt.validFromSeq(), dstArt.validFromSeq());
        assertEquals(srcArt.validToSeq(), dstArt.validToSeq());
        assertEquals(srcArt.invalidationReason(), dstArt.invalidationReason());

        // branches & decision history: statuses identical
        assertEquals(source.events().stream().map(AdjudicationEvent::status).toList(),
                target.events().stream().map(AdjudicationEvent::status).toList());

        // re-export is byte-identical in table content (digest matches)
        String reExported = new ExportImportService(target).exportJson();
        String digest1 = Json.read(exported).path("manifest").path("digest").asText();
        String digest2 = Json.read(reExported).path("manifest").path("digest").asText();
        assertEquals(digest1, digest2);
    }

    @Test
    void equivalentInputsProduceStableOrderAndIdenticalArtifactIds() {
        Store a = new Store(Database.inMemory());
        Store b = new Store(Database.inMemory());
        new CasePackLoader(a).load();
        new CasePackLoader(b).load();
        List<String> spikesA = a.spikes().stream().map(SpikeEvent::id).toList();
        List<String> spikesB = b.spikes().stream().map(SpikeEvent::id).toList();
        assertEquals(spikesA, spikesB, "equivalent input must yield stable order");
        assertEquals(a.artifacts().stream().map(Artifact::id).toList(),
                b.artifacts().stream().map(Artifact::id).toList());
        assertEquals(a.events().stream().map(AdjudicationEvent::id).toList(),
                b.events().stream().map(AdjudicationEvent::id).toList());
    }
}
