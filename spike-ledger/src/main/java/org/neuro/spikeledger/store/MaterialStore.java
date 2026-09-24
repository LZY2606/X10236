package org.neuro.spikeledger.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.neuro.spikeledger.model.CoordBasis;
import org.neuro.spikeledger.model.DetectionThreshold;
import org.neuro.spikeledger.model.Entities.Adjudication;
import org.neuro.spikeledger.model.Entities.ClusteringRun;
import org.neuro.spikeledger.model.Entities.ClusterLabel;
import org.neuro.spikeledger.model.Entities.FeatureVector;
import org.neuro.spikeledger.model.Entities.FeatureVersion;
import org.neuro.spikeledger.model.Entities.ProbeGeometry;
import org.neuro.spikeledger.model.Entities.RawWaveformRef;
import org.neuro.spikeledger.model.Entities.SpikeEvent;
import org.neuro.spikeledger.model.Physical;
import org.neuro.spikeledger.util.J;

/** 不可变材料层：六类材料分开存储；原始表示 raw_json/raw_representation 全部保留。 */
public class MaterialStore {
    private final Db db;

    public MaterialStore(Db db) {
        this.db = db;
    }

    public void putWaveform(RawWaveformRef w, String importedEvent) {
        exec("INSERT OR REPLACE INTO waveform_ref(id,recording_id,channel,uri,sample_unit,"
                + "sample_rate_hz,start_sample,end_sample,time_basis,checksum_sha256,raw_json,"
                + "imported_event) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", ps -> {
            ps.setString(1, w.id());
            ps.setString(2, w.recordingId());
            ps.setInt(3, w.channel());
            ps.setString(4, w.uri());
            ps.setString(5, w.sampleUnit());
            ps.setDouble(6, w.sampleRateHz());
            ps.setLong(7, w.startSample());
            ps.setLong(8, w.endSample());
            ps.setString(9, w.timeBasis());
            ps.setString(10, w.checksumSha256());
            ps.setString(11, J.to(w));
            ps.setString(12, importedEvent);
        });
    }

    public List<RawWaveformRef> waveforms() {
        return queryList("SELECT raw_json FROM waveform_ref ORDER BY id",
                rs -> J.from(rs.getString(1), RawWaveformRef.class));
    }

    public void putGeometry(ProbeGeometry g, String importedEvent) {
        exec("INSERT OR REPLACE INTO probe_geometry(id,revision,origin,axes,unit,channel_order,"
                + "channels_json,raw_representation,imported_event) VALUES(?,?,?,?,?,?,?,?,?)", ps -> {
            ps.setString(1, g.id());
            ps.setString(2, g.revision());
            ps.setString(3, g.basis().origin());
            ps.setString(4, J.to(g.basis().axes()));
            ps.setString(5, g.basis().unit());
            ps.setString(6, g.basis().channelOrder());
            ps.setString(7, J.to(g.channels()));
            ps.setString(8, g.rawRepresentation());
            ps.setString(9, importedEvent);
        });
    }

    public List<ProbeGeometry> geometries() {
        List<ProbeGeometry> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT id,revision,origin,axes,unit,channel_order,channels_json,raw_representation "
                        + "FROM probe_geometry ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                CoordBasis basis = new CoordBasis(rs.getString("origin"),
                        J.strList(rs.getString("axes")), rs.getString("unit"),
                        rs.getString("channel_order"));
                out.add(new ProbeGeometry(rs.getString("id"), rs.getString("revision"), basis,
                        J.channels(rs.getString("channels_json")),
                        rs.getString("raw_representation")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public void putThreshold(DetectionThreshold t, String importedEvent) {
        exec("INSERT OR REPLACE INTO detection_threshold(id,method,level_value,level_unit,level_raw,"
                + "canonical,convert_error,channels_json,version_tag,imported_event) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?)", ps -> {
            ps.setString(1, t.id());
            ps.setString(2, t.method());
            ps.setDouble(3, t.level().value());
            ps.setString(4, t.level().unit());
            ps.setString(5, t.level().raw());
            if (t.level().canonical() == null) {
                ps.setObject(6, null);
            } else {
                ps.setDouble(6, t.level().canonical());
            }
            ps.setString(7, t.level().convertError());
            ps.setString(8, J.to(t.channels()));
            ps.setString(9, t.versionTag());
            ps.setString(10, importedEvent);
        });
    }

    public List<DetectionThreshold> thresholds() {
        List<DetectionThreshold> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT id,method,level_value,level_unit,level_raw,canonical,convert_error,"
                        + "channels_json,version_tag FROM detection_threshold ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Physical p = new Physical(rs.getDouble("level_value"), rs.getString("level_unit"),
                        rs.getString("level_raw"),
                        (Double) rs.getObject("canonical"), rs.getString("convert_error"));
                out.add(new DetectionThreshold(rs.getString("id"), rs.getString("method"), p,
                        J.intList(rs.getString("channels_json")), rs.getString("version_tag")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public void putSpike(SpikeEvent e, String importedEvent) {
        exec("INSERT OR REPLACE INTO spike_event(id,recording_id,time_ms,time_basis,channel,"
                + "amplitude_value,amplitude_unit,amplitude_raw,amplitude_canonical,"
                + "amplitude_convert_error,waveform_ref_id,concurrency_group,source_line,"
                + "imported_event,superseded_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", ps -> {
            ps.setString(1, e.id());
            ps.setString(2, e.recordingId());
            ps.setDouble(3, e.timeMs());
            ps.setString(4, e.timeBasis());
            ps.setInt(5, e.channel());
            ps.setDouble(6, e.amplitude().value());
            ps.setString(7, e.amplitude().unit());
            ps.setString(8, e.amplitude().raw());
            ps.setObject(9, e.amplitude().canonical());
            ps.setString(10, e.amplitude().convertError());
            ps.setString(11, e.waveformRefId());
            ps.setObject(12, e.concurrencyGroup());
            ps.setString(13, e.sourceLine());
            ps.setString(14, importedEvent);
            ps.setObject(15, null);
        });
    }

    /** 局部失效：标记旧材料被新直接来源替换；旧行保留（历史不被覆盖）。 */
    public void supersedeSpike(String oldId, String newId) {
        exec("UPDATE spike_event SET superseded_by=? WHERE id=?", ps -> {
            ps.setString(1, newId);
            ps.setString(2, oldId);
        });
    }

    public List<SpikeEvent> spikes(boolean includeSuperseded) {
        String sql = "SELECT id,recording_id,time_ms,time_basis,channel,amplitude_value,"
                + "amplitude_unit,amplitude_raw,amplitude_canonical,amplitude_convert_error,"
                + "waveform_ref_id,concurrency_group,source_line,superseded_by FROM spike_event "
                + (includeSuperseded ? "" : "WHERE superseded_by IS NULL ") + "ORDER BY time_ms,id";
        List<SpikeEvent> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Physical p = new Physical(rs.getDouble("amplitude_value"),
                        rs.getString("amplitude_unit"), rs.getString("amplitude_raw"),
                        (Double) rs.getObject("amplitude_canonical"),
                        rs.getString("amplitude_convert_error"));
                out.add(new SpikeEvent(rs.getString("id"), rs.getString("recording_id"),
                        rs.getDouble("time_ms"), rs.getString("time_basis"),
                        rs.getInt("channel"), p, rs.getString("waveform_ref_id"),
                        (Long) rs.getObject("concurrency_group"), rs.getString("source_line")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public void putFeatureVersion(FeatureVersion fv, String importedEvent) {
        exec("INSERT OR REPLACE INTO feature_version(id,name,version,feature_names_json,params_json,"
                + "imported_event) VALUES(?,?,?,?,?,?)", ps -> {
            ps.setString(1, fv.id());
            ps.setString(2, fv.name());
            ps.setInt(3, fv.version());
            ps.setString(4, J.to(fv.featureNames()));
            ps.setString(5, fv.paramsJson());
            ps.setString(6, importedEvent);
        });
    }

    public List<FeatureVersion> featureVersions() {
        return queryList("SELECT id,name,version,feature_names_json,params_json FROM feature_version ORDER BY id",
                rs -> new FeatureVersion(rs.getString(1), rs.getString(2), rs.getInt(3),
                        J.strList(rs.getString(4)), rs.getString(5)));
    }

    public void putFeatureVector(FeatureVector v, String importedEvent) {
        exec("INSERT OR REPLACE INTO feature_vector(event_id,feature_version_id,values_json,units_json,"
                + "imported_event) VALUES(?,?,?,?,?)", ps -> {
            ps.setString(1, v.eventId());
            ps.setString(2, v.featureVersionId());
            ps.setString(3, J.to(toBoxed(v.values())));
            ps.setString(4, J.to(v.units()));
            ps.setString(5, importedEvent);
        });
    }

    public List<FeatureVector> featureVectors(String featureVersionId) {
        List<FeatureVector> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT event_id,values_json,units_json FROM feature_vector WHERE feature_version_id=?")) {
            ps.setString(1, featureVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<Double> vals = J.dblList(rs.getString(2));
                    double[] arr = vals.stream().mapToDouble(Double::doubleValue).toArray();
                    out.add(new FeatureVector(rs.getString(1), featureVersionId, arr,
                            J.strList(rs.getString(3))));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public void putRun(ClusteringRun run, String createdEvent) {
        exec("INSERT OR REPLACE INTO clustering_run(id,feature_version_id,threshold_id,geometry_id,"
                + "params_version,window_start_ms,window_end_ms,end_inclusive,input_digest,"
                + "created_event,invalidated) VALUES(?,?,?,?,?,?,?,?,?,?,0)", ps -> {
            ps.setString(1, run.id());
            ps.setString(2, run.featureVersionId());
            ps.setString(3, run.thresholdId());
            ps.setString(4, run.geometryId());
            ps.setString(5, run.paramsVersion());
            ps.setDouble(6, run.windowStartMs());
            ps.setDouble(7, run.windowEndMs());
            ps.setInt(8, run.endInclusive() ? 1 : 0);
            ps.setString(9, run.inputDigest());
            ps.setString(10, createdEvent);
        });
    }

    public void invalidateRun(String runId) {
        exec("UPDATE clustering_run SET invalidated=1 WHERE id=?", ps -> ps.setString(1, runId));
    }

    public List<ClusteringRun> runs() {
        List<ClusteringRun> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT id,feature_version_id,threshold_id,geometry_id,params_version,"
                        + "window_start_ms,window_end_ms,end_inclusive,input_digest "
                        + "FROM clustering_run ORDER BY window_start_ms,id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new ClusteringRun(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getDouble(6), rs.getDouble(7),
                        rs.getInt(8) == 1, rs.getString(9)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public void putLabel(ClusterLabel l) {
        exec("INSERT OR REPLACE INTO cluster_label(run_id,event_id,label_index,membership) "
                + "VALUES(?,?,?,?)", ps -> {
            ps.setString(1, l.runId());
            ps.setString(2, l.eventId());
            ps.setInt(3, l.labelIndex());
            ps.setDouble(4, l.membership());
        });
    }

    public List<ClusterLabel> labels(String runId) {
        List<ClusterLabel> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT run_id,event_id,label_index,membership FROM cluster_label WHERE run_id=? ORDER BY event_id")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ClusterLabel(rs.getString(1), rs.getString(2),
                            rs.getInt(3), rs.getDouble(4)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public void putAdjudication(Adjudication a) {
        exec("INSERT OR REPLACE INTO adjudication(id,branch_id,parent_event_id,target_unit_id,"
                + "target_event_id,decision,rationale,params_version,conflict_with_event_id,"
                + "created_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?)", ps -> {
            ps.setString(1, a.id());
            ps.setString(2, a.branchId());
            ps.setString(3, a.parentEventId());
            ps.setString(4, a.targetUnitId());
            ps.setString(5, a.targetEventId());
            ps.setString(6, a.decision());
            ps.setString(7, a.rationale());
            ps.setString(8, a.paramsVersion());
            ps.setString(9, a.conflictWithEventId());
            ps.setLong(10, a.createdAtMs());
        });
    }

    public List<Adjudication> adjudications(String branchId) {
        List<Adjudication> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT id,branch_id,parent_event_id,target_unit_id,target_event_id,decision,"
                        + "rationale,params_version,conflict_with_event_id,created_at_ms "
                        + "FROM adjudication WHERE branch_id=? ORDER BY created_at_ms,id")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Adjudication(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                            rs.getString(8), rs.getLong(10), rs.getString(9)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static List<Double> toBoxed(double[] a) {
        List<Double> out = new ArrayList<>(a.length);
        for (double v : a) {
            out.add(v);
        }
        return out;
    }

    private interface SqlBody {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private void exec(String sql, SqlBody body) {
        try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
            body.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> List<T> queryList(String sql, RowMapper<T> mapper) {
        List<T> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(mapper.map(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }
}
