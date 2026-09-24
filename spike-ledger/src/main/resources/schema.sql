PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

-- 元数据
CREATE TABLE IF NOT EXISTS meta (
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL
);

-- ===== 不可变材料层（分开存储） =====
CREATE TABLE IF NOT EXISTS waveform_ref (
  id TEXT PRIMARY KEY,
  recording_id TEXT NOT NULL,
  channel INTEGER NOT NULL,
  uri TEXT NOT NULL,
  sample_unit TEXT NOT NULL,
  sample_rate_hz REAL NOT NULL,
  start_sample INTEGER NOT NULL,
  end_sample INTEGER NOT NULL,
  time_basis TEXT NOT NULL,
  checksum_sha256 TEXT NOT NULL,
  raw_json TEXT NOT NULL,
  imported_event TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS probe_geometry (
  id TEXT PRIMARY KEY,
  revision TEXT NOT NULL,
  origin TEXT NOT NULL,
  axes TEXT NOT NULL,
  unit TEXT NOT NULL,
  channel_order TEXT NOT NULL,
  channels_json TEXT NOT NULL,
  raw_representation TEXT NOT NULL,
  imported_event TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS detection_threshold (
  id TEXT PRIMARY KEY,
  method TEXT NOT NULL,
  level_value REAL NOT NULL,
  level_unit TEXT NOT NULL,
  level_raw TEXT NOT NULL,
  canonical REAL,
  convert_error TEXT,
  channels_json TEXT NOT NULL,
  version_tag TEXT NOT NULL,
  imported_event TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS spike_event (
  id TEXT PRIMARY KEY,
  recording_id TEXT NOT NULL,
  time_ms REAL NOT NULL,
  time_basis TEXT NOT NULL,
  channel INTEGER NOT NULL,
  amplitude_value REAL NOT NULL,
  amplitude_unit TEXT NOT NULL,
  amplitude_raw TEXT NOT NULL,
  amplitude_canonical REAL,
  amplitude_convert_error TEXT,
  waveform_ref_id TEXT,
  concurrency_group INTEGER,
  source_line TEXT NOT NULL,
  imported_event TEXT NOT NULL,
  superseded_by TEXT
);

CREATE TABLE IF NOT EXISTS feature_version (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  version INTEGER NOT NULL,
  feature_names_json TEXT NOT NULL,
  params_json TEXT NOT NULL,
  imported_event TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS feature_vector (
  event_id TEXT NOT NULL,
  feature_version_id TEXT NOT NULL,
  values_json TEXT NOT NULL,
  units_json TEXT NOT NULL,
  imported_event TEXT NOT NULL,
  PRIMARY KEY (event_id, feature_version_id)
);

CREATE TABLE IF NOT EXISTS clustering_run (
  id TEXT PRIMARY KEY,
  feature_version_id TEXT NOT NULL,
  threshold_id TEXT NOT NULL,
  geometry_id TEXT NOT NULL,
  params_version TEXT NOT NULL,
  window_start_ms REAL NOT NULL,
  window_end_ms REAL NOT NULL,
  end_inclusive INTEGER NOT NULL,
  input_digest TEXT NOT NULL,
  created_event TEXT NOT NULL,
  invalidated INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS cluster_label (
  run_id TEXT NOT NULL,
  event_id TEXT NOT NULL,
  label_index INTEGER NOT NULL,
  membership REAL NOT NULL,
  PRIMARY KEY (run_id, event_id)
);

-- ===== 追加式事件日志（可回溯决定谱系） =====
CREATE TABLE IF NOT EXISTS event_log (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  id TEXT NOT NULL UNIQUE,
  branch_id TEXT NOT NULL,
  parent_id TEXT,
  type TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  input_digest TEXT,
  params_version TEXT,
  created_at_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_event_branch ON event_log(branch_id, seq);

CREATE TABLE IF NOT EXISTS branch (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  head_event_id TEXT,
  created_at_ms INTEGER NOT NULL
);

-- 裁定事件单独存储（不与聚类标签同表）
CREATE TABLE IF NOT EXISTS adjudication (
  id TEXT PRIMARY KEY,
  branch_id TEXT NOT NULL,
  parent_event_id TEXT,
  target_unit_id TEXT,
  target_event_id TEXT,
  decision TEXT NOT NULL,
  rationale TEXT NOT NULL,
  params_version TEXT,
  conflict_with_event_id TEXT,
  created_at_ms INTEGER NOT NULL
);

-- ===== 派生层（可重建，记录父级依赖与输入摘要） =====
CREATE TABLE IF NOT EXISTS unit (
  id TEXT NOT NULL,
  branch_id TEXT NOT NULL,
  run_id TEXT NOT NULL,
  label_index INTEGER NOT NULL,
  event_ids_json TEXT NOT NULL,
  window_start_ms REAL NOT NULL,
  window_end_ms REAL NOT NULL,
  produced_event TEXT NOT NULL,
  valid_from_seq INTEGER NOT NULL,
  invalid_at_seq INTEGER,
  PRIMARY KEY (id, valid_from_seq)
);

CREATE TABLE IF NOT EXISTS unit_evidence (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  unit_id TEXT NOT NULL,
  valid_from_seq INTEGER NOT NULL,
  type TEXT NOT NULL,
  severity REAL NOT NULL,
  event_ids_json TEXT NOT NULL,
  detail TEXT NOT NULL,
  basis TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS affected_record (
  downstream_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  caused_by_event TEXT NOT NULL,
  valid_at_seq INTEGER NOT NULL,
  detail TEXT NOT NULL,
  PRIMARY KEY (downstream_id, caused_by_event)
);

-- 诊断（无法转换 / 无法决定的项留在原位）
CREATE TABLE IF NOT EXISTS diagnostic (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  scope TEXT NOT NULL,
  material_id TEXT,
  location TEXT NOT NULL,
  code TEXT NOT NULL,
  severity TEXT NOT NULL,
  message TEXT NOT NULL,
  created_event TEXT
);

-- 导入暂存（半写恢复：未提交的导入对正式数据不可见）
CREATE TABLE IF NOT EXISTS import_staging (
  token TEXT PRIMARY KEY,
  bundle_id TEXT NOT NULL,
  state TEXT NOT NULL,
  written INTEGER NOT NULL,
  total INTEGER NOT NULL,
  manifest_json TEXT NOT NULL,
  updated_at_ms INTEGER NOT NULL
);
