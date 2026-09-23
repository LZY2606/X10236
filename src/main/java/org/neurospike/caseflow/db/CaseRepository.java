package org.neurospike.caseflow.db;

import org.neurospike.caseflow.domain.DomainRecords.AdjudicationEvent;
import org.neurospike.caseflow.domain.DomainRecords.CaseEvent;
import org.neurospike.caseflow.domain.DomainRecords.DecisionRecord;
import org.neurospike.caseflow.domain.DomainRecords.RuleVersion;
import org.neurospike.caseflow.domain.DomainRecords.SourceRevision;
import org.neurospike.caseflow.domain.DomainRecords.WindowComputation;
import org.neurospike.caseflow.util.JsonCanonical;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class CaseRepository {
    private final Path databasePath;

    public CaseRepository(Path databasePath) {
        this.databasePath = databasePath;
    }

    public Path databasePath() {
        return databasePath;
    }

    public void initialize() {
        try {
            Files.createDirectories(databasePath.toAbsolutePath().getParent());
        } catch (Exception exception) {
            throw new IllegalStateException("无法创建数据库目录", exception);
        }
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            for (String ddl : Schema.DDL) {
                statement.executeUpdate(ddl);
            }
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("无法初始化数据库", exception);
        }
    }

    public Connection connection() {
        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException exception) {
            throw new IllegalStateException("无法连接 SQLite", exception);
        }
    }

    public boolean isEmpty() {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select count(*) from recording_context")) {
            return !result.next() || result.getInt(1) == 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("无法检查数据库", exception);
        }
    }

    public void beginStaging(String stageKey, String payload) {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert or replace into import_staging (stage_key, payload) values (?, ?)")) {
            statement.setString(1, stageKey);
            statement.setString(2, payload);
            statement.executeUpdate();
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("写入导入暂存失败", exception);
        }
    }

    public boolean hasStaging() {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select count(*) from import_staging")) {
            return result.next() && result.getInt(1) > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("检查导入暂存失败", exception);
        }
    }

    public void clearStaging() {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("delete from import_staging");
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("清理导入暂存失败", exception);
        }
    }

    public long nextEventSeq() {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select coalesce(max(seq), 0) + 1 from case_events")) {
            return result.next() ? result.getLong(1) : 1;
        } catch (SQLException exception) {
            throw new IllegalStateException("读取事件序号失败", exception);
        }
    }

    public void appendRuleVersion(RuleVersion rule) {
        try (Connection connection = connection()) {
            SnapshotStore.insertRuleVersion(connection, rule);
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("追加规则版本失败", exception);
        }
    }

    public void appendSourceRevision(SourceRevision revision) {
        try (Connection connection = connection()) {
            SnapshotStore.insertSourceRevision(connection, revision);
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("追加源修订失败", exception);
        }
    }

    public void appendAdjudication(AdjudicationEvent adjudication) {
        try (Connection connection = connection()) {
            SnapshotStore.insertAdjudication(connection, adjudication,
                    SnapshotStore.nextOrder(connection, "adjudications"));
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("追加裁定失败", exception);
        }
    }

    public void appendDecision(DecisionRecord decision) {
        try (Connection connection = connection()) {
            SnapshotStore.insertDecision(connection, decision);
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("追加决定失败", exception);
        }
    }

    public void appendComputation(WindowComputation computation) {
        try (Connection connection = connection()) {
            SnapshotStore.insertComputation(connection, computation,
                    SnapshotStore.nextOrder(connection, "computations"));
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("追加计算结果失败", exception);
        }
    }

    public void appendCaseEvent(CaseEvent event) {
        try (Connection connection = connection()) {
            SnapshotStore.insertCaseEvent(connection, event);
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("追加案例事件失败", exception);
        }
    }

    public void setComputationAdopted(String computationId, boolean adopted) {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "update computations set adopted = ? where computation_id = ?")) {
            statement.setInt(1, adopted ? 1 : 0);
            statement.setString(2, computationId);
            statement.executeUpdate();
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("更新采纳状态失败", exception);
        }
    }

    public String exportJson() {
        try {
            return JsonCanonical.MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(new SnapshotStore(this).snapshot());
        } catch (Exception exception) {
            throw new IllegalStateException("导出失败", exception);
        }
    }
}
