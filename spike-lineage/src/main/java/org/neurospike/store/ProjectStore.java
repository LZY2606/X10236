package org.neurospike.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.neurospike.model.Material;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;

/**
 * 项目库外观：统一管理连接、三层 DAO，以及“操作日志 + 提交标记”。
 * 半写恢复：崩溃后未在 tx_commit 出现的 txid，其全部 op_journal 记录回滚，
 * 使数据库回到上一个一致状态。业务层不直接管理提交。
 */
public final class ProjectStore implements AutoCloseable {
    private final Connection connection;
    private final ObjectMapper json;
    private final StoreMaterial material;
    private final StoreLabels labels;
    private final StoreDecision decision;
    private final StoreDerived derived;

    public ProjectStore(String path) {
        Database db = new Database(path);
        db.migrate();
        this.connection = db.connect();
        this.json = new ObjectMapper();
        this.material = new StoreMaterial(connection, json);
        this.labels = new StoreLabels(connection);
        this.decision = new StoreDecision(connection);
        this.derived = new StoreDerived(connection);
    }

    public ObjectMapper json() { return json; }
    public StoreMaterial material() { return material; }
    public StoreLabels labels() { return labels; }
    public StoreDecision decision() { return decision; }
    public StoreDerived derived() { return derived; }
    public Connection raw() { return connection; }

    /** 恢复未提交事务（半写恢复），返回回滚的操作数。 */
    public int recoverPendingTransactions() {
        try {
            List<String> pending;
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT DISTINCT txid FROM op_journal
                    WHERE txid NOT IN (SELECT txid FROM tx_commit) ORDER BY txid
                    """);
                 ResultSet rs = ps.executeQuery()) {
                pending = new java.util.ArrayList<>();
                while (rs.next()) pending.add(rs.getString(1));
            }
            int rolled = 0;
            if (!pending.isEmpty()) {
                boolean prev = connection.getAutoCommit();
                connection.setAutoCommit(false);
                for (String txid : pending) {
                    try (PreparedStatement del = connection.prepareStatement(
                            "DELETE FROM op_journal WHERE txid=?")) {
                        del.setString(1, txid);
                        rolled += del.executeUpdate();
                    }
                }
                connection.commit();
                connection.setAutoCommit(prev);
            }
            return rolled;
        } catch (SQLException e) {
            throw new RuntimeException("recovery failed", e);
        }
    }

    /**
     * 在一个带日志的事务中执行。每个写操作先追加 journal，再执行实际写入；
     * 正常结束写 tx_commit。opAppender 允许业务层记录可重放操作。
     */
    public void transaction(String txid, Consumer<OpJournal> body) {
        try {
            boolean prev = connection.getAutoCommit();
            connection.setAutoCommit(false);
            OpJournal journal = new OpJournal(connection, txid);
            try {
                body.accept(journal);
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR IGNORE INTO tx_commit(txid,finished_at) VALUES(?,?)")) {
                    ps.setString(1, txid);
                    ps.setString(2, java.time.Instant.now().toString());
                    ps.executeUpdate();
                }
                connection.commit();
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(prev);
            }
        } catch (SQLException e) {
            throw new RuntimeException("transaction failed: " + txid, e);
        }
    }

    public static final class OpJournal {
        private final Connection c;
        private final String txid;
        private int seq = 0;

        OpJournal(Connection c, String txid) {
            this.c = c; this.txid = txid;
        }

        /** 追加一条机器可读取的重放操作（CRD：create/材料、决定、派生等）。 */
        public void record(String op, String payload) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO op_journal(txid,seq,op,payload) VALUES(?,?,?,?)")) {
                ps.setString(1, txid); ps.setInt(2, seq++); ps.setString(3, op);
                ps.setString(4, payload); ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override public void close() {
        try { connection.close(); } catch (SQLException ignored) {}
    }
}
