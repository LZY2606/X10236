package org.neurospike.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.neurospike.model.Material;
import org.neurospike.model.Quantity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** 不可变材料层的读写。所有写入按稳定键 UPSERT，保证等价输入幂等。 */
public final class StoreMaterial {
    private final Connection c;
    private final ObjectMapper json;

    public StoreMaterial(Connection c, ObjectMapper json) {
        this.c = c;
        this.json = json;
    }

    public void putChannel(Material.ProbeChannel ch) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO probe_channel(probe_id,local_index,label,x,y,coord_unit)
                VALUES(?,?,?,?,?,?)
                ON CONFLICT(probe_id,local_index) DO UPDATE SET
                  label=excluded.label,x=excluded.x,y=excluded.y,coord_unit=excluded.coord_unit
                """)) {
            ps.setString(1, ch.probeId()); ps.setInt(2, ch.localIndex());
            ps.setString(3, ch.label()); ps.setDouble(4, ch.x());
            ps.setDouble(5, ch.y()); ps.setString(6, ch.coordUnit());
            ps.executeUpdate();
        }
    }

    public List<Material.ProbeChannel> channels(String probeId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM probe_channel WHERE probe_id=? ORDER BY local_index")) {
            ps.setString(1, probeId);
            List<Material.ProbeChannel> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Material.ProbeChannel(rs.getString("probe_id"),
                            rs.getInt("local_index"), rs.getString("label"),
                            rs.getDouble("x"), rs.getDouble("y"), rs.getString("coord_unit")));
                }
            }
            return out;
        }
    }

    public void putWaveform(Material.Waveform w) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO waveform(waveform_id,channel_local_index,sample_count,
                  sample_rate_raw,sample_rate_unit,sample_rate_hz,samples_per_spike,
                  gain_to_unit,physical_unit,samples,sha256)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(waveform_id) DO NOTHING
                """)) {
            ps.setString(1, w.waveformId()); ps.setInt(2, w.channelLocalIndex());
            ps.setInt(3, w.sampleCount()); ps.setString(4, w.sampleRateRaw());
            ps.setString(5, w.sampleRateUnit()); ps.setDouble(6, w.sampleRateHz());
            ps.setInt(7, w.samplesPerSpike()); ps.setDouble(8, w.gainToUnit());
            ps.setString(9, w.physicalUnit()); ps.setBytes(10, w.samples());
            ps.setString(11, w.sha256()); ps.executeUpdate();
        }
    }

    public Material.Waveform waveform(String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM waveform WHERE waveform_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readWaveform(rs) : null;
            }
        }
    }

    public boolean waveformExists(String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM waveform WHERE waveform_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    static Material.Waveform readWaveform(ResultSet rs) throws SQLException {
        return new Material.Waveform(rs.getString("waveform_id"),
                rs.getInt("channel_local_index"), rs.getInt("sample_count"),
                rs.getString("sample_rate_raw"), rs.getString("sample_rate_unit"),
                rs.getDouble("sample_rate_hz"), rs.getInt("samples_per_spike"),
                rs.getDouble("gain_to_unit"), rs.getString("physical_unit"),
                rs.getBytes("samples"), rs.getString("sha256"));
    }

    public void putThreshold(Material.Threshold t) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO threshold_version(threshold_id,version,probe_id,channel_local_index,
                  operator,raw_value,raw_unit,si_value,si_unit,basis,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(threshold_id) DO NOTHING
                """)) {
            ps.setString(1, t.thresholdId()); ps.setInt(2, t.version());
            ps.setString(3, t.probeId()); ps.setInt(4, t.channelLocalIndex());
            ps.setString(5, t.operator()); ps.setString(6, t.value().raw());
            ps.setString(7, t.value().rawUnit());
            if (t.value().siValue() == null) ps.setNull(8, java.sql.Types.REAL);
            else ps.setDouble(8, t.value().siValue());
            ps.setString(9, t.value().siUnit()); ps.setString(10, t.basis());
            ps.setString(11, t.createdAt()); ps.executeUpdate();
        }
    }

    public List<Material.Threshold> thresholds(int version) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM threshold_version WHERE version=? ORDER BY channel_local_index")) {
            ps.setInt(1, version);
            List<Material.Threshold> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Double si = rs.getObject("si_value", Double.class);
                    out.add(new Material.Threshold(rs.getString("threshold_id"),
                            rs.getInt("version"), rs.getString("probe_id"),
                            rs.getInt("channel_local_index"), rs.getString("operator"),
                            new Quantity(rs.getString("raw_value"), rs.getString("raw_unit"),
                                    si, rs.getString("si_unit")),
                            rs.getString("basis"), rs.getString("created_at")));
                }
            }
            return out;
        }
    }

    public int maxThresholdVersion() throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(MAX(version),0) v FROM threshold_version");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt("v") : 0;
        }
    }

    public void putFeatureVersion(Material.FeatureVersion fv) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO feature_version(feature_set_id,version,algorithm,params_canonical,created_at)
                VALUES(?,?,?,?,?) ON CONFLICT(feature_set_id) DO NOTHING
                """)) {
            ps.setString(1, fv.featureSetId()); ps.setInt(2, fv.version());
            ps.setString(3, fv.algorithm()); ps.setString(4, fv.paramsCanonical());
            ps.setString(5, fv.createdAt()); ps.executeUpdate();
        }
    }

    public Material.FeatureVersion featureVersion(String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM feature_version WHERE feature_set_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Material.FeatureVersion(rs.getString("feature_set_id"),
                        rs.getInt("version"), rs.getString("algorithm"),
                        rs.getString("params_canonical"), rs.getString("created_at")) : null;
            }
        }
    }

    public void putEvent(Material.SpikeEvent e, int ingestOrder) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO spike_event(event_id,time_seconds,channel_local_index,waveform_id,
                  amplitude_raw,amplitude_raw_unit,amplitude_si,amplitude_si_unit,
                  feature_set_id,features_json,raw_line,valid,invalid_reason,
                  supersedes_order,ingest_order)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(event_id) DO UPDATE SET
                  supersedes_order=COALESCE(excluded.supersedes_order,spike_event.supersedes_order)
                """)) {
            ps.setString(1, e.eventId()); ps.setDouble(2, e.timeSeconds());
            ps.setInt(3, e.channelLocalIndex()); ps.setString(4, e.waveformId());
            ps.setString(5, e.amplitude().raw()); ps.setString(6, e.amplitude().rawUnit());
            if (e.amplitude().siValue() == null) ps.setNull(7, java.sql.Types.REAL);
            else ps.setDouble(7, e.amplitude().siValue());
            ps.setString(8, e.amplitude().siUnit()); ps.setString(9, e.featureSetId());
            try { ps.setString(10, json.writeValueAsString(e.features())); }
            catch (JsonProcessingException ex) { throw new SQLException(ex); }
            ps.setString(11, e.rawLine()); ps.setInt(12, e.valid() ? 1 : 0);
            ps.setString(13, e.invalidReason());
            if (e.supersedesOrder() == null) ps.setNull(14, java.sql.Types.INTEGER);
            else ps.setInt(14, e.supersedesOrder());
            ps.setInt(15, ingestOrder); ps.executeUpdate();
        }
    }

    public List<Material.SpikeEvent> events() throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM spike_event ORDER BY time_seconds,event_id");
             ResultSet rs = ps.executeQuery()) {
            List<Material.SpikeEvent> out = new ArrayList<>();
            while (rs.next()) out.add(readEvent(rs, json));
            return out;
        }
    }

    static Material.SpikeEvent readEvent(ResultSet rs, ObjectMapper json) throws SQLException {
        Double si = rs.getObject("amplitude_si", Double.class);
        Integer supersedes = rs.getObject("supersedes_order", Integer.class);
        List<Double> feats;
        try {
            feats = json.readValue(rs.getString("features_json"),
                    json.getTypeFactory().constructCollectionType(List.class, Double.class));
        } catch (JsonProcessingException e) { throw new SQLException(e); }
        return new Material.SpikeEvent(rs.getString("event_id"), rs.getDouble("time_seconds"),
                rs.getInt("channel_local_index"), rs.getString("waveform_id"),
                new Quantity(rs.getString("amplitude_raw"), rs.getString("amplitude_raw_unit"),
                        si, rs.getString("amplitude_si_unit")),
                rs.getString("feature_set_id"), feats, rs.getString("raw_line"),
                rs.getInt("valid") == 1, rs.getString("invalid_reason"), supersedes);
    }

    public Material.SpikeEvent event(String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM spike_event WHERE event_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readEvent(rs, json) : null;
            }
        }
    }
}
