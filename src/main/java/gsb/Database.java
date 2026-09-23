package gsb;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * SQLite storage. Immutable materials, mutable decisions and derived results live
 * in separate tables. Multi-row writes go through write intents so a crash
 * mid-write can be detected and rolled back on next open (half-write recovery).
 */
public final class Database implements AutoCloseable {
    public final Connection conn;

    private Database(Connection conn) {
        this.conn = conn;
    }

    public static Database open(String path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA busy_timeout=5000");
            }
            Database db = new Database(c);
            db.createSchema();
            return db;
        } catch (SQLException e) {
            throw new RuntimeException("cannot open database " + path + ": " + e.getMessage(), e);
        }
    }

    public static Database inMemory() {
        return open(":memory:");
    }

    private void createSchema() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            // --- immutable material layers (stored separately) ---
            s.execute("CREATE TABLE IF NOT EXISTS waveform_refs("
                    + "id TEXT PRIMARY KEY, seq INTEGER NOT NULL, channel INTEGER NOT NULL,"
                    + "time_value TEXT NOT NULL, time_unit TEXT NOT NULL, time_basis TEXT NOT NULL,"
                    + "amplitude_value TEXT NOT NULL, amplitude_unit TEXT NOT NULL,"
                    + "samples_json TEXT NOT NULL, sample_rate_value TEXT NOT NULL, sample_rate_unit TEXT NOT NULL,"
                    + "digest TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS probe_geometries("
                    + "id TEXT PRIMARY KEY, seq INTEGER NOT NULL, frame TEXT NOT NULL,"
                    + "sites_json TEXT NOT NULL, digest TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS detection_thresholds("
                    + "id TEXT PRIMARY KEY, seq INTEGER NOT NULL, channel INTEGER NOT NULL,"
                    + "value TEXT NOT NULL, unit TEXT NOT NULL, version INTEGER NOT NULL, digest TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS feature_versions("
                    + "id TEXT PRIMARY KEY, seq INTEGER NOT NULL, name TEXT NOT NULL,"
                    + "params_json TEXT NOT NULL, digest TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS cluster_labels("
                    + "id TEXT PRIMARY KEY, seq INTEGER NOT NULL, label_set_version INTEGER NOT NULL,"
                    + "cluster_no INTEGER NOT NULL, unit_id TEXT NOT NULL,"
                    + "spike_ids_json TEXT NOT NULL, digest TEXT NOT NULL)");
            // --- mutable decision layer ---
            s.execute("CREATE TABLE IF NOT EXISTS adjudication_events("
                    + "id TEXT PRIMARY KEY, seq INTEGER NOT NULL, type TEXT NOT NULL,"
                    + "payload_json TEXT NOT NULL, parents_json TEXT NOT NULL,"
                    + "status TEXT NOT NULL, digest TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS units("
                    + "id TEXT PRIMARY KEY, seq INTEGER NOT NULL, origin TEXT NOT NULL, status TEXT NOT NULL)");
            // --- derived layer: every result records inputs, parameter version and parents ---
            s.execute("CREATE TABLE IF NOT EXISTS derived_results("
                    + "id TEXT PRIMARY KEY, seq INTEGER NOT NULL, kind TEXT NOT NULL, unit_id TEXT NOT NULL,"
                    + "input_digest TEXT NOT NULL, param_version TEXT NOT NULL, parents_json TEXT NOT NULL,"
                    + "window_start REAL, window_end REAL, status TEXT NOT NULL,"
                    + "invalidation_reason TEXT, payload_json TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS evidence_events("
                    + "id TEXT PRIMARY KEY, seq INTEGER NOT NULL, unit_id TEXT NOT NULL, kind TEXT NOT NULL,"
                    + "magnitude REAL, detail_json TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS diagnostics("
                    + "id TEXT PRIMARY KEY, seq INTEGER NOT NULL, target TEXT NOT NULL,"
                    + "position TEXT NOT NULL, severity TEXT NOT NULL, message TEXT NOT NULL)");
            // --- half-write recovery journal ---
            s.execute("CREATE TABLE IF NOT EXISTS write_intents("
                    + "id TEXT PRIMARY KEY, seq INTEGER NOT NULL, state TEXT NOT NULL, payload_json TEXT NOT NULL)");
        }
    }

    public long nextSeq() {
        long seq = getMetaLong("seq", 0) + 1;
        setMeta("seq", Long.toString(seq));
        return seq;
    }

    public long currentSeq() {
        return getMetaLong("seq", 0);
    }

    public String newId(String prefix) {
        long n = getMetaLong("counter:" + prefix, 0) + 1;
        setMeta("counter:" + prefix, Long.toString(n));
        return String.format("%s%06d", prefix, n);
    }

    public long getMetaLong(String key, long dflt) {
        String v = getMeta(key);
        return v == null ? dflt : Long.parseLong(v);
    }

    public String getMeta(String key) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void setMeta(String key, String value) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Run inside a transaction; rolls back on any failure. */
    public <T> T tx(Function<Database, T> work) {
        try {
            conn.setAutoCommit(false);
            T result = work.apply(this);
            conn.commit();
            return result;
        } catch (RuntimeException e) {
            rollbackQuietly();
            throw e;
        } finally {
            setAutoCommitQuietly();
        }
    }

    public void txRun(java.util.function.Consumer<Database> work) {
        tx(db -> { work.accept(db); return null; });
    }

    private void rollbackQuietly() {
        try { conn.rollback(); } catch (SQLException ignored) {}
    }

    private void setAutoCommitQuietly() {
        try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
    }

    public void execute(String sql, Object... params) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                Object p = params[i];
                if (p == null) ps.setObject(i + 1, null);
                else if (p instanceof Long l) ps.setLong(i + 1, l);
                else if (p instanceof Integer n) ps.setInt(i + 1, n);
                else if (p instanceof Double d) ps.setDouble(i + 1, d);
                else ps.setString(i + 1, p.toString());
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("SQL failed: " + sql + " -> " + e.getMessage(), e);
        }
    }

    public interface RowMapper<T> { T map(ResultSet rs) throws SQLException; }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                Object p = params[i];
                if (p == null) ps.setObject(i + 1, null);
                else if (p instanceof Long l) ps.setLong(i + 1, l);
                else if (p instanceof Integer n) ps.setInt(i + 1, n);
                else if (p instanceof Double d) ps.setDouble(i + 1, d);
                else ps.setString(i + 1, p.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(mapper.map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException("SQL failed: " + sql + " -> " + e.getMessage(), e);
        }
    }

    /**
     * Half-write recovery: intents left in PENDING state mean a crash happened
     * between journal and apply. Rows recorded in the intent payload are removed
     * and the intent is marked ROLLED_BACK. Returns the number of recovered intents.
     */
    public int recover() {
        List<String[]> pending = query(
                "SELECT id, payload_json FROM write_intents WHERE state='PENDING' ORDER BY seq",
                rs -> new String[]{rs.getString(1), rs.getString(2)});
        int recovered = 0;
        for (String[] row : pending) {
            String intentId = row[0];
            com.fasterxml.jackson.databind.JsonNode payload = Json.parse(row[1]);
            txRun(db -> {
                for (com.fasterxml.jackson.databind.JsonNode written : payload.withArray("writtenRows")) {
                    String table = written.get("table").asText();
                    String rowId = written.get("id").asText();
                    if (!table.matches("[a-z_]+")) throw new IllegalStateException("bad table " + table);
                    db.execute("DELETE FROM " + table + " WHERE id=?", rowId);
                }
                db.execute("UPDATE write_intents SET state='ROLLED_BACK' WHERE id=?", intentId);
            });
            recovered++;
        }
        return recovered;
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
