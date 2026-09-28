package org.neurospike.domain;

public record AnalysisWindow(
        String id,
        long startMs,
        long endMs,
        String startRule,
        String endRule,
        String timeBasis,
        String status,
        String version) {
}
