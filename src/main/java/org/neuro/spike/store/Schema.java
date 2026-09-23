package org.neuro.spike.store;

/** Physical separation of immutable materials, versions, labels, adjudications and derived outputs. */
public final class Schema {
    private Schema() {
    }

    public static final String[] DDL = {
            """
            CREATE TABLE IF NOT EXISTS material (
              uid TEXT PRIMARY KEY,
              type TEXT NOT NULL,
              payload_json TEXT NOT NULL,
              unit TEXT,
              frame TEXT,
              diagnostics_json TEXT NOT NULL DEFAULT '[]',
              seq INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS feature_version (
              uid TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              revision INTEGER NOT NULL,
              spec_json TEXT NOT NULL,
              seq INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS feature_point (
              uid TEXT PRIMARY KEY,
              feature_version TEXT NOT NULL,
              event_uid TEXT NOT NULL,
              x REAL NOT NULL,
              y REAL NOT NULL,
              seq INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS cluster (
              uid TEXT PRIMARY KEY,
              label INTEGER NOT NULL,
              param_version TEXT NOT NULL,
              feature_version TEXT NOT NULL,
              t_start REAL,
              t_end REAL,
              members_json TEXT NOT NULL,
              batch TEXT NOT NULL,
              seq INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS cluster_lineage (
              uid TEXT PRIMARY KEY,
              relation TEXT NOT NULL,
              parents_json TEXT NOT NULL,
              children_json TEXT NOT NULL,
              shared_events_json TEXT NOT NULL DEFAULT '[]',
              rationale TEXT,
              seq INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS proposal (
              uid TEXT PRIMARY KEY,
              kind TEXT NOT NULL,
              cluster_uids_json TEXT NOT NULL,
              candidates_json TEXT NOT NULL,
              detail_json TEXT NOT NULL,
              status TEXT NOT NULL,
              seq INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS decision (
              uid TEXT PRIMARY KEY,
              action TEXT NOT NULL,
              target_proposal TEXT,
              subject_cluster TEXT,
              payload_json TEXT NOT NULL,
              decision_version TEXT NOT NULL,
              intent_id TEXT,
              seq INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS evidence (
              uid TEXT PRIMARY KEY,
              kind TEXT NOT NULL,
              cluster_uid TEXT NOT NULL,
              detail_json TEXT NOT NULL,
              strength REAL NOT NULL,
              seq INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS event_log (
              uid TEXT PRIMARY KEY,
              type TEXT NOT NULL,
              subject_uid TEXT,
              payload_json TEXT NOT NULL,
              basis TEXT,
              derivation_id TEXT,
              seq INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS derived_result (
              uid TEXT PRIMARY KEY,
              kind TEXT NOT NULL,
              target_uid TEXT NOT NULL,
              input_digest TEXT NOT NULL,
              param_version TEXT NOT NULL,
              feature_version TEXT,
              parents_json TEXT NOT NULL DEFAULT '[]',
              status TEXT NOT NULL,
              output_json TEXT,
              diagnostics_json TEXT NOT NULL DEFAULT '[]',
              budget_limit INTEGER NOT NULL,
              budget_used INTEGER NOT NULL,
              t_start INTEGER,
              t_end INTEGER,
              window_key TEXT,
              seq INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS write_intent (
              intent_id TEXT PRIMARY KEY,
              started_seq INTEGER NOT NULL,
              committed INTEGER NOT NULL DEFAULT 0,
              detail TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS seq_counter (
              id INTEGER PRIMARY KEY CHECK (id = 1),
              next_seq INTEGER NOT NULL
            )
            """,
            "INSERT OR IGNORE INTO seq_counter (id, next_seq) VALUES (1, 1)"
    };
}
