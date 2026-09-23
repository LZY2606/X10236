package spikeline.store;

/**
 * SQLite schema. Layer separation is visible in the table layout:
 *
 *  immutable materials: recording / probe_site / waveform_ref / detection_event /
 *                        threshold_rule / feature_version
 *  derived, append-only: run / cluster_label / unit_identity / assignment /
 *                        feature_row / evidence
 *  human decisions:      decision
 *  lineage + recovery:   log_event / branch_info / write_ahead
 */
public final class Schema {

    public static final int CURRENT_VERSION = 1;

    public static final String DDL = """
            PRAGMA journal_mode = WAL;

            CREATE TABLE IF NOT EXISTS meta (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS recording (
              recording_id    TEXT PRIMARY KEY,
              name            TEXT NOT NULL,
              time_basis      TEXT NOT NULL,
              start_raw       TEXT,
              start_value     REAL,
              start_unit      TEXT,
              start_basis     TEXT,
              start_diag      TEXT,
              rate_raw        TEXT,
              rate_value      REAL,
              rate_unit       TEXT,
              rate_basis      TEXT,
              rate_diag       TEXT,
              raw_json        TEXT NOT NULL,
              tx_id           TEXT,
              ingested_seq    INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS probe_site (
              site_id    TEXT PRIMARY KEY,
              channel    TEXT NOT NULL,
              x_raw TEXT, x_value REAL, x_unit TEXT, x_basis TEXT, x_diag TEXT,
              y_raw TEXT, y_value REAL, y_unit TEXT, y_basis TEXT, y_diag TEXT,
              z_raw TEXT, z_value REAL, z_unit TEXT, z_basis TEXT, z_diag TEXT,
              raw_json   TEXT NOT NULL,
              tx_id      TEXT,
              ingested_seq INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS waveform_ref (
              ref_id          TEXT PRIMARY KEY,
              channel         TEXT NOT NULL,
              uri             TEXT NOT NULL,
              peak_sample     INTEGER NOT NULL,
              peak_raw        TEXT,
              peak_value      REAL,
              peak_unit       TEXT,
              peak_basis      TEXT,
              peak_diag       TEXT,
              pre_samples     INTEGER NOT NULL,
              post_samples    INTEGER NOT NULL,
              rate_raw        TEXT,
              rate_value      REAL,
              rate_unit       TEXT,
              rate_basis      TEXT,
              rate_diag       TEXT,
              samples_json    TEXT NOT NULL,
              raw_json        TEXT NOT NULL,
              tx_id           TEXT,
              ingested_seq    INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS detection_event (
              event_id    TEXT PRIMARY KEY,
              channel     TEXT NOT NULL,
              ref_id      TEXT,
              sample_index INTEGER NOT NULL,
              time_raw    TEXT,
              time_value  REAL,
              time_unit   TEXT,
              time_basis  TEXT,
              time_diag   TEXT,
              amp_raw     TEXT,
              amp_value   REAL,
              amp_unit    TEXT,
              amp_basis   TEXT,
              amp_diag    TEXT,
              raw_json    TEXT NOT NULL,
              tx_id       TEXT,
              ingested_seq INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS threshold_rule (
              threshold_id TEXT PRIMARY KEY,
              channel      TEXT NOT NULL,
              thr_raw TEXT, thr_value REAL, thr_unit TEXT, thr_basis TEXT, thr_diag TEXT,
              method       TEXT NOT NULL,
              refr_raw TEXT, refr_value REAL, refr_unit TEXT, refr_basis TEXT, refr_diag TEXT,
              raw_json     TEXT NOT NULL,
              tx_id        TEXT,
              ingested_seq  INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS feature_version (
              feature_version TEXT PRIMARY KEY,
              algorithm       TEXT NOT NULL,
              params_json     TEXT NOT NULL,
              basis           TEXT NOT NULL,
              raw_json        TEXT NOT NULL,
              tx_id           TEXT,
              ingested_seq    INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS run (
              run_id           TEXT PRIMARY KEY,
              parent_run_id    TEXT,
              branch           TEXT NOT NULL,
              stage            TEXT NOT NULL,
              seq              INTEGER NOT NULL,
              feature_version  TEXT NOT NULL,
              win_start_raw TEXT, win_start_value REAL, win_start_unit TEXT, win_start_basis TEXT, win_start_diag TEXT,
              win_end_raw   TEXT, win_end_value   REAL, win_end_unit   TEXT, win_end_basis   TEXT, win_end_diag TEXT,
              endpoint_inclusive INTEGER NOT NULL,
              rule_version     TEXT NOT NULL,
              source_digest    TEXT NOT NULL,
              basis            TEXT NOT NULL,
              tx_id            TEXT,
              as_of_seq        INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS cluster_label (
              run_id         TEXT NOT NULL,
              event_id       TEXT NOT NULL,
              cluster_number INTEGER NOT NULL,
              raw_label      TEXT NOT NULL,
              raw_json       TEXT NOT NULL,
              tx_id          TEXT,
              as_of_seq      INTEGER NOT NULL,
              PRIMARY KEY (run_id, event_id)
            );

            CREATE TABLE IF NOT EXISTS unit_identity (
              unit_id              TEXT PRIMARY KEY,
              first_run_id         TEXT NOT NULL,
              first_cluster_number INTEGER NOT NULL,
              stable_order         INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS assignment (
              assignment_id       TEXT PRIMARY KEY,
              run_id              TEXT NOT NULL,
              branch              TEXT NOT NULL,
              event_id            TEXT NOT NULL,
              unit_id             TEXT NOT NULL,
              cluster_number      INTEGER NOT NULL,
              shared_after_split  INTEGER NOT NULL,
              input_digest        TEXT NOT NULL,
              parents_json        TEXT NOT NULL,
              rule_version        TEXT NOT NULL,
              as_of_seq           INTEGER NOT NULL,
              tx_id               TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_assignment_run ON assignment(run_id);
            CREATE INDEX IF NOT EXISTS idx_assignment_unit ON assignment(unit_id);
            CREATE INDEX IF NOT EXISTS idx_assignment_tx ON assignment(tx_id);

            CREATE TABLE IF NOT EXISTS feature_row (
              feature_id      TEXT PRIMARY KEY,
              run_id          TEXT NOT NULL,
              event_id        TEXT NOT NULL,
              feature_version TEXT NOT NULL,
              peak_uv         REAL,
              half_width_ms   REAL,
              pc1             REAL,
              raw_digest      TEXT NOT NULL,
              input_digest    TEXT NOT NULL,
              parents_json    TEXT NOT NULL,
              rule_version    TEXT NOT NULL,
              as_of_seq       INTEGER NOT NULL,
              tx_id           TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_feature_run ON feature_row(run_id);
            CREATE INDEX IF NOT EXISTS idx_feature_tx ON feature_row(tx_id);

            CREATE TABLE IF NOT EXISTS evidence (
              evidence_id   TEXT PRIMARY KEY,
              run_id        TEXT NOT NULL,
              branch        TEXT NOT NULL,
              unit_id       TEXT,
              kind          TEXT NOT NULL,
              severity      TEXT NOT NULL,
              detail_json   TEXT NOT NULL,
              win_start_raw TEXT, win_start_value REAL, win_start_unit TEXT, win_start_basis TEXT, win_start_diag TEXT,
              win_end_raw   TEXT, win_end_value REAL, win_end_unit TEXT, win_end_basis TEXT, win_end_diag TEXT,
              input_digest  TEXT NOT NULL,
              parents_json  TEXT NOT NULL,
              rule_version  TEXT NOT NULL,
              as_of_seq     INTEGER NOT NULL,
              tx_id         TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_evidence_run ON evidence(run_id);
            CREATE INDEX IF NOT EXISTS idx_evidence_unit ON evidence(unit_id);
            CREATE INDEX IF NOT EXISTS idx_evidence_tx ON evidence(tx_id);

            CREATE TABLE IF NOT EXISTS decision (
              decision_id     TEXT PRIMARY KEY,
              seq             INTEGER NOT NULL,
              branch          TEXT NOT NULL,
              stage           TEXT NOT NULL,
              unit_id         TEXT,
              run_id          TEXT,
              cluster_number  INTEGER,
              action          TEXT NOT NULL,
              status          TEXT NOT NULL,
              rationale       TEXT,
              conflicts_with  TEXT,
              parent_event_id TEXT,
              payload_json    TEXT,
              input_digest    TEXT NOT NULL,
              parents_json    TEXT NOT NULL,
              rule_version    TEXT NOT NULL,
              tx_id           TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_decision_seq ON decision(seq);
            CREATE INDEX IF NOT EXISTS idx_decision_unit ON decision(unit_id);

            CREATE TABLE IF NOT EXISTS log_event (
              seq             INTEGER PRIMARY KEY,
              branch          TEXT NOT NULL,
              stage           TEXT NOT NULL,
              type            TEXT NOT NULL,
              payload_json    TEXT NOT NULL,
              input_digest    TEXT NOT NULL,
              parent_event_ids TEXT NOT NULL,
              rule_version    TEXT NOT NULL,
              tx_id           TEXT
            );

            CREATE TABLE IF NOT EXISTS branch_info (
              name              TEXT PRIMARY KEY,
              parent_branch     TEXT,
              forked_from_seq   INTEGER NOT NULL
            );

            -- Write-ahead log for staged material ingestion. BEGIN rows stay
            -- committed until COMMIT; a BEGIN without COMMIT after reopen means
            -- a half-written transaction that must be rolled back.
            CREATE TABLE IF NOT EXISTS write_ahead (
              tx_id        TEXT PRIMARY KEY,
              kind         TEXT NOT NULL,
              started_seq  INTEGER NOT NULL,
              begun_at     INTEGER NOT NULL,
              detail_json  TEXT NOT NULL
            );
            """;

    private Schema() {
    }
}
