package eph.archive;

/**
 * SQLite DDL.
 *
 * Layering:
 * <ul>
 *   <li><b>immutable material</b>: waveform references, probe geometry/sites, detection runs &
 *       thresholds, detected events, feature versions, label batches/assignments,</li>
 *   <li><b>derived layer</b>: units (stable identity), memberships, recomputed results with
 *       input summaries / parameter versions / parent dependencies,</li>
 *   <li><b>decision layer</b>: adjudication events (append-only; accepted and rejected rows
 *       coexist),</li>
 *   <li><b>evidence/journal</b>: machine-readable evidence events + diagnostics.</li>
 * </ul>
 * Cluster numbers are never used as identity: they are plain columns on label assignments.
 */
public final class Schema {

    private Schema() {
    }

    public static final String DDL = """
            PRAGMA journal_mode=WAL;

            CREATE TABLE IF NOT EXISTS project (
                project_id      TEXT PRIMARY KEY,
                title           TEXT NOT NULL,
                case_pack_id    TEXT NOT NULL,
                created_at_utc  TEXT NOT NULL,
                counter         INTEGER NOT NULL DEFAULT 0
            );

            -- ===== immutable material layer =====
            CREATE TABLE IF NOT EXISTS recording (
                project_id      TEXT NOT NULL,
                recording_id    TEXT NOT NULL,
                start_wall_clock_utc TEXT NOT NULL,
                sample_rate_raw TEXT NOT NULL,
                duration_raw    TEXT NOT NULL,
                sample_rate_hz  REAL,
                duration_s      REAL,
                PRIMARY KEY (project_id, recording_id)
            );

            CREATE TABLE IF NOT EXISTS probe (
                project_id      TEXT NOT NULL,
                probe_id        TEXT NOT NULL,
                type            TEXT NOT NULL,
                coordinate_frame TEXT NOT NULL,
                PRIMARY KEY (project_id, probe_id)
            );

            CREATE TABLE IF NOT EXISTS probe_site (
                project_id      TEXT NOT NULL,
                site_id         TEXT NOT NULL,
                channel         INTEGER NOT NULL,
                x_raw TEXT NOT NULL, y_raw TEXT NOT NULL, z_raw TEXT,
                x_m REAL, y_m REAL, z_m REAL,
                unit_raw TEXT,
                PRIMARY KEY (project_id, site_id)
            );

            CREATE TABLE IF NOT EXISTS waveform_source (
                project_id      TEXT NOT NULL,
                waveform_id     TEXT NOT NULL,
                kind            TEXT NOT NULL,
                uri             TEXT NOT NULL,
                encoding        TEXT NOT NULL,
                amplitude_unit  TEXT NOT NULL,
                time_unit       TEXT NOT NULL,
                samples_per_snippet INTEGER NOT NULL,
                sample_rate_raw TEXT NOT NULL,
                PRIMARY KEY (project_id, waveform_id)
            );

            CREATE TABLE IF NOT EXISTS detection_run (
                project_id      TEXT NOT NULL,
                run_id          TEXT NOT NULL,
                parameter_version TEXT NOT NULL,
                PRIMARY KEY (project_id, run_id)
            );

            CREATE TABLE IF NOT EXISTS detection_threshold (
                project_id      TEXT NOT NULL,
                run_id          TEXT NOT NULL,
                channel         INTEGER NOT NULL,
                value_raw       TEXT NOT NULL,
                canonical_value REAL,
                basis           TEXT NOT NULL,
                PRIMARY KEY (project_id, run_id, channel)
            );

            CREATE TABLE IF NOT EXISTS spike (
                project_id      TEXT NOT NULL,
                spike_id        TEXT NOT NULL,
                channel         INTEGER NOT NULL,
                time_raw        TEXT NOT NULL,
                time_s          REAL,
                amplitude_raw_text TEXT NOT NULL,
                amplitude_v     REAL,
                snippet_json    TEXT NOT NULL,
                waveform_id     TEXT NOT NULL,
                paired_channel  INTEGER,
                note            TEXT,
                PRIMARY KEY (project_id, spike_id)
            );

            CREATE TABLE IF NOT EXISTS feature_version (
                project_id      TEXT NOT NULL,
                feature_version_id TEXT NOT NULL,
                name            TEXT NOT NULL,
                description     TEXT NOT NULL,
                parameters_json TEXT NOT NULL,
                PRIMARY KEY (project_id, feature_version_id)
            );

            CREATE TABLE IF NOT EXISTS label_batch (
                project_id      TEXT NOT NULL,
                batch_id        TEXT NOT NULL,
                feature_version_id TEXT NOT NULL,
                parameter_version TEXT NOT NULL,
                generated_at    TEXT NOT NULL,
                PRIMARY KEY (project_id, batch_id)
            );

            -- cluster_number is a DISPOSABLE label; unit_id is the stable identity.
            CREATE TABLE IF NOT EXISTS label_assignment (
                project_id      TEXT NOT NULL,
                batch_id        TEXT NOT NULL,
                spike_id        TEXT NOT NULL,
                cluster_number  TEXT NOT NULL,
                unit_id         TEXT NOT NULL,
                note            TEXT,
                PRIMARY KEY (project_id, batch_id, spike_id, unit_id)
            );

            -- ===== derived layer (stable identities, provenance) =====
            CREATE TABLE IF NOT EXISTS unit (
                project_id      TEXT NOT NULL,
                unit_id         TEXT NOT NULL,
                created_in_step INTEGER NOT NULL,
                parent_unit_id  TEXT,
                lineage_kind    TEXT NOT NULL,
                PRIMARY KEY (project_id, unit_id)
            );

            CREATE TABLE IF NOT EXISTS derived_result (
                project_id      TEXT NOT NULL,
                result_id       TEXT NOT NULL,
                unit_id         TEXT NOT NULL,
                kind            TEXT NOT NULL,
                batch_id        TEXT NOT NULL,
                step_seq        INTEGER NOT NULL,
                input_summary   TEXT NOT NULL,
                parameter_version TEXT NOT NULL,
                parent_dependency_json TEXT NOT NULL,
                window_start_s  REAL,
                window_end_s    REAL,
                status          TEXT NOT NULL,
                payload_json    TEXT,
                superseded_by   TEXT,
                created_at_order INTEGER NOT NULL,
                PRIMARY KEY (project_id, result_id)
            );

            -- ===== decision layer (append-only adjudication events) =====
            CREATE TABLE IF NOT EXISTS adjudication (
                project_id      TEXT NOT NULL,
                decision_id     TEXT NOT NULL,
                step_seq        INTEGER NOT NULL,
                at_time         TEXT NOT NULL,
                action          TEXT NOT NULL,
                unit_id         TEXT,
                spike_id        TEXT,
                rationale       TEXT NOT NULL,
                evidence_kind   TEXT,
                adopted         INTEGER NOT NULL,
                conflicts_with  TEXT,
                payload_hash    TEXT NOT NULL,
                seq             INTEGER NOT NULL,
                UNIQUE (project_id, seq)
            );

            -- ===== machine readable evidence / diagnostics =====
            CREATE TABLE IF NOT EXISTS evidence_event (
                project_id      TEXT NOT NULL,
                event_id        TEXT NOT NULL,
                step_seq        INTEGER NOT NULL,
                code            TEXT NOT NULL,
                unit_id         TEXT,
                spike_id        TEXT,
                payload_json    TEXT NOT NULL,
                judgment        TEXT NOT NULL,
                created_at_order INTEGER NOT NULL,
                PRIMARY KEY (project_id, event_id)
            );

            CREATE TABLE IF NOT EXISTS diagnostic (
                project_id      TEXT NOT NULL,
                diagnostic_id   TEXT NOT NULL,
                step_seq        INTEGER NOT NULL,
                severity        TEXT NOT NULL,
                code            TEXT NOT NULL,
                location        TEXT NOT NULL,
                message         TEXT NOT NULL,
                context_json    TEXT NOT NULL,
                PRIMARY KEY (project_id, diagnostic_id)
            );

            -- replay order / lineage of imported history operations
            CREATE TABLE IF NOT EXISTS replay_step (
                project_id      TEXT NOT NULL,
                step_seq        INTEGER NOT NULL,
                type            TEXT NOT NULL,
                description     TEXT NOT NULL,
                parameter_version TEXT,
                changed_spike_json TEXT,
                label_batch_json TEXT,
                window_json     TEXT,
                affected_json   TEXT,
                outcome         TEXT NOT NULL,
                PRIMARY KEY (project_id, step_seq)
            );

            -- write-ahead ledger for half-write recovery
            CREATE TABLE IF NOT EXISTS write_ledger (
                project_id      TEXT NOT NULL,
                tx_id           TEXT NOT NULL,
                phase           TEXT NOT NULL,
                started_utc     TEXT NOT NULL,
                finished_utc    TEXT
            );

            CREATE INDEX IF NOT EXISTS idx_result_unit ON derived_result(project_id, unit_id, step_seq);
            CREATE INDEX IF NOT EXISTS idx_evidence_step ON evidence_event(project_id, step_seq);
            CREATE INDEX IF NOT EXISTS idx_assignment_spike ON label_assignment(project_id, spike_id);
            """;
}
