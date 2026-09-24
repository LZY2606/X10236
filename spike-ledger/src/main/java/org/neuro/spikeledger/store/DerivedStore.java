package org.neuro.spikeledger.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.neuro.spikeledger.model.Diagnostic;
import org.neuro.spikeledger.model.Entities.Unit;
import org.neuro.spikeledger.model.Evidence;
import org.neuro.spikeledger.util.J;

/** 派生层：版本有效期 [valid_from_seq, invalid_at_seq)，支撑时间旅行与局部失效。 */
public class DerivedStore {
    private final Db db;

    public DerivedStore(Db db) {
        this.db = db;
    }

    public void putUnit(Unit u, long validFromSeq) {
        exec("INSERT INTO unit(id,branch_id,run_id,label_index,event_ids_json,window_start_ms,"
                + "window_end_ms,produced_event,valid_from_seq,invalid_at_seq) "
                + "VALUES(?,?,?,?,?,?,?,?,?,NULL)", ps -> {
            ps.setString(1, u.id());
            ps.setString(2, u.branchId());
            ps.setString(3, u.runId());
            ps.setInt(4, u.labelIndex());
            ps.setString(5, J.to(u.eventIds()));
            ps.setDouble(6, u.windowStartMs());
            ps.setDouble(7, u.windowEndMs());
            ps.setString(8, producedEvent(u));
            ps.setLong(9, validFromSeq);
        });
    }

    private static String producedEvent(Unit u) {
        return u.runId();
    }

    /** 使某事件窗口与变化窗口相交的旧单元版本在 seq 处失效；不相交的保持有效（只重算相交簇）。 */
    public int invalidateIntersecting(String branchId, double changeStart, double changeEnd, long seq) {
        List<Unit> live = liveUnits(branchId);
        int n = 0;
        for (Unit u : live) {
            if (intersects(u.windowStartMs(), u.windowEndMs(), changeStart, changeEnd)) {
                exec("UPDATE unit SET invalid_at_seq=? WHERE id=? AND invalid_at_seq IS NULL", ps -> {
                    ps.setLong(1, seq);
                    ps.setString(2, u.id());
                });
                n++;
            }
        }
        return n;
    }

    public static boolean intersects(double aStart, double aEnd, double bStart, double bEnd) {
        return aStart < bEnd && bStart < aEnd;
    }

    /** 某 seq 时刻分支上的有效单元（时间旅行视图）。 */
    public List<Unit> unitsAt(String branchId, long seq) {
        List<Unit> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT id,branch_id,run_id,label_index,event_ids_json,window_start_ms,window_end_ms "
                        + "FROM unit WHERE branch_id=? AND valid_from_seq<=? "
                        + "AND (invalid_at_seq IS NULL OR invalid_at_seq>?) ORDER BY id,valid_from_seq")) {
            ps.setString(1, branchId);
            ps.setLong(2, seq);
            ps.setLong(3, seq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Unit(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getInt(4), J.strList(rs.getString(5)),
                            rs.getDouble(6), rs.getDouble(7)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<Unit> liveUnits(String branchId) {
        List<Unit> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT id,branch_id,run_id,label_index,event_ids_json,window_start_ms,window_end_ms "
                        + "FROM unit WHERE branch_id=? AND invalid_at_seq IS NULL ORDER BY id")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Unit(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getInt(4), J.strList(rs.getString(5)),
                            rs.getDouble(6), rs.getDouble(7)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public void putEvidence(Evidence e, long seq) {
        exec("INSERT INTO unit_evidence(unit_id,valid_from_seq,type,severity,event_ids_json,detail,basis) "
                + "VALUES(?,?,?,?,?,?,?)", ps -> {
            ps.setString(1, e.unitId());
            ps.setLong(2, seq);
            ps.setString(3, e.type());
            ps.setDouble(4, e.severity());
            ps.setString(5, J.to(e.eventIds()));
            ps.setString(6, e.detail());
            ps.setString(7, e.basis());
        });
    }

    public List<Evidence> evidenceAt(String unitId, long seq) {
        List<Evidence> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT unit_id,type,severity,event_ids_json,detail,basis FROM unit_evidence "
                        + "WHERE unit_id=? AND valid_from_seq<=? ORDER BY type")) {
            ps.setString(1, unitId);
            ps.setLong(2, seq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Evidence(rs.getString(1), rs.getString(2), rs.getDouble(3),
                            J.strList(rs.getString(4)), rs.getString(5), rs.getString(6)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public void putAffected(String downstreamId, String kind, String causedByEvent, long seq,
                            String detail) {
        exec("INSERT OR REPLACE INTO affected_record(downstream_id,kind,caused_by_event,valid_at_seq,detail) "
                + "VALUES(?,?,?,?,?)", ps -> {
            ps.setString(1, downstreamId);
            ps.setString(2, kind);
            ps.setString(3, causedByEvent);
            ps.setLong(4, seq);
            ps.setString(5, detail);
        });
    }

    public record Affected(String downstreamId, String kind, String causedByEvent, long seq,
                           String detail) {}

    public List<Affected> affectedBy(String causedByEvent) {
        List<Affected> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT downstream_id,kind,caused_by_event,valid_at_seq,detail FROM affected_record "
                        + "WHERE caused_by_event=? ORDER BY downstream_id")) {
            ps.setString(1, causedByEvent);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Affected(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getLong(4), rs.getString(5)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public void putDiagnostic(Diagnostic d, String scope, String materialId, String createdEvent) {
        exec("INSERT INTO diagnostic(scope,material_id,location,code,severity,message,created_event) "
                + "VALUES(?,?,?,?,?,?,?)", ps -> {
            ps.setString(1, scope);
            ps.setString(2, materialId);
            ps.setString(3, d.location());
            ps.setString(4, d.code());
            ps.setString(5, d.severity());
            ps.setString(6, d.message());
            ps.setString(7, createdEvent);
        });
    }

    public List<Diagnostic> diagnostics() {
        List<Diagnostic> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT severity,code,location,message FROM diagnostic ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Diagnostic(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private interface SqlBody {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private void exec(String sql, SqlBody body) {
        try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
            body.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
