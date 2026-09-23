package org.neurospike.caseflow.db;

import org.neurospike.caseflow.domain.DomainRecords.AdjudicationEvent;
import org.neurospike.caseflow.domain.DomainRecords.CaseEvent;
import org.neurospike.caseflow.domain.DomainRecords.DecisionRecord;
import org.neurospike.caseflow.domain.DomainRecords.RuleVersion;
import org.neurospike.caseflow.domain.DomainRecords.SourceRevision;
import org.neurospike.caseflow.domain.DomainRecords.WindowComputation;

import java.sql.Connection;
import java.sql.SQLException;

public class SnapshotStore {
    private final CaseRepository repository;

    public SnapshotStore(CaseRepository repository) {
        this.repository = repository;
    }

    public CaseSnapshot snapshot() {
        try (Connection connection = repository.connection()) {
            CaseSnapshot snapshot = SnapshotLoader.load(connection);
            connection.commit();
            return snapshot;
        } catch (SQLException exception) {
            throw new IllegalStateException("读取案例快照失败", exception);
        }
    }

    public void replaceSnapshot(CaseSnapshot snapshot) {
        try (Connection connection = repository.connection()) {
            SnapshotWriter.replaceAll(connection, snapshot);
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("替换案例快照失败: " + exception.getMessage(), exception);
        }
    }

    static int nextOrder(Connection connection, String table) throws SQLException {
        return SnapshotWriter.nextOrder(connection, table);
    }

    static void insertRuleVersion(Connection connection, RuleVersion rule) throws SQLException {
        SnapshotWriter.insertRuleVersion(connection, rule);
    }

    static void insertSourceRevision(Connection connection, SourceRevision revision) throws SQLException {
        SnapshotWriter.insertSourceRevision(connection, revision);
    }

    static void insertAdjudication(Connection connection, AdjudicationEvent adjudication, int order)
            throws SQLException {
        SnapshotWriter.insertAdjudication(connection, adjudication, order);
    }

    static void insertDecision(Connection connection, DecisionRecord decision) throws SQLException {
        SnapshotWriter.insertDecision(connection, decision);
    }

    static void insertComputation(Connection connection, WindowComputation computation, int order)
            throws SQLException {
        SnapshotWriter.insertComputation(connection, computation, order);
    }

    static void insertCaseEvent(Connection connection, CaseEvent event) throws SQLException {
        SnapshotWriter.insertCaseEvent(connection, event);
    }
}
