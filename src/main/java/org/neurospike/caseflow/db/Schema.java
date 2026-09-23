package org.neurospike.caseflow.db;

final class Schema {
    private Schema() {
    }

    static final String[] DDL = {
            """
            create table if not exists meta (
                key text primary key,
                value text not null
            )""",
            """
            create table if not exists recording_context (
                id integer primary key check (id = 1),
                recording_id text not null,
                time_basis text not null,
                sample_rate_hz integer not null,
                sample_unit text not null,
                amplitude_unit text not null,
                coordinate_basis text not null
            )""",
            """
            create table if not exists probe_geometries (
                probe_id text primary key,
                coordinate_basis text not null,
                channels_json text not null
            )""",
            """
            create table if not exists detection_thresholds (
                threshold_id text primary key,
                channel integer not null,
                value real not null,
                unit text not null,
                comparator text not null
            )""",
            """
            create table if not exists waveform_fragments (
                waveform_id text primary key,
                detection_id text not null,
                channel integer not null,
                start_sample integer not null,
                sample_unit text not null,
                amplitude_unit text not null,
                samples_json text not null
            )""",
            """
            create table if not exists detection_events (
                detection_id text primary key,
                sample_index integer not null,
                channel integer not null,
                amplitude real not null,
                amplitude_unit text not null,
                waveform_id text not null,
                identity_order integer not null unique
            )""",
            """
            create table if not exists feature_versions (
                feature_version_id text primary key,
                extractor text not null,
                normalization text not null,
                components_json text not null
            )""",
            """
            create table if not exists feature_points (
                feature_point_id text primary key,
                detection_id text not null,
                feature_version_id text not null,
                coordinates_json text not null,
                coordinate_basis text not null,
                identity_order integer not null unique
            )""",
            """
            create table if not exists label_assignments (
                label_version_id text not null,
                cluster_label text not null,
                stable_unit_id text not null,
                detection_ids_json text not null,
                identity_order integer not null unique,
                primary key (label_version_id, cluster_label)
            )""",
            """
            create table if not exists adjudications (
                adjudication_id text primary key,
                decision_type text not null,
                actor text not null,
                rationale text not null,
                target_ids_json text not null,
                outcome text not null,
                identity_order integer not null unique
            )""",
            """
            create table if not exists rule_versions (
                rule_version_id text primary key,
                refractory_ms real not null,
                coincidence_ms real not null,
                drift_ratio_threshold real not null,
                endpoint_epsilon_ms real not null,
                budget integer not null
            )""",
            """
            create table if not exists source_revisions (
                revision_id text primary key,
                target_type text not null,
                target_id text not null,
                replacement_id text not null,
                reason text not null
            )""",
            """
            create table if not exists computations (
                computation_id text primary key,
                rule_version_id text not null,
                feature_version_id text not null,
                label_version_id text not null,
                start_sample integer not null,
                end_sample integer not null,
                status text not null,
                input_digest text not null,
                parents_json text not null,
                evidence_json text not null,
                candidates_json text not null,
                diagnostics_json text not null,
                affected_json text not null,
                adopted integer not null,
                identity_order integer not null unique
            )""",
            """
            create table if not exists decisions (
                decision_id text primary key,
                adjudication_id text not null,
                candidate_id text,
                status text not null,
                rationale text not null
            )""",
            """
            create table if not exists case_events (
                event_id text primary key,
                seq integer not null unique,
                event_type text not null,
                summary text not null,
                computation_id text,
                rule_version_id text,
                source_revision_id text,
                adjudication_id text
            )""",
            """
            create table if not exists import_staging (
                stage_key text primary key,
                payload text not null,
                created_at text not null default current_timestamp
            )"""
    };
}
