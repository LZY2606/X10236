package com.neuro.gsb.pipeline;

import com.neuro.gsb.domain.Result;
import com.neuro.gsb.store.ComputationStore;
import com.neuro.gsb.store.Database;
import com.neuro.gsb.store.EventStore;
import com.neuro.gsb.store.LineageStore;
import com.neuro.gsb.store.MaterialStore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A local source-data change: the old material stays immutable, the patched
 * file is stored as a NEW material, the normalized series is rebuilt, and
 * only downstream computations that depend on the changed events are
 * invalidated and recomputed (partial invalidation).
 */
public final class SourceChangeService {
    private final Database db;
    private final MaterialStore materials;
    private final EventStore events;
    private final LineageStore lineage;
    private final ComputationStore computations;
    private final ImportService importer;
    private final DriftAnalysis analysis;
    private final AdjudicationService adjudications;

    public SourceChangeService(Database db, MaterialStore materials, EventStore events,
                               LineageStore lineage, ComputationStore computations,
                               ImportService importer, DriftAnalysis analysis,
                               AdjudicationService adjudications) {
        this.db = db;
        this.materials = materials;
        this.events = events;
        this.lineage = lineage;
        this.computations = computations;
        this.importer = importer;
        this.analysis = analysis;
        this.adjudications = adjudications;
    }

    public record PatchOutcome(long eventSeq, String newMaterialId, List<String> affectedIdentities) {}

    /** Replace detection_events.csv with a patched version. Only owners of changed events are recomputed. */
    public Result<PatchOutcome> patchDetectionEvents(String newCsv, String description) {
        Result<List<EventCsv.RawEvent>> parsed = EventCsv.parse(newCsv, ImportService.ImportBudget.defaultBudget());
        if (!(parsed instanceof Result.Ok<List<EventCsv.RawEvent>> ok)) {
            @SuppressWarnings("unchecked")
            Result<PatchOutcome> failure = (Result<PatchOutcome>) parsed;
            return failure;
        }
        EventCsv.Normalized norm = EventCsv.normalize(ok.value());

        // which event keys changed amplitude/time vs current canonical series?
        var before = analysis.loadSeries(Long.MAX_VALUE);
        java.util.Map<String, DriftAnalysis.Ev> beforeByKey = new java.util.HashMap<>();
        for (var e : before) beforeByKey.put(e.key(), e);
        Set<String> changedKeys = new HashSet<>();
        for (var e : norm.events()) {
            var old = beforeByKey.get(e.eventKey());
            if (old == null || old.tMs() != e.timeMs() || old.amplitudeUv() != e.amplitudeUv()
                    || !old.label().equals(e.clusterLabel())) {
                changedKeys.add(e.eventKey());
            }
        }

        return db.inTransaction(conn -> {
            var evt = events.append(conn, "SOURCE_PATCH",
                    "{\"description\":\"" + description.replace("\"", "\\\"")
                            + "\",\"changed_events\":" + changedKeys.size() + "}", List.of());
            long seq = evt.seq();
            var m = materials.put(conn, MaterialStore.Kind.DETECTION_EVENTS, newCsv,
                    "detection_events.csv (patched)", seq);
            // invalidate old global series, persist the new one
            computations.invalidateForIdentities(conn, List.of("global"), seq);
            importer.persistSeries(conn, seq, newCsv, norm, List.of(m.id()));

            // affected identities = owners of changed events
            Set<String> affected = new HashSet<>();
            for (String key : changedKeys) {
                affected.addAll(lineage.ownersOfEvent(conn, key, Long.MAX_VALUE));
            }
            if (!affected.isEmpty()) {
                analysis.recompute(conn, seq, adjudications.currentParams(), List.copyOf(affected));
            }
            lineage.addDiagnostics(conn, norm.diagnostics(), seq);
            return Result.ok(new PatchOutcome(seq, m.id(), affected.stream().sorted().toList()));
        });
    }
}
