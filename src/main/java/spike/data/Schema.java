package spike.data;

public final class Schema {

    private Schema() {
    }

    public static final String DDL = """
            PRAGMA journal_mode = WAL;

            CREATE TABLE IF NOT EXISTS meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );

            -- Immutable materials: six source kinds plus waveform snippets.
            CREATE TABLE IF NOT EXISTS material (
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                version TEXT,
                source_ref TEXT,
                raw_json TEXT NOT NULL,
                received_seq INTEGER NOT NULL,
                superseded_by TEXT,
                UNIQUE(kind, source_ref, version)
            );
            CREATE INDEX IF NOT EXISTS idx_material_kind ON material(kind, received_seq);

            CREATE TABLE IF NOT EXISTS waveform_snippet (
                material_id TEXT PRIMARY KEY,
                channel TEXT NOT NULL,
                sample_start INTEGER NOT NULL,
                sample_count INTEGER NOT NULL,
                sample_rate_hz REAL NOT NULL,
                unit TEXT NOT NULL,
                samples_json TEXT NOT NULL,
                FOREIGN KEY(material_id) REFERENCES material(id)
            );

            CREATE TABLE IF NOT EXISTS branch (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                parent_branch_id TEXT,
                parent_decision_seq INTEGER,
                created_at_seq INTEGER NOT NULL,
                note TEXT
            );

            CREATE TABLE IF NOT EXISTS decision (
                id TEXT PRIMARY KEY,
                branch_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                kind TEXT NOT NULL,
                subject_type TEXT,
                subject_id TEXT,
                payload_json TEXT NOT NULL,
                params_version TEXT,
                rationale TEXT,
                based_on_result_id TEXT,
                forked_to_branch TEXT,
                op_seq INTEGER,
                UNIQUE(branch_id, seq)
            );

            CREATE TABLE IF NOT EXISTS replay_op (
                seq INTEGER PRIMARY KEY,
                type TEXT NOT NULL,
                label TEXT,
                payload_json TEXT NOT NULL,
                expects_outcome TEXT,
                expects_diagnostic_code TEXT,
                applied_decision_id TEXT,
                applied_at_seq INTEGER
            );

            CREATE TABLE IF NOT EXISTS result (
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                branch_id TEXT NOT NULL,
                branch_seq INTEGER NOT NULL,
                outcome TEXT NOT NULL,
                value_json TEXT,
                input_digest TEXT NOT NULL,
                params_version TEXT NOT NULL,
                deps_digest TEXT NOT NULL,
                budget_micros INTEGER NOT NULL,
                stable_order_key TEXT NOT NULL,
                produced_seq INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_result_kind_order ON result(kind, branch_id, branch_seq);

            CREATE TABLE IF NOT EXISTS result_parent (
                result_id TEXT NOT NULL,
                parent_id TEXT NOT NULL,
                ord INTEGER NOT NULL,
                PRIMARY KEY(result_id, parent_id)
            );
            CREATE TABLE IF NOT EXISTS result_material (
                result_id TEXT NOT NULL,
                material_id TEXT NOT NULL,
                ord INTEGER NOT NULL,
                PRIMARY KEY(result_id, material_id)
            );
            CREATE TABLE IF NOT EXISTS diagnostic (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                result_id TEXT,
                op_seq INTEGER,
                severity TEXT NOT NULL,
                code TEXT NOT NULL,
                material_id TEXT,
                path TEXT,
                message TEXT NOT NULL,
                raw_value TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_diag_result ON diagnostic(result_id);

            CREATE TABLE IF NOT EXISTS unit_identity (
                unit_id TEXT PRIMARY KEY,
                fingerprint TEXT NOT NULL UNIQUE,
                first_seen_seq INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS unit_code (
                unit_id TEXT NOT NULL,
                run_id TEXT NOT NULL,
                code TEXT NOT NULL,
                valid_from_seq INTEGER NOT NULL,
                valid_to_seq INTEGER,
                PRIMARY KEY(unit_id, run_id, valid_from_seq)
            );
            CREATE TABLE IF NOT EXISTS unit_lineage (
                child_unit_id TEXT NOT NULL,
                parent_unit_id TEXT NOT NULL,
                relation TEXT NOT NULL,
                op_seq INTEGER NOT NULL,
                evidence TEXT,
                PRIMARY KEY(child_unit_id, parent_unit_id, op_seq)
            );

            CREATE TABLE IF NOT EXISTS evidence (
                id TEXT PRIMARY KEY,
                type TEXT NOT NULL,
                unit_id TEXT,
                first_sample INTEGER,
                detail_json TEXT NOT NULL,
                produced_seq INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_ev_unit ON evidence(unit_id, produced_seq);
            CREATE INDEX IF NOT EXISTS idx_ev_type ON evidence(type, produced_seq);

            -- Half-write recovery journal. An open entry at startup means a
            -- multi-step write was interrupted; recovery either completes from
            -- the materialized steps or rolls back to last_good_seq.
            CREATE TABLE IF NOT EXISTS write_journal (
                txn_id TEXT PRIMARY KEY,
                operation TEXT NOT NULL,
                started_seq INTEGER NOT NULL,
                last_good_seq INTEGER NOT NULL,
                total_steps INTEGER NOT NULL,
                done_steps INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL,
                payload_json TEXT NOT NULL
            );
            """;
}
