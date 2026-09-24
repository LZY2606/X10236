package org.neuro.spikeledger.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.neuro.spikeledger.util.J;

/** 追加式事件日志与分支指针；事件只增不改，时间旅行依赖 parent 链。 */
public class EventStore {
    public record Event(long seq, String id, String branchId, String parentId, String type,
                        Map<String, Object> payload, String inputDigest,
                        String paramsVersion, long createdAtMs) {}

    private final Db db;

    public EventStore(Db db) {
        this.db = db;
    }

    public void ensureBranch(String branchId, String name) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "INSERT OR IGNORE INTO branch(id,name,head_event_id,created_at_ms) VALUES(?,?,?,?)")) {
            ps.setString(1, branchId);
            ps.setString(2, name);
            ps.setString(3, null);
            ps.setLong(4, Instant.now().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<String> branches() {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement("SELECT id FROM branch ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public String head(String branchId) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT head_event_id FROM branch WHERE id=?")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 追加事件并推进分支头；同分支顺序由 seq 决定，内容相同则 id 相同（稳定重放）。 */
    public Event append(String branchId, String eventId, String parentId, String type,
                        String payloadJson, String inputDigest, String paramsVersion,
                        long createdAtMs) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "INSERT INTO event_log(id,branch_id,parent_id,type,payload_json,input_digest,"
                        + "params_version,created_at_ms) VALUES(?,?,?,?,?,?,?,?)",
                PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, eventId);
            ps.setString(2, branchId);
            ps.setString(3, parentId);
            ps.setString(4, type);
            ps.setString(5, payloadJson);
            ps.setString(6, inputDigest);
            ps.setString(7, paramsVersion);
            ps.setLong(8, createdAtMs);
            ps.executeUpdate();
            long seq;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                seq = keys.getLong(1);
            }
            try (PreparedStatement up = db.conn().prepareStatement(
                    "UPDATE branch SET head_event_id=? WHERE id=?")) {
                up.setString(1, eventId);
                up.setString(2, branchId);
                up.executeUpdate();
            }
            return new Event(seq, eventId, branchId, parentId, type, J.map(payloadJson),
                    inputDigest, paramsVersion, createdAtMs);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Event bySeq(long seq) {
        try (PreparedStatement ps = db.conn().prepareStatement("SELECT * FROM event_log WHERE seq=?")) {
            ps.setLong(1, seq);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Event byId(String id) {
        try (PreparedStatement ps = db.conn().prepareStatement("SELECT * FROM event_log WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Event> branchEvents(String branchId) {
        List<Event> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT * FROM event_log WHERE branch_id=? ORDER BY seq")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /** 返回分支上某事件（含）之前的祖先链 seq 列表。 */
    public List<Long> ancestrySeq(String branchId, String eventId) {
        List<Long> out = new ArrayList<>();
        String cur = eventId;
        int guard = 0;
        while (cur != null && guard++ < 1_000_000) {
            Event e = byId(cur);
            if (e == null || !e.branchId().equals(branchId)) {
                break;
            }
            out.add(e.seq());
            cur = e.parentId();
        }
        java.util.Collections.reverse(out);
        return out;
    }

    public long maxSeq() {
        try (PreparedStatement ps = db.conn().prepareStatement("SELECT COALESCE(MAX(seq),0) FROM event_log");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Event map(ResultSet rs) throws SQLException {
        return new Event(rs.getLong("seq"), rs.getString("id"), rs.getString("branch_id"),
                rs.getString("parent_id"), rs.getString("type"),
                J.map(rs.getString("payload_json")), rs.getString("input_digest"),
                rs.getString("params_version"), rs.getLong("created_at_ms"));
    }

    // ---- 导入暂存（崩溃围栏）----
    public void putStaging(String token, String bundleId, String state, int written, int total,
                           String manifestJson) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "INSERT INTO import_staging(token,bundle_id,state,written,total,manifest_json,updated_at_ms) "
                        + "VALUES(?,?,?,?,?,?,?) ON CONFLICT(token) DO UPDATE SET state=excluded.state,"
                        + "written=excluded.written,total=excluded.total,manifest_json=excluded.manifest_json,"
                        + "updated_at_ms=excluded.updated_at_ms")) {
            ps.setString(1, token);
            ps.setString(2, bundleId);
            ps.setString(3, state);
            ps.setInt(4, written);
            ps.setInt(5, total);
            ps.setString(6, manifestJson);
            ps.setLong(7, Instant.now().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public String stagingState(String token) {
        try (PreparedStatement ps = db.conn().prepareStatement("SELECT state FROM import_staging WHERE token=?")) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void deleteStaging(String token) {
        try (PreparedStatement ps = db.conn().prepareStatement("DELETE FROM import_staging WHERE token=?")) {
            ps.setString(1, token);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<String> resumeStagingTokens() {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT token FROM import_staging WHERE state IN ('WRITING','PARTIAL') ORDER BY updated_at_ms");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public java.sql.Timestamp unused(Timestamp t) {
        return t;
    }
}
