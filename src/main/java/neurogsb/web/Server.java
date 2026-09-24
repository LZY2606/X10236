package neurogsb.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import io.javalin.http.Context;
import neurogsb.db.Database;
import neurogsb.model.OpResult;
import neurogsb.model.Records.Derived;
import neurogsb.service.*;

import java.util.List;

public final class Server {
    private final Database db;
    private final Store store;
    private final ReplayService replay;
    private final AdjudicationService adjudication;
    private final ExportService exportService;
    private final CaseLoader caseLoader;

    public Server(Database db) {
        this.db = db;
        this.store = new Store(db);
        this.replay = new ReplayService(db, store);
        this.adjudication = new AdjudicationService(db, store, new EvidenceService(db, store));
        this.exportService = new ExportService(db, store);
        this.caseLoader = new CaseLoader(db);
    }

    public Javalin create() {
        var app = Javalin.create(cfg -> cfg.showJavalinBanner = false);
        app.get("/", ctx -> ctx.html(Page.HTML));
        app.get("/api/state", ctx -> {
            Long seq = ctx.queryParam("seq") != null ? Long.parseLong(ctx.queryParam("seq")) : null;
            ctx.json(replay.stateAt(seq).toString());
        });
        app.get("/api/export", ctx -> {
            ctx.header("Content-Disposition", "attachment; filename=neuro-gsb-export.json");
            ctx.json(exportService.export().toString());
        });
        app.post("/api/import", ctx -> {
            JsonNode body;
            try { body = Store.M.readTree(ctx.body()); }
            catch (Exception e) {
                ctx.json(resultJson(new OpResult.FormatCorrupt("body", "JSON 解析失败: " + e.getMessage())).toString());
                return;
            }
            ctx.json(resultJson(importReplacing(body)).toString());
        });
        app.post("/api/reset", ctx -> {
            resetDb();
            caseLoader.load();
            ctx.json("{\"ok\":true}");
        });
        app.post("/api/adjudicate", ctx -> {
            JsonNode b;
            try { b = Store.M.readTree(ctx.body()); }
            catch (Exception e) {
                ctx.json(resultJson(new OpResult.FormatCorrupt("body", "JSON 解析失败")).toString());
                return;
            }
            String action = b.has("action") ? b.get("action").asText() : "";
            String actor = b.has("actor") ? b.get("actor").asText() : "anonymous";
            OpResult r = switch (action) {
                case "accept", "reject" -> adjudication.decide(
                        b.get("subject_id").asText(), b.get("subject_kind").asText(),
                        "accept".equals(action), actor,
                        b.has("rationale") ? b.get("rationale").asText() : "");
                case "window" -> adjudication.changeWindow(b.get("t0").asDouble(), b.get("t1").asDouble(),
                        actor, CaseLoader.DEFAULT_BUDGET);
                case "renumber" -> adjudication.renumber(b.get("unit_id").asText(),
                        b.get("cluster_no").asInt(), actor);
                case "split" -> adjudication.split(b.get("unit_id").asText(),
                        b.get("t_split").asDouble(), actor);
                case "resolve" -> adjudication.resolve(b.get("subject_id").asText(),
                        b.get("candidate_id").asText(), actor);
                default -> new OpResult.FormatCorrupt("action", "未知动作 " + action);
            };
            ctx.json(resultJson(r).toString());
        });
        app.get("/svg/{name}", this::svg);
        return app;
    }

    private OpResult importReplacing(JsonNode body) {
        resetDb();
        return exportService.importInto(body);
    }

    private void resetDb() {
        for (String t : List.of("materials", "spike_events", "adjudication_events", "derived_results",
                "invalidations", "candidates", "diagnostics", "op_journal", "kv")) {
            try (var ps = db.conn().prepareStatement("DELETE FROM " + t)) { ps.executeUpdate(); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    static ObjectNode resultJson(OpResult r) {
        ObjectNode out = Store.M.createObjectNode();
        if (r instanceof OpResult.Ok ok) {
            out.put("type", "ok");
            out.put("summary", ok.summary());
            ArrayNode a = out.putArray("affected");
            ok.affected().forEach(a::add);
        } else if (r instanceof OpResult.BudgetExhausted be) {
            out.put("type", "budget_exhausted");
            out.put("detail", "预算耗尽: 需要 " + be.required() + "，预算 " + be.budget() + "（阶段 " + be.stage() + "）");
        } else if (r instanceof OpResult.FormatCorrupt fc) {
            out.put("type", "format_corrupt");
            out.put("position", fc.position());
            out.put("detail", fc.detail());
        } else if (r instanceof OpResult.SemanticUnsolvable su) {
            out.put("type", "semantic_unsolvable");
            out.put("position", su.position());
            out.put("detail", su.detail());
        }
        return out;
    }

    private void svg(Context ctx) {
        String name = ctx.pathParam("name");
        String unit = ctx.queryParam("unit");
        double selT = ctx.queryParam("t") != null ? Double.parseDouble(ctx.queryParam("t")) : 0;
        Long seq = ctx.queryParam("seq") != null ? Long.parseLong(ctx.queryParam("seq")) : null;
        if (unit == null) { ctx.result(Svg.esc("no unit")); return; }
        var spikes = store.spikes(unit);
        var derived = store.derivedFor(unit, seq);
        // 在该视点被失效的派生结果不显示
        var invalidated = new java.util.HashSet<String>();
        store.invalidations(seq).forEach(i -> invalidated.add(i.derivedId()));
        java.util.Map<String, JsonNode> latest = new java.util.HashMap<>();
        for (Derived d : derived) {
            if (!invalidated.contains(d.id()) && "ok".equals(d.status())) latest.put(d.kind(), d.payload());
        }
        String svg = switch (name) {
            case "waveform" -> Svg.waveform(spikes, selT, 900, 200);
            case "projection" -> Svg.projection(latest.get("projection"), store.allSpikes(), unit, 440, 240);
            case "acg" -> Svg.acg(latest.get("acg"), 440, 240);
            case "rate" -> Svg.rate(latest.get("rate"), selT, 440, 240);
            case "drift" -> Svg.drift(spikes, latest.get("evidence.drift"), selT, 440, 240);
            default -> "<svg xmlns='http://www.w3.org/2000/svg'></svg>";
        };
        ctx.contentType("image/svg+xml").result(svg);
    }
}
