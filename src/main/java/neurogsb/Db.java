package neurogsb;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** SQLite schema. Immutable materials and mutable decisions live in separate tables. */
public final class Db {
    private Db() {}

    public static Connection open(String path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA busy_timeout=5000");
            }
            migrate(c);
            return c;
        } catch (Exception e) {
            throw new RuntimeException("cannot open db " + path + ": " + e.getMessage(), e);
        }
    }

    public static void migrate(Connection c) {
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)",
            // ---- immutable material layers (append-only, content digested) ----
            "CREATE TABLE IF NOT EXISTS waveform_refs(" +
                "id TEXT PRIMARY KEY, unit_uid TEXT, channel INTEGER," +
                "t_raw TEXT, t_unit TEXT, t_ms REAL," +
                "sample_ref TEXT, snippet_json TEXT, fs_hz REAL," +
                "raw_json TEXT, digest TEXT, ord INTEGER)",
            "CREATE TABLE IF NOT EXISTS probe_geometry(" +
                "id TEXT PRIMARY KEY, site_id TEXT, channel INTEGER," +
                "x_raw TEXT, y_raw TEXT, coord_unit TEXT, coord_frame TEXT," +
                "x_um REAL, y_um REAL, raw_json TEXT, digest TEXT, ord INTEGER)",
            "CREATE TABLE IF NOT EXISTS detection_thresholds(" +
                "id TEXT PRIMARY KEY, channel INTEGER," +
                "value_raw TEXT, unit TEXT, value_uv REAL," +
                "valid_from_ms REAL, valid_to_ms REAL, version INTEGER," +
                "raw_json TEXT, digest TEXT, ord INTEGER)",
            "CREATE TABLE IF NOT EXISTS feature_versions(" +
                "id TEXT PRIMARY KEY, name TEXT, version INTEGER, params_json TEXT," +
                "raw_json TEXT, digest TEXT, ord INTEGER)",
            "CREATE TABLE IF NOT EXISTS cluster_labels(" +
                "id TEXT PRIMARY KEY, unit_uid TEXT, label_no INTEGER, seq INTEGER," +
                "event TEXT, raw_json TEXT, digest TEXT, ord INTEGER)",
            "CREATE TABLE IF NOT EXISTS spike_events(" +
                "id TEXT PRIMARY KEY, unit_uid TEXT, channel INTEGER," +
                "t_raw TEXT, t_unit TEXT, t_ms REAL," +
                "amp_raw TEXT, amp_unit TEXT, amp_uv REAL," +
                "shared_group TEXT, raw_json TEXT, digest TEXT, ord INTEGER)",
            // ---- mutable decision layer ----
            "CREATE TABLE IF NOT EXISTS adjudication_events(" +
                "id TEXT PRIMARY KEY, seq INTEGER UNIQUE, branch TEXT," +
                "kind TEXT, targets_json TEXT, outcome TEXT, rationale TEXT," +
                "evidence_json TEXT, param_version INTEGER," +
                "window_from_ms REAL, window_to_ms REAL," +
                "status TEXT, supersedes TEXT, conflict_with TEXT," +
                "affected_json TEXT, raw_json TEXT, digest TEXT, ord INTEGER)",
            "CREATE TABLE IF NOT EXISTS supersessions(" +
                "id TEXT PRIMARY KEY, seq INTEGER, event_id TEXT, new_status TEXT, ord INTEGER)",
            // ---- machine-readable evidence (never identity) ----
            "CREATE TABLE IF NOT EXISTS evidence_events(" +
                "id TEXT PRIMARY KEY, seq INTEGER, kind TEXT," +
                "subjects_json TEXT, metrics_json TEXT," +
                "window_from_ms REAL, window_to_ms REAL," +
                "role TEXT, statement TEXT, raw_json TEXT, digest TEXT, ord INTEGER)",
            // ---- derived layer: recomputed from materials, temporally versioned ----
            "CREATE TABLE IF NOT EXISTS derived_records(" +
                "id TEXT PRIMARY KEY, kind TEXT, unit_uid TEXT," +
                "input_digest TEXT, param_version INTEGER, parents_json TEXT," +
                "payload_json TEXT, seq_from INTEGER, seq_to INTEGER," +
                "status TEXT, invalidation_reason TEXT," +
                "valid_from_ms REAL, valid_to_ms REAL," +
                "manifest_id TEXT, ord INTEGER)",
            "CREATE TABLE IF NOT EXISTS diagnostics(" +
                "id TEXT PRIMARY KEY, source TEXT, position TEXT," +
                "code TEXT, severity TEXT, message TEXT, raw_json TEXT, ord INTEGER)",
            "CREATE TABLE IF NOT EXISTS manifests(" +
                "id TEXT PRIMARY KEY, status TEXT, description TEXT, ord INTEGER)"
        };
        try (Statement s = c.createStatement()) {
            for (String d : ddl) s.execute(d);
        } catch (Exception e) {
            throw new RuntimeException("migration failed: " + e.getMessage(), e);
        }
    }
}
