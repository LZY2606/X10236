package spike.store;

/** SQLite schema. Immutable material, derived results and mutable decisions live in separate tables. */
public final class Schema {
    private Schema() {
    }

    public static final String[] DDL = {
        """
        CREATE TABLE IF NOT EXISTS meta (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS probe_channel (
            id TEXT PRIMARY KEY,
            probe_id TEXT NOT NULL,
            idx INTEGER NOT NULL,
            x REAL NOT NULL,
            y REAL NOT NULL,
            coord_unit TEXT NOT NULL,
            coord_basis TEXT NOT NULL,
            ord INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS waveform_ref (
            id TEXT PRIMARY KEY,
            channel_id TEXT NOT NULL,
            sample_rate REAL NOT NULL,
            rate_unit TEXT NOT NULL,
            gain REAL NOT NULL,
            raw_unit TEXT NOT NULL,
            peak_index INTEGER NOT NULL,
            storage_uri TEXT,
            samples TEXT NOT NULL,
            ord INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS threshold (
            id TEXT PRIMARY KEY,
            channel_id TEXT NOT NULL,
            value REAL NOT NULL,
            unit TEXT NOT NULL,
            method TEXT NOT NULL,
            basis TEXT NOT NULL,
            version TEXT NOT NULL,
            ord INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS feature_version (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            version TEXT NOT NULL,
            dimensions INTEGER NOT NULL,
            component_names TEXT NOT NULL,
            amplitude_unit TEXT NOT NULL,
            time_basis TEXT NOT NULL,
            ord INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS spike_event (
            id TEXT PRIMARY KEY,
            channel_ids TEXT NOT NULL,
            sample_index INTEGER NOT NULL,
            time_sec REAL NOT NULL,
            amplitude REAL NOT NULL,
            amp_unit TEXT NOT NULL,
            raw_value REAL,
            raw_unit TEXT,
            valid_low REAL,
            valid_high REAL,
            waveform_ref_id TEXT,
            threshold_id TEXT,
            features TEXT NOT NULL,
            feature_version_id TEXT,
            valid INTEGER NOT NULL,
            invalid_reason TEXT,
            ord INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS clustering_run (
            id TEXT PRIMARY KEY,
            feature_version_id TEXT NOT NULL,
            threshold_version TEXT NOT NULL,
            params_version TEXT NOT NULL,
            window_start REAL NOT NULL,
            window_end REAL NOT NULL,
            input_digest TEXT NOT NULL,
            since_seq INTEGER NOT NULL,
            until_seq INTEGER,
            status TEXT NOT NULL,
            note TEXT
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS cluster (
            id TEXT PRIMARY KEY,
            run_id TEXT NOT NULL,
            number INTEGER NOT NULL,
            since_seq INTEGER NOT NULL,
            until_seq INTEGER
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS cluster_membership (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            run_id TEXT NOT NULL,
            cluster_id TEXT NOT NULL,
            spike_id TEXT NOT NULL,
            membership TEXT NOT NULL,
            since_seq INTEGER NOT NULL,
            until_seq INTEGER,
            UNIQUE(run_id, cluster_id, spike_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS evidence (
            id TEXT PRIMARY KEY,
            type TEXT NOT NULL,
            subject_clusters TEXT NOT NULL,
            spike_ids TEXT NOT NULL,
            detail TEXT NOT NULL,
            magnitude REAL NOT NULL,
            unit TEXT,
            run_id TEXT NOT NULL,
            since_seq INTEGER NOT NULL,
            valid INTEGER NOT NULL,
            invalid_reason TEXT
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS lineage_link (
            id TEXT PRIMARY KEY,
            type TEXT NOT NULL,
            from_cluster_id TEXT NOT NULL,
            to_cluster_id TEXT NOT NULL,
            from_run_id TEXT,
            to_run_id TEXT,
            evidence_ids TEXT NOT NULL,
            since_seq INTEGER NOT NULL,
            note TEXT
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS adjudication (
            id TEXT PRIMARY KEY,
            decision TEXT NOT NULL,
            status TEXT NOT NULL,
            cluster_ids TEXT NOT NULL,
            spike_ids TEXT NOT NULL,
            evidence_ids TEXT NOT NULL,
            rationale TEXT,
            reviewer TEXT NOT NULL,
            parameter_version TEXT,
            time_basis TEXT,
            seq INTEGER NOT NULL,
            superseded_by_seq INTEGER,
            conflict_group TEXT
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS ledger_event (
            seq INTEGER PRIMARY KEY,
            parent_seq INTEGER,
            kind TEXT NOT NULL,
            subject_id TEXT NOT NULL,
            payload TEXT NOT NULL,
            time_basis TEXT,
            actor TEXT NOT NULL,
            rule_version TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS diagnostic (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            kind TEXT NOT NULL,
            code TEXT NOT NULL,
            location TEXT NOT NULL,
            message TEXT NOT NULL,
            scope TEXT NOT NULL,
            seq INTEGER
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_membership_run ON cluster_membership(run_id)",
        "CREATE INDEX IF NOT EXISTS idx_membership_spike ON cluster_membership(spike_id)",
        "CREATE INDEX IF NOT EXISTS idx_cluster_run ON cluster(run_id)",
        "CREATE INDEX IF NOT EXISTS idx_evidence_run ON evidence(run_id)",
        "CREATE INDEX IF NOT EXISTS idx_lineage_from ON lineage_link(from_cluster_id)"
    };
}
