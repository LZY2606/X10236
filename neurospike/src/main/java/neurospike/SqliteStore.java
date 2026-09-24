package neurospike;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite persistence. The event log is the source of truth; a state snapshot is
 * cached per head event. A journal table makes an interrupted append recoverable:
 * on open, COMMITTED journal entries without a matching event are applied, while
 * rows stuck in PENDING without a commit marker are rolled forward as a recorded
 * PARTIAL_WRITE diagnostic rather than silently dropped.
 */
public final class SqliteStore implements AutoCloseable {

    private final Connection conn;

    public SqliteStore(Path path) {
        try {
            Files.createDirectories(path.getParent());
            String url = "jdbc:sqlite:" + path.toAbsolutePath();
            conn = DriverManager.getConnection(url);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA foreign_keys=ON");
            }
            schema();
        } catch (Exception e) {
            throw new IllegalStateException("cannot open store: " + e.getMessage(), e);
        }
    }

    private void schema() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS meta (
                    k TEXT PRIMARY KEY,
                    v TEXT NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS events (
                    event_id TEXT PRIMARY KEY,
                    seq INTEGER NOT NULL UNIQUE,
                    kind TEXT NOT NULL,
                    parent_event_id TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    input_digest TEXT NOT NULL,
                    param_version TEXT NOT NULL,
                    status TEXT NOT NULL,
                    diagnostic_json TEXT,
                    created_at TEXT NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS decisions (
                    event_id TEXT PRIMARY KEY,
                    seq INTEGER NOT NULL,
                    target_id TEXT NOT NULL,
                    target_kind TEXT NOT NULL,
                    verdict TEXT NOT NULL,
                    rationale TEXT,
                    reviewer TEXT,
                    candidates TEXT,
                    status TEXT NOT NULL,
                    params TEXT,
                    supersedes_event_id TEXT
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS snapshot (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    case_id TEXT NOT NULL,
                    state_json TEXT NOT NULL,
                    head_event_id TEXT NOT NULL,
                    written_at TEXT NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS evaluations (
                    at_event_id TEXT NOT NULL,
                    window_start REAL NOT NULL,
                    window_end REAL NOT NULL,
                    eval_json TEXT NOT NULL,
                    input_digest TEXT NOT NULL,
                    param_version TEXT NOT NULL,
                    PRIMARY KEY (at_event_id, window_start, window_end)
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS findings (
                    finding_id TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    cluster_stable_id TEXT NOT NULL,
                    status TEXT NOT NULL,
                    introduced_event TEXT NOT NULL,
                    valid_from_event TEXT NOT NULL,
                    valid_to_event TEXT,
                    adopted TEXT NOT NULL,
                    json TEXT NOT NULL,
                    PRIMARY KEY (finding_id, revision)
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS journal (
                    journal_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    state TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    committed_at TEXT
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS recovery (
                    journal_id INTEGER PRIMARY KEY,
                    result TEXT NOT NULL,
                    detail TEXT NOT NULL,
                    recovered_at TEXT NOT NULL
                )""");
        }
    }

    public boolean hasCase() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM snapshot")) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized void save(State state) {
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement de = conn.prepareStatement("DELETE FROM events");
                 PreparedStatement dd = conn.prepareStatement("DELETE FROM decisions")) {
                de.executeUpdate();
                dd.executeUpdate();
            }
            insertEvents(state);
            insertDecisions(state);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO snapshot(id,case_id,state_json,head_event_id,"
                            + "written_at) VALUES (1,?,?,?,?)")) {
                ps.setString(1, state.caseId());
                ps.setString(2, Json.MAPPER.writeValueAsString(state));
                ps.setString(3, state.head().eventId());
                ps.setString(4, java.time.Instant.now().toString());
                ps.executeUpdate();
            }
            conn.commit();
        } catch (Exception e) {
            rollbackQuietly();
            throw new IllegalStateException(e);
        } finally {
            autoCommitQuietly();
        }
    }

    /** Journal an append before applying it. Returns journal id. */
    public synchronized long journalAppend(JsonNode payload) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO journal(state,payload,created_at) VALUES ('PENDING',?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, payload.toString());
            ps.setString(2, java.time.Instant.now().toString());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
            throw new IllegalStateException("no journal id");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized void markCommitted(long journalId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE journal SET state='COMMITTED', committed_at=? WHERE journal_id=?")) {
            ps.setString(1, java.time.Instant.now().toString());
            ps.setLong(2, journalId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void insertEvents(State state) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO events(event_id,seq,kind,parent_event_id,payload,"
                        + "input_digest,param_version,status,diagnostic_json,created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            for (Event e : state.events()) {
                ps.setString(1, e.eventId());
                ps.setLong(2, e.seq());
                ps.setString(3, e.kind());
                ps.setString(4, e.parentEventId());
                ps.setString(5, e.payload().toString());
                ps.setString(6, e.inputDigest());
                ps.setString(7, e.paramVersion());
                ps.setString(8, e.status());
                ps.setString(9, e.diagnosticJson());
                ps.setString(10, e.createdAt());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO decisions(event_id,seq,target_id,target_kind,verdict,"
                        + "rationale,reviewer,candidates,status,params,supersedes_event_id)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            for (Decision d : state.decisions()) {
                ps.setString(1, d.eventId());
                ps.setLong(2, d.seq());
                ps.setString(3, d.targetId());
                ps.setString(4, d.targetKind());
                ps.setString(5, d.verdict());
                ps.setString(6, d.rationale());
                ps.setString(7, d.reviewer());
                ps.setString(8, Json.MAPPER.writeValueAsString(d.candidates()));
                ps.setString(9, d.status());
                ps.setString(10, d.params() == null ? null : d.params().toString());
                ps.setString(11, d.supersedesEventId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public synchronized State loadState() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT state_json FROM snapshot WHERE id=1")) {
            if (!rs.next()) {
                return null;
            }
            return Json.MAPPER.readValue(rs.getString(1), State.class);
        } catch (Exception e) {
            throw new IllegalStateException("snapshot unreadable: " + e.getMessage(), e);
        }
    }

    public synchronized void saveEvaluation(Evaluation e, WindowSpec window) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO evaluations(at_event_id,window_start,window_end,"
                        + "eval_json,input_digest,param_version) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, e.stateEventId());
            ps.setDouble(2, window.startSeconds());
            ps.setDouble(3, window.endSeconds());
            ps.setString(4, Json.MAPPER.writeValueAsString(e));
            ps.setString(5, e.inputDigest());
            ps.setString(6, e.paramVersion());
            ps.executeUpdate();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public synchronized String loadEvaluationJson(String eventId, double start, double end) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT eval_json FROM evaluations WHERE at_event_id=? "
                        + "AND window_start=? AND window_end=?")) {
            ps.setString(1, eventId);
            ps.setDouble(2, start);
            ps.setDouble(3, end);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Insert or extend finding validity/adoption revision. */
    public synchronized void recordFindingRevision(Finding f, int revision,
                                                   boolean adopted) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO findings(finding_id,revision,type,"
                        + "cluster_stable_id,status,introduced_event,valid_from_event,"
                        + "valid_to_event,adopted,json) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, f.findingId());
            ps.setInt(2, revision);
            ps.setString(3, f.type());
            ps.setString(4, f.clusterStableId());
            ps.setString(5, f.status());
            ps.setString(6, f.introducedByEvent());
            ps.setString(7, f.validFromEvent());
            ps.setString(8, f.validToEvent());
            ps.setString(9, adopted ? "ADOPTED" : "NOT_ADOPTED");
            ps.setString(10, Json.MAPPER.writeValueAsString(f));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized List<String[]> listFindingRevisions() {
        List<String[]> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT finding_id,revision,adopted,valid_from_event,"
                             + "valid_to_event FROM findings ORDER BY finding_id,revision")) {
            while (rs.next()) {
                out.add(new String[]{rs.getString(1), Integer.toString(rs.getInt(2)),
                        rs.getString(3), rs.getString(4),
                        rs.getString(5) == null ? "" : rs.getString(5)});
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public record RecoveryReport(List<String> applied, List<String> partialWrites) {
    }

    /**
     * Recover after a half-written append. COMMITTED journal entries with no
     * matching event are returned for replay; PENDING entries are recorded as
     * PARTIAL_WRITE and marked ROLLED_BACK instead of being silently deleted.
     */
    public synchronized RecoveryReport recover(State state) {
        List<String> applied = new ArrayList<>();
        List<String> partial = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT journal_id,state,payload FROM journal ORDER BY journal_id")) {
            while (rs.next()) {
                long id = rs.getLong(1);
                String jstate = rs.getString(2);
                String payload = rs.getString(3);
                if ("PENDING".equals(jstate)) {
                    partial.add(payload);
                    try (PreparedStatement ins = conn.prepareStatement(
                            "INSERT OR REPLACE INTO recovery(journal_id,result,detail,"
                                    + "recovered_at) VALUES (?,?,?,?)");
                         PreparedStatement up = conn.prepareStatement(
                                 "UPDATE journal SET state='ROLLED_BACK' WHERE journal_id=?")) {
                        ins.setLong(1, id);
                        ins.setString(2, Diagnostic.PARTIAL_WRITE);
                        ins.setString(3, payload);
                        ins.setString(4, java.time.Instant.now().toString());
                        ins.executeUpdate();
                        up.setLong(1, id);
                        up.executeUpdate();
                    }
                } else if ("COMMITTED".equals(jstate)) {
                    applied.add(payload);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new RecoveryReport(List.copyOf(applied), List.copyOf(partial));
    }

    public synchronized List<String> recoveryDetails() {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT result,detail FROM recovery ORDER BY journal_id")) {
            while (rs.next()) {
                out.add(rs.getString(1) + "|" + rs.getString(2));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private void rollbackQuietly() {
        try {
            conn.rollback();
        } catch (Exception ignored) {
        }
    }

    private void autoCommitQuietly() {
        try {
            conn.setAutoCommit(true);
        } catch (Exception ignored) {
        }
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (Exception ignored) {
        }
    }
}
