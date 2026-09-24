package atlas.core;

import atlas.json.Json;
import atlas.model.Result;
import atlas.model.Units;
import atlas.store.AtlasStore;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 导入层：保留原始表示（raw_amplitude / raw_unit / time_basis / coord_basis），
 * 规范化只写入派生列；无法转换的值留在原位置并附带位置的诊断。
 */
public final class ImportService {
    private final AtlasStore store;

    public ImportService(AtlasStore store) { this.store = store; }

    public Result<int[]> importMaterials(String jsonText) {
        JsonNode root = Json.tryRead(jsonText);
        if (root == null)
            return Result.fail(Result.Status.CORRUPT_FORMAT, "$", "输入不是合法 JSON，未导入任何记录");
        return importMaterials(root);
    }

    public Result<int[]> importMaterials(JsonNode root) {
        List<Result.Diagnostic> diags = new ArrayList<>();
        int[] counts = new int[5];
        long seq = store.nextSeq();

        JsonNode geo = root.get("probe_geometry");
        if (geo != null) {
            String version = req(geo, "version", "probe_geometry.version", diags);
            String basis = req(geo, "coord_basis", "probe_geometry.coord_basis", diags);
            if (version != null && basis != null && geo.has("sites")) {
                int i = 0;
                for (JsonNode site : geo.withArray("sites")) {
                    String path = "probe_geometry.sites[" + i + "]";
                    if (!site.has("channel") || !site.has("x") || !site.has("y")) {
                        diags.add(new Result.Diagnostic(path, "缺少 channel/x/y，该测点未导入"));
                        store.diagnostic("import", path, "缺少 channel/x/y，该测点未导入", seq);
                    } else {
                        store.insertProbeGeometry(version, site.get("channel").asInt(),
                            site.get("x").asDouble(), site.get("y").asDouble(), basis, seq);
                        counts[1]++;
                    }
                    i++;
                }
            }
        }

        JsonNode params = root.get("detection_params");
        if (params != null) {
            String version = req(params, "version", "detection_params.version", diags);
            String rawThreshold = req(params, "threshold", "detection_params.threshold", diags);
            if (version != null && rawThreshold != null) {
                Units.Parsed p = Units.parse(rawThreshold);
                Double uv = Units.toMicroVolts(p);
                if (uv == null) {
                    diags.add(new Result.Diagnostic("detection_params.threshold",
                        "阈值单位无法转换为 µV: " + rawThreshold + "，保留原始表示"));
                    store.diagnostic("import", "detection_params.threshold",
                        "阈值单位无法转换为 µV: " + rawThreshold, seq);
                }
                store.insertDetectionParams(version, rawThreshold, uv,
                    params.path("refractory_ms").asDouble(1.5),
                    params.path("window_ms").asDouble(2.0), seq);
                store.meta("param_version", version);
                counts[2]++;
            }
        }

        JsonNode features = root.get("feature_sets");
        if (features != null && features.isArray()) {
            for (JsonNode f : features) {
                String version = req(f, "version", "feature_sets[].version", diags);
                if (version != null) {
                    store.insertFeatureSet(version, f.path("algo").asText("unknown"), seq);
                    counts[3]++;
                }
            }
        }

        JsonNode refs = root.get("waveform_refs");
        if (refs != null && refs.isArray()) {
            int i = 0;
            for (JsonNode r : refs) {
                String path = "waveform_refs[" + i + "]";
                if (!r.has("event_id") || !r.has("channel") || !r.has("t_ms")
                        || !r.has("raw_amplitude") || !r.has("snippet")) {
                    diags.add(new Result.Diagnostic(path,
                        "缺少必填字段(event_id/channel/t_ms/raw_amplitude/snippet)，该事件未导入"));
                    store.diagnostic("import", path,
                        "缺少必填字段(event_id/channel/t_ms/raw_amplitude/snippet)，该事件未导入", seq);
                    i++;
                    continue;
                }
                String rawAmp = r.get("raw_amplitude").asText();
                Units.Parsed parsed = Units.parse(rawAmp);
                Double ampUv = Units.toMicroVolts(parsed);
                String unitDiag = null;
                if (ampUv == null) {
                    unitDiag = "单位无法转换为 µV，原始值保留在原位置: " + rawAmp;
                    diags.add(new Result.Diagnostic(path + ".raw_amplitude", unitDiag));
                    store.diagnostic("import", path + ".raw_amplitude", unitDiag, seq);
                }
                double t = r.get("t_ms").asDouble();
                store.insertWaveformRef(
                    r.get("event_id").asText(),
                    r.path("version").asLong(1),
                    r.get("channel").asInt(),
                    t,
                    r.path("t_end_ms").asDouble(t + 0.8),
                    rawAmp,
                    parsed == null ? null : parsed.unit(),
                    ampUv,
                    unitDiag,
                    Json.write(r.get("snippet")),
                    r.path("sample_rate khz").asDouble(r.path("sample_rate_khz").asDouble(30.0)),
                    r.path("source_ref").asText("unknown"),
                    r.path("time_basis").asText("recording-clock/ms"),
                    seq);
                counts[0]++;
                i++;
            }
        }

        JsonNode labels = root.get("cluster_labels");
        if (labels != null) {
            String fv = req(labels, "feature_version", "cluster_labels.feature_version", diags);
            if (fv != null && labels.has("clusters")) {
                for (JsonNode c : labels.withArray("clusters")) {
                    int no = c.get("cluster_no").asInt();
                    for (JsonNode e : c.withArray("events")) {
                        store.insertClusterLabel(fv, no, e.asText(), seq);
                        counts[4]++;
                    }
                }
                store.meta("feature_version", fv);
            }
        }
        return Result.ok(counts, diags);
    }

    private static String req(JsonNode n, String field, String path, List<Result.Diagnostic> diags) {
        if (n == null || !n.has(field)) {
            diags.add(new Result.Diagnostic(path, "缺少必填字段，未用默认值伪装"));
            return null;
        }
        return n.get(field).asText();
    }
}
