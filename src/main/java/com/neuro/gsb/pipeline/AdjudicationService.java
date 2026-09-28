package com.neuro.gsb.pipeline;

import com.neuro.gsb.domain.Diagnostic;
import com.neuro.gsb.domain.Ids;
import com.neuro.gsb.domain.Result;
import com.neuro.gsb.store.ComputationStore;
import com.neuro.gsb.store.Database;
import com.neuro.gsb.store.EventStore;
import com.neuro.gsb.store.LineageStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Human adjudications are append-only events. Accepted and rejected outcomes
 * are both kept. Conflicting decisions stay in the log together and surface
 * as a CONFLICT state — neither is silently overwritten. When a target
 * cannot be uniquely resolved, all legal candidates are preserved and the
 * call returns SemanticUnsolvable.
 */
public final class AdjudicationService {
    private final Database db;
    private final EventStore events;
    private final LineageStore lineage;
    private final ComputationStore computations;
    private final DriftAnalysis analysis;

    public AdjudicationService(Database db, EventStore events, LineageStore lineage,
                               ComputationStore computations, DriftAnalysis analysis) {
        this.db = db;
        this.events = events;
        this.lineage = lineage;
        this.computations = computations;
        this.analysis = analysis;
    }

    public record DecisionOutcome(long eventSeq, String decisionEventId, List<String> affectedDownstream,
                                  String conflictWith) {}

    /** Resolve a sorter label to identities. Ambiguity → SemanticUnsolvable with all candidates. */
    public Result<String> resolveLabel(String labelSet, String label) {
        List<String> candidates = lineage.identitiesAsOf(Long.MAX_VALUE).stream()
                .map(LineageStore.Identity::identity)
                .filter(id -> lineage.evidenceAsOf(Long.MAX_VALUE, id).stream()
                        .anyMatch(ev -> ev.kind().equals("LABEL_OBSERVED")
                                && ev.payload().contains("\"label_set\":\"" + labelSet + "\"")
                                && ev.payload().contains("\"label\":\"" + label + "\"")))
                .sorted()
                .toList();
        if (candidates.isEmpty()) {
            return new Result.SemanticUnsolvable<>(
                    "no identity observed for label " + labelSet + "/" + label, List.of(), List.of());
        }
        if (candidates.size() > 1) {
            return new Result.SemanticUnsolvable<>(
                    "label " + labelSet + "/" + label + " maps to multiple identities; cluster number is not identity",
                    candidates, List.of());
        }
        return Result.ok(candidates.get(0));
    }

    /** Accept or reject an identity. A prior opposite decision produces a kept conflict. */
    public Result<DecisionOutcome> decide(String identity, boolean accept, String rationale) {
        if (lineage.identitiesAsOf(Long.MAX_VALUE).stream().noneMatch(i -> i.identity().equals(identity))) {
            return new Result.SemanticUnsolvable<>("unknown identity " + identity, List.of(), List.of());
        }
        return db.inTransaction(conn -> {
            String conflictWith = findOppositeDecision(identity, accept);
            String kind = accept ? "ADJUDICATION_ACCEPT" : "ADJUDICATION_REJECT";
            List<String> parents = new ArrayList<>();
            parents.add(identity);
            if (conflictWith != null) parents.add(conflictWith);
            var evt = events.append(conn, kind,
                    "{\"identity\":\"" + identity + "\",\"rationale\":\"" + escape(rationale) + "\""
                            + (conflictWith != null ? ",\"conflict_with\":\"" + conflictWith + "\"" : "")
                            + "}", parents);
            if (conflictWith != null) {
                lineage.addEvidence(conn, identity, "ADJUDICATION_CONFLICT",
                        "{\"with_event\":\"" + conflictWith + "\",\"rule\":\"both decisions retained; neither overwrites the other\"}",
                        evt.seq());
                lineage.addDiagnostics(conn, List.of(Diagnostic.warning(
                        "adjudication:" + identity,
                        "conflicting decisions retained: " + kind + " vs event " + conflictWith)), evt.seq());
            }
            List<String> affected = computations.invalidateForIdentities(conn, List.of(identity), evt.seq());
            analysis.recompute(conn, evt.seq(), currentParams(), List.of(identity));
            return Result.ok(new DecisionOutcome(evt.seq(), evt.id(), affected, conflictWith));
        });
    }

    private String findOppositeDecision(String identity, boolean accept) {
        String opposite = accept ? "ADJUDICATION_REJECT" : "ADJUDICATION_ACCEPT";
        return events.upTo(Long.MAX_VALUE).stream()
                .filter(e -> e.kind().equals(opposite) && e.payload().contains("\"identity\":\"" + identity + "\""))
                .map(EventStore.Event::id)
                .reduce((a, b) -> b) // latest
                .orElse(null);
    }

    /**
     * Split an identity in two. Both children deterministically derive their
     * identity from the parent and split key; pre-split events stay shared
     * (visible on parent and both children) and are marked as SHARED_EVENT
     * evidence.
     */
    public Result<DecisionOutcome> split(String parentIdentity, String splitKey) {
        if (lineage.identitiesAsOf(Long.MAX_VALUE).stream().noneMatch(i -> i.identity().equals(parentIdentity))) {
            return new Result.SemanticUnsolvable<>("unknown identity " + parentIdentity, List.of(), List.of());
        }
        return db.inTransaction(conn -> {
            var evt = events.append(conn, "ADJUDICATION_SPLIT",
                    "{\"parent\":\"" + parentIdentity + "\",\"split_key\":\"" + escape(splitKey) + "\"}",
                    List.of(parentIdentity));
            String childA = Ids.derivedUnitIdentity(parentIdentity, splitKey, 0);
            String childB = Ids.derivedUnitIdentity(parentIdentity, splitKey, 1);
            lineage.createIdentity(conn, childA, evt.seq(), "ACTIVE", parentIdentity);
            lineage.createIdentity(conn, childB, evt.seq(), "ACTIVE", parentIdentity);
            lineage.setStatus(conn, parentIdentity, "SPLIT_PARENT");
            lineage.copyMembership(conn, parentIdentity, childA, evt.seq());
            lineage.copyMembership(conn, parentIdentity, childB, evt.seq());
            for (String child : List.of(childA, childB)) {
                lineage.addEvidence(conn, child, "SHARED_EVENT",
                        "{\"parent\":\"" + parentIdentity + "\",\"rule\":\"pre-split events remain shared between parent and both children\"}",
                        evt.seq());
            }
            List<String> affected = computations.invalidateForIdentities(conn,
                    List.of(parentIdentity, childA, childB), evt.seq());
            analysis.recompute(conn, evt.seq(), currentParams(), List.of(parentIdentity, childA, childB));
            return Result.ok(new DecisionOutcome(evt.seq(), evt.id(), affected, null));
        });
    }

    /**
     * Cluster renumbering: a new label set maps an old label to a new number.
     * The cluster number is never treated as identity — continuity is only
     * established by this adjudicated, evidence-carrying link event.
     */
    public Result<DecisionOutcome> linkRenumberedLabel(String identity, String fromLabelSet, String fromLabel,
                                                       String toLabelSet, String toLabel, String rationale) {
        if (lineage.identitiesAsOf(Long.MAX_VALUE).stream().noneMatch(i -> i.identity().equals(identity))) {
            return new Result.SemanticUnsolvable<>("unknown identity " + identity, List.of(), List.of());
        }
        return db.inTransaction(conn -> {
            var evt = events.append(conn, "ADJUDICATION_LABEL_LINK",
                    "{\"identity\":\"" + identity + "\",\"from_label_set\":\"" + fromLabelSet
                            + "\",\"from_label\":\"" + fromLabel + "\",\"to_label_set\":\"" + toLabelSet
                            + "\",\"to_label\":\"" + toLabel + "\",\"rationale\":\"" + escape(rationale) + "\"}",
                    List.of(identity));
            lineage.addEvidence(conn, identity, "LABEL_RENUMBERED",
                    "{\"from_label_set\":\"" + fromLabelSet + "\",\"from_label\":\"" + fromLabel
                            + "\",\"to_label_set\":\"" + toLabelSet + "\",\"to_label\":\"" + toLabel
                            + "\",\"rule\":\"cluster number is not identity; continuity is adjudicated evidence\"}",
                    evt.seq());
            return Result.ok(new DecisionOutcome(evt.seq(), evt.id(), List.of(identity), null));
        });
    }

    /** Change the analysis time window: only identities intersecting the window are recomputed. */
    public Result<DecisionOutcome> changeWindow(Double startMs, Double endMs, String newVersion) {
        return db.inTransaction(conn -> {
            AnalysisParams params = currentParams().withWindow(startMs, endMs, newVersion);
            var evt = events.append(conn, "WINDOW_CHANGE",
                    "{\"window_start_ms\":" + (startMs == null ? "null" : startMs)
                            + ",\"window_end_ms\":" + (endMs == null ? "null" : endMs)
                            + ",\"unit\":\"ms\",\"basis\":\"RECORDING_CLOCK\",\"param_version\":\"" + params.paramVersion() + "\"}",
                    List.of());
            List<String> intersecting = analysis.identitiesIntersecting(evt.seq(), startMs, endMs);
            List<String> affected = analysis.recompute(conn, evt.seq(), params, intersecting);
            return Result.ok(new DecisionOutcome(evt.seq(), evt.id(), affected, null));
        });
    }

    /** Parameter revision (e.g. detection threshold): recorded as its own event + material. */
    public Result<DecisionOutcome> reviseParam(String description, String paramVersion, List<String> parentIds) {
        return db.inTransaction(conn -> {
            var evt = events.append(conn, "PARAM_REVISION",
                    "{\"description\":\"" + escape(description) + "\",\"param_version\":\"" + escape(paramVersion) + "\"}",
                    parentIds);
            return Result.ok(new DecisionOutcome(evt.seq(), evt.id(), List.of(), null));
        });
    }

    /** Latest analysis param version in use (from the most recent WINDOW_CHANGE, else initial). */
    public AnalysisParams currentParams() {
        return events.upTo(Long.MAX_VALUE).stream()
                .filter(e -> e.kind().equals("WINDOW_CHANGE"))
                .reduce((a, b) -> b)
                .map(e -> {
                    String p = e.payload();
                    Double s = extractNullableDouble(p, "window_start_ms");
                    Double en = extractNullableDouble(p, "window_end_ms");
                    String ver = extractVersion(p);
                    return AnalysisParams.initial().withWindow(s, en, ver);
                })
                .orElse(AnalysisParams.initial());
    }

    private static Double extractNullableDouble(String payload, String key) {
        String marker = "\"" + key + "\":";
        int i = payload.indexOf(marker);
        if (i < 0) return null;
        String rest = payload.substring(i + marker.length());
        if (rest.startsWith("null")) return null;
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char ch = rest.charAt(i);
            if (ch == ',' || ch == '}') { end = i; break; }
        }
        try {
            return Double.parseDouble(rest.substring(0, end < 0 ? rest.length() : end));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String extractVersion(String payload) {
        String marker = "\"param_version\":\"";
        int i = payload.indexOf(marker);
        if (i < 0) return "analysis-v2";
        int start = i + marker.length();
        int end = payload.indexOf('"', start);
        String full = payload.substring(start, end);
        int pipe = full.indexOf('|');
        return pipe < 0 ? full : full.substring(0, pipe);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
