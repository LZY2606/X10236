package neuro.spike.store;

/**
 * Relational schema. The layering mandated by the domain is represented by separate
 * tables: waveform_ref / probe_geometry / detect_threshold / feature_version /
 * cluster_label / adjudication_event are never co-mingled. Every derived row records its
 * input digest, parameter version and parent dependencies.
 */
public final class Schema {
    private Schema() {
    }

    public static final String DDL = """
            PRAGMA journal_mode=WAL;

            CREATE TABLE IF NOT EXISTS meta (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS material (
                kind        TEXT NOT NULL CHECK (kind IN
                    ('waveform_ref','probe_geometry','detect_threshold','time_base',
                     'detection_event','feature_version','cluster_label')),
                id          TEXT NOT NULL,
                seq         INTEGER NOT NULL,
                revision    TEXT NOT NULL,
                payload     TEXT NOT NULL,
                input_digest TEXT NOT NULL,
                imported_in TEXT,
                PRIMARY KEY (kind, id, revision)
            );

            CREATE TABLE IF NOT EXISTS global_order (
                seq        INTEGER PRIMARY KEY,
                kind       TEXT NOT NULL,
                id         TEXT NOT NULL,
                revision   TEXT NOT NULL,
                branch     TEXT NOT NULL DEFAULT 'main'
            );

            CREATE TABLE IF NOT EXISTS unit (
                stable_id       TEXT PRIMARY KEY,
                first_seen_seq  INTEGER NOT NULL,
                first_cluster_no INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS membership (
                unit_id         TEXT NOT NULL,
                label_id        TEXT NOT NULL,
                detection_id    TEXT NOT NULL,
                cluster_number  INTEGER NOT NULL,
                weight          REAL NOT NULL,
                seq             INTEGER NOT NULL,
                PRIMARY KEY (unit_id, label_id, detection_id, cluster_number)
            );

            CREATE TABLE IF NOT EXISTS derived_result (
                id          TEXT NOT NULL,
                view_seq    INTEGER NOT NULL,
                kind        TEXT NOT NULL,
                target_id   TEXT NOT NULL,
                params_hash TEXT NOT NULL,
                input_digest TEXT NOT NULL,
                parent_ids  TEXT NOT NULL,
                status      TEXT NOT NULL,
                payload     TEXT,
                PRIMARY KEY (id, view_seq)
            );

            CREATE TABLE IF NOT EXISTS evidence_event (
                seq          INTEGER PRIMARY KEY,
                rule_id      TEXT NOT NULL,
                evidence     TEXT NOT NULL,
                status       TEXT NOT NULL,
                target_id    TEXT NOT NULL,
                subject_id   TEXT,
                view_seq     INTEGER NOT NULL,
                dedupe_key   TEXT NOT NULL UNIQUE,
                basis_json   TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS adjudication_event (
                id           TEXT PRIMARY KEY,
                seq          INTEGER NOT NULL,
                decision     TEXT NOT NULL CHECK (decision IN ('accept','reject','adjust','resolve')),
                target_unit  TEXT,
                rationale    TEXT,
                params_json  TEXT NOT NULL,
                conflicts    TEXT NOT NULL,
                parent_id    TEXT,
                branch       TEXT NOT NULL DEFAULT 'main',
                adopted      INTEGER NOT NULL DEFAULT 1
            );

            CREATE TABLE IF NOT EXISTS diagnostic (
                seq         INTEGER PRIMARY KEY,
                scope_kind  TEXT,
                scope_id    TEXT,
                view_seq    INTEGER,
                code        TEXT NOT NULL,
                position    TEXT,
                message     TEXT NOT NULL,
                raw_value   TEXT
            );

            CREATE TABLE IF NOT EXISTS window_bounds (
                view_seq    INTEGER PRIMARY KEY,
                t_start_s   REAL NOT NULL,
                t_end_s     REAL NOT NULL
            );

            CREATE TABLE IF NOT EXISTS recovery_journal (
                tx_id       TEXT PRIMARY KEY,
                kind        TEXT NOT NULL,
                phase       TEXT NOT NULL,
                backup_path TEXT,
                payload     TEXT,
                started_at  TEXT NOT NULL
            );
            """;
}
