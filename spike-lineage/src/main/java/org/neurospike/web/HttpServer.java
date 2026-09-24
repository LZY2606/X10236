package org.neurospike.web;

import io.javalin.Javalin;
import io.javalin.http.ContentType;
import org.neurospike.adjudicate.AdjudicationService;
import org.neurospike.adjudicate.HistoryService;
import org.neurospike.engine.DerivationEngine;
import org.neurospike.export.ProjectArchive;
import org.neurospike.store.ProjectStore;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Javalin 路由：业务规则调用服务端服务，页面只负责展示与联动。 */
public final class HttpServer {
    private final ProjectStore store;
    private final SvgRenderer svg;

    public HttpServer(ProjectStore store) {
        this.store = store;
        this.svg = new SvgRenderer(store);
    }

    public Javalin start(String host, int port) {
        Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false);
        AdjudicationService svc = new AdjudicationService(store);
        HistoryService history = new HistoryService(store);

        app.get("/", ctx -> ctx.redirect("/review"));
        app.get("/review", ctx -> ctx.contentType(ContentType.HTML)
                .result(getClass().getResourceAsStream("/web/review.html").readAllBytes()));

        app.get("/api/state", ctx -> ctx.json(new AppState(store).full()));

        app.post("/api/adjudicate", ctx -> {
            var body = ctx.bodyAsClass(java.util.Map.class);
            String verdict = String.valueOf(body.get("verdict"));
            String nodeId = String.valueOf(body.get("nodeId"));
            String comment = body.get("comment") == null ? "" : String.valueOf(body.get("comment"));
            AdjudicationService.Outcome o = svc.decide(verdict, nodeId, comment);
            ctx.json(outcomeJson(o));
        });

        app.post("/api/window", ctx -> {
            var body = ctx.bodyAsClass(java.util.Map.class);
            double s = Double.parseDouble(String.valueOf(body.get("start")));
            double e = Double.parseDouble(String.valueOf(body.get("end")));
            ctx.json(outcomeJson(svc.setWindow(s, e)));
        });

        app.post("/api/threshold", ctx -> {
            int v = Integer.parseInt(String.valueOf(ctx.bodyAsClass(java.util.Map.class).get("version")));
            ctx.json(outcomeJson(svc.setThresholdVersion(v)));
        });

        app.post("/api/labels", ctx -> {
            String id = String.valueOf(ctx.bodyAsClass(java.util.Map.class).get("labelSetId"));
            ctx.json(outcomeJson(svc.activateLabelSet(id)));
        });

        app.post("/api/patch", ctx -> {
            var body = ctx.bodyAsClass(java.util.Map.class);
            ctx.json(outcomeJson(svc.patchEventAmplitude(
                    String.valueOf(body.get("eventId")),
                    String.valueOf(body.get("amplitude")),
                    String.valueOf(body.getOrDefault("unit", "uV")))));
        });

        app.post("/api/assign", ctx -> {
            var body = ctx.bodyAsClass(java.util.Map.class);
            ctx.json(outcomeJson(svc.assignEvent(
                    String.valueOf(body.get("eventId")),
                    String.valueOf(body.get("clusterKey")),
                    String.valueOf(body.getOrDefault("labelSetId",
                            store.decision().meta("active_label_set"))))));
        });

        app.get("/api/history/{seq}", ctx ->
                ctx.json(history.snapshotAt(Long.parseLong(ctx.pathParam("seq")))));

        app.get("/api/recompute", ctx -> {
            DerivationEngine.Result r = new DerivationEngine(store).recompute(0L, null);
            ctx.json(Map.of("nodes", r.nodes().size(), "affectedClusters", r.affectedClusters()));
        });

        app.get("/svg/probe", ctx -> ctx.contentType(ContentType.SVG)
                .result(svg.probeGeometry(store.decision().meta("probe_id"))));
        app.get("/svg/waveform/{id}", ctx -> ctx.contentType(ContentType.SVG)
                .result(svg.waveform(ctx.pathParam("id"))));
        app.get("/svg/projection", ctx -> ctx.contentType(ContentType.SVG)
                .result(svg.projection(store.decision().meta("active_label_set"))));
        app.get("/svg/drift", ctx -> ctx.contentType(ContentType.SVG)
                .result(svg.driftTimeline()));

        app.get("/api/export", ctx -> {
            Path tmp = Path.of(System.getProperty("java.io.tmpdir"),
                    "spike-project-" + System.nanoTime() + ".spkzip");
            var r = new ProjectArchive().exportTo(store, tmp);
            ctx.header("X-Archive-Sha256", r.sha256());
            ctx.contentType("application/octet-stream")
                    .header("Content-Disposition", "attachment; filename=project.spkzip")
                    .result(java.nio.file.Files.readAllBytes(tmp));
            java.nio.file.Files.deleteIfExists(tmp);
        });

        app.exception(Exception.class, (e, ctx) -> {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getClass().getSimpleName());
            err.put("message", e.getMessage());
            ctx.status(500).json(err);
        });

        app.start(host, port);
        return app;
    }

    private Map<String, Object> outcomeJson(AdjudicationService.Outcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (o instanceof AdjudicationService.Outcome.Ok ok) {
            m.put("ok", true); m.put("seq", ok.seq()); m.put("state", ok.state());
            m.put("affectedClusters", ok.affectedClusters());
        } else {
            var f = ((AdjudicationService.Outcome.Failed) o).failure();
            m.put("ok", false); m.put("failureType", f.type().name());
            m.put("diagnostics", f.diagnostics());
        }
        return m;
    }
}
