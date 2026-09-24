package neurospike.store;

/**
 * Storage is layered by immutability:
 *
 *   session / materials_*  -> immutable imported facts (versioned, never edited)
 *   cluster_run/unit/unit_lineage/membership -> clustering labels and stable identity
 *   decision_event         -> human decisions (append-only, may conflict)
 *   derived_result         -> computed outputs with input summary + parent inputs
 *   event_log              -> append-only audit timeline that drives replay
 *
 * All explicit orderings live in `ord` columns; integer ids are never used to
 * imply order or identity.
 */
public final class Schema {
    private Schema() {
    }

    public static final String DDL = """
            PRAGMA journal_mode=WAL;
            PRAGMA foreign_keys=ON;

            CREATE TABLE IF NOT EXISTS session (
                session_id      TEXT PRIMARY KEY,
                name            TEXT NOT NULL,
                time_base       TEXT NOT NULL,
                sample_rate_hz  REAL NOT NULL,
                t0_iso          TEXT NOT NULL,
                created_seq     INTEGER NOT NULL,
                created_at_ms   INTEGER NOT NULL,
                import_hash     TEXT NOT NULL
            );

            -- Original waveform references. snippets_json stores real samples
            -- (domain material), so the package works fully offline.
            CREATE TABLE IF NOT EXISTS materials_waveform (
                wave_id         TEXT PRIMARY KEY,
                session_id      TEXT NOT NULL REFERENCES session(session_id),
                channel_id      TEXT NOT NULL,
                uri             TEXT NOT NULL,
                raw_uri         TEXT NOT NULL,
                checksum        TEXT NOT NULL,
                samples_json    TEXT NOT NULL,
                unit            TEXT NOT NULL,
                frame           TEXT NOT NULL,
                sample_rate_hz  REAL NOT NULL,
                start_sample    INTEGER NOT NULL,
                ord             INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS materials_probe (
                probe_id        TEXT PRIMARY KEY,
                session_id      TEXT NOT NULL REFERENCES session(session_id),
                ord             INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS materials_probe_site (
                probe_id        TEXT NOT NULL REFERENCES materials_probe(probe_id),
                channel_id      TEXT NOT NULL,
                x_pv            TEXT NOT NULL,
                y_pv            TEXT NOT NULL,
                x_value         REAL,
                y_value         REAL,
                valid           INTEGER NOT NULL,
                diag            TEXT,
                unit            TEXT NOT NULL,
                frame           TEXT NOT NULL,
                ord             INTEGER NOT NULL,
                PRIMARY KEY (probe_id, channel_id)
            );

            -- Detection thresholds are versioned: a revision inserts a new row
            -- rather than overwriting the old one.
            CREATE TABLE IF NOT EXISTS materials_threshold (
                threshold_id    TEXT PRIMARY KEY,
                session_id      TEXT NOT NULL REFERENCES session(session_id),
                channel_id      TEXT NOT NULL,
                version         INTEGER NOT NULL,
                value_pv        TEXT NOT NULL,
                value_num       REAL,
                unit            TEXT NOT NULL,
                valid           INTEGER NOT NULL,
                diag            TEXT,
                supersedes      TEXT,
                created_seq     INTEGER NOT NULL,
                created_at_ms   INTEGER NOT NULL,
                ord             INTEGER NOT NULL,
                UNIQUE (channel_id, version)
            );

            -- Feature extraction rule set. Multiple versions coexist.
            CREATE TABLE IF NOT EXISTS materials_feature_version (
                feature_version_id TEXT PRIMARY KEY,
                session_id      TEXT NOT NULL REFERENCES session(session_id),
                version         INTEGER NOT NULL,
                method          TEXT NOT NULL,
                n_components    INTEGER NOT NULL,
                params_json     TEXT NOT NULL,
                created_seq     INTEGER NOT NULL,
                created_at_ms   INTEGER NOT NULL,
                ord             INTEGER NOT NULL,
                UNIQUE (session_id, version)
            );

            CREATE TABLE IF NOT EXISTS materials_feature_point (
                feature_point_id TEXT PRIMARY KEY,
                session_id      TEXT NOT NULL REFERENCES session(session_id),
                feature_version_id TEXT NOT NULL
                    REFERENCES materials_feature_version(feature_version_id),
                spike_id        TEXT NOT NULL,
                coords_json     TEXT NOT NULL,
                ord             INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS detection (
                spike_id        TEXT PRIMARY KEY,
                session_id      TEXT NOT NULL REFERENCES session(session_id),
                wave_id         TEXT NOT NULL REFERENCES materials_waveform(wave_id),
                channel_id      TEXT NOT NULL,
                sample_index    INTEGER NOT NULL,
                t_ms            REAL NOT NULL,
                amplitude_pv    TEXT NOT NULL,
                amplitude_num   REAL,
                amplitude_unit  TEXT NOT NULL,
                amp_valid       INTEGER NOT NULL,
                amp_diag        TEXT,
                threshold_id    TEXT,
                version         INTEGER NOT NULL,
                supersedes      TEXT,
                created_seq     INTEGER NOT NULL,
                ord             INTEGER NOT NULL
            );

            -- A clustering run is one parameterised application of a feature
            -- version. Labels are run-local numbers, never stable identities.
            CREATE TABLE IF NOT EXISTS cluster_run (
                run_id          TEXT PRIMARY KEY,
                session_id      TEXT NOT NULL REFERENCES session(session_id),
                feature_version_id TEXT NOT NULL
                    REFERENCES materials_feature_version(feature_version_id),
                params_json     TEXT NOT NULL,
                params_version  TEXT NOT NULL,
                algo            TEXT NOT NULL,
                created_seq     INTEGER NOT NULL,
                created_at_ms   INTEGER NOT NULL,
                supersedes_run  TEXT,
                ord             INTEGER NOT NULL
            );

            -- Stable unit identity that survives renumber/merge/split.
            CREATE TABLE IF NOT EXISTS unit (
                unit_id         TEXT PRIMARY KEY,
                session_id      TEXT NOT NULL REFERENCES session(session_id),
                first_run_id    TEXT NOT NULL,
                first_label     INTEGER NOT NULL,
                created_seq     INTEGER NOT NULL,
                ord             INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS unit_lineage (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                child_unit_id   TEXT NOT NULL REFERENCES unit(unit_id),
                parent_unit_id  TEXT NOT NULL REFERENCES unit(unit_id),
                relation        TEXT NOT NULL,
                decision_event_id TEXT,
                run_id          TEXT NOT NULL,
                ord             INTEGER NOT NULL
            );

            -- Membership labels. A spike can have several legal candidate units
            -- after a split; is_primary marks the canonical one (if any).
            CREATE TABLE IF NOT EXISTS membership (
                spike_id        TEXT NOT NULL REFERENCES detection(spike_id),
                run_id          TEXT NOT NULL REFERENCES cluster_run(run_id),
                label           INTEGER NOT NULL,
                unit_id         TEXT NOT NULL REFERENCES unit(unit_id),
                is_primary      INTEGER NOT NULL,
                candidate       INTEGER NOT NULL,
                ord             INTEGER NOT NULL,
                PRIMARY KEY (spike_id, run_id, unit_id)
            );

            CREATE TABLE IF NOT EXISTS decision_event (
                decision_event_id TEXT PRIMARY KEY,
                session_id      TEXT NOT NULL REFERENCES session(session_id),
                kind            TEXT NOT NULL,
                code            TEXT NOT NULL,
                subject_type    TEXT NOT NULL,
                subject_id      TEXT NOT NULL,
                payload_json    TEXT NOT NULL,
                conflicts_with  TEXT,
                created_seq     INTEGER NOT NULL,
                created_at_ms   INTEGER NOT NULL,
                ord             INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS derived_result (
                result_id       TEXT PRIMARY KEY,
                session_id      TEXT NOT NULL REFERENCES session(session_id),
                kind            TEXT NOT NULL,
                subject_id      TEXT NOT NULL,
                run_id          TEXT,
                window_id       TEXT,
                status          TEXT NOT NULL,
                outcome         TEXT NOT NULL,
                input_summary   TEXT NOT NULL,
                params_version  TEXT NOT NULL,
                parents_json    TEXT NOT NULL,
                value_json      TEXT NOT NULL,
                diagnostics_json TEXT NOT NULL,
                created_seq     INTEGER NOT NULL,
                created_at_ms   INTEGER NOT NULL,
                ord             INTEGER NOT NULL,
                UNIQUE (kind, subject_id, run_id, window_id, input_summary)
            );

            CREATE TABLE IF NOT EXISTS analysis_window (
                window_id       TEXT PRIMARY KEY,
                session_id      TEXT NOT NULL REFERENCES session(session_id),
                label           TEXT NOT NULL,
                start_ms        REAL NOT NULL,
                end_ms          REAL NOT NULL,
                end_inclusive   INTEGER NOT NULL,
                version         INTEGER NOT NULL,
                supersedes      TEXT,
                created_seq     INTEGER NOT NULL,
                created_at_ms   INTEGER NOT NULL,
                ord             INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS event_log (
                seq             INTEGER PRIMARY KEY,
                session_id      TEXT NOT NULL,
                kind            TEXT NOT NULL,
                event_id        TEXT NOT NULL,
                payload_json    TEXT NOT NULL,
                at_ms           INTEGER NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_detection_t ON detection(session_id, t_ms);
            CREATE INDEX IF NOT EXISTS idx_membership_unit ON membership(unit_id, run_id);
            CREATE INDEX IF NOT EXISTS idx_derived_lookup ON derived_result(kind, subject_id, session_id);
            CREATE INDEX IF NOT EXISTS idx_event_session ON event_log(session_id, seq);
            CREATE INDEX IF NOT EXISTS idx_threshold_chan ON materials_threshold(channel_id, version);
            CREATE INDEX IF NOT EXISTS idx_detection_version ON detection(session_id, version);
            """ ;
}
