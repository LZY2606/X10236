package spikesort.db;

final class Schema {
    private Schema() {}

    static final String[] ALL = {
        "CREATE TABLE IF NOT EXISTS meta("
            + " k TEXT PRIMARY KEY, v TEXT NOT NULL)",

        // ---- immutable materials, one concern per table ----
        "CREATE TABLE IF NOT EXISTS waveform_refs("
            + " id TEXT PRIMARY KEY, channel INTEGER NOT NULL,"
            + " t_raw TEXT NOT NULL, t_unit TEXT NOT NULL,"
            + " sample_rate_raw TEXT NOT NULL, sample_rate_unit TEXT NOT NULL,"
            + " samples_raw TEXT NOT NULL, samples_unit TEXT NOT NULL,"
            + " source_uri TEXT, created_seq INTEGER NOT NULL, sha TEXT NOT NULL)",

        "CREATE TABLE IF NOT EXISTS probe_geometry("
            + " id TEXT PRIMARY KEY, site INTEGER NOT NULL,"
            + " x_raw TEXT NOT NULL, x_unit TEXT NOT NULL,"
            + " y_raw TEXT NOT NULL, y_unit TEXT NOT NULL,"
            + " basis TEXT NOT NULL, created_seq INTEGER NOT NULL, sha TEXT NOT NULL)",

        "CREATE TABLE IF NOT EXISTS detection_thresholds("
            + " id TEXT PRIMARY KEY, channel INTEGER NOT NULL,"
            + " value_raw TEXT NOT NULL, value_unit TEXT NOT NULL,"
            + " version INTEGER NOT NULL, created_seq INTEGER NOT NULL, sha TEXT NOT NULL)",

        "CREATE TABLE IF NOT EXISTS feature_versions("
            + " id TEXT PRIMARY KEY, name TEXT NOT NULL, version INTEGER NOT NULL,"
            + " params TEXT NOT NULL, created_seq INTEGER NOT NULL, sha TEXT NOT NULL)",

        "CREATE TABLE IF NOT EXISTS detection_events("
            + " id TEXT PRIMARY KEY, t_raw TEXT NOT NULL, t_unit TEXT NOT NULL,"
            + " channel INTEGER NOT NULL, waveform_id TEXT NOT NULL,"
            + " superseded_by TEXT, superseded_seq INTEGER, created_seq INTEGER NOT NULL, sha TEXT NOT NULL)",

        "CREATE TABLE IF NOT EXISTS cluster_labels("
            + " id TEXT PRIMARY KEY, cluster_no INTEGER NOT NULL, unit_id TEXT NOT NULL,"
            + " label_version INTEGER NOT NULL, event_ids TEXT NOT NULL,"
            + " created_seq INTEGER NOT NULL, sha TEXT NOT NULL)",

        // ---- mutable decision log (append-only; conflicts kept, never overwritten) ----
        "CREATE TABLE IF NOT EXISTS adjudication_events("
            + " id TEXT PRIMARY KEY, seq INTEGER NOT NULL, branch TEXT NOT NULL,"
            + " kind TEXT NOT NULL, target TEXT NOT NULL, payload TEXT NOT NULL,"
            + " supersedes TEXT, accepted INTEGER NOT NULL, decided_at TEXT NOT NULL,"
            + " sha TEXT NOT NULL)",

        // ---- machine-readable evidence (never identity) ----
        "CREATE TABLE IF NOT EXISTS evidence_events("
            + " id TEXT PRIMARY KEY, seq INTEGER NOT NULL, kind TEXT NOT NULL,"
            + " principle TEXT NOT NULL, subjects TEXT NOT NULL, payload TEXT NOT NULL,"
            + " sha TEXT NOT NULL)",

        // ---- derived layer: digest + param version + parents + validity interval ----
        "CREATE TABLE IF NOT EXISTS derived_records("
            + " id TEXT PRIMARY KEY, cid TEXT NOT NULL, kind TEXT NOT NULL,"
            + " subject TEXT NOT NULL, input_digest TEXT NOT NULL,"
            + " param_version TEXT NOT NULL, parents TEXT NOT NULL,"
            + " t0_ms REAL, t1_ms REAL,"
            + " valid_from_seq INTEGER NOT NULL, valid_to_seq INTEGER,"
            + " status TEXT NOT NULL, payload TEXT NOT NULL, invalidated_reason TEXT)",

        "CREATE TABLE IF NOT EXISTS diagnostics("
            + " id TEXT PRIMARY KEY, stage TEXT NOT NULL, location TEXT NOT NULL,"
            + " severity TEXT NOT NULL, message TEXT NOT NULL, raw_value TEXT)",

        "CREATE TABLE IF NOT EXISTS write_intents("
            + " id TEXT PRIMARY KEY, kind TEXT NOT NULL, state TEXT NOT NULL,"
            + " detail TEXT, created_at TEXT NOT NULL)",

        "CREATE INDEX IF NOT EXISTS idx_derived_subject ON derived_records(subject, kind)",
        "CREATE INDEX IF NOT EXISTS idx_adj_seq ON adjudication_events(seq)",
        "CREATE INDEX IF NOT EXISTS idx_labels_unit ON cluster_labels(unit_id)"
    };
}
