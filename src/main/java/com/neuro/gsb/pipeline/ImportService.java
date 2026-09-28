package com.neuro.gsb.pipeline;

import com.neuro.gsb.domain.Ids;
import com.neuro.gsb.domain.Result;
import com.neuro.gsb.store.ComputationStore;
import com.neuro.gsb.store.Database;
import com.neuro.gsb.store.EventStore;
import com.neuro.gsb.store.LineageStore;
import com.neuro.gsb.store.MaterialStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Imports a case pack: raw files are stored verbatim as immutable materials
 * (raw representation preserved); normalization happens only in the derived
 * layer. Unconvertible values stay in place with a positioned diagnostic.
 *
 * Failure modes are distinct:
 *  - BudgetExhausted: more records than the budget allows
 *  - FormatCorrupt:   a record does not parse
 *  - SemanticUnsolvable: parsed, but no unique legal resolution
 */
public final class ImportService {
    public static final String NORMALIZATION_VERSION = "norm-v1";

    public record ImportBudget(int maxEvents) {
        public static ImportBudget defaultBudget() { return new ImportBudget(10_000); }
    }

    public record ImportOutcome(String sessionId, long importEventSeq, int eventCount,
                                List<String> materialIds, List<String> identities) {}

    private final Database db;
    private final MaterialStore materials;
    private final EventStore events;
    private final LineageStore lineage;
    private final ComputationStore computations;

    public ImportService(Database db, MaterialStore materials, EventStore events,
                         LineageStore lineage, ComputationStore computations) {
        this.db = db;
        this.materials = materials;
        this.events = events;
        this.lineage = lineage;
        this.computations = computations;
    }

    public Result<ImportOutcome> importPack(Map<String, String> files, ImportBudget budget) {
        String sessionId = "imp-" + Ids.sha256(String.valueOf(files.hashCode())).substring(0, 12)
                + "-" + System.nanoTime();
        db.registerSession(sessionId, "STARTED", "case pack import");
        try {
            Result<ImportOutcome> result = doImport(files, budget, sessionId);
            db.registerSession(sessionId, result.isOk() ? "COMMITTED" : "ABORTED",
                    result.isOk() ? "ok" : result.getClass().getSimpleName());
            return result;
        } catch (RuntimeException e) {
            db.registerSession(sessionId, "ABORTED", e.getMessage());
            throw e;
        }
    }

    private Result<ImportOutcome> doImport(Map<String, String> files, ImportBudget budget, String sessionId) {
        String eventsCsv = files.get("detection_events.csv");
        if (eventsCsv == null) {
            return new Result.FormatCorrupt<>("detection_events.csv", "required file missing from pack");
        }
        // parse + normalize BEFORE any write: failures leave the store untouched
        Result<List<EventCsv.RawEvent>> parsed = EventCsv.parse(eventsCsv, budget);
        if (!(parsed instanceof Result.Ok<List<EventCsv.RawEvent>> ok)) {
            @SuppressWarnings("unchecked")
            Result<ImportOutcome> failure = (Result<ImportOutcome>) parsed;
            return failure;
        }
        EventCsv.Normalized norm = EventCsv.normalize(ok.value());

        return db.inTransaction(conn -> {
            var importEvent = events.append(conn, "IMPORT",
                    "{\"session\":\"" + sessionId + "\",\"pack\":\"pulse-cluster-spectrum\"}", List.of());
            long seq = importEvent.seq();
            List<String> materialIds = new ArrayList<>();
            for (var entry : new TreeMap<>(files).entrySet()) {
                MaterialStore.Kind kind = kindFor(entry.getKey());
                if (kind == null) continue;
                var m = materials.put(conn, kind, entry.getValue(), entry.getKey(), seq);
                materialIds.add(m.id());
            }
            persistSeries(conn, seq, eventsCsv, norm, materialIds);

            Map<String, String> identityByLabel = new LinkedHashMap<>();
            for (var e : norm.events()) {
                String key = e.labelSetVersion() + "|" + e.clusterLabel();
                String identity = identityByLabel.computeIfAbsent(key, k -> {
                    String id = "unit-" + Ids.sha256("label:" + k).substring(0, 12);
                    lineage.createIdentity(conn, id, seq, "ACTIVE", null);
                    lineage.addEvidence(conn, id, "LABEL_OBSERVED",
                            "{\"label_set\":\"" + e.labelSetVersion() + "\",\"label\":\"" + e.clusterLabel()
                                    + "\",\"note\":\"sorter label is evidence, not identity\"}", seq);
                    return id;
                });
                lineage.assign(conn, identity, e.eventKey(), e.labelSetVersion(), e.clusterLabel(), seq);
            }
            lineage.addDiagnostics(conn, norm.diagnostics(), seq);
            return Result.ok(new ImportOutcome(sessionId, seq, norm.events().size(),
                    materialIds, List.copyOf(identityByLabel.values())), norm.diagnostics());
        });
    }

    void persistSeries(java.sql.Connection conn, long seq, String eventsCsv,
                       EventCsv.Normalized norm, List<String> parentMaterialIds) {
        String digest = Ids.sha256(eventsCsv);
        computations.put(conn, new ComputationStore.Computation(
                ComputationStore.computationId("DETECTION_SERIES", "global", digest, NORMALIZATION_VERSION),
                "DETECTION_SERIES", "global", digest, NORMALIZATION_VERSION,
                jsonArray(parentMaterialIds), EventCsv.seriesPayload(norm.events(), norm.keptRaw()),
                "VALID", seq, null));
    }

    static MaterialStore.Kind kindFor(String filename) {
        if (filename.startsWith("probe_geometry")) return MaterialStore.Kind.PROBE_GEOMETRY;
        if (filename.startsWith("detection_thresholds")) return MaterialStore.Kind.DETECTION_THRESHOLD;
        if (filename.startsWith("feature_version")) return MaterialStore.Kind.FEATURE_VERSION;
        if (filename.startsWith("cluster_labels")) return MaterialStore.Kind.CLUSTER_LABELS;
        if (filename.startsWith("waveforms")) return MaterialStore.Kind.WAVEFORM_REF;
        if (filename.startsWith("detection_events")) return MaterialStore.Kind.DETECTION_EVENTS;
        return null;
    }

    static String jsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(items.get(i)).append('"');
        }
        return sb.append(']').toString();
    }
}
