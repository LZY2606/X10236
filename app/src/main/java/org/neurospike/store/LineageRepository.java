package org.neurospike.store;

import org.neurospike.domain.AnalysisWindow;
import org.neurospike.domain.DecisionRecord;
import org.neurospike.domain.DerivedResult;
import org.neurospike.domain.EvidenceEvent;
import org.neurospike.domain.FeatureObservation;
import org.neurospike.domain.LineageLink;
import org.neurospike.domain.Material;
import org.neurospike.domain.MaterialKind;
import org.neurospike.domain.SpikeDetection;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class LineageRepository {
    private final Database database;

    public LineageRepository(Database database) {
        this.database = database;
    }

    public void addMaterial(Material material) {
        String sql = """
                insert into material(id,kind,version,supersedes_id,direct_source_id,raw_representation,normalized_json,
                  coordinate_basis,unit,status,diagnostic,created_at,ordinal)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, material.id());
            ps.setString(2, material.kind().name());
            ps.setString(3, material.version());
            setNullable(ps, 4, material.supersedesId());
            setNullable(ps, 5, material.directSourceId());
            ps.setString(6, material.rawRepresentation());
            ps.setString(7, material.normalizedJson());
            setNullable(ps, 8, material.coordinateBasis());
            setNullable(ps, 9, material.unit());
            ps.setString(10, material.status());
            setNullable(ps, 11, material.diagnostic());
            ps.setLong(12, parseLong(material.createdAt()));
            ps.setInt(13, database.nextOrdinal("material"));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("cannot insert material " + material.id(), e);
        }
    }

    public List<Material> materials() {
        return queryMaterials("select * from material order by ordinal", List.of());
    }

    public Optional<Material> material(String id) {
        List<Material> values = queryMaterials("select * from material where id=?", List.of(id));
        return values.stream().findFirst();
    }

    public List<Material> materialsByKind(MaterialKind kind) {
        return queryMaterials("select * from material where kind=? order by ordinal", List.of(kind.name()));
    }

    public void addSpike(SpikeDetection spike, String version) {
        String sql = """
                insert into spike_detection(id,version,waveform_id,threshold_id,channel_id,time_ms,amplitude_uv,
                  raw_representation,status,diagnostic,created_at,ordinal)
                values(?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, spike.id());
            ps.setString(2, version);
            ps.setString(3, spike.waveformId());
            ps.setString(4, spike.thresholdId());
            ps.setString(5, spike.channelId());
            ps.setLong(6, spike.timeMs());
            if (Double.isNaN(spike.amplitudeMicroV()) || spike.status().equals("INVALID")) {
                ps.setNull(7, Types.REAL);
            } else {
                ps.setDouble(7, spike.amplitudeMicroV());
            }
            ps.setString(8, spike.rawRepresentation());
            ps.setString(9, spike.status());
            setNullable(ps, 10, spike.diagnostic());
            ps.setLong(11, System.currentTimeMillis());
            ps.setInt(12, database.nextOrdinal("spike_detection"));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("cannot insert spike " + spike.id() + "@" + version, e);
        }
    }

    public List<SpikeDetection> spikes(String version) {
        String sql = "select id,waveform_id,threshold_id,channel_id,time_ms,coalesce(amplitude_uv,'NaN'),raw_representation,status,diagnostic from spike_detection where version=? order by time_ms,id";
        List<SpikeDetection> values = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.add(new SpikeDetection(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getLong(5), rs.getDouble(6), rs.getString(7), rs.getString(8), rs.getString(9)));
                }
            }
        } catch (SQLException e) {
            throw new StorageException("cannot read spikes", e);
        }
        return values;
    }

    public void addFeature(FeatureObservation feature, String spikeVersion) {
        String sql = """
                insert into feature_observation(id,version,spike_id,spike_version,feature_version_id,x,y,
                  coordinate_basis,unit,raw_representation,status,diagnostic,ordinal)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, feature.id());
            ps.setString(2, feature.featureVersionId());
            ps.setString(3, feature.spikeId());
            ps.setString(4, spikeVersion);
            ps.setString(5, feature.featureVersionId());
            if (feature.status().equals("INVALID")) {
                ps.setNull(6, Types.REAL);
                ps.setNull(7, Types.REAL);
            } else {
                ps.setDouble(6, feature.x());
                ps.setDouble(7, feature.y());
            }
            setNullable(ps, 8, feature.coordinateBasis());
            setNullable(ps, 9, feature.unit());
            ps.setString(10, feature.rawRepresentation());
            ps.setString(11, feature.status());
            setNullable(ps, 12, feature.diagnostic());
            ps.setInt(13, database.nextOrdinal("feature_observation"));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("cannot insert feature " + feature.id(), e);
        }
    }

    public List<FeatureObservation> features(String featureVersion) {
        String sql = """
                select id,spike_id,feature_version_id,coalesce(x,'NaN'),coalesce(y,'NaN'),coordinate_basis,unit,
                  raw_representation,status,diagnostic
                from feature_observation where version=? order by spike_id,id
                """;
        List<FeatureObservation> values = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, featureVersion);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.add(new FeatureObservation(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getDouble(4), rs.getDouble(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            rs.getString(9), rs.getString(10)));
                }
            }
        } catch (SQLException e) {
            throw new StorageException("cannot read features", e);
        }
        return values;
    }

    public void addWindow(AnalysisWindow window) {
        String sql = "insert into analysis_window(id,start_ms,end_ms,start_rule,end_rule,time_basis,status,version,ordinal) values(?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, window.id());
            ps.setLong(2, window.startMs());
            ps.setLong(3, window.endMs());
            ps.setString(4, window.startRule());
            ps.setString(5, window.endRule());
            ps.setString(6, window.timeBasis());
            ps.setString(7, window.status());
            ps.setString(8, window.version());
            ps.setInt(9, database.nextOrdinal("analysis_window"));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("cannot insert window " + window.id(), e);
        }
    }

    public List<AnalysisWindow> windows() {
        String sql = "select id,start_ms,end_ms,start_rule,end_rule,time_basis,status,version from analysis_window order by start_ms,ordinal";
        List<AnalysisWindow> values = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                values.add(new AnalysisWindow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)));
            }
        } catch (SQLException e) {
            throw new StorageException("cannot read windows", e);
        }
        return values;
    }

    public Optional<AnalysisWindow> window(String id) {
        return windows().stream().filter(window -> window.id().equals(id)).findFirst();
    }

    public void addResult(DerivedResult result) {
        String sql = """
                insert into derived_result(id,kind,stable_subject_id,input_digest,parameter_version,
                  parent_dependencies_json,window_id,status,validity_start_ms,validity_end_ms,invalid_reason,
                  payload_json,sequence,ordinal)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, result.id());
            ps.setString(2, result.kind());
            ps.setString(3, result.stableSubjectId());
            ps.setString(4, resultDigest(result));
            ps.setString(5, result.parameterVersion());
            ps.setString(6, result.parentDependenciesJson());
            setNullable(ps, 7, result.windowId());
            ps.setString(8, result.status());
            setNullableLong(ps, 9, result.validityStartMs());
            setNullableLong(ps, 10, result.validityEndMs());
            setNullable(ps, 11, result.invalidReason());
            ps.setString(12, result.payloadJson());
            ps.setLong(13, result.sequence());
            ps.setInt(14, database.nextOrdinal("derived_result"));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("cannot insert result " + result.id(), e);
        }
    }

    public List<DerivedResult> results() {
        return queryResults("select * from derived_result order by sequence,ordinal", List.of());
    }

    public List<DerivedResult> resultsForWindow(String windowId) {
        return queryResults("select * from derived_result where window_id=? order by sequence,ordinal", List.of(windowId));
    }

    public Optional<DerivedResult> result(String id) {
        return queryResults("select * from derived_result where id=?", List.of(id)).stream().findFirst();
    }

    public void addEvidence(EvidenceEvent event) {
        String sql = """
                insert into evidence_event(sequence,id,at_ms,type,severity,subject_stable_id,rule_version,basis,
                  payload_json,accepted_result_id)
                values(?,?,?,?,?,?,?,?,?,?)
                """;
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setLong(1, event.sequence());
            ps.setString(2, event.id());
            ps.setLong(3, parseLong(event.atMs()));
            ps.setString(4, event.type());
            ps.setString(5, event.severity());
            ps.setString(6, event.subjectStableId());
            ps.setString(7, event.ruleVersion());
            ps.setString(8, event.basis());
            ps.setString(9, event.payloadJson());
            setNullable(ps, 10, event.acceptedResultId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("cannot insert evidence " + event.id(), e);
        }
    }

    public List<EvidenceEvent> evidence() {
        String sql = "select sequence,id,at_ms,type,severity,subject_stable_id,rule_version,basis,payload_json,accepted_result_id from evidence_event order by sequence";
        List<EvidenceEvent> values = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                values.add(new EvidenceEvent(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10)));
            }
        } catch (SQLException e) {
            throw new StorageException("cannot read evidence", e);
        }
        return values;
    }

    public void addDecision(DecisionRecord decision) {
        String sql = """
                insert into decision_record(id,revision_id,scope_id,action,rationale,author,at_ms,status,
                  conflicts_with_id,payload_json,ordinal)
                values(?,?,?,?,?,?,?,?,?,?,?)
                """;
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, decision.id());
            ps.setString(2, decision.revisionId());
            ps.setString(3, decision.scopeId());
            ps.setString(4, decision.action());
            ps.setString(5, decision.rationale());
            ps.setString(6, decision.author());
            ps.setLong(7, parseLong(decision.atMs()));
            ps.setString(8, decision.status());
            setNullable(ps, 9, decision.conflictsWithId());
            ps.setString(10, decision.payloadJson());
            ps.setInt(11, database.nextOrdinal("decision_record"));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("cannot insert decision " + decision.id(), e);
        }
    }

    public List<DecisionRecord> decisions() {
        String sql = "select id,revision_id,scope_id,action,rationale,author,at_ms,status,conflicts_with_id,payload_json from decision_record order by at_ms,ordinal";
        List<DecisionRecord> values = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                values.add(new DecisionRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10)));
            }
        } catch (SQLException e) {
            throw new StorageException("cannot read decisions", e);
        }
        return values;
    }

    public void addLink(LineageLink link) {
        String sql = "insert into lineage_link(parent_id,parent_kind,child_id,child_kind,relation,ordinal) values(?,?,?,?,?,?)";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, link.parentId());
            ps.setString(2, link.parentKind());
            ps.setString(3, link.childId());
            ps.setString(4, link.childKind());
            ps.setString(5, link.relation());
            ps.setInt(6, database.nextOrdinal("lineage_link"));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("cannot insert lineage link", e);
        }
    }

    public List<LineageLink> links() {
        String sql = "select parent_id,parent_kind,child_id,child_kind,relation from lineage_link order by ordinal";
        List<LineageLink> values = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                values.add(new LineageLink(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
            }
        } catch (SQLException e) {
            throw new StorageException("cannot read lineage links", e);
        }
        return values;
    }

    public void addCaseRun(String id, String stage, String eventType, long atMs, String status, String summary) {
        String sql = "insert into case_run(id,stage,event_type,at_ms,status,summary,ordinal) values(?,?,?,?,?,?,?)";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, stage);
            ps.setString(3, eventType);
            ps.setLong(4, atMs);
            ps.setString(5, status);
            ps.setString(6, summary);
            ps.setInt(7, database.nextOrdinal("case_run"));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("cannot insert case run " + id, e);
        }
    }

    public List<String[]> caseRuns() {
        String sql = "select id,stage,event_type,at_ms,status,summary from case_run order by ordinal";
        List<String[]> values = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                values.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)});
            }
        } catch (SQLException e) {
            throw new StorageException("cannot read case run", e);
        }
        return values;
    }

    public void beginJournal(String id, long expected, String detail) {
        String sql = "insert into import_journal(journal_id,started_at,finished_at,status,detail,expected_records,applied_records) values(?,?,?,?,?,?,?)";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setLong(2, System.currentTimeMillis());
            ps.setNull(3, Types.BIGINT);
            ps.setString(4, "HALF_WRITTEN");
            ps.setString(5, detail);
            ps.setLong(6, expected);
            ps.setLong(7, 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("cannot begin import journal", e);
        }
    }

    public void finishJournal(String id, String status, long applied) {
        String sql = "update import_journal set finished_at=?,status=?,applied_records=? where journal_id=?";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, status);
            ps.setLong(3, applied);
            ps.setString(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("cannot finish import journal", e);
        }
    }

    public List<String[]> journals() {
        String sql = "select journal_id,started_at,coalesce(finished_at,''),status,detail,expected_records,applied_records from import_journal order by started_at";
        List<String[]> values = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                values.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)});
            }
        } catch (SQLException e) {
            throw new StorageException("cannot read import journals", e);
        }
        return values;
    }

    private List<Material> queryMaterials(String sql, List<String> parameters) {
        List<Material> values = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) {
                ps.setString(i + 1, parameters.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.add(new Material(rs.getString("id"), MaterialKind.valueOf(rs.getString("kind")),
                            rs.getString("version"), rs.getString("supersedes_id"), rs.getString("direct_source_id"),
                            rs.getString("raw_representation"), rs.getString("normalized_json"),
                            rs.getString("coordinate_basis"), rs.getString("unit"), rs.getString("status"),
                            rs.getString("diagnostic"), rs.getString("created_at")));
                }
            }
        } catch (SQLException e) {
            throw new StorageException("cannot read materials", e);
        }
        return values;
    }

    private List<DerivedResult> queryResults(String sql, List<String> parameters) {
        List<DerivedResult> values = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) {
                ps.setString(i + 1, parameters.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.add(new DerivedResult(rs.getString("id"), rs.getString("kind"),
                            rs.getString("stable_subject_id"), rs.getString("input_digest"),
                            rs.getString("parameter_version"), rs.getString("parent_dependencies_json"),
                            rs.getString("window_id"), rs.getString("status"),
                            nullableLongString(rs, "validity_start_ms"), nullableLongString(rs, "validity_end_ms"),
                            rs.getString("invalid_reason"), rs.getString("payload_json"), rs.getLong("sequence")));
                }
            }
        } catch (SQLException e) {
            throw new StorageException("cannot read derived results", e);
        }
        return values;
    }

    private String resultDigest(DerivedResult result) {
        return result.inputDigest() == null || result.inputDigest().isBlank()
                ? "sha256:" + Integer.toHexString((result.kind() + result.stableSubjectId()
                + result.parameterVersion() + result.payloadJson()).hashCode())
                : result.inputDigest();
    }

    private static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static void setNullableLong(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, Long.parseLong(value));
        }
    }

    private static long parseLong(String value) {
        return value == null || value.isBlank() ? System.currentTimeMillis() : Long.parseLong(value);
    }

    private static String nullableLongString(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Long.toString(value);
    }
}
