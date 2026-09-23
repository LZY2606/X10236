package org.neurospike.caseflow.db;

import org.neurospike.caseflow.domain.DomainRecords.AdjudicationEvent;
import org.neurospike.caseflow.domain.DomainRecords.CaseEvent;
import org.neurospike.caseflow.domain.DomainRecords.ChannelGeometry;
import org.neurospike.caseflow.domain.DomainRecords.DecisionRecord;
import org.neurospike.caseflow.domain.DomainRecords.DetectionEvent;
import org.neurospike.caseflow.domain.DomainRecords.DetectionThreshold;
import org.neurospike.caseflow.domain.DomainRecords.FeaturePoint;
import org.neurospike.caseflow.domain.DomainRecords.FeatureVersion;
import org.neurospike.caseflow.domain.DomainRecords.LabelAssignment;
import org.neurospike.caseflow.domain.DomainRecords.ProbeGeometry;
import org.neurospike.caseflow.domain.DomainRecords.RecordingContext;
import org.neurospike.caseflow.domain.DomainRecords.RuleVersion;
import org.neurospike.caseflow.domain.DomainRecords.SourceRevision;
import org.neurospike.caseflow.domain.DomainRecords.WaveformFragment;
import org.neurospike.caseflow.domain.DomainRecords.WindowComputation;

import java.util.List;

public record CaseSnapshot(
        RecordingContext recording,
        List<ProbeGeometry> probeGeometries,
        List<DetectionThreshold> thresholds,
        List<WaveformFragment> waveforms,
        List<DetectionEvent> detections,
        List<FeatureVersion> featureVersions,
        List<FeaturePoint> featurePoints,
        List<LabelAssignment> labelAssignments,
        List<AdjudicationEvent> adjudications,
        List<RuleVersion> ruleVersions,
        List<SourceRevision> sourceRevisions,
        List<WindowComputation> computations,
        List<CaseEvent> caseEvents,
        List<DecisionRecord> decisions) {
    public static CaseSnapshot empty() {
        return new CaseSnapshot(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
