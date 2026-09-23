package org.neurospike.store;

import java.util.List;

public final class Schema {
    private Schema() {
    }

    public static final int VERSION = 1;

    public static final List<String> DDL = List.of(
            """
            CREATE TABLE IF NOT EXISTS meta(
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS waveform_ref(
              ref_id TEXT PRIMARY KEY,
              uri TEXT NOT NULL,
              checksum TEXT NOT NULL,
              format TEXT NOT NULL,
              time_origin TEXT NOT NULL,
              time_unit TEXT NOT NULL,
              sample_rate_hz REAL NOT NULL,
              sample_count INTEGER NOT NULL,
              sample_index INTEGER NOT NULL,
              sample_length INTEGER NOT NULL,
              channel_gains_json TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS site(
              ref_id TEXT NOT NULL,
              channel INTEGER NOT NULL,
              x_raw TEXT, y_raw TEXT, z_raw TEXT,
              x_value REAL, y_value REAL, z_value REAL,
              unit TEXT NOT NULL,
              frame TEXT NOT NULL,
              convertible INTEGER NOT NULL,
              diagnostic TEXT,
              PRIMARY KEY(ref_id, channel)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS threshold(
              threshold_id TEXT PRIMARY KEY,
              channel INTEGER NOT NULL,
              raw TEXT,
              value REAL,
              unit TEXT NOT NULL,
              reference TEXT,
              rule TEXT NOT NULL,
              feature_version TEXT NOT NULL,
              convertible INTEGER NOT NULL,
              diagnostic TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS spike(
              event_id INTEGER PRIMARY KEY,
              time_raw TEXT,
              time_value REAL,
              time_unit TEXT,
              time_origin TEXT,
              time_convertible INTEGER NOT NULL,
              primary_channel INTEGER NOT NULL,
              waveform_ref_id TEXT NOT NULL REFERENCES waveform_ref(ref_id),
              sample_index INTEGER NOT NULL,
              sample_length INTEGER NOT NULL,
              channels_json TEXT NOT NULL,
              measurements_json TEXT NOT NULL,
              time_diagnostic TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS feature_set(
              feature_id TEXT PRIMARY KEY,
              event_id INTEGER NOT NULL REFERENCES spike(event_id),
              version TEXT NOT NULL,
              basis TEXT NOT NULL,
              values_json TEXT NOT NULL,
              diagnostic TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS cluster_version(
              cluster_id TEXT PRIMARY KEY,
              version TEXT NOT NULL,
              seq INTEGER NOT NULL,
              feature_version TEXT NOT NULL,
              threshold_id TEXT,
              window_start TEXT,
              window_end TEXT,
              params_json TEXT NOT NULL,
              input_digest TEXT NOT NULL,
              parents_json TEXT NOT NULL,
              created_at TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS unit(
              unit_id TEXT PRIMARY KEY,
              first_seen_cluster TEXT NOT NULL,
              first_label INTEGER NOT NULL,
              status TEXT NOT NULL,
              note TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS assignment(
              cluster_id TEXT NOT NULL REFERENCES cluster_version(cluster_id),
              event_id INTEGER NOT NULL REFERENCES spike(event_id),
              unit_id TEXT NOT NULL REFERENCES unit(unit_id),
              label INTEGER NOT NULL,
              score REAL NOT NULL,
              PRIMARY KEY(cluster_id, event_id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS lineage_edge(
              edge_id TEXT PRIMARY KEY,
              from_id TEXT NOT NULL,
              to_id TEXT NOT NULL,
              relation TEXT NOT NULL,
              shared_events INTEGER NOT NULL,
              via_decision_id TEXT,
              cluster_id TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS decision_event(
              decision_id TEXT PRIMARY KEY,
              seq INTEGER NOT NULL,
              type TEXT NOT NULL,
              actor TEXT NOT NULL,
              at_event TEXT,
              payload_json TEXT NOT NULL,
              basis_rule_id TEXT,
              parent_cluster_id TEXT,
              adopted TEXT NOT NULL,
              created_at TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS derived_result(
              result_id TEXT PRIMARY KEY,
              kind TEXT NOT NULL,
              scope TEXT NOT NULL,
              cluster_id TEXT,
              unit_id TEXT,
              window_start TEXT,
              window_end TEXT,
              input_digest TEXT NOT NULL,
              param_version TEXT NOT NULL,
              parents_json TEXT NOT NULL,
              content_json TEXT NOT NULL,
              created_at TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS diagnostic_record(
              diag_id INTEGER PRIMARY KEY AUTOINCREMENT,
              stage TEXT NOT NULL,
              code TEXT NOT NULL,
              file TEXT,
              line INTEGER,
              column INTEGER,
              message TEXT NOT NULL,
              raw_value TEXT,
              context_json TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS replay_step(
              step_id INTEGER PRIMARY KEY AUTOINCREMENT,
              seq INTEGER NOT NULL,
              stage TEXT NOT NULL,
              scenario TEXT NOT NULL,
              input_json TEXT NOT NULL,
              decision_state TEXT NOT NULL,
              affected_json TEXT NOT NULL,
              result_id TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS archive_check(
              check_id TEXT PRIMARY KEY,
              scenario TEXT NOT NULL,
              stable_identity TEXT NOT NULL,
              claim TEXT NOT NULL,
              verdict TEXT NOT NULL,
              basis TEXT NOT NULL,
              evidence_json TEXT NOT NULL,
              adopted_result_id TEXT,
              rejected_result_id TEXT,
              created_at TEXT NOT NULL
            )
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_assignment_unit ON assignment(unit_id)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_assignment_cluster ON assignment(cluster_id)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_feature_event ON feature_set(event_id)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_derived_cluster ON derived_result(cluster_id)
            """);
}
