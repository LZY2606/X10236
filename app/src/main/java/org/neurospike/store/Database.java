package org.neurospike.store;

import org.neurospike.domain.DerivedResult;
import org.neurospike.domain.LineageLink;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class Database implements AutoCloseable {
    private final String url;
    private final Connection connection;

    public Database(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            this.url = path.equals(":memory:") ? "jdbc:sqlite::memory:" : "jdbc:sqlite:" + path;
            this.connection = DriverManager.getConnection(url);
            this.connection.createStatement().execute("PRAGMA foreign_keys=ON");
        } catch (ClassNotFoundException | SQLException e) {
            throw new StorageException("cannot open database: " + path, e);
        }
    }

    public static Database memory() {
        Database database = new Database(":memory:");
        database.migrate();
        return database;
    }

    public Connection connection() {
        return connection;
    }

    public void migrate() {
        execute("""
                create table if not exists schema_meta(key text primary key, value text not null)
                """);
        execute("""
                create table if not exists material(
                  id text primary key,
                  kind text not null check(kind in ('waveform_reference','probe_geometry','detection_threshold','feature_version','cluster_label','adjudication_event')),
                  version text not null,
                  supersedes_id text references material(id),
                  direct_source_id text references material(id),
                  raw_representation text not null,
                  normalized_json text not null,
                  coordinate_basis text,
                  unit text,
                  status text not null,
                  diagnostic text,
                  created_at integer not null,
                  ordinal integer not null unique)
                """);
        execute("""
                create table if not exists spike_detection(
                  id text not null,
                  version text not null,
                  waveform_id text not null references material(id),
                  threshold_id text not null references material(id),
                  channel_id text not null,
                  time_ms integer not null,
                  amplitude_uv real,
                  raw_representation text not null,
                  status text not null,
                  diagnostic text,
                  created_at integer not null,
                  ordinal integer not null unique,
                  primary key(id, version))
                """);
        execute("""
                create table if not exists feature_observation(
                  id text not null,
                  version text not null,
                  spike_id text not null,
                  spike_version text not null,
                  feature_version_id text not null references material(id),
                  x real,
                  y real,
                  coordinate_basis text,
                  unit text,
                  raw_representation text not null,
                  status text not null,
                  diagnostic text,
                  ordinal integer not null unique,
                  primary key(id, version),
                  foreign key(spike_id, spike_version) references spike_detection(id, version))
                """);
        execute("""
                create table if not exists analysis_window(
                  id text primary key,
                  start_ms integer not null,
                  end_ms integer not null,
                  start_rule text not null,
                  end_rule text not null,
                  time_basis text not null,
                  status text not null,
                  version text not null,
                  ordinal integer not null unique)
                """);
        execute("""
                create table if not exists derived_result(
                  id text primary key,
                  kind text not null,
                  stable_subject_id text not null,
                  input_digest text not null,
                  parameter_version text not null,
                  parent_dependencies_json text not null,
                  window_id text references analysis_window(id),
                  status text not null,
                  validity_start_ms integer,
                  validity_end_ms integer,
                  invalid_reason text,
                  payload_json text not null,
                  sequence integer not null unique,
                  ordinal integer not null unique)
                """);
        execute("""
                create table if not exists evidence_event(
                  sequence integer primary key,
                  id text not null unique,
                  at_ms integer not null,
                  type text not null,
                  severity text not null,
                  subject_stable_id text not null,
                  rule_version text not null,
                  basis text not null,
                  payload_json text not null,
                  accepted_result_id text references derived_result(id))
                """);
        execute("""
                create table if not exists decision_record(
                  id text primary key,
                  revision_id text not null references material(id),
                  scope_id text not null,
                  action text not null,
                  rationale text not null,
                  author text not null,
                  at_ms integer not null,
                  status text not null,
                  conflicts_with_id text references decision_record(id),
                  payload_json text not null,
                  ordinal integer not null unique)
                """);
        execute("""
                create table if not exists lineage_link(
                  parent_id text not null,
                  parent_kind text not null,
                  child_id text not null,
                  child_kind text not null,
                  relation text not null,
                  ordinal integer not null unique,
                  primary key(parent_id, parent_kind, child_id, child_kind, relation))
                """);
        execute("""
                create table if not exists case_run(
                  id text primary key,
                  stage text not null,
                  event_type text not null,
                  at_ms integer not null,
                  status text not null,
                  summary text not null,
                  ordinal integer not null unique)
                """);
        execute("""
                create table if not exists import_journal(
                  journal_id text primary key,
                  started_at integer not null,
                  finished_at integer,
                  status text not null,
                  detail text not null,
                  expected_records integer not null,
                  applied_records integer not null)
                """);
    }

    public void execute(String sql) {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new StorageException("SQL failed: " + sql, e);
        }
    }

    public int nextOrdinal(String table) {
        String sql = "select coalesce(max(ordinal) + 1, 0) from " + table;
        try (var statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new StorageException("cannot read ordinal from " + table, e);
        }
    }

    public long nextEvidenceSequence() {
        try (var statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select coalesce(max(sequence) + 1, 1) from evidence_event")) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new StorageException("cannot read evidence sequence", e);
        }
    }

    public void inTransaction(Transaction transaction) {
        try {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                transaction.run();
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException e) {
            throw new StorageException("transaction failure", e);
        }
    }

    public List<LineageLink> linksByChild(String childId, String childKind, String relation) {
        String sql = "select parent_id,parent_kind,child_id,child_kind,relation from lineage_link where child_id=? and child_kind=? and relation=? order by ordinal";
        List<LineageLink> links = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, childId);
            ps.setString(2, childKind);
            ps.setString(3, relation);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    links.add(new LineageLink(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
                }
            }
        } catch (SQLException e) {
            throw new StorageException("cannot read lineage links", e);
        }
        return links;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new StorageException("cannot close database", e);
        }
    }

    @FunctionalInterface
    public interface Transaction {
        void run() throws Exception;
    }
}
