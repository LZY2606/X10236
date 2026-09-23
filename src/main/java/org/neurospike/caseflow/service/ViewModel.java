package org.neurospike.caseflow.service;

import org.neurospike.caseflow.domain.DomainRecords.WindowComputation;

import java.util.List;

public record ViewModel(
        String recordingId,
        String timeBasis,
        List<UnitSummary> units,
        List<Panel> panels,
        WindowComputation computation,
        List<DownstreamRecord> downstream,
        List<DiagnosticView> diagnostics) {

    public record UnitSummary(
            String stableUnitId,
            String latestClusterLabel,
            String labelVersionId,
            int detectionCount,
            List<String> detectionIds) {
    }

    public record Panel(
            String stableId,
            String title,
            String svg,
            String unit,
            String coordinateBasis,
            long validRangeStartSample,
            long validRangeEndSample,
            String invalidReason) {
    }

    public record DownstreamRecord(
            String stableId,
            String recordType,
            String recordId,
            String sourceRelation) {
    }

    public record DiagnosticView(
            String code,
            String path,
            String message,
            String materialId,
            String unit,
            String coordinateBasis) {
    }
}
