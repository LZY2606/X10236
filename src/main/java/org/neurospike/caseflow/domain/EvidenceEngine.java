package org.neurospike.caseflow.domain;

import org.neurospike.caseflow.domain.DomainRecords.AdjudicationEvent;
import org.neurospike.caseflow.domain.DomainRecords.Candidate;
import org.neurospike.caseflow.domain.DomainRecords.DetectionEvent;
import org.neurospike.caseflow.domain.DomainRecords.EvidenceRecord;
import org.neurospike.caseflow.domain.DomainRecords.FeatureVersion;
import org.neurospike.caseflow.domain.DomainRecords.LabelAssignment;
import org.neurospike.caseflow.domain.DomainRecords.RecordingContext;
import org.neurospike.caseflow.domain.DomainRecords.RuleVersion;
import org.neurospike.caseflow.domain.DomainRecords.WaveformFragment;
import org.neurospike.caseflow.domain.DomainRecords.WindowComputation;
import org.neurospike.caseflow.util.JsonCanonical;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class EvidenceEngine {
    public static final String STATUS_VALID = "VALID";
    public static final String STATUS_VALID_WITH_DIAGNOSTICS = "VALID_WITH_DIAGNOSTICS";
    public static final String STATUS_INVALID_INPUT = "INVALID_INPUT";
    public static final String STATUS_LOCAL_INVALID = "LOCAL_INVALID";
    public static final String STATUS_BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";

    public record Input(
            RecordingContext recording,
            List<DetectionEvent> detections,
            Map<String, WaveformFragment> waveforms,
            List<LabelAssignment> allLabels,
            String labelVersionId,
            FeatureVersion featureVersion,
            RuleVersion ruleVersion,
            List<AdjudicationEvent> adjudications,
            long startSample,
            long endSample,
            List<String> parentIds,
            String computationId) {
    }

    public WindowComputation compute(Input input) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<EvidenceRecord> evidence = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        RuleVersion rule = input.ruleVersion();

        if (input.startSample() > input.endSample()) {
            diagnostics.add(new Diagnostic("WINDOW_INVERTED", "window.startSample",
                    "时间窗起点大于终点，无法求交", null, input.recording().sampleUnit(),
                    input.recording().timeBasis()));
            return result(input, STATUS_INVALID_INPUT, diagnostics, evidence, candidates, Set.of());
        }

        List<DetectionEvent> ordered = input.detections().stream()
                .sorted(Comparator.comparingLong(DetectionEvent::sampleIndex)
                        .thenComparingInt(DetectionEvent::channel)
                        .thenComparing(DetectionEvent::detectionId))
                .toList();

        if (ordered.size() > rule.budget()) {
            diagnostics.add(new Diagnostic("BUDGET_EXHAUSTED", "rule.budget",
                    "资源预算耗尽：检测事件 " + ordered.size() + " 超过预算 " + rule.budget(),
                    null, "count", null));
            return result(input, STATUS_BUDGET_EXHAUSTED, diagnostics, evidence, candidates, Set.of());
        }

        List<DetectionEvent> inWindow = ordered.stream()
                .filter(detection -> detection.sampleIndex() >= input.startSample()
                        && detection.sampleIndex() <= input.endSample())
                .toList();

        boolean invalidInput = false;
        boolean localInvalid = false;
        boolean unitDiagnostics = false;
        Map<String, Double> normalizedPeak = new LinkedHashMap<>();
        for (int index = 0; index < inWindow.size(); index++) {
            DetectionEvent detection = inWindow.get(index);
            WaveformFragment fragment = input.waveforms().get(detection.waveformId());
            String path = "detections[" + index + "].waveformId";
            if (fragment == null) {
                diagnostics.add(new Diagnostic("MISSING_WAVEFORM", path,
                        "检测事件 " + detection.detectionId() + " 缺少波形片段 " + detection.waveformId(),
                        detection.detectionId(), detection.amplitudeUnit(), input.recording().timeBasis()));
                invalidInput = true;
                continue;
            }
            if (fragment.samples() == null || fragment.samples().isEmpty()
                    || fragment.samples().stream().anyMatch(value -> value == null || value.isNaN())) {
                diagnostics.add(new Diagnostic("LOCAL_INVALID", "waveforms." + fragment.waveformId() + ".samples",
                        "波形片段 " + fragment.waveformId() + " 局部失效：采样为空或含非数值",
                        fragment.waveformId(), fragment.amplitudeUnit(), input.recording().timeBasis()));
                localInvalid = true;
                continue;
            }
            Double factor = amplitudeToMicrovolt(fragment.amplitudeUnit());
            double peak = fragment.samples().stream().mapToDouble(Math::abs).max().orElse(0.0);
            if (factor == null) {
                diagnostics.add(new Diagnostic("UNIT_UNCONVERTIBLE", "waveforms." + fragment.waveformId() + ".amplitudeUnit",
                        "单位 " + fragment.amplitudeUnit() + " 无法转换，原始值保留在原位置",
                        fragment.waveformId(), fragment.amplitudeUnit(), input.recording().timeBasis()));
                unitDiagnostics = true;
            } else {
                peak = peak * factor;
            }
            normalizedPeak.put(detection.detectionId(), peak);
        }

        List<LabelAssignment> activeLabels = input.allLabels().stream()
                .filter(label -> label.labelVersionId().equals(input.labelVersionId()))
                .sorted(Comparator.comparing(LabelAssignment::stableUnitId))
                .toList();
        Map<String, List<String>> detectionToUnits = new TreeMap<>();
        Map<String, String> unitToCluster = new TreeMap<>();
        for (LabelAssignment label : activeLabels) {
            unitToCluster.put(label.stableUnitId(), label.clusterLabel());
            for (String detectionId : label.detectionIds()) {
                detectionToUnits.computeIfAbsent(detectionId, key -> new ArrayList<>()).add(label.stableUnitId());
            }
        }

        Set<String> affected = new LinkedHashSet<>();
        Map<String, List<DetectionEvent>> byUnit = new TreeMap<>();
        for (DetectionEvent detection : inWindow) {
            List<String> units = detectionToUnits.getOrDefault(detection.detectionId(), List.of());
            for (String unit : units) {
                affected.add(unit);
                byUnit.computeIfAbsent(unit, key -> new ArrayList<>()).add(detection);
            }
        }

        int evidenceSeq = 0;
        double samplesPerMs = input.recording().sampleRateHz() / 1000.0;
        for (Map.Entry<String, List<DetectionEvent>> entry : byUnit.entrySet()) {
            List<DetectionEvent> unitDetections = entry.getValue();
            for (int index = 1; index < unitDetections.size(); index++) {
                long delta = unitDetections.get(index).sampleIndex() - unitDetections.get(index - 1).sampleIndex();
                double deltaMs = delta / samplesPerMs;
                if (deltaMs < rule.refractoryMs()) {
                    evidence.add(new EvidenceRecord(evidenceId(input, "REFRACTORY_VIOLATION", evidenceSeq++),
                            "REFRACTORY_VIOLATION", "EVIDENCE",
                            "不应期违例仅作为证据：簇内间隔 " + round(deltaMs) + " ms 小于 "
                                    + rule.refractoryMs() + " ms；聚类序号不是稳定身份",
                            List.of(entry.getKey(), unitDetections.get(index - 1).detectionId(),
                                    unitDetections.get(index).detectionId()),
                            "ms", input.recording().timeBasis()));
                }
            }
            double min = Double.MAX_VALUE;
            double max = 0.0;
            for (DetectionEvent detection : unitDetections) {
                Double peak = normalizedPeak.get(detection.detectionId());
                if (peak == null) {
                    continue;
                }
                min = Math.min(min, peak);
                max = Math.max(max, peak);
            }
            if (min != Double.MAX_VALUE && min > 0 && max / min >= rule.driftRatioThreshold()) {
                evidence.add(new EvidenceRecord(evidenceId(input, "AMPLITUDE_DRIFT", evidenceSeq++),
                        "AMPLITUDE_DRIFT", "EVIDENCE",
                        "波形振幅漂移仅作为证据：峰峰值比 " + round(max / min) + " 超过阈值 "
                                + rule.driftRatioThreshold() + "；聚类序号不是稳定身份",
                        List.of(entry.getKey()), "uV", input.recording().timeBasis()));
            }
        }

        long coincidenceSamples = Math.round(rule.coincidenceMs() * samplesPerMs);
        for (int left = 0; left < inWindow.size(); left++) {
            for (int right = left + 1; right < inWindow.size(); right++) {
                DetectionEvent a = inWindow.get(left);
                DetectionEvent b = inWindow.get(right);
                if (b.sampleIndex() - a.sampleIndex() > coincidenceSamples) {
                    break;
                }
                if (a.channel() == b.channel()) {
                    continue;
                }
                List<String> unitsA = detectionToUnits.getOrDefault(a.detectionId(), List.of());
                List<String> unitsB = detectionToUnits.getOrDefault(b.detectionId(), List.of());
                boolean sameUnit = unitsA.stream().anyMatch(unitsB::contains);
                String code = sameUnit ? "MULTICHANNEL_PROPAGATION" : "CONCURRENT_SPIKE";
                String message = sameUnit
                        ? "同时多通道传播仅作为证据：通道 " + a.channel() + " 与 " + b.channel()
                        + " 在 " + rule.coincidenceMs() + " ms 内共同发放；聚类序号不是稳定身份"
                        : "并发棘波：通道 " + a.channel() + " 与 " + b.channel() + " 在 "
                        + rule.coincidenceMs() + " ms 内分别发放";
                evidence.add(new EvidenceRecord(evidenceId(input, code, evidenceSeq++), code, "EVIDENCE",
                        message, List.of(a.detectionId(), b.detectionId()), "ms", input.recording().timeBasis()));
            }
        }

        Map<String, Set<String>> labelsByUnit = new TreeMap<>();
        for (LabelAssignment label : input.allLabels()) {
            labelsByUnit.computeIfAbsent(label.stableUnitId(), key -> new LinkedHashSet<>())
                    .add(label.labelVersionId() + ":" + label.clusterLabel());
        }
        for (Map.Entry<String, Set<String>> entry : labelsByUnit.entrySet()) {
            Set<String> distinct = new LinkedHashSet<>();
            for (String value : entry.getValue()) {
                distinct.add(value.substring(value.indexOf(':') + 1));
            }
            if (distinct.size() > 1) {
                evidence.add(new EvidenceRecord(evidenceId(input, "CLUSTER_LABEL_CHANGE", evidenceSeq++),
                        "CLUSTER_LABEL_CHANGE", "EVIDENCE",
                        "聚类编号变化：稳定单元 " + entry.getKey() + " 的历史编号为 " + distinct
                                + "；编号变化不改变稳定身份",
                        List.of(entry.getKey()), null, null));
            }
        }

        int candidateSeq = 0;
        for (Map.Entry<String, List<String>> entry : detectionToUnits.entrySet()) {
            List<String> units = entry.getValue().stream().distinct().sorted().toList();
            if (units.size() > 1) {
                evidence.add(new EvidenceRecord(evidenceId(input, "SPLIT_SHARED_EVENT", evidenceSeq++),
                        "SPLIT_SHARED_EVENT", "EVIDENCE",
                        "拆分后共享事件：检测 " + entry.getKey() + " 同时属于 " + units,
                        new ArrayList<>(units), null, null));
                candidates.add(new Candidate(candidateId(input, candidateSeq++), "VALID_CANDIDATE",
                        "无法唯一决定，保留全部合法候选", units));
            }
        }

        long epsilonSamples = Math.round(rule.endpointEpsilonMs() * samplesPerMs);
        for (DetectionEvent detection : inWindow) {
            long toStart = Math.abs(detection.sampleIndex() - input.startSample());
            long toEnd = Math.abs(detection.sampleIndex() - input.endSample());
            if (toStart <= epsilonSamples || toEnd <= epsilonSamples) {
                evidence.add(new EvidenceRecord(evidenceId(input, "WINDOW_ENDPOINT", evidenceSeq++),
                        "WINDOW_ENDPOINT", "EVIDENCE",
                        "时间窗端点：检测 " + detection.detectionId() + " 落在窗口端点 ±"
                                + rule.endpointEpsilonMs() + " ms 内",
                        List.of(detection.detectionId()), input.recording().sampleUnit(),
                        input.recording().timeBasis()));
            }
        }

        Map<String, Set<String>> outcomesByUnit = new TreeMap<>();
        for (AdjudicationEvent adjudication : input.adjudications()) {
            for (String unit : adjudication.targetStableUnitIds()) {
                outcomesByUnit.computeIfAbsent(unit, key -> new LinkedHashSet<>())
                        .add(adjudication.decisionType() + "=" + adjudication.outcome());
            }
        }
        for (Map.Entry<String, Set<String>> entry : outcomesByUnit.entrySet()) {
            if (entry.getValue().size() > 1) {
                evidence.add(new EvidenceRecord(evidenceId(input, "ADJUDICATION_CONFLICT", evidenceSeq++),
                        "ADJUDICATION_CONFLICT", "CONFLICT",
                        "裁定冲突：稳定单元 " + entry.getKey() + " 存在互相冲突的人工决定 " + entry.getValue(),
                        List.of(entry.getKey()), null, null));
                candidates.add(new Candidate(candidateId(input, candidateSeq++), "CONFLICTING",
                        "人工裁定互相冲突，等待复核", List.of(entry.getKey())));
            }
        }

        String status = STATUS_VALID;
        if (invalidInput) {
            status = STATUS_INVALID_INPUT;
        } else if (localInvalid) {
            status = STATUS_LOCAL_INVALID;
        } else if (unitDiagnostics || !diagnostics.isEmpty()) {
            status = STATUS_VALID_WITH_DIAGNOSTICS;
        }
        return result(input, status, diagnostics, evidence, candidates, affected);
    }

    private WindowComputation result(Input input, String status, List<Diagnostic> diagnostics,
                                     List<EvidenceRecord> evidence, List<Candidate> candidates,
                                     Set<String> affected) {
        String digestSource = JsonCanonical.canonical(JsonCanonical.MAPPER.valueToTree(Map.of(
                "computationId", input.computationId(),
                "ruleVersionId", input.ruleVersion().ruleVersionId(),
                "featureVersionId", input.featureVersion().featureVersionId(),
                "labelVersionId", input.labelVersionId(),
                "startSample", input.startSample(),
                "endSample", input.endSample(),
                "detections", input.detections().stream()
                        .map(DetectionEvent::detectionId).sorted().toList(),
                "waveforms", input.waveforms().keySet().stream().sorted().toList())));
        return new WindowComputation(input.computationId(), input.ruleVersion().ruleVersionId(),
                input.featureVersion().featureVersionId(), input.labelVersionId(),
                input.startSample(), input.endSample(), status,
                JsonCanonical.sha256(digestSource), List.copyOf(input.parentIds()),
                List.copyOf(evidence), List.copyOf(candidates), List.copyOf(diagnostics),
                new LinkedHashSet<>(affected), false);
    }

    private String evidenceId(Input input, String code, int seq) {
        return input.computationId() + ":ev:" + code + ":" + seq;
    }

    private String candidateId(Input input, int seq) {
        return input.computationId() + ":cand:" + seq;
    }

    private Double amplitudeToMicrovolt(String unit) {
        if (unit == null) {
            return null;
        }
        return switch (unit) {
            case "uV", "µV", "microvolt" -> 1.0;
            case "mV" -> 1000.0;
            case "V" -> 1_000_000.0;
            default -> null;
        };
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
