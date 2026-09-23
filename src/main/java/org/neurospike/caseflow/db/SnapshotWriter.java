package org.neurospike.caseflow.db;

import org.neurospike.caseflow.domain.DomainRecords.AdjudicationEvent;
import org.neurospike.caseflow.domain.DomainRecords.CaseEvent;
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
import org.neurospike.caseflow.util.JsonCanonical;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

final class SnapshotWriter {
    private SnapshotWriter() {
    }

    static int nextOrder(Connection connection, String table) throws SQLException {
        try (ResultSet result = connection.createStatement().executeQuery(
                "select coalesce(max(identity_order), -1) + 1 from " + table)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    static void clearAll(Connection connection) throws SQLException {
        for (String table : new String[]{"case_events", "decisions", "computations", "source_revisions",
                "rule_versions", "adjudications", "label_assignments", "feature_points", "feature_versions",
                "detection_events", "waveform_fragments", "detection_thresholds", "probe_geometries",
                "recording_context", "meta"}) {
            connection.createStatement().executeUpdate("delete from " + table);
        }
    }

    static void insertRecording(Connection connection, RecordingContext recording) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into recording_context
                (id, recording_id, time_basis, sample_rate_hz, sample_unit, amplitude_unit, coordinate_basis)
                values (1, ?, ?, ?, ?, ?, ?)""")) {
            statement.setString(1, recording.recordingId());
            statement.setString(2, recording.timeBasis());
            statement.setLong(3, recording.sampleRateHz());
            statement.setString(4, recording.sampleUnit());
            statement.setString(5, recording.amplitudeUnit());
            statement.setString(6, recording.coordinateBasis());
            statement.executeUpdate();
        }
    }

    static void insertProbe(Connection connection, ProbeGeometry probe) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into probe_geometries (probe_id, coordinate_basis, channels_json)
                values (?, ?, ?)""")) {
            statement.setString(1, probe.probeId());
            statement.setString(2, probe.coordinateBasis());
            statement.setString(3, JsonCanonical.MAPPER.writeValueAsString(probe.channels()));
            statement.executeUpdate();
        }
    }

    static void insertThreshold(Connection connection, DetectionThreshold threshold) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into detection_thresholds (threshold_id, channel, value, unit, comparator)
                values (?, ?, ?, ?, ?)""")) {
            statement.setString(1, threshold.thresholdId());
            statement.setInt(2, threshold.channel());
            statement.setDouble(3, threshold.value());
            statement.setString(4, threshold.unit());
            statement.setString(5, threshold.comparator());
            statement.executeUpdate();
        }
    }

    static void insertWaveform(Connection connection, WaveformFragment waveform) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into waveform_fragments
                (waveform_id, detection_id, channel, start_sample, sample_unit, amplitude_unit, samples_json)
                values (?, ?, ?, ?, ?, ?, ?)""")) {
            statement.setString(1, waveform.waveformId());
            statement.setString(2, waveform.detectionId());
            statement.setInt(3, waveform.channel());
            statement.setLong(4, waveform.startSample());
            statement.setString(5, waveform.sampleUnit());
            statement.setString(6, waveform.amplitudeUnit());
            statement.setString(7, JsonCanonical.MAPPER.writeValueAsString(waveform.samples()));
            statement.executeUpdate();
        }
    }

    static void insertDetection(Connection connection, DetectionEvent detection, int order) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into detection_events
                (detection_id, sample_index, channel, amplitude, amplitude_unit, waveform_id, identity_order)
                values (?, ?, ?, ?, ?, ?, ?)""")) {
            statement.setString(1, detection.detectionId());
            statement.setLong(2, detection.sampleIndex());
            statement.setInt(3, detection.channel());
            statement.setDouble(4, detection.amplitude());
            statement.setString(5, detection.amplitudeUnit());
            statement.setString(6, detection.waveformId());
            statement.setInt(7, order);
            statement.executeUpdate();
        }
    }

    static void insertFeatureVersion(Connection connection, FeatureVersion version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into feature_versions (feature_version_id, extractor, normalization, components_json)
                values (?, ?, ?, ?)""")) {
            statement.setString(1, version.featureVersionId());
            statement.setString(2, version.extractor());
            statement.setString(3, version.normalization());
            statement.setString(4, JsonCanonical.MAPPER.writeValueAsString(version.components()));
            statement.executeUpdate();
        }
    }

    static void insertFeaturePoint(Connection connection, FeaturePoint point, int order) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into feature_points
                (feature_point_id, detection_id, feature_version_id, coordinates_json, coordinate_basis, identity_order)
                values (?, ?, ?, ?, ?, ?)""")) {
            statement.setString(1, point.featurePointId());
            statement.setString(2, point.detectionId());
            statement.setString(3, point.featureVersionId());
            statement.setString(4, JsonCanonical.MAPPER.writeValueAsString(point.coordinates()));
            statement.setString(5, point.coordinateBasis());
            statement.setInt(6, order);
            statement.executeUpdate();
        }
    }

    static void insertLabel(Connection connection, LabelAssignment label, int order) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into label_assignments
                (label_version_id, cluster_label, stable_unit_id, detection_ids_json, identity_order)
                values (?, ?, ?, ?, ?)""")) {
            statement.setString(1, label.labelVersionId());
            statement.setString(2, label.clusterLabel());
            statement.setString(3, label.stableUnitId());
            statement.setString(4, JsonCanonical.MAPPER.writeValueAsString(label.detectionIds()));
            statement.setInt(5, order);
            statement.executeUpdate();
        }
    }

    static void insertAdjudication(Connection connection, AdjudicationEvent adjudication, int order)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into adjudications
                (adjudication_id, decision_type, actor, rationale, target_ids_json, outcome, identity_order)
                values (?, ?, ?, ?, ?, ?, ?)""")) {
            statement.setString(1, adjudication.adjudicationId());
            statement.setString(2, adjudication.decisionType());
            statement.setString(3, adjudication.actor());
            statement.setString(4, adjudication.rationale());
            statement.setString(5, JsonCanonical.MAPPER.writeValueAsString(adjudication.targetStableUnitIds()));
            statement.setString(6, adjudication.outcome());
            statement.setInt(7, order);
            statement.executeUpdate();
        }
    }

    static void insertRuleVersion(Connection connection, RuleVersion rule) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into rule_versions
                (rule_version_id, refractory_ms, coincidence_ms, drift_ratio_threshold, endpoint_epsilon_ms, budget)
                values (?, ?, ?, ?, ?, ?)""")) {
            statement.setString(1, rule.ruleVersionId());
            statement.setDouble(2, rule.refractoryMs());
            statement.setDouble(3, rule.coincidenceMs());
            statement.setDouble(4, rule.driftRatioThreshold());
            statement.setDouble(5, rule.endpointEpsilonMs());
            statement.setInt(6, rule.budget());
            statement.executeUpdate();
        }
    }

    static void insertSourceRevision(Connection connection, SourceRevision revision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into source_revisions (revision_id, target_type, target_id, replacement_id, reason)
                values (?, ?, ?, ?, ?)""")) {
            statement.setString(1, revision.revisionId());
            statement.setString(2, revision.targetType());
            statement.setString(3, revision.targetId());
            statement.setString(4, revision.replacementId());
            statement.setString(5, revision.reason());
            statement.executeUpdate();
        }
    }

    static void insertComputation(Connection connection, WindowComputation computation, int order)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into computations
                (computation_id, rule_version_id, feature_version_id, label_version_id, start_sample, end_sample,
                 status, input_digest, parents_json, evidence_json, candidates_json, diagnostics_json,
                 affected_json, adopted, identity_order)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            statement.setString(1, computation.computationId());
            statement.setString(2, computation.ruleVersionId());
            statement.setString(3, computation.featureVersionId());
            statement.setString(4, computation.labelVersionId());
            statement.setLong(5, computation.startSample());
            statement.setLong(6, computation.endSample());
            statement.setString(7, computation.status());
            statement.setString(8, computation.inputDigest());
            statement.setString(9, JsonCanonical.MAPPER.writeValueAsString(computation.parentIds()));
            statement.setString(10, JsonCanonical.MAPPER.writeValueAsString(computation.evidence()));
            statement.setString(11, JsonCanonical.MAPPER.writeValueAsString(computation.candidates()));
            statement.setString(12, JsonCanonical.MAPPER.writeValueAsString(computation.diagnostics()));
            statement.setString(13, JsonCanonical.MAPPER.writeValueAsString(computation.affectedStableUnitIds()));
            statement.setInt(14, computation.adopted() ? 1 : 0);
            statement.setInt(15, order);
            statement.executeUpdate();
        }
    }

    static void insertCaseEvent(Connection connection, CaseEvent event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into case_events
                (event_id, seq, event_type, summary, computation_id, rule_version_id, source_revision_id,
                 adjudication_id)
                values (?, ?, ?, ?, ?, ?, ?, ?)""")) {
            statement.setString(1, event.eventId());
            statement.setLong(2, event.seq());
            statement.setString(3, event.eventType());
            statement.setString(4, event.summary());
            statement.setString(5, event.computationId());
            statement.setString(6, event.ruleVersionId());
            statement.setString(7, event.sourceRevisionId());
            statement.setString(8, event.adjudicationId());
            statement.executeUpdate();
        }
    }

    static void insertDecision(Connection connection, DecisionRecord decision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into decisions (decision_id, adjudication_id, candidate_id, status, rationale)
                values (?, ?, ?, ?, ?)""")) {
            statement.setString(1, decision.decisionId());
            statement.setString(2, decision.adjudicationId());
            statement.setString(3, decision.candidateId());
            statement.setString(4, decision.status());
            statement.setString(5, decision.rationale());
            statement.executeUpdate();
        }
    }

    static void replaceAll(Connection connection, CaseSnapshot snapshot) throws SQLException {
        clearAll(connection);
        if (snapshot.recording() == null) {
            throw new SQLException("SEMANTIC_UNSOLVABLE: 缺少 recording_context");
        }
        insertRecording(connection, snapshot.recording());
        for (ProbeGeometry probe : snapshot.probeGeometries()) {
            insertProbe(connection, probe);
        }
        for (DetectionThreshold threshold : snapshot.thresholds()) {
            insertThreshold(connection, threshold);
        }
        for (WaveformFragment waveform : snapshot.waveforms()) {
            insertWaveform(connection, waveform);
        }
        int order = 0;
        for (DetectionEvent detection : snapshot.detections()) {
            insertDetection(connection, detection, order++);
        }
        for (FeatureVersion version : snapshot.featureVersions()) {
            insertFeatureVersion(connection, version);
        }
        order = 0;
        for (FeaturePoint point : snapshot.featurePoints()) {
            insertFeaturePoint(connection, point, order++);
        }
        order = 0;
        for (LabelAssignment label : snapshot.labelAssignments()) {
            insertLabel(connection, label, order++);
        }
        order = nextOrder(connection, "adjudications");
        for (AdjudicationEvent adjudication : snapshot.adjudications()) {
            insertAdjudication(connection, adjudication, order++);
        }
        for (RuleVersion rule : snapshot.ruleVersions()) {
            insertRuleVersion(connection, rule);
        }
        for (SourceRevision revision : snapshot.sourceRevisions()) {
            insertSourceRevision(connection, revision);
        }
        order = nextOrder(connection, "computations");
        for (WindowComputation computation : snapshot.computations()) {
            insertComputation(connection, computation, order++);
        }
        for (CaseEvent event : snapshot.caseEvents()) {
            insertCaseEvent(connection, event);
        }
        for (DecisionRecord decision : snapshot.decisions()) {
            insertDecision(connection, decision);
        }
    }
}
