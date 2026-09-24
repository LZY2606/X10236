package ephys;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** SQLite storage. Immutable materials, imported spikes and the append-only
 *  event log are stored separately; all derived state is obtained by replay. */
public final class Db implements AutoCloseable {
    public final Connection conn;

    private Db(Connection conn) {
        this.conn = conn;
    }

    public static Db open(Path file) {
        try {
            if (file.getParent() != null) file.getParent().toFile().mkdirs();
            return init(DriverManager.getConnection("jdbc:sqlite:" + file));
        } catch (Exception e) {
            throw new IllegalStateException("cannot open db " + file, e);
        }
    }

    public static Db inMemory() {
        try {
            return init(DriverManager.getConnection("jdbc:sqlite::memory:"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Db init(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA synchronous=NORMAL");
        }
        Db db = new Db(c);
        db.createSchema();
        db.recover();
        return db;
    }

    private void createSchema() {
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS spikes("
                + "id TEXT PRIMARY KEY, channel TEXT NOT NULL, t_ms REAL, t_raw TEXT NOT NULL,"
                + "amp_uv REAL, amp_raw TEXT NOT NULL, wave_ref TEXT, ord INTEGER NOT NULL,"
                + "imported_seq INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS materials("
                + "kind TEXT NOT NULL, id TEXT NOT NULL, version TEXT NOT NULL,"
                + "payload TEXT NOT NULL, hash TEXT NOT NULL, seq INTEGER NOT NULL,"
                + "PRIMARY KEY(kind, id, version))",
            "CREATE TABLE IF NOT EXISTS events("
                + "seq INTEGER PRIMARY KEY, kind TEXT NOT NULL, status TEXT NOT NULL,"
                + "payload TEXT NOT NULL, input_hash TEXT NOT NULL, param_version TEXT,"
                + "parents TEXT NOT NULL, created TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS import_guard("
                + "id INTEGER PRIMARY KEY, started TEXT NOT NULL, status TEXT NOT NULL)"
        };
        try (Statement s = conn.createStatement()) {
            for (String d : ddl) s.execute(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Half-write recovery: drop events whose declared parent seqs are missing
     *  (a crash between writes can only leave a dangling tail), and clear stale
     *  import guards. Runs inside one transaction. */
    public void recover() {
        txRun(() -> {
            exec("DELETE FROM import_guard WHERE status='running'");
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT seq, parents FROM events ORDER BY seq")) {
                List<Long> seqs = new ArrayList<>();
                List<String> payloads = new ArrayList<>();
                while (rs.next()) {
                    seqs.add(rs.getLong(1));
                    payloads.add(rs.getString(2));
                }
                java.util.Set<Long> have = new java.util.HashSet<>();
                List<Long> dangling = new ArrayList<>();
                for (int i = 0; i < seqs.size(); i++) {
                    long seq = seqs.get(i);
                    boolean ok = true;
                    for (com.fasterxml.jackson.databind.JsonNode p : Json.parse(payloads.get(i))) {
                        long ps = p.asLong();
                        if (ps != 0 && !have.contains(ps) && ps != seq) ok = false;
                    }
                    if (ok) have.add(seq); else dangling.add(seq);
                }
                for (long d : dangling) {
                    exec("DELETE FROM events WHERE seq=" + d);
                    exec("DELETE FROM spikes WHERE imported_seq=" + d);
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    public void exec(String sql) {
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException(sql, e);
        }
    }

    public void txRun(Runnable r) {
        tx(() -> {
            r.run();
            return null;
        });
    }

    public <T> T tx(Supplier<T> r) {
        try {
            conn.setAutoCommit(false);
            try {
                T v = r.get();
                conn.commit();
                return v;
            } catch (RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e);
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (Exception ignore) {
                // connection reset
            }
        }
    }

    public long nextSeq() {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(seq),0)+1 FROM events")) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public long headSeq() {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(seq),0) FROM events")) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public PreparedStatement prepare(String sql) {
        try {
            return conn.prepareStatement(sql);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (Exception ignore) {
            // already closed
        }
    }
}
