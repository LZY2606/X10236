package gsb.spikes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Typed access over the layered store. Materials are immutable and
 * content-addressed; events are append-only; computations record their
 * input digest, parameter version and parent dependencies.
 */
public final class Store {
    public final Db db;

    public Store(Db db) { this.db = db; }

    // ---- materials (immutable) ----

    public String putMaterial(String kind, JsonNode content) {
        return putMaterial(kind, content, null);
    }

    public String putMaterial(String kind, JsonNode content, String intent) {
        String json = Json.canonical(content);
        String id = kind + "-" + Json.sha256(json).substring(0, 24);
        String digest = Json.sha256(json);
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT OR IGNORE INTO materials(kind,id,digest,content,seq,import_intent) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, kind);
            ps.setString(2, id);
            ps.setString(3, digest);
            ps.setString(4, json);
            ps.setLong(5, db.nextSeq());
            ps.setString(6, intent);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    public record Material(String kind, String id, String digest, JsonNode content, long seq) {}

    public Optional<Material> material(String id) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT kind,id,digest,content,seq FROM materials WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Material(rs.getString(1), rs.getString(2), rs.getString(3),
                        Json.parse(rs.getString(4)), rs.getLong(5)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Material> materialsOfKind(String kind) {
        List<Material> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT kind,id,digest,content,seq FROM materials WHERE kind=? ORDER BY seq")) {
            ps.setString(1, kind);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Material(rs.getString(1), rs.getString(2),
                        rs.getString(3), Json.parse(rs.getString(4)), rs.getLong(5)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    // ---- units (stable identity; cluster numbers are only labels) ----

    public record Unit(String id, long seq, String label, String status, String origin) {}

    public String putUnit(String id, String label, String status, String origin) {
        return putUnit(id, label, status, origin, null);
    }

    public String putUnit(String id, String label, String status, String origin, String intent) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT OR REPLACE INTO units(id,seq,label,status,origin,import_intent) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setLong(2, db.nextSeq());
            ps.setString(3, label);
            ps.setString(4, status);
            ps.setString(5, origin);
            ps.setString(6, intent);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    public List<Unit> units() {
        List<Unit> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT id,seq,label,status,origin FROM units ORDER BY seq")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Unit(rs.getString(1), rs.getLong(2),
                        rs.getString(3), rs.getString(4), rs.getString(5)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public Optional<Unit> unit(String id) {
        return units().stream().filter(u -> u.id.equals(id)).findFirst();
    }

    public void setUnitStatus(String id, String status) {
        try (PreparedStatement ps = db.conn.prepareStatement("UPDATE units SET status=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Relabel (e.g. clustering rerun renumbered clusters): identity untouched. */
    public void relabel(String unitId, String newLabel) {
        try (PreparedStatement ps = db.conn.prepareStatement("UPDATE units SET label=? WHERE id=?")) {
            ps.setString(1, newLabel);
            ps.setString(2, unitId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- events (append-only adjudication log) ----

    public record Event(String id, long seq, String branch, String type,
                        JsonNode payload, List<String> parents, String created) {}

    public String putEvent(String branch, String type, JsonNode payload, List<String> parents) {
        return putEvent(branch, type, payload, parents, null);
    }

    public String putEvent(String branch, String type, JsonNode payload, List<String> parents, String intent) {
        long seq = db.nextSeq();
        String id = "evt-" + Json.sha256(branch + "|" + type + "|" + Json.canonical(payload)
                + "|" + String.join(",", parents) + "|" + seq).substring(0, 20);
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO events(id,seq,branch,type,payload,parents,created,import_intent) VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setLong(2, seq);
            ps.setString(3, branch);
            ps.setString(4, type);
            ps.setString(5, Json.canonical(payload));
            ps.setString(6, Json.canonical(parents));
            ps.setString(7, "seq:" + seq);
            ps.setString(8, intent);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    public List<Event> events(String branch) {
        List<Event> out = new ArrayList<>();
        String sql = branch == null
                ? "SELECT id,seq,branch,type,payload,parents,created FROM events ORDER BY seq"
                : "SELECT id,seq,branch,type,payload,parents,created FROM events WHERE branch=? ORDER BY seq";
        try (PreparedStatement ps = db.conn.prepareStatement(sql)) {
            if (branch != null) ps.setString(1, branch);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<String> parents = new ArrayList<>();
                    Json.parse(rs.getString(6)).forEach(n -> parents.add(n.asText()));
                    out.add(new Event(rs.getString(1), rs.getLong(2), rs.getString(3),
                            rs.getString(4), Json.parse(rs.getString(5)), parents, rs.getString(7)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    // ---- evidence ----

    public record Evidence(String id, long seq, String unitId, String kind,
                           JsonNode metrics, double winFrom, double winTo, List<String> sources) {}

    public String putEvidence(String unitId, String kind, JsonNode metrics,
                              double winFrom, double winTo, List<String> sources) {
        return putEvidence(unitId, kind, metrics, winFrom, winTo, sources, null);
    }

    public String putEvidence(String unitId, String kind, JsonNode metrics,
                              double winFrom, double winTo, List<String> sources, String intent) {
        long seq = db.nextSeq();
        String id = "evd-" + Json.sha256(unitId + kind + Json.canonical(metrics)
                + winFrom + "|" + winTo + "|" + seq).substring(0, 20);
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO evidence(id,seq,unit_id,kind,metrics,win_from,win_to,sources,import_intent) VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setLong(2, seq);
            ps.setString(3, unitId);
            ps.setString(4, kind);
            ps.setString(5, Json.canonical(metrics));
            ps.setDouble(6, winFrom);
            ps.setDouble(7, winTo);
            ps.setString(8, Json.canonical(sources));
            ps.setString(9, intent);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    public List<Evidence> evidenceFor(String unitId) {
        List<Evidence> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT id,seq,unit_id,kind,metrics,win_from,win_to,sources FROM evidence WHERE unit_id=? ORDER BY seq")) {
            ps.setString(1, unitId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<String> sources = new ArrayList<>();
                    Json.parse(rs.getString(8)).forEach(n -> sources.add(n.asText()));
                    out.add(new Evidence(rs.getString(1), rs.getLong(2), rs.getString(3),
                            rs.getString(4), Json.parse(rs.getString(5)),
                            rs.getDouble(6), rs.getDouble(7), sources));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    // ---- computations (derived layer) ----

    public record Computation(String id, long seq, String kind, String subject,
                              String inputDigest, String paramVersion, List<String> parents,
                              String status, JsonNode result, Double winFrom, Double winTo,
                              String invalidationReason) {}

    public String putComputation(String kind, String subject, String paramVersion,
                                 List<String> parents, JsonNode result,
                                 Double winFrom, Double winTo) {
        return putComputation(kind, subject, paramVersion, parents, result, winFrom, winTo, null);
    }

    public String putComputation(String kind, String subject, String paramVersion,
                                 List<String> parents, JsonNode result,
                                 Double winFrom, Double winTo, String intent) {
        List<String> sortedParents = new ArrayList<>(parents);
        Collections.sort(sortedParents);
        String inputDigest = Json.sha256(kind + "|" + subject + "|" + paramVersion
                + "|" + String.join(",", sortedParents));
        String id = "cmp-" + inputDigest.substring(0, 20);
        long seq = db.nextSeq();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT OR REPLACE INTO computations(id,seq,kind,subject,input_digest,param_version,parents,status,result,win_from,win_to,invalidation_reason,import_intent) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setLong(2, seq);
            ps.setString(3, kind);
            ps.setString(4, subject);
            ps.setString(5, inputDigest);
            ps.setString(6, paramVersion);
            ps.setString(7, Json.canonical(sortedParents));
            ps.setString(8, "VALID");
            ps.setString(9, Json.canonical(result));
            if (winFrom == null) ps.setNull(10, Types0.REAL); else ps.setDouble(10, winFrom);
            if (winTo == null) ps.setNull(11, Types0.REAL); else ps.setDouble(11, winTo);
            ps.setNull(12, Types0.VARCHAR);
            ps.setString(13, intent);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    // tiny indirection to avoid importing java.sql.Types into call sites
    private static final class Types0 {
        static final int REAL = java.sql.Types.REAL;
        static final int VARCHAR = java.sql.Types.VARCHAR;
    }

    public List<Computation> computations() {
        List<Computation> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT id,seq,kind,subject,input_digest,param_version,parents,status,result,win_from,win_to,invalidation_reason FROM computations ORDER BY seq")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<String> parents = new ArrayList<>();
                    Json.parse(rs.getString(7)).forEach(n -> parents.add(n.asText()));
                    double wf = rs.getDouble(10);
                    double wt = rs.getDouble(11);
                    out.add(new Computation(rs.getString(1), rs.getLong(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), parents,
                            rs.getString(8), Json.parse(rs.getString(9)),
                            rs.wasNull() ? null : wf, rs.wasNull() ? null : wt,
                            rs.getString(12)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public void invalidateComputation(String id, String reason) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "UPDATE computations SET status='INVALIDATED', invalidation_reason=? WHERE id=?")) {
            ps.setString(1, reason);
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void markComputationUndecided(String id) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "UPDATE computations SET status='UNDECIDED' WHERE id=?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- counterexample archive ----

    public record Counterexample(String id, long seq, String name, JsonNode baseline,
                                 JsonNode mutation, JsonNode expectedScope,
                                 JsonNode actualScope, String verdict, boolean adopted) {}

    public String putCounterexample(String name, JsonNode baseline, JsonNode mutation,
                                    JsonNode expectedScope, JsonNode actualScope,
                                    String verdict, boolean adopted) {
        long seq = db.nextSeq();
        String id = "cex-" + Json.sha256(name + "|" + seq).substring(0, 20);
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO counterexamples(id,seq,name,baseline,mutation,expected_scope,actual_scope,verdict,adopted) VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setLong(2, seq);
            ps.setString(3, name);
            ps.setString(4, Json.canonical(baseline));
            ps.setString(5, Json.canonical(mutation));
            ps.setString(6, Json.canonical(expectedScope));
            ps.setString(7, actualScope == null ? null : Json.canonical(actualScope));
            ps.setString(8, verdict);
            ps.setInt(9, adopted ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    public List<Counterexample> counterexamples() {
        List<Counterexample> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT id,seq,name,baseline,mutation,expected_scope,actual_scope,verdict,adopted FROM counterexamples ORDER BY seq")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String actual = rs.getString(7);
                    out.add(new Counterexample(rs.getString(1), rs.getLong(2), rs.getString(3),
                            Json.parse(rs.getString(4)), Json.parse(rs.getString(5)),
                            Json.parse(rs.getString(6)), actual == null ? null : Json.parse(actual),
                            rs.getString(8), rs.getInt(9) == 1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public void updateCounterexampleVerdict(String id, JsonNode actualScope, String verdict, boolean adopted) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "UPDATE counterexamples SET actual_scope=?, verdict=?, adopted=? WHERE id=?")) {
            ps.setString(1, Json.canonical(actualScope));
            ps.setString(2, verdict);
            ps.setInt(3, adopted ? 1 : 0);
            ps.setString(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Machine-readable judgment-basis event (e.g. "cluster number is not identity"). */
    public String recordJudgmentBasis(String branch, String principle, JsonNode detail, List<String> parents) {
        var payload = Json.obj();
        payload.put("principle", principle);
        payload.set("detail", detail);
        return putEvent(branch, "JUDGMENT_BASIS", payload, parents);
    }
}
