package org.neurospike.ingest;

import org.neurospike.model.Diagnostic;
import org.neurospike.model.FailureResult;
import org.neurospike.model.FailureType;
import org.neurospike.model.Ids;
import org.neurospike.model.Material;
import org.neurospike.model.Quantity;
import org.neurospike.model.UnitTable;
import org.neurospike.store.ProjectStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 导入服务（业务规则在服务端，可被测试直接调用）。
 * 分层写入：波形引用/几何/阈值/特征版本/聚类标签/裁定事件彼此分离。
 * 行级问题保留原位并附诊断；批级结构问题返回三种明确失败之一。
 */
public final class IngestService {

    public IngestResult importCase(ProjectStore store, CaseSource source, ResourceBudget budget) {
        ManifestReader.Loaded loaded = new ManifestReader().load(source);
        if (loaded instanceof ManifestReader.Loaded.Failed f) {
            return IngestResult.failed(f.failure());
        }
        CaseManifest m = ((ManifestReader.Loaded.Ok) loaded).manifest();
        List<Diagnostic> warnings = new ArrayList<>();
        List<Diagnostic> semantic = new ArrayList<>();
        List<Diagnostic> corrupt = new ArrayList<>();

        try {
            // 1) 探针几何（坐标基准单位）
            String coordUnit = m.coordinateBasis().unit();
            if (UnitTable.toSi(coordUnit, "um") == null && UnitTable.toSi(coordUnit, "µm") == null) {
                corrupt.add(Diagnostic.of("corrupt-coordinate-unit", "manifest.coordinateBasis",
                        "unsupported coordinate unit: " + coordUnit));
            }
            for (CaseManifest.GeometryRow g : m.files().geometry()) {
                store.material().putChannel(new Material.ProbeChannel(
                        m.probeId(), g.localIndex(), g.label(), g.x(), g.y(), coordUnit));
            }

            // 2) 特征版本
            for (CaseManifest.FeatureSetFile fsf : m.files().featureSets()) {
                String params = source.readUtf8(fsf.paramsCsv());
                String canonical = String.join("\n",
                        Csv.parse(params, fsf.paramsCsv(), new ArrayList<>()).rows().stream()
                                .map(r -> String.join("|", r.all().values())).toList());
                store.material().putFeatureVersion(new Material.FeatureVersion(
                        fsf.featureSetId(), fsf.version(), fsf.algorithm(),
                        canonical, fsf.createdAt()));
            }

            // 3) 检测阈值（全部版本入库；清单声明的为初始激活版本）
            for (String rel : List.of("thresholds/thresholds-v1.csv", "thresholds/thresholds-v2.csv")) {
                importThresholds(store, source, m.probeId(), rel, warnings, corrupt);
            }

            // 4) 原始波形（内容寻址）+ 检测事件
            String eventsPath = m.files().eventsCsv();
            String eventsCsv = source.readUtf8(eventsPath);
            Csv.Table eventsTable = Csv.parse(eventsCsv, eventsPath, corrupt);
            for (String col : List.of("event_id", "time_seconds", "channel_local_index",
                    "waveform_ref", "amplitude", "amplitude_unit", "feature_set_id",
                    "feature_vector")) {
                Diagnostic d = Csv.requireColumn(eventsTable, col, eventsPath);
                if (d != null) corrupt.add(d);
            }
            if (!corrupt.isEmpty()) {
                return IngestResult.failed(FailureResult.of(FailureType.CORRUPT_FORMAT, corrupt));
            }
            if (eventsTable.rows().size() > budget.maxEvents()) {
                return IngestResult.failed(FailureResult.of(FailureType.RESOURCE_EXHAUSTED, List.of(
                        Diagnostic.of("budget-events", eventsPath,
                                "events " + eventsTable.rows().size() + " > budget " + budget.maxEvents()))));
            }

            int imported = 0, retained = 0, wfBytes = 0;
            int order = 0;
            List<Material.SpikeEvent> spikeEvents = new ArrayList<>();
            for (Csv.Row row : eventsTable.rows()) {
                String rowPath = eventsPath + ":" + row.lineNo;
                String eventId = row.get("event_id");
                int channel = parseInt(row.get("channel_local_index"));
                double time = parseDouble(row.get("time_seconds"));
                String wfRef = row.get("waveform_ref");
                String ampRaw = row.get("amplitude");
                String ampUnit = row.get("amplitude_unit");
                String fset = row.get("feature_set_id");
                String fvec = row.get("feature_vector");

                List<Double> feats = parseFeatures(fvec, rowPath, warnings);
                Quantity.ParseResult pr = Quantity.parse(ampRaw, ampUnit, "uV");
                if (!pr.ok()) warnings.add(Diagnostic.of("row-" + pr.diagnostic().split(":")[0],
                        rowPath + "#amplitude", pr.diagnostic()));

                boolean wfMissing = wfRef == null || wfRef.isBlank()
                        || wfRef.startsWith("wf_MISSING_") || !waveformExists(source, wfRef);
                String reason = null;
                if (!pr.ok()) reason = "amplitude-unit-unconvertible";
                if (wfMissing) reason = "waveform-reference-missing";
                boolean valid = pr.ok() && !wfMissing
                        && store.material().featureVersion(fset) != null;

                if (!wfMissing) {
                    byte[] blob = source.read("waveforms/" + wfRef + ".bin");
                    if (blob.length > 4096) {
                        return IngestResult.failed(FailureResult.of(FailureType.RESOURCE_EXHAUSTED,
                                List.of(Diagnostic.of("budget-waveform", rowPath + "#waveform",
                                        "waveform too large: " + blob.length))));
                    }
                    wfBytes += blob.length;
                    if (wfBytes > budget.maxWaveformBytes()) {
                        return IngestResult.failed(FailureResult.of(FailureType.RESOURCE_EXHAUSTED,
                                List.of(Diagnostic.of("budget-waveform-bytes",
                                        "waveforms", "exceeded " + budget.maxWaveformBytes()))));
                    }
                    String sha = sha256(blob);
                    store.material().putWaveform(new Material.Waveform(
                            wfRef, channel, blob.length / 2,
                            m.timeBasis().rateRaw(), m.timeBasis().rateUnit(),
                            m.timeBasis().sampleRateHz(), 48,
                            1.0, "uV", blob, sha));
                }

                Material.SpikeEvent e = new Material.SpikeEvent(
                        stableEventId(m.caseId(), eventId), time, channel, wfRef,
                        pr.quantity(), fset, feats, rebuildRawLine(row), valid, reason, null);
                spikeEvents.add(e);
                store.material().putEvent(e, order++);
                imported++;
                if (!valid) retained++;
            }

            // 5) 聚类标签集（与事件表分离）+ 拆分共享候选
            for (CaseManifest.LabelFile lf : m.files().labels()) {
                importLabels(store, source, lf, semantic, corrupt);
            }
            importCandidates(store, source, "labels/candidates-v3.csv");

            store.decision().putMeta("case_id", m.caseId());
            store.decision().putMeta("probe_id", m.probeId());
            store.decision().putMeta("time_basis", m.timeBasis().t0IsoUtc());
            store.decision().putMeta("active_threshold_version", "1");
            store.decision().putMeta("active_label_set", "ls-v1");
            store.decision().putMeta("window_start", "0.0");
            store.decision().putMeta("window_end", "1.0E12");
            store.decision().putMeta("concurrency_tol_seconds", "0.001");
            store.decision().putMeta("refractory_seconds", "0.001");
            if (!semantic.isEmpty()) {
                return IngestResult.failed(FailureResult.of(FailureType.SEMANTIC_UNRESOLVABLE, semantic));
            }
            return IngestResult.ok(imported, retained, warnings);
        } catch (Exception ex) {
            return IngestResult.failed(FailureResult.of(FailureType.CORRUPT_FORMAT,
                    List.of(Diagnostic.of("corrupt-read", "casepack",
                            ex.getClass().getSimpleName() + ": " + ex.getMessage()))));
        }
    }

    private boolean waveformExists(CaseSource source, String wfRef) {
        try {
            source.read("waveforms/" + wfRef + ".bin");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void importThresholds(ProjectStore store, CaseSource source, String probeId,
                                  String rel, List<Diagnostic> warnings,
                                  List<Diagnostic> corrupt) throws Exception {
        Csv.Table t = Csv.parse(source.readUtf8(rel), rel, new ArrayList<>());
        int version = rel.contains("v1") ? 1 : 2;
        String createdAt = version == 1 ? "2026-09-01T00:01:00Z" : "2026-09-02T00:01:00Z";
        for (Csv.Row r : t.rows()) {
            String raw = r.get("value");
            Quantity.ParseResult pr = Quantity.parse(raw, r.get("unit"), "uV");
            if (!pr.ok()) warnings.add(Diagnostic.of("threshold-unconvertible",
                    rel + ":" + r.lineNo, pr.diagnostic()));
            String id = Ids.contentId("th", String.join("|", probeId,
                    String.valueOf(version), r.get("channel_local_index"), raw, r.get("unit")));
            store.material().putThreshold(new Material.Threshold(id, version, probeId,
                    parseInt(r.get("channel_local_index")), r.get("operator"),
                    pr.quantity(), r.get("basis"), createdAt));
        }
    }

    private void importLabels(ProjectStore store, CaseSource source, CaseManifest.LabelFile lf,
                              List<Diagnostic> semantic, List<Diagnostic> corrupt) throws Exception {
        store.labels().putLabelSet(new Material.LabelSet(lf.labelSetId(), lf.version(),
                lf.parentLabelSetId(), lf.note(), lf.createdAt()));
        Csv.Table t = Csv.parse(source.readUtf8(lf.labelsCsv()), lf.labelsCsv(), new ArrayList<>());
        List<Material.ClusterAssignment> rows = new ArrayList<>();
        for (Csv.Row r : t.rows()) {
            rows.add(new Material.ClusterAssignment(lf.labelSetId(),
                    r.get("cluster_key"), parseInt(r.get("display_number")),
                    stableEventId("pulse-cluster-lineage", r.get("event_id"))));
        }
        store.labels().putAssignments(lf.labelSetId(), rows);
    }

    private void importCandidates(ProjectStore store, CaseSource source, String rel) throws Exception {
        String content;
        try { content = source.readUtf8(rel); } catch (Exception e) { return; }
        Csv.Table t = Csv.parse(content, rel, new ArrayList<>());
        try (var ps = store.raw().prepareStatement("""
                INSERT INTO cluster_candidate(label_set_id,cluster_key,event_id,reason)
                VALUES(?,?,?,?) ON CONFLICT(label_set_id,cluster_key,event_id) DO NOTHING
                """)) {
            for (Csv.Row r : t.rows()) {
                ps.setString(1, r.get("label_set_id"));
                ps.setString(2, r.get("cluster_key"));
                ps.setString(3, stableEventId("pulse-cluster-lineage", r.get("event_id")));
                ps.setString(4, r.get("reason"));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private List<Double> parseFeatures(String fvec, String path, List<Diagnostic> warnings) {
        List<Double> out = new ArrayList<>();
        if (fvec == null || fvec.isBlank()) return out;
        for (String part : fvec.split(",")) {
            try { out.add(Double.parseDouble(part.trim())); }
            catch (NumberFormatException e) {
                warnings.add(Diagnostic.of("feature-not-numeric", path + "#feature_vector", part));
            }
        }
        return out;
    }

    private String rebuildRawLine(Csv.Row row) {
        return String.join(",", row.all().values());
    }

    static int parseInt(String s) { return Integer.parseInt(s.trim()); }
    static double parseDouble(String s) { return Double.parseDouble(s.trim()); }

    public static String stableEventId(String caseId, String localEventId) {
        return "ev_" + Ids.sha256Hex(caseId + "|" + localEventId).substring(0, 20);
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
