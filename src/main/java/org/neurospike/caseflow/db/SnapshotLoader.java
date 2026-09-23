package org.neurospike.caseflow.db;

import com.fasterxml.jackson.core.type.TypeReference;
import org.neurospike.caseflow.domain.Diagnostic;
import org.neurospike.caseflow.domain.DomainRecords.AdjudicationEvent;
import org.neurospike.caseflow.domain.DomainRecords.Candidate;
import org.neurospike.caseflow.domain.DomainRecords.CaseEvent;
import org.neurospike.caseflow.domain.DomainRecords.ChannelGeometry;
import org.neurospike.caseflow.domain.DomainRecords.DecisionRecord;
import org.neurospike.caseflow.domain.DomainRecords.DetectionEvent;
import org.neurospike.caseflow.domain.DomainRecords.DetectionThreshold;
import org.neurospike.caseflow.domain.DomainRecords.EvidenceRecord;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class SnapshotLoader {
    private SnapshotLoader() {
    }

    interface RowReader<T> {
        T read(ResultSet result) throws SQLException;
    }

    static CaseSnapshot load(Connection connection) throws SQLException {
        RecordingContext recording = null;
        try (ResultSet result = connection.createStatement().executeQuery("select * from recording_context")) {
            if (result.next()) {
                recording = new RecordingContext(result.getString("recording_id"),
                        result.getString("time_basis"), result.getLong("sample_rate_hz"),
                        result.getString("sample_unit"), result.getString("amplitude_unit"),
                        result.getString("coordinate_basis"));
            }
        }
        List<ProbeGeometry> probes = loadList(connection,
                "select * from probe_geometries order by probe_id", result -> new ProbeGeometry(
                        result.getString("probe_id"), result.getString("coordinate_basis"),
                        readJson(result.getString("channels_json"), new TypeReference<List<ChannelGeometry>>() {
                        })));
        List<DetectionThreshold> thresholds = loadList(connection,
                "select * from detection_thresholds order by channel, threshold_id",
                result -> new DetectionThreshold(result.getString("threshold_id"), result.getInt("channel"),
                        result.getDouble("value"), result.getString("unit"), result.getString("comparator")));
        List<WaveformFragment> waveforms = loadList(connection,
                "select * from waveform_fragments order by waveform_id", result -> new WaveformFragment(
                        result.getString("waveform_id"), result.getString("detection_id"),
                        result.getInt("channel"), result.getLong("start_sample"),
                        result.getString("sample_unit"), result.getString("amplitude_unit"),
                        readJson(result.getString("samples_json"), new TypeReference<List<Double>>() {
                        })));
        List<DetectionEvent> detections = loadList(connection,
                "select * from detection_events order by identity_order", result -> new DetectionEvent(
                        result.getString("detection_id"), result.getLong("sample_index"),
                        result.getInt("channel"), result.getDouble("amplitude"),
                        result.getString("amplitude_unit"), result.getString("waveform_id")));
        List<FeatureVersion> featureVersions = loadList(connection,
                "select * from feature_versions order by feature_version_id", result -> new FeatureVersion(
                        result.getString("feature_version_id"), result.getString("extractor"),
                        result.getString("normalization"),
                        readJson(result.getString("components_json"), new TypeReference<List<String>>() {
                        })));
        List<FeaturePoint> featurePoints = loadList(connection,
                "select * from feature_points order by identity_order", result -> new FeaturePoint(
                        result.getString("feature_point_id"), result.getString("detection_id"),
                        result.getString("feature_version_id"),
                        readJson(result.getString("coordinates_json"), new TypeReference<List<Double>>() {
                        }), result.getString("coordinate_basis")));
        List<LabelAssignment> labels = loadList(connection,
                "select * from label_assignments order by identity_order", result -> new LabelAssignment(
                        result.getString("label_version_id"), result.getString("cluster_label"),
                        result.getString("stable_unit_id"),
                        readJson(result.getString("detection_ids_json"), new TypeReference<List<String>>() {
                        })));
        List<AdjudicationEvent> adjudications = loadList(connection,
                "select * from adjudications order by identity_order", result -> new AdjudicationEvent(
                        result.getString("adjudication_id"), result.getString("decision_type"),
                        result.getString("actor"), result.getString("rationale"),
                        readJson(result.getString("target_ids_json"), new TypeReference<List<String>>() {
                        }), result.getString("outcome")));
        List<RuleVersion> rules = loadList(connection,
                "select * from rule_versions order by rule_version_id", result -> new RuleVersion(
                        result.getString("rule_version_id"), result.getDouble("refractory_ms"),
                        result.getDouble("coincidence_ms"), result.getDouble("drift_ratio_threshold"),
                        result.getDouble("endpoint_epsilon_ms"), result.getInt("budget")));
        List<SourceRevision> revisions = loadList(connection,
                "select * from source_revisions order by revision_id", result -> new SourceRevision(
                        result.getString("revision_id"), result.getString("target_type"),
                        result.getString("target_id"), result.getString("replacement_id"),
                        result.getString("reason")));
        List<WindowComputation> computations = loadList(connection,
                "select * from computations order by identity_order", result -> new WindowComputation(
                        result.getString("computation_id"), result.getString("rule_version_id"),
                        result.getString("feature_version_id"), result.getString("label_version_id"),
                        result.getLong("start_sample"), result.getLong("end_sample"),
                        result.getString("status"), result.getString("input_digest"),
                        readJson(result.getString("parents_json"), new TypeReference<List<String>>() {
                        }),
                        readJson(result.getString("evidence_json"), new TypeReference<List<EvidenceRecord>>() {
                        }),
                        readJson(result.getString("candidates_json"), new TypeReference<List<Candidate>>() {
                        }),
                        readJson(result.getString("diagnostics_json"), new TypeReference<List<Diagnostic>>() {
                        }),
                        new LinkedHashSet<>(readJson(result.getString("affected_json"),
                                new TypeReference<List<String>>() {
                                })),
                        result.getInt("adopted") == 1));
        List<CaseEvent> events = loadList(connection, "select * from case_events order by seq",
                result -> new CaseEvent(result.getString("event_id"), result.getLong("seq"),
                        result.getString("event_type"), result.getString("summary"),
                        result.getString("computation_id"), result.getString("rule_version_id"),
                        result.getString("source_revision_id"), result.getString("adjudication_id")));
        List<DecisionRecord> decisions = loadList(connection,
                "select * from decisions order by decision_id", result -> new DecisionRecord(
                        result.getString("decision_id"), result.getString("adjudication_id"),
                        result.getString("candidate_id"), result.getString("status"),
                        result.getString("rationale")));
        return new CaseSnapshot(recording, probes, thresholds, waveforms, detections, featureVersions,
                featurePoints, labels, adjudications, rules, revisions, computations, events, decisions);
    }

    private static <T> List<T> loadList(Connection connection, String sql, RowReader<T> reader)
            throws SQLException {
        List<T> values = new ArrayList<>();
        try (ResultSet result = connection.createStatement().executeQuery(sql)) {
            while (result.next()) {
                values.add(reader.read(result));
            }
        }
        return values;
    }

    private static <T> T readJson(String json, TypeReference<T> type) {
        try {
            return JsonCanonical.MAPPER.readValue(json, type);
        } catch (Exception exception) {
            throw new IllegalStateException("JSON 反序列化失败", exception);
        }
    }
}
