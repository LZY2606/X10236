package neurogsb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Imports a case pack into the layered store.
 *
 * Failure contract — three distinct outcomes, never silent success:
 *   FORMAT_CORRUPT      input bytes/JSON are broken (position attached)
 *   BUDGET_EXHAUSTED    resource budget (max records) exceeded
 *   SEMANTIC_UNSOLVABLE references/units/windows cannot be resolved
 *
 * Raw representations are always preserved; normalization happens only in
 * derived fields. Unconvertible values stay in place with a diagnostic.
 */
public final class Importer {
    public enum Status { OK, FORMAT_CORRUPT, BUDGET_EXHAUSTED, SEMANTIC_UNSOLVABLE }

    public static final class Result {
        public final Status status;
        public final ArrayNode diagnostics = Json.arr();
        public int imported = 0;
        Result(Status s) { this.status = s; }
        void diag(String source, String position, String code, String severity, String message) {
            ObjectNode d = Json.obj();
            d.put("source", source).put("position", position).put("code", code)
             .put("severity", severity).put("message", message);
            diagnostics.add(d);
        }
    }

    public static final class Budget {
        public final int maxRecords;
        public Budget(int maxRecords) { this.maxRecords = maxRecords; }
    }

    private final Store store;
    public Importer(Store store) { this.store = store; }

    public Result importPack(String packJson, Budget budget) {
        JsonNode root;
        try {
            root = Json.parse(packJson);
        } catch (Json.JsonFormatException e) {
            Result r = new Result(Status.FORMAT_CORRUPT);
            r.diag("pack", "byte-stream", "FORMAT_CORRUPT", "error",
                "input is not well-formed JSON: " + e.getMessage());
            return r;
        }
        if (!root.isObject() || !root.has("materials")) {
            Result r = new Result(Status.FORMAT_CORRUPT);
            r.diag("pack", "root", "FORMAT_CORRUPT", "error",
                "pack root must be an object with a 'materials' array");
            return r;
        }
        return importMaterials(root, budget);
    }

    private Result importMaterials(JsonNode root, Budget budget) {
        Result result = new Result(Status.OK);
        ArrayNode materials = (ArrayNode) root.get("materials");

        // Deterministic ordering: equivalent inputs (any order) -> same insertion order.
        List<JsonNode> sorted = Json.sortedCanonically(materials);

        if (sorted.size() > budget.maxRecords) {
            Result r = new Result(Status.BUDGET_EXHAUSTED);
            r.diag("pack", "materials", "BUDGET_EXHAUSTED", "error",
                "record budget exceeded: " + sorted.size() + " > " + budget.maxRecords);
            return r;
        }

        List<String> semanticErrors = new ArrayList<>();
        List<JsonNode> staged = new ArrayList<>();

        for (JsonNode m : sorted) {
            String kind = m.path("kind").asText("");
            String id = m.path("id").asText("");
            String pos = "materials[" + kind + ":" + id + "]";
            switch (kind) {
                case "waveform_ref" -> stageWaveform(m, pos, result, semanticErrors, staged);
                case "probe_geometry" -> stageProbe(m, pos, result, semanticErrors, staged);
                case "detection_threshold" -> stageThreshold(m, pos, result, semanticErrors, staged);
                case "feature_version" -> stageFeature(m, pos, result, semanticErrors, staged);
                case "cluster_label" -> stageLabel(m, pos, result, semanticErrors, staged);
                case "spike_event" -> stageSpike(m, pos, result, semanticErrors, staged);
                case "adjudication_event" -> stageAdjudication(m, pos, result, semanticErrors, staged);
                default -> {
                    result.diag("pack", pos, "SEMANTIC_UNSOLVABLE", "error",
                        "unknown material kind: " + kind);
                    semanticErrors.add(pos);
                }
            }
        }

        if (!semanticErrors.isEmpty()) {
            Result r = new Result(Status.SEMANTIC_UNSOLVABLE);
            for (JsonNode d : result.diagnostics) r.diagnostics.add(d);
            return r;
        }
        for (JsonNode s : staged) apply(s);
        result.imported = staged.size();
        for (JsonNode d : result.diagnostics) {
            store.addDiagnostic("dg-" + Json.sha256(Json.canonical(d)),
                d.get("source").asText(), d.get("position").asText(), d.get("code").asText(),
                d.get("severity").asText(), d.get("message").asText(), d);
        }
        return result;
    }

    private void stageWaveform(JsonNode m, String pos, Result r, List<String> errs, List<JsonNode> staged) {
        String tUnit = m.path("t_unit").asText(null);
        Double tMs = Units.timeToMs(m.path("t_raw").asText(null), tUnit);
        if (tMs == null) {
            r.diag("waveform_ref", pos, "UNIT_UNCONVERTIBLE", "warning",
                "time value kept raw; unit not convertible to ms: " + tUnit);
        }
        staged.add(m);
    }

    private void stageProbe(JsonNode m, String pos, Result r, List<String> errs, List<JsonNode> staged) {
        String unit = m.path("coord_unit").asText(null);
        if (!Units.knownLengthUnit(unit)) {
            r.diag("probe_geometry", pos, "UNIT_UNCONVERTIBLE", "warning",
                "coordinates kept raw; unknown coordinate unit: " + unit);
        }
        staged.add(m);
    }

    private void stageThreshold(JsonNode m, String pos, Result r, List<String> errs, List<JsonNode> staged) {
        String unit = m.path("unit").asText(null);
        Double uv = Units.amplitudeToUv(m.path("value_raw").asText(null), unit);
        if (uv == null) {
            r.diag("detection_threshold", pos, "UNIT_UNCONVERTIBLE", "warning",
                "threshold kept raw; unit not convertible to uV: " + unit);
        } else if (uv <= 0) {
            r.diag("detection_threshold", pos, "SEMANTIC_UNSOLVABLE", "error",
                "threshold must be positive, got " + uv + " uV");
            errs.add(pos);
        }
        staged.add(m);
    }

    private void stageFeature(JsonNode m, String pos, Result r, List<String> errs, List<JsonNode> staged) {
        if (!m.has("name") || !m.has("version")) {
            r.diag("feature_version", pos, "SEMANTIC_UNSOLVABLE", "error",
                "feature_version requires name and version");
            errs.add(pos);
        }
        staged.add(m);
    }

    private void stageLabel(JsonNode m, String pos, Result r, List<String> errs, List<JsonNode> staged) {
        if (!m.has("unit_uid")) {
            r.diag("cluster_label", pos, "SEMANTIC_UNSOLVABLE", "error",
                "cluster_label requires unit_uid (stable identity)");
            errs.add(pos);
        }
        staged.add(m);
    }

    private void stageSpike(JsonNode m, String pos, Result r, List<String> errs, List<JsonNode> staged) {
        String tUnit = m.path("t_unit").asText(null);
        Double tMs = Units.timeToMs(m.path("t_raw").asText(null), tUnit);
        if (tMs == null) {
            r.diag("spike_event", pos, "UNIT_UNCONVERTIBLE", "warning",
                "spike time kept raw; unit not convertible to ms: " + tUnit);
        }
        String ampUnit = m.path("amp_unit").asText(null);
        if (ampUnit != null && Units.amplitudeToUv(m.path("amp_unit").asText(null) == null ? null : m.path("amp_raw").asText(null), ampUnit) == null) {
            r.diag("spike_event", pos, "UNIT_UNCONVERTIBLE", "warning",
                "spike amplitude kept raw; unit not convertible to uV: " + ampUnit);
        }
        if (!m.has("unit_uid")) {
            r.diag("spike_event", pos, "SEMANTIC_UNSOLVABLE", "error",
                "spike_event requires unit_uid");
            errs.add(pos);
        }
        staged.add(m);
    }

    private void stageAdjudication(JsonNode m, String pos, Result r, List<String> errs, List<JsonNode> staged) {
        if (!m.has("kind") || !m.has("targets")) {
            r.diag("adjudication_event", pos, "SEMANTIC_UNSOLVABLE", "error",
                "adjudication_event requires kind and targets");
            errs.add(pos);
        }
        staged.add(m);
    }

    private void apply(JsonNode m) {
        String kind = m.get("kind").asText();
        String id = m.get("id").asText();
        switch (kind) {
            case "waveform_ref" -> store.addWaveformRef(id,
                m.path("unit_uid").asText(null), m.path("channel").asInt(),
                m.path("t_raw").asText(null), m.path("t_unit").asText(null),
                Units.timeToMs(m.path("t_raw").asText(null), m.path("t_unit").asText(null)),
                m.path("sample_ref").asText(null),
                m.has("snippet") ? Json.canonical(m.get("snippet")) : null,
                m.path("fs_hz").asDouble(30000.0), m);
            case "probe_geometry" -> store.addProbeSite(id,
                m.path("site_id").asText(null), m.path("channel").asInt(),
                m.path("x_raw").asText(null), m.path("y_raw").asText(null),
                m.path("coord_unit").asText(null), m.path("coord_frame").asText(null),
                Units.lengthToUm(m.path("x_raw").asText(null), m.path("coord_unit").asText(null)),
                Units.lengthToUm(m.path("y_raw").asText(null), m.path("coord_unit").asText(null)), m);
            case "detection_threshold" -> store.addThreshold(id,
                m.path("channel").asInt(), m.path("value_raw").asText(null),
                m.path("unit").asText(null),
                Units.amplitudeToUv(m.path("value_raw").asText(null), m.path("unit").asText(null)),
                m.has("valid_from_ms") ? m.get("valid_from_ms").asDouble() : null,
                m.has("valid_to_ms") ? m.get("valid_to_ms").asDouble() : null,
                m.path("version").asInt(1), m);
            case "feature_version" -> store.addFeatureVersion(id,
                m.get("name").asText(), m.get("version").asInt(),
                m.path("params"), m);
            case "cluster_label" -> store.addClusterLabel(id,
                m.get("unit_uid").asText(), m.path("label_no").asInt(),
                m.path("seq").asInt(0), m.path("event").asText("assigned"), m);
            case "spike_event" -> store.addSpike(id,
                m.get("unit_uid").asText(), m.path("channel").asInt(),
                m.path("t_raw").asText(null), m.path("t_unit").asText(null),
                Units.timeToMs(m.path("t_raw").asText(null), m.path("t_unit").asText(null)),
                m.path("amp_raw").asText(null), m.path("amp_unit").asText(null),
                Units.amplitudeToUv(m.path("amp_raw").asText(null), m.path("amp_unit").asText(null)),
                m.path("shared_group").asText(null), m);
            case "adjudication_event" -> store.addAdjudication(id,
                m.path("seq").asInt(store.nextSeq()), m.path("branch").asText("main"),
                m.get("kind").asText(), m.get("targets"),
                m.path("outcome").asText(null), m.path("rationale").asText(null),
                m.has("evidence") ? m.get("evidence") : null,
                m.path("param_version").asInt(store.paramVersion()),
                m.has("window_from_ms") ? m.get("window_from_ms").asDouble() : null,
                m.has("window_to_ms") ? m.get("window_to_ms").asDouble() : null,
                m.path("status").asText("applied"),
                m.path("supersedes").asText(null), m.path("conflict_with").asText(null),
                m.has("affected") ? m.get("affected") : null, m);
            default -> throw new IllegalStateException("unreachable: " + kind);
        }
    }
}
