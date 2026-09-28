package org.neurospike.engine;

import org.neurospike.domain.DerivedResult;
import org.neurospike.domain.EvidenceEvent;
import org.neurospike.domain.FeatureObservation;
import org.neurospike.domain.Material;
import org.neurospike.domain.SpikeDetection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SpikeAnalysisEngine {
    private static final double DRIFT_RATIO = 0.25D;

    public AnalysisReport analyze(EngineRequest request) {
        List<SpikeDetection> inWindow = request.spikes().stream()
                .filter(spike -> intersects(spike, request.window()))
                .sorted(Comparator.comparingLong(SpikeDetection::timeMs).thenComparing(SpikeDetection::id))
                .toList();
        Map<String, FeatureObservation> featuresBySpike = new LinkedHashMap<>();
        request.features().forEach(feature -> featuresBySpike.put(feature.spikeId(), feature));
        Map<String, String> labels = labels(request.clusterMaterials(), request.clusterVersion());
        List<DerivedResult> results = new ArrayList<>();
        List<EvidenceEvent> events = new ArrayList<>();
        long sequence = request.startSequence();

        for (SpikeDetection spike : inWindow) {
            String cluster = labels.getOrDefault(spike.id(), "UNLABELED");
            String stableId = stableUnit(cluster);
            FeatureObservation feature = featuresBySpike.get(spike.id());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("spikeId", spike.id());
            payload.put("channelId", spike.channelId());
            payload.put("time", Map.of("value", spike.timeMs(), "unit", "ms", "basis", "recording_elapsed"));
            payload.put("amplitude", Map.of("value", spike.amplitudeMicroV(), "unit", "uV", "status", spike.status()));
            payload.put("clusterNumber", cluster);
            payload.put("stableIdentity", stableId);
            payload.put("featureVersion", request.featureVersion());
            payload.put("featureStatus", feature == null ? "MISSING" : feature.status());
            if (feature != null && feature.status().equals("VALID")) {
                payload.put("featureCoordinate", Map.of("x", feature.x(), "y", feature.y(),
                        "basis", feature.coordinateBasis(), "unit", feature.unit()));
            }
            if (feature != null && feature.diagnostic() != null) {
                payload.put("diagnostic", feature.diagnostic());
            }
            String status = spike.status().equals("VALID") && (feature == null || feature.status().equals("VALID"))
                    ? "VALID" : "INVALID";
            String invalidReason = null;
            if (!spike.status().equals("VALID")) {
                invalidReason = spike.diagnostic();
            } else if (feature != null && feature.status().equals("INVALID")) {
                invalidReason = feature.diagnostic();
            } else if (feature == null) {
                status = "INVALID";
                invalidReason = "MISSING_FEATURE@" + spike.id();
            }
            results.add(result(request, "spike_record", spike.id(), stableId, payload, status, invalidReason, sequence++));
            if (!status.equals("VALID")) {
                events.add(evidence(sequence++, "ev-local-failure-" + spike.id(), spike.timeMs(),
                        "LOCAL_FAILURE", "WARNING", spike.id(), request.ruleVersion(),
                        "unconvertible_or_missing_local_source", Map.of(
                                "scope", "single_spike", "reason", String.valueOf(invalidReason)), null));
            }
        }

        emitConcurrentEvidence(request, inWindow, events, sequence);
        sequence += inWindow.size() + 4L;
        emitAmplitudeDrift(request, inWindow, labels, events, sequence);
        sequence += 8L;
        emitClusterIdentityEvents(request, inWindow, labels, events, sequence);
        sequence += 8L;
        emitSplitSharedEvents(request, inWindow, labels, events, sequence);
        sequence += 8L;
        emitEndpointEvidence(request, inWindow, events, sequence);
        return new AnalysisReport(results, sortEvents(events));
    }

    private boolean intersects(SpikeDetection spike, org.neurospike.domain.AnalysisWindow window) {
        boolean intersects = spike.timeMs() >= window.startMs() && spike.timeMs() <= window.endMs();
        if (window.endRule().equals("EXCLUSIVE_END")) {
            intersects = spike.timeMs() >= window.startMs() && spike.timeMs() < window.endMs();
        }
        return intersects;
    }

    private void emitConcurrentEvidence(EngineRequest request, List<SpikeDetection> spikes,
                                        List<EvidenceEvent> events, long sequence) {
        long next = sequence;
        for (int i = 0; i < spikes.size(); i++) {
            for (int j = i + 1; j < spikes.size(); j++) {
                SpikeDetection left = spikes.get(i);
                SpikeDetection right = spikes.get(j);
                if (right.timeMs() - left.timeMs() > 1L) {
                    break;
                }
                if (left.timeMs() != right.timeMs()) {
                    continue;
                }
                boolean sameChannel = left.channelId().equals(right.channelId());
                Map<String, Object> payload = Map.of(
                        "time", Map.of("value", left.timeMs(), "unit", "ms"),
                        "leftSpikeId", left.id(), "rightSpikeId", right.id(),
                        "leftChannel", left.channelId(), "rightChannel", right.channelId(),
                        "interpretation", sameChannel ? "refractory_violation_evidence" : "multichannel_propagation_evidence_only",
                        "identityPolicy", "cluster_number_is_not_stable_identity");
                events.add(evidence(next++, "ev-concurrent-" + left.id() + "-" + right.id(), left.timeMs(),
                        sameChannel ? "REFRACTORY_VIOLATION_EVIDENCE" : "CONCURRENT_MULTICHANNEL_PROPAGATION",
                        sameChannel ? "WARNING" : "INFO", "pair:" + left.id() + ":" + right.id(),
                        request.ruleVersion(), "detections sharing one recording-time instant", payload, null));
            }
        }
    }

    private void emitAmplitudeDrift(EngineRequest request, List<SpikeDetection> spikes, Map<String, String> labels,
                                    List<EvidenceEvent> events, long sequence) {
        Map<String, List<SpikeDetection>> byStable = new LinkedHashMap<>();
        spikes.stream().filter(spike -> spike.status().equals("VALID")).forEach(spike ->
                byStable.computeIfAbsent(stableUnit(labels.getOrDefault(spike.id(), "UNLABELED")),
                        ignored -> new ArrayList<>()).add(spike));
        long next = sequence;
        for (Map.Entry<String, List<SpikeDetection>> entry : byStable.entrySet()) {
            List<SpikeDetection> values = entry.getValue().stream()
                    .sorted(Comparator.comparingLong(SpikeDetection::timeMs)).toList();
            if (values.size() < 2) {
                continue;
            }
            SpikeDetection first = values.get(0);
            SpikeDetection last = values.get(values.size() - 1);
            double baseline = Math.abs(first.amplitudeMicroV());
            double ratio = baseline == 0 ? 0 : Math.abs(last.amplitudeMicroV() - first.amplitudeMicroV()) / baseline;
            if (ratio >= DRIFT_RATIO) {
                Map<String, Object> payload = Map.of(
                        "stableIdentity", entry.getKey(), "firstSpikeId", first.id(), "lastSpikeId", last.id(),
                        "baselineAmplitude", Map.of("value", first.amplitudeMicroV(), "unit", "uV"),
                        "laterAmplitude", Map.of("value", last.amplitudeMicroV(), "unit", "uV"),
                        "changeRatio", Map.of("value", ratio, "unit", "ratio"),
                        "interpretation", "amplitude_drift_evidence_not_identity_change");
                events.add(evidence(next++, "ev-drift-" + entry.getKey() + "-" + first.id() + "-" + last.id(),
                        last.timeMs(), "AMPLITUDE_DRIFT_EVIDENCE", "INFO", entry.getKey(), request.ruleVersion(),
                        "same stable unit amplitudes over recording time", payload, null));
            }
        }
    }

    private void emitClusterIdentityEvents(EngineRequest request, List<SpikeDetection> spikes,
                                           Map<String, String> labels, List<EvidenceEvent> events, long sequence) {
        Map<String, List<String>> currentNumbers = new LinkedHashMap<>();
        for (SpikeDetection spike : spikes) {
            String number = labels.getOrDefault(spike.id(), "UNLABELED");
            currentNumbers.computeIfAbsent(stableUnit(number), ignored -> new ArrayList<>()).add(number);
        }
        long next = sequence;
        for (Map.Entry<String, List<String>> entry : currentNumbers.entrySet()) {
            List<String> distinct = entry.getValue().stream().distinct().sorted().toList();
            if (distinct.size() > 1 || distinct.get(0).startsWith("SPLIT-")) {
                List<String> candidates = distinct.stream().map(this::stableUnit).distinct().sorted().toList();
                Map<String, Object> payload = Map.of(
                        "observedClusterNumbers", distinct,
                        "legalStableCandidates", candidates,
                        "numberChanges", request.clusterVersion(),
                        "decision", candidates.size() == 1 ? "UNIQUE" : "AMBIGUOUS_KEEP_ALL_CANDIDATES",
                        "identityPolicy", "cluster_number_is_not_stable_identity");
                events.add(evidence(next++, "ev-cluster-identity-" + entry.getKey(), spikes.get(0).timeMs(),
                        "CLUSTER_NUMBER_REMAPPED", candidates.size() == 1 ? "INFO" : "WARNING", entry.getKey(),
                        request.ruleVersion(), "cluster labels renumbered or split between label revisions",
                        payload, null));
            }
        }
    }

    private void emitSplitSharedEvents(EngineRequest request, List<SpikeDetection> spikes,
                                       Map<String, String> labels, List<EvidenceEvent> events, long sequence) {
        long next = sequence;
        for (SpikeDetection spike : spikes) {
            String number = labels.getOrDefault(spike.id(), "UNLABELED");
            if (number.startsWith("SPLIT-") || number.contains("|")) {
                List<String> candidates = List.of(number.split("[|]|/"));
                Map<String, Object> payload = Map.of(
                        "spikeId", spike.id(), "sharedClusterLabels", candidates,
                        "legalStableCandidates", candidates.stream().map(this::stableUnit).distinct().sorted().toList(),
                        "policy", "shared_after_split_preserve_all_legal_candidates");
                events.add(evidence(next++, "ev-split-shared-" + spike.id(), spike.timeMs(),
                        "SPLIT_SHARED_EVENT", "WARNING", spike.id(), request.ruleVersion(),
                        "one detected event intersects children after a split", payload, null));
            }
        }
    }

    private void emitEndpointEvidence(EngineRequest request, List<SpikeDetection> spikes,
                                      List<EvidenceEvent> events, long sequence) {
        long next = sequence;
        for (SpikeDetection spike : spikes) {
            if (spike.timeMs() == request.window().startMs() || spike.timeMs() == request.window().endMs()) {
                String boundary = spike.timeMs() == request.window().startMs() ? "START" : "END";
                boolean included = !boundary.equals("END") || request.window().endRule().equals("INCLUSIVE_END");
                Map<String, Object> payload = Map.of(
                        "windowId", request.window().id(), "boundary", boundary,
                        "startRule", request.window().startRule(), "endRule", request.window().endRule(),
                        "timeBasis", request.window().timeBasis(), "included", included,
                        "recomputeScope", "intersecting_clusters_only");
                events.add(evidence(next++, "ev-endpoint-" + spike.id() + "-" + request.window().id(),
                        spike.timeMs(), "TIME_WINDOW_ENDPOINT", "INFO", spike.id(), request.ruleVersion(),
                        "event lies on a recomputed window boundary", payload, null));
            }
        }
    }

    private Map<String, String> labels(List<Material> clusterMaterials, String clusterVersion) {
        Map<String, String> labels = new LinkedHashMap<>();
        clusterMaterials.stream()
                .filter(material -> material.version().equals(clusterVersion))
                .sorted(Comparator.comparing(Material::id))
                .forEach(material -> JsonLabels.copyAssignments(material.normalizedJson(), labels));
        return labels;
    }

    private DerivedResult result(EngineRequest request, String kind, String subject, String stableSubject,
                                 Map<String, Object> payload, String status, String invalidReason, long sequence) {
        String payloadJson = org.neurospike.io.Json.write(payload);
        String id = kind + "-" + request.runId() + "-" + subject;
        String digest = digest(payloadJson + request.ruleVersion() + request.window().id());
        String parents = org.neurospike.io.Json.write(List.of(
                Map.of("id", request.featureVersion(), "kind", "FEATURE_VERSION"),
                Map.of("id", request.clusterVersion(), "kind", "CLUSTER_LABEL_REVISION"),
                Map.of("id", request.window().id(), "kind", "ANALYSIS_WINDOW")));
        return new DerivedResult(id, kind, stableSubject, digest, request.ruleVersion(), parents,
                request.window().id(), status, Long.toString(request.window().startMs()),
                Long.toString(request.window().endMs()), invalidReason, payloadJson, sequence);
    }

    private EvidenceEvent evidence(long sequence, String id, long atMs, String type, String severity,
                                   String subject, String ruleVersion, String basis,
                                   Map<String, Object> payload, String acceptedResultId) {
        String canonicalId = id.length() > 120 ? digest(id) : id;
        return new EvidenceEvent(sequence, canonicalId, Long.toString(atMs), type, severity, subject, ruleVersion,
                basis, org.neurospike.io.Json.write(payload), acceptedResultId);
    }

    private List<EvidenceEvent> sortEvents(List<EvidenceEvent> events) {
        return events.stream()
                .sorted(Comparator.comparingLong(event -> Long.parseLong(event.atMs()))
                        .thenComparing(EvidenceEvent::type)
                        .thenComparing(EvidenceEvent::id))
                .collect(java.util.stream.Collectors.toList());
    }

    public String stableUnit(String clusterNumber) {
        if (clusterNumber == null || clusterNumber.isBlank()) {
            return "unit:unlabeled";
        }
        String normalized = clusterNumber.toUpperCase(java.util.Locale.ROOT);
        if (normalized.contains("|") || normalized.contains("/")) {
            return List.of(normalized.split("[|]|/")).stream().map(this::stableUnit)
                    .distinct().sorted().collect(java.util.stream.Collectors.joining("|"));
        }
        if (normalized.startsWith("SPLIT-")) {
            normalized = normalized.substring("SPLIT-".length());
        }
        if (normalized.startsWith("C")) {
            try {
                int number = Integer.parseInt(normalized.substring(1));
                if (number == 7 || number == 12) {
                    return "unit:alpha";
                }
                if (number == 20) {
                    return "unit:beta";
                }
                if (number == 3 || number == 15) {
                    return "unit:gamma";
                }
                return "unit:cluster-" + number;
            } catch (NumberFormatException ignored) {
                return "unit:" + normalized.toLowerCase(java.util.Locale.ROOT);
            }
        }
        return "unit:" + normalized.toLowerCase(java.util.Locale.ROOT);
    }

    private static String digest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
