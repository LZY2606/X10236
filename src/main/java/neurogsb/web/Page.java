package neurogsb.web;

/** 审阅页外壳：页面联动波形、特征投影、自相关、发放率与漂移时间线。 */
public final class Page {
    private Page() {}

    public static final String HTML = """
<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<title>脉冲簇谱 · 神经棘波候选、漂移簇与人工裁定谱系</title>
<style>
  :root { --bg:#0b0e14; --panel:#141926; --line:#232c40; --fg:#c9d4e8; --dim:#7a8699; --acc:#8fd3ff; --warn:#ffd166; --bad:#ff5d73; }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--bg); color:var(--fg); font:13px/1.5 "SF Mono", Menlo, monospace; }
  header { padding:10px 16px; border-bottom:1px solid var(--line); display:flex; gap:16px; align-items:baseline; flex-wrap:wrap; }
  header h1 { font-size:15px; margin:0; color:var(--acc); }
  header .meta { color:var(--dim); font-size:11px; }
  main { display:grid; grid-template-columns: 300px 1fr; gap:10px; padding:10px; }
  .card { background:var(--panel); border:1px solid var(--line); border-radius:8px; padding:10px; }
  .card h2 { font-size:12px; margin:0 0 8px; color:var(--acc); font-weight:600; }
  #units .unit { padding:6px 8px; border:1px solid var(--line); border-radius:6px; margin-bottom:6px; cursor:pointer; }
  #units .unit.sel { border-color:var(--acc); background:#182136; }
  #units .unit .uid { color:var(--warn); }
  #units .badge { font-size:10px; padding:1px 5px; border-radius:4px; background:#233; margin-left:4px; }
  #units .badge.conflict { background:#4a1f2b; color:var(--bad); }
  #units .badge.superseded { background:#2a2f3d; color:var(--dim); }
  .panels { display:grid; grid-template-columns:1fr 1fr; gap:10px; }
  .panels .wide { grid-column:1 / -1; }
  .panel-svg { width:100%; height:auto; display:block; }
  #events { max-height:260px; overflow:auto; }
  #events .ev { padding:3px 6px; border-left:2px solid var(--line); margin:3px 0; cursor:pointer; font-size:11px; }
  #events .ev:hover, #events .ev.sel { border-left-color:var(--acc); background:#182136; }
  #events .ev .seq { color:var(--warn); }
  #affected { font-size:11px; }
  #affected .row { padding:2px 0; border-bottom:1px dashed var(--line); }
  button { background:#1d2740; color:var(--fg); border:1px solid var(--line); border-radius:5px; padding:5px 10px; cursor:pointer; font:inherit; font-size:12px; }
  button:hover { border-color:var(--acc); }
  input, select { background:#0f1420; color:var(--fg); border:1px solid var(--line); border-radius:4px; padding:4px 6px; font:inherit; font-size:12px; width:90px; }
  .diag { color:var(--warn); font-size:11px; }
  .inv { color:var(--bad); font-size:11px; }
  .kv { color:var(--dim); font-size:11px; }
  .rowflex { display:flex; gap:8px; align-items:center; flex-wrap:wrap; margin:6px 0; }
  #toast { position:fixed; right:14px; bottom:14px; background:#1d2740; border:1px solid var(--acc); padding:8px 12px; border-radius:6px; display:none; max-width:40vw; }
  a.dl { color:var(--acc); font-size:12px; }
</style>
</head>
<body>
<header>
  <h1>脉冲簇谱 · 神经棘波候选、漂移簇与人工裁定谱系</h1>
  <span class="meta" id="bases"></span>
  <span class="meta" id="seqinfo"></span>
  <a class="dl" href="/api/export" download="neuro-gsb-export.json">导出档案</a>
  <button onclick="document.getElementById('imp').click()">导入</button>
  <input type="file" id="imp" style="display:none" onchange="doImport(this)">
  <button onclick="resetCase()">重置案例</button>
</header>
<main>
  <div>
    <div class="card" id="units-card"><h2>单元（稳定身份 ≠ 聚类编号）</h2><div id="units"></div></div>
    <div class="card" style="margin-top:10px"><h2>裁定</h2>
      <div class="rowflex">
        <select id="evidence-kind">
          <option value="evidence.drift">幅度漂移证据</option>
          <option value="evidence.refractory">不应期违例证据</option>
          <option value="evidence.coprop">多通道传播证据</option>
        </select>
        <input id="actor" placeholder="审阅者" value="reviewer-kim">
      </div>
      <div class="rowflex">
        <button onclick="decide(true)">接受</button>
        <button onclick="decide(false)">否定</button>
      </div>
      <div class="rowflex">
        <input id="w0" placeholder="t0 ms" value="1000"><input id="w1" placeholder="t1 ms" value="40000">
        <button onclick="setWindow()">调整时间窗</button>
      </div>
      <div class="rowflex">
        <input id="newno" placeholder="新聚类号" style="width:70px">
        <button onclick="renumber()">改编号</button>
        <input id="tsplit" placeholder="拆分点 ms" style="width:90px">
        <button onclick="splitUnit()">拆分</button>
      </div>
      <div id="affected" class="card" style="display:none; margin-top:8px"><h2>受影响的下游记录</h2><div id="affected-list"></div></div>
    </div>
    <div class="card" style="margin-top:10px"><h2>事件时间线（点击回放）</h2><div id="events"></div></div>
    <div class="card" style="margin-top:10px"><h2>诊断与失效</h2><div id="diags"></div><div id="invs"></div></div>
  </div>
  <div class="panels">
    <div class="card wide"><h2>波形片段</h2><div id="p-wave"></div></div>
    <div class="card"><h2>特征投影</h2><div id="p-proj"></div></div>
    <div class="card"><h2>自相关</h2><div id="p-acg"></div></div>
    <div class="card"><h2>发放率</h2><div id="p-rate"></div></div>
    <div class="card"><h2>漂移时间线</h2><div id="p-drift"></div></div>
    <div class="card wide"><h2>单元详情（单位 / 基准 / 有效区间 / 失效原因）</h2><div id="detail" class="kv"></div></div>
  </div>
</main>
<div id="toast"></div>
<script>
let state = null, selUnit = null, selT = 0, selSeq = null;

async function load() {
  const q = selSeq ? ('?seq=' + selSeq) : '';
  state = await (await fetch('/api/state' + q)).json();
  document.getElementById('bases').textContent =
    '时间基准: ' + state.bases.time + ' · 振幅: ' + state.bases.amplitude + ' · 几何: ' + state.bases.geometry;
  document.getElementById('seqinfo').textContent =
    '视点 seq=' + state.seq + ' / 最新=' + state.latest_seq + (selSeq ? '（历史回放）' : '');
  if (!selUnit && state.units.length) selUnit = state.units[0].unit_id;
  renderUnits(); renderEvents(); renderDiags(); renderPanels(); renderDetail();
}

function renderUnits() {
  const el = document.getElementById('units');
  el.innerHTML = '';
  for (const u of state.units) {
    const d = document.createElement('div');
    d.className = 'unit' + (u.unit_id === selUnit ? ' sel' : '');
    const conflict = state.candidates.some(c => c.kind === 'conflicting_decision' && c.status === 'open'
      && u.derived.some(x => x.id === c.subject_id));
    d.innerHTML = '<span class="uid">' + u.unit_id + '</span> · 簇号 ' + u.cluster_no
      + ' · ' + u.spike_count + ' 棘波'
      + (u.status === 'superseded' ? '<span class="badge superseded">已拆分</span>' : '')
      + (conflict ? '<span class="badge conflict">裁定冲突</span>' : '');
    d.onclick = () => { selUnit = u.unit_id; load(); };
    el.appendChild(d);
  }
}

function renderEvents() {
  const el = document.getElementById('events');
  el.innerHTML = '';
  for (const e of [...state.events].reverse()) {
    const d = document.createElement('div');
    d.className = 'ev' + (selSeq === e.seq ? ' sel' : '');
    d.innerHTML = '<span class="seq">#' + e.seq + '</span> ' + e.kind + ' · ' + (e.actor || 'system')
      + ' · ' + JSON.stringify(e.payload).slice(0, 90);
    d.onclick = () => { selSeq = (selSeq === e.seq ? null : e.seq); load(); };
    el.appendChild(d);
  }
}

function renderDiags() {
  document.getElementById('diags').innerHTML = state.diagnostics.map(d =>
    '<div class="diag">⚠ ' + d.position + ': ' + d.message + (d.raw_value ? '（原值 ' + d.raw_value + '）' : '') + '</div>').join('');
  document.getElementById('invs').innerHTML = state.invalidations.map(i =>
    '<div class="inv">✕ ' + i.derived_id + ' 失效: ' + i.reason + ' (scope=' + i.scope + ')</div>').join('');
}

async function renderPanels() {
  const q = '?unit=' + selUnit + '&t=' + selT + (selSeq ? '&seq=' + selSeq : '');
  for (const [id, name] of [['p-wave','waveform'],['p-proj','projection'],['p-acg','acg'],['p-rate','rate'],['p-drift','drift']]) {
    const r = await fetch('/svg/' + name + q);
    document.getElementById(id).innerHTML = await r.text();
  }
  document.querySelectorAll('[data-t]').forEach(el => {
    el.style.cursor = 'pointer';
    el.addEventListener('click', () => { selT = parseFloat(el.getAttribute('data-t')); renderPanels(); });
  });
}

function renderDetail() {
  const u = state.units.find(x => x.unit_id === selUnit);
  if (!u) { document.getElementById('detail').innerHTML = ''; return; }
  let html = '<div>稳定身份 <b>' + u.unit_id + '</b> · 当前簇号 ' + u.cluster_no
    + '（簇号可变，身份不变）· 标签版本 v' + u.label_version + '</div>';
  for (const d of u.derived) {
    html += '<div>' + d.kind + ' · 状态 ' + d.status + ' · 参数版本 ' + d.param_version
      + ' · 输入摘要 ' + d.input_digest.slice(0, 12) + '…';
    if (d.payload && d.payload.judgment_basis) html += '<br>&nbsp;&nbsp;判定依据: ' + d.payload.judgment_basis;
    if (d.payload && d.payload.slope_uv_per_ms !== undefined)
      html += '<br>&nbsp;&nbsp;漂移斜率 ' + d.payload.slope_uv_per_ms.toFixed(6) + ' µV/ms，检出=' + d.payload.detected;
    if (d.payload && d.payload.violations !== undefined)
      html += '<br>&nbsp;&nbsp;不应期违例 ' + d.payload.violations + ' 对（阈值 ' + d.payload.refractory_ms + ' ms）';
    if (d.payload && d.payload.concurrent_events !== undefined)
      html += '<br>&nbsp;&nbsp;并发事件 ' + d.payload.concurrent_events + ' 次（窗口 ±' + d.payload.coprop_window_ms + ' ms）';
    html += '</div>';
  }
  const open = state.candidates.filter(c => c.status === 'open');
  if (open.length) {
    html += '<div class="diag">未唯一决定的合法候选（全部保留）:</div>';
    for (const c of open) html += '<div class="diag">· ' + c.kind + ' @ ' + c.subject_id + ': '
      + JSON.stringify(c.payload).slice(0, 120) + '</div>';
  }
  document.getElementById('detail').innerHTML = html;
}

async function post(url, body) {
  const r = await fetch(url, {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(body)});
  const j = await r.json();
  if (j.type && j.type !== 'ok') { toast('✕ ' + j.type + ': ' + (j.detail || j.position || '')); return null; }
  return j;
}

function showAffected(j) {
  const box = document.getElementById('affected');
  box.style.display = 'block';
  document.getElementById('affected-list').innerHTML =
    '<div class="row">' + (j.summary || '') + '</div>'
    + (j.affected || []).map(a => '<div class="row">→ ' + a + '</div>').join('');
}

async function decide(accept) {
  const u = state.units.find(x => x.unit_id === selUnit);
  const kind = document.getElementById('evidence-kind').value;
  const der = [...u.derived].reverse().find(d => d.kind === kind && d.status === 'ok');
  if (!der) { toast('该单元没有可裁定的 ' + kind); return; }
  const j = await post('/api/adjudicate', {action: accept ? 'accept' : 'reject', subject_id: der.id,
    subject_kind: kind, actor: document.getElementById('actor').value, rationale: accept ? '证据充分' : '证据不足'});
  if (j) { showAffected(j); load(); }
}
async function setWindow() {
  const j = await post('/api/adjudicate', {action:'window', t0: parseFloat(document.getElementById('w0').value),
    t1: parseFloat(document.getElementById('w1').value), actor: document.getElementById('actor').value});
  if (j) { showAffected(j); load(); }
}
async function renumber() {
  const j = await post('/api/adjudicate', {action:'renumber', unit_id: selUnit,
    cluster_no: parseInt(document.getElementById('newno').value), actor: document.getElementById('actor').value});
  if (j) { showAffected(j); load(); }
}
async function splitUnit() {
  const j = await post('/api/adjudicate', {action:'split', unit_id: selUnit,
    t_split: parseFloat(document.getElementById('tsplit').value), actor: document.getElementById('actor').value});
  if (j) { showAffected(j); load(); }
}
async function doImport(input) {
  const f = input.files[0]; if (!f) return;
  const text = await f.text();
  const j = await post('/api/import', JSON.parse(text));
  if (j) { toast('导入完成: ' + j.summary); load(); }
}
async function resetCase() { await fetch('/api/reset', {method:'POST'}); selSeq = null; selUnit = null; load(); }
function toast(msg) {
  const t = document.getElementById('toast');
  t.textContent = msg; t.style.display = 'block';
  setTimeout(() => t.style.display = 'none', 5000);
}
load();
</script>
</body>
</html>
""";
}
