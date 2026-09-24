package neurogsb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import neurogsb.db.Database;
import neurogsb.model.Records.*;

import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class Store {
    public static final ObjectMapper M = new ObjectMapper();
    private final Database db;

    public Store(Database db) { this.db = db; }

    public static String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String digest(String s) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static JsonNode json(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    static List<String> strList(String s) {
        List<String> out = new ArrayList<>();
        json(s).forEach(n -> out.add(n.asText()));
        return out;
    }

    // ---------- materials ----------
    public synchronized void insertMaterial(Material m, String opId) {
        try (var ps = db.conn().prepareStatement(
                "INSERT INTO materials(id,kind,version,payload,digest,seq,op_id) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, m.id()); ps.setString(2, m.kind()); ps.setInt(3, m.version());
            ps.setString(4, m.payload().toString()); ps.setString(5, m.digest());
            ps.setLong(6, m.seq()); ps.setString(7, opId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public synchronized List<Material> materials(String kind) {
        List<Material> out = new ArrayList<>();
        try (var ps = db.conn().prepareStatement(
                "SELECT id,kind,version,payload,digest,seq FROM materials WHERE kind=? ORDER BY seq")) {
            ps.setString(1, kind);
            var rs = ps.executeQuery();
            while (rs.next()) out.add(new Material(rs.getString(1), rs.getString(2), rs.getInt(3),
                    json(rs.getString(4)), rs.getString(5), rs.getLong(6)));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public synchronized Optional<Material> material(String id) {
        try (var ps = db.conn().prepareStatement(
                "SELECT id,kind,version,payload,digest,seq FROM materials WHERE id=?")) {
            ps.setString(1, id);
            var rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(new Material(rs.getString(1), rs.getString(2), rs.getInt(3),
                    json(rs.getString(4)), rs.getString(5), rs.getLong(6)));
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public synchronized void supersedeMaterial(String id, String byId) {
        try (var ps = db.conn().prepareStatement("UPDATE materials SET superseded_by=? WHERE id=?")) {
            ps.setString(1, byId); ps.setString(2, id); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // ---------- spikes ----------
    public synchronized void insertSpike(Spike s, String opId) {
        try (var ps = db.conn().prepareStatement(
                "INSERT INTO spike_events(id,unit_id,t_raw,t_ms,channel,amp_raw,amp_uv,waveform_ref_id,f1,f2,op_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, s.id()); ps.setString(2, s.unitId()); ps.setString(3, s.tRaw());
            if (s.tMs() == null) ps.setNull(4, java.sql.Types.REAL); else ps.setDouble(4, s.tMs());
            if (s.channel() == null) ps.setNull(5, java.sql.Types.INTEGER); else ps.setInt(5, s.channel());
            ps.setString(6, s.ampRaw());
            if (s.ampUv() == null) ps.setNull(7, java.sql.Types.REAL); else ps.setDouble(7, s.ampUv());
            ps.setString(8, s.waveformRefId()); ps.setDouble(9, s.f1()); ps.setDouble(10, s.f2());
            ps.setString(11, opId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public synchronized List<Spike> spikes(String unitId) {
        List<Spike> out = new ArrayList<>();
        try (var ps = db.conn().prepareStatement(
                "SELECT id,unit_id,t_raw,t_ms,channel,amp_raw,amp_uv,waveform_ref_id,f1,f2 FROM spike_events WHERE unit_id=? ORDER BY t_ms")) {
            ps.setString(1, unitId);
            var rs = ps.executeQuery();
            while (rs.next()) out.add(mapSpike(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public synchronized List<Spike> allSpikes() {
        List<Spike> out = new ArrayList<>();
        try (var ps = db.conn().prepareStatement(
                "SELECT id,unit_id,t_raw,t_ms,channel,amp_raw,amp_uv,waveform_ref_id,f1,f2 FROM spike_events ORDER BY t_ms");
             var rs = ps.executeQuery()) {
            while (rs.next()) out.add(mapSpike(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    private static Spike mapSpike(java.sql.ResultSet rs) throws SQLException {
        return new Spike(rs.getString(1), rs.getString(2), rs.getString(3),
                (Double) rs.getObject(4), (Integer) rs.getObject(5), rs.getString(6),
                (Double) rs.getObject(7), rs.getString(8), rs.getDouble(9), rs.getDouble(10));
    }

    public synchronized void reassignSpikes(String fromUnit, String toUnit, Double tFrom, Double tTo, String opId) {
        try (var ps = db.conn().prepareStatement(
                "UPDATE spike_events SET unit_id=? WHERE unit_id=? AND t_ms>=? AND t_ms<?")) {
            ps.setString(1, toUnit); ps.setString(2, fromUnit);
            ps.setDouble(3, tFrom); ps.setDouble(4, tTo);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // ---------- events ----------
    public synchronized Event insertEvent(String kind, String actor, String targetUnitId,
                                          JsonNode payload, List<String> parents, String branch, String opId) {
        long seq = db.nextSeq();
        String id = "ev-" + seq;
        try (var ps = db.conn().prepareStatement(
                "INSERT INTO adjudication_events(id,seq,kind,actor,target_unit_id,payload,parents,branch,op_id) VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id); ps.setLong(2, seq); ps.setString(3, kind); ps.setString(4, actor);
            ps.setString(5, targetUnitId); ps.setString(6, payload.toString());
            ArrayNode p = M.createArrayNode(); parents.forEach(p::add);
            ps.setString(7, p.toString()); ps.setString(8, branch); ps.setString(9, opId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        return new Event(id, seq, kind, actor, targetUnitId, payload, parents, branch);
    }

    public synchronized List<Event> events(Long upToSeq) {
        List<Event> out = new ArrayList<>();
        String sql = "SELECT id,seq,kind,actor,target_unit_id,payload,parents,branch FROM adjudication_events"
                + (upToSeq != null ? " WHERE seq<=" + upToSeq : "") + " ORDER BY seq";
        try (var ps = db.conn().prepareStatement(sql); var rs = ps.executeQuery()) {
            while (rs.next()) out.add(new Event(rs.getString(1), rs.getLong(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), json(rs.getString(6)), strList(rs.getString(7)), rs.getString(8)));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    // ---------- derived ----------
    public synchronized void insertDerived(Derived d, String opId) {
        try (var ps = db.conn().prepareStatement(
                "INSERT INTO derived_results(id,kind,unit_id,input_digest,param_version,parents,status,payload,event_seq,op_id) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, d.id()); ps.setString(2, d.kind()); ps.setString(3, d.unitId());
            ps.setString(4, d.inputDigest()); ps.setString(5, d.paramVersion());
            ArrayNode p = M.createArrayNode(); d.parents().forEach(p::add);
            ps.setString(6, p.toString()); ps.setString(7, d.status());
            ps.setString(8, d.payload().toString()); ps.setLong(9, d.eventSeq()); ps.setString(10, opId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public synchronized List<Derived> derivedFor(String unitId, Long upToSeq) {
        List<Derived> out = new ArrayList<>();
        String sql = "SELECT id,kind,unit_id,input_digest,param_version,parents,status,payload,event_seq FROM derived_results WHERE unit_id=?"
                + (upToSeq != null ? " AND event_seq<=" + upToSeq : "") + " ORDER BY event_seq";
        try (var ps = db.conn().prepareStatement(sql)) {
            ps.setString(1, unitId);
            var rs = ps.executeQuery();
            while (rs.next()) out.add(mapDerived(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public synchronized List<Derived> allDerived() {
        List<Derived> out = new ArrayList<>();
        try (var ps = db.conn().prepareStatement(
                "SELECT id,kind,unit_id,input_digest,param_version,parents,status,payload,event_seq FROM derived_results ORDER BY event_seq");
             var rs = ps.executeQuery()) {
            while (rs.next()) out.add(mapDerived(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    private static Derived mapDerived(java.sql.ResultSet rs) throws SQLException {
        return new Derived(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), strList(rs.getString(6)), rs.getString(7), json(rs.getString(8)), rs.getLong(9));
    }

    public synchronized void invalidate(String derivedId, String reason, String scope, long eventSeq) {
        try (var ps = db.conn().prepareStatement("UPDATE derived_results SET status='invalidated' WHERE id=?")) {
            ps.setString(1, derivedId); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        try (var ps = db.conn().prepareStatement(
                "INSERT INTO invalidations(id,derived_id,reason,scope,event_seq) VALUES(?,?,?,?,?)")) {
            ps.setString(1, newId("inv")); ps.setString(2, derivedId);
            ps.setString(3, reason); ps.setString(4, scope); ps.setLong(5, eventSeq);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public synchronized List<Invalidation> invalidations(Long upToSeq) {
        List<Invalidation> out = new ArrayList<>();
        String sql = "SELECT id,derived_id,reason,scope,event_seq FROM invalidations"
                + (upToSeq != null ? " WHERE event_seq<=" + upToSeq : "") + " ORDER BY event_seq";
        try (var ps = db.conn().prepareStatement(sql); var rs = ps.executeQuery()) {
            while (rs.next()) out.add(new Invalidation(rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getString(4), rs.getLong(5)));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    // ---------- candidates ----------
    public synchronized void insertCandidate(Candidate c) {
        try (var ps = db.conn().prepareStatement(
                "INSERT INTO candidates(id,subject_id,kind,payload,status) VALUES(?,?,?,?,?)")) {
            ps.setString(1, c.id()); ps.setString(2, c.subjectId()); ps.setString(3, c.kind());
            ps.setString(4, c.payload().toString()); ps.setString(5, c.status());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public synchronized List<Candidate> candidates(String subjectId) {
        List<Candidate> out = new ArrayList<>();
        try (var ps = db.conn().prepareStatement(
                "SELECT id,subject_id,kind,payload,status FROM candidates WHERE subject_id=? ORDER BY id")) {
            ps.setString(1, subjectId);
            var rs = ps.executeQuery();
            while (rs.next()) out.add(new Candidate(rs.getString(1), rs.getString(2),
                    rs.getString(3), json(rs.getString(4)), rs.getString(5)));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public synchronized List<Candidate> allCandidates() {
        List<Candidate> out = new ArrayList<>();
        try (var ps = db.conn().prepareStatement("SELECT id,subject_id,kind,payload,status FROM candidates ORDER BY id");
             var rs = ps.executeQuery()) {
            while (rs.next()) out.add(new Candidate(rs.getString(1), rs.getString(2),
                    rs.getString(3), json(rs.getString(4)), rs.getString(5)));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public synchronized void setCandidateStatus(String id, String status) {
        try (var ps = db.conn().prepareStatement("UPDATE candidates SET status=? WHERE id=?")) {
            ps.setString(1, status); ps.setString(2, id); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // ---------- diagnostics ----------
    public synchronized void insertDiagnostic(Diagnostic d) {
        try (var ps = db.conn().prepareStatement(
                "INSERT INTO diagnostics(id,severity,position,message,raw_value,material_id) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, d.id()); ps.setString(2, d.severity()); ps.setString(3, d.position());
            ps.setString(4, d.message()); ps.setString(5, d.rawValue()); ps.setString(6, d.materialId());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public synchronized List<Diagnostic> diagnostics() {
        List<Diagnostic> out = new ArrayList<>();
        try (var ps = db.conn().prepareStatement(
                "SELECT id,severity,position,message,raw_value,material_id FROM diagnostics ORDER BY id");
             var rs = ps.executeQuery()) {
            while (rs.next()) out.add(new Diagnostic(rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    // ---------- kv ----------
    public synchronized void kvPut(String k, String v) {
        try (var ps = db.conn().prepareStatement("INSERT INTO kv(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
            ps.setString(1, k); ps.setString(2, v); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public synchronized Optional<String> kvGet(String k) {
        try (var ps = db.conn().prepareStatement("SELECT v FROM kv WHERE k=?")) {
            ps.setString(1, k);
            var rs = ps.executeQuery();
            return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
}
