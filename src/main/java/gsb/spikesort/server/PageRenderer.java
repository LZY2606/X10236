package gsb.spikesort.server;

/** The single-page review UI. All business rules live server-side; the page
 *  only renders state and posts decisions. */
public final class PageRenderer {
    private PageRenderer() {}

    public static final String HTML = """
<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<title>脉冲簇谱 · 神经棘波候选、漂移簇与人工裁定谱系</title>
<style>
  body { font-family: ui-sans-serif, system-ui, sans-serif; margin: 0; background: #f1f5f9; color: #0f172a; }
  header { background: #0f172a; color: #e2e8f0; padding: 10px 16px; }
  header h1 { font-size: 16px; margin: 0; }
  header small { color: #94a3b8; }
  .layout { display: grid; grid-template-columns: 1fr 340px; gap: 10px; padding: 10px; }
  .panel { background: #fff; border: 1px solid #e2e8f0; border-radius: 6px; padding: 8px; }
  .panel h2 { font-size: 12px; margin: 0 0 6px; color: #475569; text-transform: uppercase; letter-spacing: .05em; }
  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
  .grid .wide { grid-column: 1 / -1; }
  .spk { cursor: pointer; }
  .spk.hl { stroke: #0f172a; stroke-width: 2; }
  select, button, input { font: inherit; font-size: 12px; }
  button { background: #2563eb; color: #fff; border: 0; border-radius: 4px; padding: 4px 10px; cursor: pointer; }
  button.ghost { background: #e2e8f0; color: #0f172a; }
  button.danger { background: #dc2626; }
  .row { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; margin: 4px 0; }
  .tag { display: inline-block; background: #e2e8f0; border-radius: 3px; padding: 1px 6px; margin: 1px; font-size: 11px; }
  .tag.evi { background: #fef3c7; }
  .tag.err { background: #fee2e2; }
  .tag.ok  { background: #dcfce7; }
  #events { max-height: 180px; overflow: auto; }
  #events div { padding: 2px 4px; cursor: pointer; border-radius: 3px; font-size: 12px; }
  #events div.cur { background: #dbeafe; }
  #events div.conflicted { color: #b91c1c; text-decoration: line-through; }
  pre { font-size: 11px; background: #f8fafc; padding: 6px; border-radius: 4px; overflow: auto; max-height: 160px; }
  .affected { background: #fff7ed; border: 1px solid #fed7aa; }
</style>
</head>
<body>
<header>
  <h1>脉冲簇谱 · 神经棘波候选、漂移簇与人工裁定谱系</h1>
  <small>多通道检测事件 → 单元聚类 → 可回溯的合并 / 拆分 / 漂移证据 · 全部本地，无外部服务</small>
</header>
<div class="layout">
  <div>
    <div class="panel wide">
      <h2>事件时间线（点击回到任一历史事件，恢复当时的依赖版本与可见结论）</h2>
      <div class="row">
        <button class="ghost" id="btn-latest">最新状态</button>
        <span id="seq-label" class="tag ok"></span>
      </div>
      <div id="events"></div>
    </div>
    <div class="grid">
      <div class="panel wide"><h2>波形片段（µV · recording_ms）</h2><div id="v-wave"></div></div>
      <div class="panel"><h2>特征投影</h2><div id="v-feat"></div></div>
      <div class="panel"><h2>自相关</h2><div id="v-acg"></div></div>
      <div class="panel"><h2>发放率</h2><div id="v-rate"></div></div>
      <div class="panel"><h2>漂移时间线</h2><div id="v-drift"></div></div>
    </div>
  </div>
  <div>
    <div class="panel">
      <h2>单元与聚类标签（聚类序号 ≠ 稳定身份）</h2>
      <div id="units"></div>
    </div>
    <div class="panel">
      <h2>裁定（生成新事件，显示受影响下游记录）</h2>
      <div class="row">
        <select id="dec-type">
          <option value="ACCEPT">接受 ACCEPT</option>
          <option value="REJECT">否定 REJECT</option>
          <option value="MERGE">合并 MERGE</option>
          <option value="SPLIT">拆分 SPLIT</option>
          <option value="PARAM_REVISION">参数修订 PARAM_REVISION</option>
          <option value="WINDOW_CHANGE">时间窗 WINDOW_CHANGE</option>
        </select>
        <input id="dec-unit" placeholder="unitId / 参数" size="14">
        <button id="dec-go">提交裁定</button>
      </div>
      <div class="row" id="affected-box" style="display:none">
        <div class="panel affected" style="width:100%">
          <h2>受影响的下游记录</h2>
          <div id="affected"></div>
        </div>
      </div>
    </div>
    <div class="panel">
      <h2>证据事件（机器可读 · 仅证据，不改身份）</h2>
      <div id="evidence"></div>
    </div>
    <div class="panel">
      <h2>诊断（带位置 · 不用空值伪装成功）</h2>
      <div id="diags"></div>
    </div>
    <div class="panel">
      <h2>工作流</h2>
      <div class="row">
        <button class="ghost" id="btn-export">导出</button>
        <button class="ghost" id="btn-import">导入</button>
        <button class="ghost" id="btn-reset">重置案例</button>
      </div>
      <pre id="wf-log"></pre>
    </div>
  </div>
</div>
<script>
let seq = null;           // null = latest
let currentUnit = null;
const $ = id => document.getElementById(id);

async function jget(url) { const r = await fetch(url); return r.json(); }
async function jpost(url, body) {
  const r = await fetch(url, {method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify(body)});
  return r.json();
}

function seqParam() { return seq == null ? '' : ('?seq=' + seq); }

async function loadState() {
  const st = await jget('/api/state' + seqParam());
  $('seq-label').textContent = st.viewSeq == null ? '最新' : ('回看事件 #' + st.viewSeq);
  // events timeline
  const ev = $('events'); ev.innerHTML = '';
  for (const e of st.events) {
    const d = document.createElement('div');
    d.textContent = '#' + e.seq + ' ' + e.type + ' · ' + e.status + ' · ' + e.id.slice(0,12);
    if (e.status === 'conflicted') d.classList.add('conflicted');
    if (st.viewSeq === e.seq) d.classList.add('cur');
    d.onclick = () => { seq = e.seq; refresh(); };
    ev.appendChild(d);
  }
  // units + labels
  const un = $('units'); un.innerHTML = '';
  for (const u of st.units) {
    const labels = st.clusterLabels.filter(l => l.unitId === u.id)
        .map(l => 'cluster#' + l.clusterIndex + '@' + l.featureSetId).join(' ');
    const d = document.createElement('div');
    d.innerHTML = '<span class="tag">' + u.id + '</span> <span class="tag">' + u.status +
        '</span> <small>' + labels + '</small>';
    d.style.cursor = 'pointer';
    d.onclick = () => { currentUnit = u.id; $('dec-unit').value = u.id; refresh(); };
    un.appendChild(d);
  }
  // evidence: dedupe by kind+unit for display; full machine-readable list via /api/state
  const seen = new Set();
  const evTags = [];
  for (const e of st.evidence) {
    const k = e.kind + '|' + (e.unitId || '');
    if (seen.has(k)) continue;
    seen.add(k);
    evTags.push('<span class="tag evi" title="' + (e.basisJson||'').replaceAll('"','&quot;') + '">' +
      e.kind + (e.unitId ? ' · ' + e.unitId : '') + '</span>');
  }
  $('evidence').innerHTML = evTags.join('');
  // diagnostics
  $('diags').innerHTML = st.diagnostics.map(d =>
    '<span class="tag ' + (d.severity === 'ERROR' ? 'err' : 'evi') + '" title="' +
    (d.message||'').replaceAll('"','&quot;') + '">' + d.code + ' @ ' + d.location + '</span>').join('');
  if (!currentUnit && st.units.length) currentUnit = st.units[0].id;
}

async function loadSvg() {
  const q = seqParam();
  const sep = q ? '&' : '?';
  $('v-wave').innerHTML = await (await fetch('/svg/waveforms' + q)).text();
  $('v-feat').innerHTML = await (await fetch('/svg/features' + q)).text();
  if (currentUnit) {
    $('v-acg').innerHTML   = await (await fetch('/svg/acg'   + q + sep + 'unit=' + currentUnit)).text();
    $('v-rate').innerHTML  = await (await fetch('/svg/rate'  + q + sep + 'unit=' + currentUnit)).text();
    $('v-drift').innerHTML = await (await fetch('/svg/drift' + q + sep + 'unit=' + currentUnit)).text();
  }
  // linked highlighting across all five views
  document.querySelectorAll('.spk').forEach(el => {
    el.addEventListener('click', () => {
      const id = el.getAttribute('data-spike-id');
      document.querySelectorAll('.spk').forEach(x =>
        x.classList.toggle('hl', x.getAttribute('data-spike-id') === id));
    });
  });
}

async function refresh() { await loadState(); await loadSvg(); }

$('btn-latest').onclick = () => { seq = null; refresh(); };

$('dec-go').onclick = async () => {
  const type = $('dec-type').value;
  const arg = $('dec-unit').value.trim();
  let payload = {};
  if (type === 'ACCEPT' || type === 'REJECT') payload = {unitId: arg, rationale: 'reviewer action from UI'};
  else if (type === 'MERGE') payload = {fromUnitIds: [arg], toUnitId: arg + '-merged'};
  else if (type === 'SPLIT') payload = {unitId: arg, intoUnitIds: [arg + '-1', arg + '-2'], sharedSpikeIds: [], assignments: {}};
  else if (type === 'PARAM_REVISION') payload = {thresholdUv: 55.0, refractoryMs: 1.5};
  else if (type === 'WINDOW_CHANGE') payload = {windowStartMs: 5000.0, windowEndMs: 55000.0};
  const r = await jpost('/api/decide', {type, payload});
  $('affected-box').style.display = 'block';
  $('affected').innerHTML =
    '<div>事件: ' + (r.event ? r.event.id + ' (#' + r.event.seq + ' ' + r.event.status + ')' : '失败') + '</div>' +
    '<div>受影响单元: ' + (r.affectedUnitIds || []).map(u => '<span class="tag">' + u + '</span>').join('') + '</div>' +
    '<div>受影响产物: ' + (r.affectedArtifactIds || []).map(a => '<span class="tag err">' + a + '</span>').join('') + '</div>' +
    (r.diagnostics || []).map(d => '<div class="tag err">' + d.code + ' @ ' + d.location + '</div>').join('');
  seq = null;
  refresh();
};

$('btn-export').onclick = async () => {
  const r = await fetch('/api/export');
  const text = await r.text();
  $('wf-log').textContent = '导出完成 · ' + text.length + ' bytes · digest 见 manifest';
};
$('btn-import').onclick = async () => {
  const exported = await (await fetch('/api/export')).text();
  const r = await jpost('/api/import', JSON.parse(exported));
  $('wf-log').textContent = '导入结果: ' + r.status + ' · rows=' + r.imported;
  refresh();
};
$('btn-reset').onclick = async () => { await jpost('/api/reset', {}); seq = null; currentUnit = null; refresh(); };

refresh();
</script>
</body>
</html>
""";
}
