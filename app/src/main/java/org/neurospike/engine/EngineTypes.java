package org.neurospike.engine;

import org.neurospike.domain.DerivedResult;
import org.neurospike.domain.EvidenceEvent;

import java.util.List;

public record AnalysisReport(List<DerivedResult> results, List<EvidenceEvent> events) {
    public AnalysisReport {
        results = List.copyOf(results);
        events = List.copyOf(events);
    }
}
