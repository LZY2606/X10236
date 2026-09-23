package ephys.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import ephys.db.Database;
import ephys.model.Ids;
import ephys.model.Json;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Typed access over the layered tables. Materials are immutable; decisions are append-only. */
public final class Store {
    private final Database db;

    public Store(Database db) {
        this.db = db;
    }

    public Database db() {
        return db;
    }

    // ---- materials (immutable) ----

    public void putMaterial(String id, String kind, int version, JsonNode payload) {
        String sql = "INSERT INTO materials(id,kind,version,payload,digest,imported_at) VALUES(?,?,?,?,?,?)";
        try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, kind);
            ps.setInt(3, version);
            String text = Json.writeCompact(payload);
            ps.setString(4, text);
            ps.setString(5, Ids.digest(List.of(text)));
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Material(String id, String kind, int version, JsonNode payload, String digest) {}

    public Optional<Material> material(String id) {
        return materialsByKind(null).stream().filter(m -> m.id.equals(id)).findFirst();
    }

    public List<Material> materialsByKind(String kind) {
        String sql = kind == null ? "SELECT * FROM materials ORDER BY id"
                : "SELECT * FROM materials WHERE kind=? ORDER BY id";
        try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
            if (kind != null) ps.setString(1, kind);
            List<Material> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Material(rs.getString("id"), rs.getString("kind"), rs.getInt("version"),
                            Json.parse(rs.getString("payload")), rs.getString("digest")));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- units (stable identity) ----

    public void putUnit(String id, String originEvent, String status, long createdSeq) {
        try (PreparedStatement ps = db.conn()
                .prepareStatement("INSERT INTO units(id,origin_event,status,created_seq) VALUES(?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, originEvent);
            ps.setString(3, status);
            ps.setLong(4, createdSeq);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void setUnitStatus(String id, String status) {
        try (PreparedStatement ps = db.conn().prepareStatement("UPDATE units SET status=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Unit(String id, String originEvent, String status, long createdSeq) {}

    public List<Unit> units() {
        try (PreparedStatement ps = db.conn().prepareStatement("SELECT * FROM units ORDER BY created_seq, id");
             ResultSet rs = ps.executeQuery()) {
            List<Unit> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new Unit(rs.getString("id"), rs.getString("origin_event"),
                        rs.getString("status"), rs.getLong("created_seq")));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- cluster labels (unstable) ----

    public void putClusterLabel(String id, String runId, int clusterNo, String unitId,
                                double windowStartS, double windowEndS) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "INSERT INTO cluster_labels(id,run_id,cluster_no,unit_id,window_start_s,window_end_s)"
                        + " VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, runId);
            ps.setInt(3, clusterNo);
            ps.setString(4, unitId);
            ps.setDouble(5, windowStartS);
            ps.setDouble(6, windowEndS);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public record ClusterLabel(String id, String runId, int clusterNo, String unitId,
                               double windowStartS, double windowEndS) {}

    public List<ClusterLabel> clusterLabels() {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT * FROM cluster_labels ORDER BY run_id, cluster_no");
             ResultSet rs = ps.executeQuery()) {
            List<ClusterLabel> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new ClusterLabel(rs.getString("id"), rs.getString("run_id"), rs.getInt("cluster_no"),
                        rs.getString("unit_id"), rs.getDouble("window_start_s"), rs.getDouble("window_end_s")));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- adjudication events (append-only, conflicts coexist) ----

    public long nextEventSeq() {
        try (PreparedStatement ps = db.conn().prepareStatement("SELECT COALESCE(MAX(seq),0)+1 AS n FROM adjudication_events");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong("n") : 1;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void putEvent(String id, long seq, String ts, String type, String actor,
                         JsonNode payload, List<String> parents, String conflictsWith) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "INSERT INTO adjudication_events(id,seq,ts,type,actor,payload,parents,conflicts_with)"
                        + " VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setLong(2, seq);
            ps.setString(3, ts);
            ps.setString(4, type);
            ps.setString(5, actor);
            ps.setString(6, Json.writeCompact(payload));
            ArrayNode p = Json.arr();
            parents.forEach(p::add);
            ps.setString(7, Json.writeCompact(p));
            ps.setString(8, conflictsWith);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Event(String id, long seq, String ts, String type, String actor,
                        JsonNode payload, List<String> parents, String conflictsWith) {}

    public List<Event> events() {
        try (PreparedStatement ps = db.conn().prepareStatement("SELECT * FROM adjudication_events ORDER BY seq");
             ResultSet rs = ps.executeQuery()) {
            List<Event> out = new ArrayList<>();
            while (rs.next()) {
                List<String> parents = new ArrayList<>();
                Json.parse(rs.getString("parents")).forEach(n -> parents.add(n.asText()));
                out.add(new Event(rs.getString("id"), rs.getLong("seq"), rs.getString("ts"),
                        rs.getString("type"), rs.getString("actor"), Json.parse(rs.getString("payload")),
                        parents, rs.getString("conflicts_with")));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- evidence events (machine-readable judgment basis) ----

    public void putEvidence(String id, String kind, List<String> subjects, JsonNode basis, long createdSeq) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "INSERT INTO evidence_events(id,kind,subjects,basis,created_seq) VALUES(?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, kind);
            ArrayNode s = Json.arr();
            subjects.forEach(s::add);
            ps.setString(3, Json.writeCompact(s));
            ps.setString(4, Json.writeCompact(basis));
            ps.setLong(5, createdSeq);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Evidence(String id, String kind, List<String> subjects, JsonNode basis, long createdSeq) {}

    public List<Evidence> evidence() {
        try (PreparedStatement ps = db.conn().prepareStatement("SELECT * FROM evidence_events ORDER BY created_seq, id");
             ResultSet rs = ps.executeQuery()) {
            List<Evidence> out = new ArrayList<>();
            while (rs.next()) {
                List<String> subjects = new ArrayList<>();
                Json.parse(rs.getString("subjects")).forEach(n -> subjects.add(n.asText()));
                out.add(new Evidence(rs.getString("id"), rs.getString("kind"), subjects,
                        Json.parse(rs.getString("basis")), rs.getLong("created_seq")));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- computed results (provenance + invalidation) ----

    public void putResult(String id, String kind, String subject, String inputDigest, String paramVersion,
                          List<String> parents, String status, JsonNode payload, JsonNode diagnostics,
                          Double windowStartS, Double windowEndS, String validFrom, String validTo) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "INSERT INTO computed_results(id,kind,subject,input_digest,param_version,parents,status,payload,"
                        + "diagnostics,window_start_s,window_end_s,valid_from,valid_to) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, kind);
            ps.setString(3, subject);
            ps.setString(4, inputDigest);
            ps.setString(5, paramVersion);
            ArrayNode p = Json.arr();
            parents.forEach(p::add);
            ps.setString(6, Json.writeCompact(p));
            ps.setString(7, status);
            ps.setString(8, payload == null ? null : Json.writeCompact(payload));
            ps.setString(9, diagnostics == null ? null : Json.writeCompact(diagnostics));
            if (windowStartS == null) ps.setNull(10, java.sql.Types.REAL); else ps.setDouble(10, windowStartS);
            if (windowEndS == null) ps.setNull(11, java.sql.Types.REAL); else ps.setDouble(11, windowEndS);
            ps.setString(12, validFrom);
            ps.setString(13, validTo);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void invalidateResult(String id, String invalidatedBy, String reason) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "UPDATE computed_results SET invalidated_by=?, invalidation_reason=? WHERE id=?")) {
            ps.setString(1, invalidatedBy);
            ps.setString(2, reason);
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Result(String id, String kind, String subject, String inputDigest, String paramVersion,
                         List<String> parents, String status, JsonNode payload, JsonNode diagnostics,
                         Double windowStartS, Double windowEndS, String validFrom, String validTo,
                         String invalidatedBy, String invalidationReason) {}

    public List<Result> results() {
        try (PreparedStatement ps = db.conn().prepareStatement("SELECT * FROM computed_results ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            List<Result> out = new ArrayList<>();
            while (rs.next()) {
                List<String> parents = new ArrayList<>();
                Json.parse(rs.getString("parents")).forEach(n -> parents.add(n.asText()));
                double ws = rs.getDouble("window_start_s");
                double we = rs.getDouble("window_end_s");
                String payload = rs.getString("payload");
                String diag = rs.getString("diagnostics");
                out.add(new Result(rs.getString("id"), rs.getString("kind"), rs.getString("subject"),
                        rs.getString("input_digest"), rs.getString("param_version"), parents,
                        rs.getString("status"), payload == null ? null : Json.parse(payload),
                        diag == null ? null : Json.parse(diag),
                        rs.wasNull() ? null : ws, rs.wasNull() ? null : we,
                        rs.getString("valid_from"), rs.getString("valid_to"),
                        rs.getString("invalidated_by"), rs.getString("invalidation_reason")));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- candidates (all legal options kept when not uniquely decidable) ----

    public void putCandidate(String id, String decisionPoint, JsonNode options, String resolvedBy) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "INSERT INTO candidates(id,decision_point,options,resolved_by) VALUES(?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, decisionPoint);
            ps.setString(3, Json.writeCompact(options));
            ps.setString(4, resolvedBy);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void resolveCandidate(String id, String resolvedBy) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "UPDATE candidates SET resolved_by=? WHERE id=?")) {
            ps.setString(1, resolvedBy);
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Candidate(String id, String decisionPoint, JsonNode options, String resolvedBy) {}

    public List<Candidate> candidates() {
        try (PreparedStatement ps = db.conn().prepareStatement("SELECT * FROM candidates ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            List<Candidate> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new Candidate(rs.getString("id"), rs.getString("decision_point"),
                        Json.parse(rs.getString("options")), rs.getString("resolved_by")));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
