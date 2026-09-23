package org.neurospike.caseflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.neurospike.caseflow.db.CaseRepository;
import org.neurospike.caseflow.db.CaseSnapshot;
import org.neurospike.caseflow.db.SnapshotStore;
import org.neurospike.caseflow.domain.CaseException;
import org.neurospike.caseflow.domain.Diagnostic;
import org.neurospike.caseflow.domain.DomainRecords.AdjudicationEvent;
import org.neurospike.caseflow.domain.DomainRecords.CaseEvent;
import org.neurospike.caseflow.domain.DomainRecords.DecisionRecord;
import org.neurospike.caseflow.domain.DomainRecords.DetectionEvent;
import org.neurospike.caseflow.domain.DomainRecords.FeatureVersion;
import org.neurospike.caseflow.domain.DomainRecords.LabelAssignment;
import org.neurospike.caseflow.domain.DomainRecords.RuleVersion;
import org.neurospike.caseflow.domain.DomainRecords.SourceRevision;
import org.neurospike.caseflow.domain.DomainRecords.WaveformFragment;
import org.neurospike.caseflow.domain.DomainRecords.WindowComputation;
import org.neurospike.caseflow.domain.EvidenceEngine;
import org.neurospike.caseflow.util.JsonCanonical;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class CaseService {
    public static final String BUNDLE_FORMAT = "pulse-lineage-bundle";

    private final CaseRepository repository;
    private final SnapshotStore store;
    private final EvidenceEngine engine = new EvidenceEngine();

    public CaseService(CaseRepository repository) {
        this.repository = repository;
        this.store = new SnapshotStore(repository);
    }

    public CaseRepository repository() {
        return repository;
    }

    public CaseSnapshot snapshot() {
        return store.snapshot();
    }

    public void replaceSnapshot(CaseSnapshot snapshot) {
        validateSemantics(snapshot);
        store.replaceSnapshot(snapshot);
    }

    public void recoverFromHalfWrite() {
        if (repository.hasStaging()) {
            repository.clearStaging();
        }
    }

    public String exportBundle() {
        try {
            JsonNode snapshot = JsonCanonical.MAPPER.valueToTree(snapshot());
            var root = JsonCanonical.object();
            root.put("format", BUNDLE_FORMAT);
            root.put("version", 1);
            root.set("snapshot", snapshot);
            return JsonCanonical.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception exception) {
            throw new IllegalStateException("导出失败", exception);
        }
    }

    public void importBundle(String json) {
        JsonNode root;
        try {
            root = JsonCanonical.MAPPER.readTree(json);
        } catch (Exception exception) {
            throw new CaseException(CaseException.Kind.FORMAT_CORRUPT,
                    new Diagnostic("FORMAT_CORRUPT", "$", "导入内容不是合法 JSON: "
                            + exception.getMessage(), null, null, null));
        }
        if (!BUNDLE_FORMAT.equals(root.path("format").asText()) || root.path("version").asInt() != 1
                || !root.has("snapshot")) {
            throw new CaseException(CaseException.Kind.FORMAT_CORRUPT,
                    new Diagnostic("FORMAT_CORRUPT", "$.format",
                            "导入格式不是 " + BUNDLE_FORMAT + " v1", null, null, null));
        }
        repository.beginStaging("import", String.valueOf(json.length()));
        try {
            CaseSnapshot snapshot = JsonCanonical.MAPPER.treeToValue(root.path("snapshot"),
                    CaseSnapshot.class);
            validateSemantics(snapshot);
            store.replaceSnapshot(snapshot);
        } catch (CaseException exception) {
            repository.clearStaging();
            throw exception;
        } catch (Exception exception) {
            repository.clearStaging();
            throw new CaseException(CaseException.Kind.FORMAT_CORRUPT,
                    new Diagnostic("FORMAT_CORRUPT", "$.snapshot",
                            "快照结构损坏: " + exception.getMessage(), null, null, null));
        }
        repository.clearStaging();
    }

    private void validateSemantics(CaseSnapshot snapshot) {
        if (snapshot.recording() == null) {
            throw new CaseException(CaseException.Kind.SEMANTIC_UNSOLVABLE,
                    new Diagnostic("MISSING_RECORDING", "snapshot.recording",
                            "缺少记录上下文，语义无解", null, null, null));
        }
        Set<String> waveformIds = snapshot.waveforms().stream()
                .map(WaveformFragment::waveformId).collect(Collectors.toSet());
        Set<String> detectionIds = new HashSet<>();
        for (int index = 0; index < snapshot.detections().size(); index++) {
            DetectionEvent detection = snapshot.detections().get(index);
            detectionIds.add(detection.detectionId());
            if (!waveformIds.contains(detection.waveformId())) {
                throw new CaseException(CaseException.Kind.SEMANTIC_UNSOLVABLE,
                        new Diagnostic("MISSING_WAVEFORM", "snapshot.detections[" + index + "].waveformId",
                                "检测事件 " + detection.detectionId() + " 引用了不存在的波形 "
                                        + detection.waveformId(),
                                detection.detectionId(), detection.amplitudeUnit(), null));
            }
        }
        Set<String> featureVersionIds = snapshot.featureVersions().stream()
                .map(FeatureVersion::featureVersionId).collect(Collectors.toSet());
        for (int index = 0; index < snapshot.featurePoints().size(); index++) {
            var point = snapshot.featurePoints().get(index);
            if (!detectionIds.contains(point.detectionId())) {
                throw new CaseException(CaseException.Kind.SEMANTIC_UNSOLVABLE,
                        new Diagnostic("MISSING_DETECTION", "snapshot.featurePoints[" + index + "].detectionId",
                                "特征点 " + point.featurePointId() + " 引用了不存在的检测 "
                                        + point.detectionId(),
                                point.featurePointId(), null, point.coordinateBasis()));
            }
            if (!featureVersionIds.contains(point.featureVersionId())) {
                throw new CaseException(CaseException.Kind.SEMANTIC_UNSOLVABLE,
                        new Diagnostic("MISSING_FEATURE_VERSION",
                                "snapshot.featurePoints[" + index + "].featureVersionId",
                                "特征点 " + point.featurePointId() + " 引用了不存在的特征版本",
                                point.featurePointId(), null, point.coordinateBasis()));
            }
        }
        for (int index = 0; index < snapshot.labelAssignments().size(); index++) {
            LabelAssignment label = snapshot.labelAssignments().get(index);
            for (String detectionId : label.detectionIds()) {
                if (!detectionIds.contains(detectionId)) {
                    throw new CaseException(CaseException.Kind.SEMANTIC_UNSOLVABLE,
                            new Diagnostic("MISSING_DETECTION",
                                    "snapshot.labelAssignments[" + index + "].detectionIds",
                                    "聚类标签 " + label.clusterLabel() + " 引用了不存在的检测 " + detectionId,
                                    label.stableUnitId(), null, null));
                }
            }
        }
    }

    public WindowComputation computeAndStore(long start, long end, List<String> parentIds,
                                             boolean adopted) {
        CaseSnapshot snapshot = snapshot();
        RuleVersion rule = latestRule(snapshot);
        FeatureVersion feature = snapshot.featureVersions().stream()
                .reduce((first, second) -> second)
                .orElseThrow(() -> new CaseException(CaseException.Kind.SEMANTIC_UNSOLVABLE,
                        new Diagnostic("MISSING_FEATURE_VERSION", "snapshot.featureVersions",
                                "缺少特征版本", null, null, null)));
        String labelVersionId = snapshot.labelAssignments().stream()
                .map(LabelAssignment::labelVersionId).max(String::compareTo)
                .orElse("LV-none");
        Map<String, WaveformFragment> waveforms = snapshot.waveforms().stream()
                .collect(Collectors.toMap(WaveformFragment::waveformId, waveform -> waveform,
                        (a, b) -> a, LinkedHashMap::new));
        Map<String, String> replacements = new LinkedHashMap<>();
        for (SourceRevision revision : snapshot.sourceRevisions()) {
            if ("waveform".equals(revision.targetType())) {
                replacements.put(revision.targetId(), revision.replacementId());
            }
        }
        List<DetectionEvent> resolved = new ArrayList<>();
        for (DetectionEvent detection : snapshot.detections()) {
            String waveformId = replacements.getOrDefault(detection.waveformId(), detection.waveformId());
            resolved.add(new DetectionEvent(detection.detectionId(), detection.sampleIndex(),
                    detection.channel(), detection.amplitude(), detection.amplitudeUnit(), waveformId));
        }
        String computationId = nextComputationId(snapshot);
        WindowComputation computation = engine.compute(new EvidenceEngine.Input(
                snapshot.recording(), resolved, waveforms, snapshot.labelAssignments(), labelVersionId,
                feature, rule, snapshot.adjudications(), start, end, parentIds, computationId));
        repository.appendComputation(computation.withAdopted(adopted));
        return computation.withAdopted(adopted);
    }

    public WindowComputation reviseRule(RuleVersion newRule, String summary) {
        repository.appendRuleVersion(newRule);
        CaseSnapshot snapshot = snapshot();
        WindowComputation computation = computeAndStore(0, recordingEnd(snapshot),
                latestComputationIds(snapshot), true);
        appendEvent("PARAMETER_REVISION", summary, computation.computationId(),
                newRule.ruleVersionId(), null, null);
        return computation;
    }

    public WindowComputation reviseWaveform(String targetWaveformId, String replacementWaveformId,
                                            String reason) {
        CaseSnapshot snapshot = snapshot();
        Set<String> waveformIds = snapshot.waveforms().stream()
                .map(WaveformFragment::waveformId).collect(Collectors.toSet());
        if (!waveformIds.contains(replacementWaveformId)) {
            throw new CaseException(CaseException.Kind.SEMANTIC_UNSOLVABLE,
                    new Diagnostic("MISSING_WAVEFORM", "sourceRevision.replacementId",
                            "替换波形 " + replacementWaveformId + " 不存在于材料层",
                            replacementWaveformId, null, null));
        }
        String revisionId = "REV-" + String.format("%03d", snapshot.sourceRevisions().size() + 1);
        repository.appendSourceRevision(new SourceRevision(revisionId, "waveform",
                targetWaveformId, replacementWaveformId, reason));
        WindowComputation computation = computeAndStore(0, recordingEnd(snapshot),
                latestComputationIds(snapshot), true);
        appendEvent("SOURCE_REVISION", reason, computation.computationId(), null, revisionId, null);
        return computation;
    }

    public WindowComputation adjudicate(String decisionType, String actor, String rationale,
                                        List<String> targetStableUnitIds, String outcome) {
        CaseSnapshot snapshot = snapshot();
        String adjudicationId = "ADJ-" + String.format("%03d", snapshot.adjudications().size() + 1);
        repository.appendAdjudication(new AdjudicationEvent(adjudicationId, decisionType, actor,
                rationale, List.copyOf(targetStableUnitIds), outcome));
        String decisionId = "DEC-" + String.format("%03d", snapshot.decisions().size() + 1);
        repository.appendDecision(new DecisionRecord(decisionId, adjudicationId, null,
                outcome, rationale));
        WindowComputation computation = computeAndStore(0, recordingEnd(snapshot),
                latestComputationIds(snapshot), true);
        appendEvent("ADJUDICATION", rationale, computation.computationId(), null, null, adjudicationId);
        return computation;
    }

    public WindowComputation recomputeWindow(long start, long end) {
        CaseSnapshot snapshot = snapshot();
        WindowComputation computation = computeAndStore(start, end, latestComputationIds(snapshot), true);
        appendEvent("WINDOW_RECOMPUTE", "修改时间窗 [" + start + ", " + end + "]，只重算相交簇",
                computation.computationId(), null, null, null);
        return computation;
    }

    public CaseEvent appendEvent(String type, String summary, String computationId,
                                 String ruleVersionId, String sourceRevisionId, String adjudicationId) {
        long seq = repository.nextEventSeq();
        CaseEvent event = new CaseEvent("EVT-" + String.format("%04d", seq), seq, type, summary,
                computationId, ruleVersionId, sourceRevisionId, adjudicationId);
        repository.appendCaseEvent(event);
        return event;
    }

    public RuleVersion latestRule(CaseSnapshot snapshot) {
        return snapshot.ruleVersions().stream().reduce((first, second) -> second)
                .orElseThrow(() -> new CaseException(CaseException.Kind.SEMANTIC_UNSOLVABLE,
                        new Diagnostic("MISSING_RULE", "snapshot.ruleVersions",
                                "缺少规则版本", null, null, null)));
    }

    public long recordingEnd(CaseSnapshot snapshot) {
        return snapshot.detections().stream().mapToLong(DetectionEvent::sampleIndex).max().orElse(0);
    }

    private List<String> latestComputationIds(CaseSnapshot snapshot) {
        if (snapshot.computations().isEmpty()) {
            return List.of();
        }
        return List.of(snapshot.computations().get(snapshot.computations().size() - 1).computationId());
    }

    private String nextComputationId(CaseSnapshot snapshot) {
        return "COMP-" + String.format("%04d", snapshot.computations().size() + 1);
    }
}
