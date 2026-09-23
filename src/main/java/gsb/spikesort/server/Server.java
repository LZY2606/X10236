package gsb.spikesort.server;

import com.fasterxml.jackson.databind.JsonNode;
import gsb.spikesort.domain.AdjudicationService;
import gsb.spikesort.domain.CasePackLoader;
import gsb.spikesort.domain.ExportImportService;
import gsb.spikesort.model.Json;
import gsb.spikesort.model.Model.*;
import gsb.spikesort.store.Store;
import io.javalin.Javalin;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** HTTP layer. Thin: every rule lives in the domain services so tests can
 *  call them directly without a browser. */
public final class Server {
    private final Store store;
    private final AdjudicationService adjudication;
    private final ExportImportService exportImport;
    private final SvgRenderer svg;
    private final Runnable reset;

    public Server(Store store, Runnable reset) {
        this.store = store;
        this.adjudication = new AdjudicationService(store);
        this.exportImport = new ExportImportService(store);
        this.svg = new SvgRenderer(store);
        this.reset = reset;
    }

    public Javalin create() {
        Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false);

        app.get("/", ctx -> ctx.html(PageRenderer.HTML));

        app.get("/api/state", ctx -> {
            Long seq = ctx.queryParam("seq") == null ? null : Long.parseLong(ctx.queryParam("seq"));
            json(ctx, stateJson(seq));
        });

        app.get("/svg/waveforms", ctx -> ctx.result(svg.waveforms(seqOf(ctx.queryParam("seq")))).contentType("image/svg+xml"));
        app.get("/svg/features",  ctx -> ctx.result(svg.featureProjection(seqOf(ctx.queryParam("seq")))).contentType("image/svg+xml"));
        app.get("/svg/acg",   ctx -> ctx.result(svg.acg(ctx.queryParam("unit"), seqOf(ctx.queryParam("seq")))).contentType("image/svg+xml"));
        app.get("/svg/rate",  ctx -> ctx.result(svg.firingRate(ctx.queryParam("unit"), seqOf(ctx.queryParam("seq")))).contentType("image/svg+xml"));
        app.get("/svg/drift", ctx -> ctx.result(svg.drift(ctx.queryParam("unit"), seqOf(ctx.queryParam("seq")))).contentType("image/svg+xml"));

        app.post("/api/decide", ctx -> {
            JsonNode body = Json.read(ctx.body());
            var result = adjudication.decide(body.get("type").asText(), body.get("payload"));
            json(ctx, decisionJson(result));
        });

        app.post("/api/window", ctx -> {
            JsonNode body = Json.read(ctx.body());
            var result = adjudication.decide("WINDOW_CHANGE", body);
            json(ctx, decisionJson(result));
        });

        app.get("/api/export", ctx -> ctx.result(exportImport.exportJson()).contentType("application/json"));

        app.post("/api/import", ctx -> {
            IngestResult r = exportImport.importJson(ctx.body());
            json(ctx, Json.MAPPER.valueToTree(r).toString());
        });

        app.post("/api/reset", ctx -> {
            reset.run();
            json(ctx, "{\"status\":\"OK\"}");
        });

        return app;
    }

    private static void json(io.javalin.http.Context ctx, String body) {
        ctx.result(body).contentType("application/json");
    }

    private static long seqOf(String seq) {
        if (seq != null) return Long.parseLong(seq);
        return Long.MAX_VALUE; // latest
    }

    private String stateJson(Long seq) {
        long view = seq == null ? store.nextEventSeq() - 1 : seq;
        Map<String, Object> out = new TreeMap<>();
        out.put("viewSeq", seq);
        out.put("events", store.eventsUpTo(view));
        out.put("units", store.units());
        out.put("clusterLabels", store.clusterLabels());
        out.put("evidence", store.evidence());
        out.put("diagnostics", store.diagnostics());
        out.put("detectionParams", store.detectionParams());
        List<Map<String, Object>> arts = new java.util.ArrayList<>();
        for (Artifact a : store.artifactsAt(view)) {
            Map<String, Object> m = new TreeMap<>();
            m.put("id", a.id());
            m.put("kind", a.kind());
            m.put("unitId", a.unitId());
            m.put("inputDigest", a.inputDigest());
            m.put("paramVersion", a.paramVersion());
            m.put("parents", a.parents());
            m.put("validFromSeq", a.validFromSeq());
            m.put("validToSeq", a.validToSeq());
            m.put("status", a.status());
            m.put("invalidationReason", a.invalidationReason());
            arts.add(m);
        }
        out.put("artifacts", arts);
        return Json.write(out);
    }

    private static String decisionJson(AdjudicationService.DecisionResult r) {
        Map<String, Object> m = new TreeMap<>();
        m.put("event", r.event());
        m.put("affectedArtifactIds", r.affectedArtifactIds());
        m.put("affectedUnitIds", r.affectedUnitIds());
        m.put("diagnostics", r.diagnostics());
        return Json.write(m);
    }
}
