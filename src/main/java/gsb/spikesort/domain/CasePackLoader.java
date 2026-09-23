package gsb.spikesort.domain;

import com.fasterxml.jackson.databind.JsonNode;
import gsb.spikesort.model.Json;
import gsb.spikesort.model.Model.*;
import gsb.spikesort.store.Store;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Replays the bundled offline case pack "脉冲簇谱" through the real ingest
 *  and adjudication services — never by bypassing them — so the resulting
 *  state carries the same provenance as hand-driven work. */
public final class CasePackLoader {
    public static final String RESOURCE = "/casepack/pulse-cluster-spectrum.json";

    private final Store store;
    private final IngestService ingest;
    private final AdjudicationService adjudication;

    public CasePackLoader(Store store) {
        this.store = store;
        this.ingest = new IngestService(store);
        this.adjudication = new AdjudicationService(store);
    }

    public record LoadReport(IngestResult ingest, int artifacts, List<String> decisionEventIds) {}

    public LoadReport load() {
        JsonNode root;
        try (InputStream in = CasePackLoader.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("case pack missing: " + RESOURCE);
            root = Json.MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("cannot read case pack", e);
        }
        IngestResult result = ingest.importMaterials(root.get("materials"));
        if (!result.ok()) throw new IllegalStateException("case pack import failed: " + result.diagnostics());
        var artifacts = adjudication.bootstrapArtifacts();
        List<String> decisionIds = new ArrayList<>();
        for (JsonNode step : root.get("script")) {
            if (!"decide".equals(step.path("op").asText())) continue;
            var r = adjudication.decide(step.get("type").asText(), step.get("payload"));
            if (r.event() != null) decisionIds.add(r.event().id());
        }
        return new LoadReport(result, artifacts.size(), decisionIds);
    }
}
