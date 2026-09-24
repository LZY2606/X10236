let STATE = null;
let selectedEvent = null;

async function refresh() {
  STATE = await (await fetch('/api/state')).json();
  render();
}

function render() {
  const b = STATE.basis;
  document.getElementById('basis').textContent =
    `坐标 ${b.coordinateUnit} (原点 ${b.coordinateOrigin}) · 时间基准 t0=${b.timeBasisT0} · ` +
    `幅度 ${b.amplitudeUnit} · 有效时间窗 [${b.windowSeconds[0]}, ${b.windowSeconds[1]}) 秒（半开）`;
  document.getElementById('params').textContent =
    `标签集 ${STATE.params.activeLabelSet} · 阈值 th-v${STATE.params.activeThresholdVersion} · ` +
    `并发容差 ${STATE.params.concurrencyTolSeconds}s · 不应期 ${STATE.params.refractorySeconds}s · ` +
    `源修订 ${STATE.params.sourcePatch || '无'}`;

  // 事件表
  const rows = STATE.events.map(e => {
    const amp = e.valid ? `${e.amplitudeSiUv} uV` : `${e.amplitudeRaw} ⚠ ${e.invalidReason}`;
    return `<tr class="evt ${e.valid ? '' : 'invalid'} ${e.eventId === selectedEvent ? 'sel' : ''}" data-id="${e.eventId}">
      <td>${short(e.eventId)}</td><td>${e.timeSeconds.toFixed(4)}s</td><td>ch${e.channel}</td>
      <td>${amp}</td><td>${e.supersedesOrder ? 'v' + e.supersedesOrder : ''}</td></tr>`;
  }).join('');
  document.getElementById('evtTable').innerHTML =
    `<tr><th>事件</th><th>时间</th><th>通道</th><th>幅度</th><th>修订</th></tr>${rows}`;

  document.querySelectorAll('tr.evt').forEach(tr => tr.onclick = () => selectEvent(tr.dataset.id));
  document.getElementById('paneProbe').innerHTML =
    `<h2 style="font-size:11px;margin:0 0 4px">探针几何（页面联动）</h2>` +
    `<img src="/svg/probe" style="width:300px"/>`;
  document.getElementById('paneProj').innerHTML =
    `<h2 style="font-size:11px;margin:0 0 4px">特征投影</h2><img src="/svg/projection"/>`;
  document.getElementById('paneDrift').innerHTML =
    `<h2 style="font-size:11px;margin:0 0 4px">漂移时间线</h2><img src="/svg/drift"/>`;
  renderClusterPanes();
  renderNodes();
  renderLineage();
  renderHistory();
}

function renderClusterPanes() {
  // 自相关/发放率：取当前激活标签集下第一个有派生节点的簇，从 derivedNodes payload 提取
  const acNode = STATE.derivedNodes.find(n => n.kind === 'AUTOCORRELATION');
  const rateNode = STATE.derivedNodes.find(n => n.kind === 'RATE_VIEW');
  document.getElementById('paneAC').innerHTML = acNode
    ? `<h2 style="font-size:11px;margin:0 0 4px">自相关 ${acNode.payload.clusterKey}</h2>` +
      svgImgAc(acNode)
    : '<div class="muted">暂无自相关</div>';
  document.getElementById('paneRate').innerHTML = rateNode
    ? `<h2 style="font-size:11px;margin:0 0 4px">发放率 ${rateNode.payload.clusterKey} [Hz]</h2>` +
      svgImgRate(rateNode)
    : '<div class="muted">暂无发放率</div>';
}

function svgImgAc(n) {
  return `<img src="/svg/ac/${encodeURIComponent(n.nodeId)}"/>`;
}
function svgImgRate(n) {
  return `<img src="/svg/rate/${encodeURIComponent(n.nodeId)}"/>`;
}

async function selectEvent(id) {
  selectedEvent = id;
  render();
  document.getElementById('paneWave').innerHTML =
    `<h2 style="font-size:11px;margin:0 0 4px">波形片段 ${short(id)}</h2>` +
    `<img src="/svg/waveform/${encodeURIComponent(eventWf(id))}"/>`;
}
function eventWf(id) { return STATE.events.find(e => e.eventId === id)?.waveformId || ''; }

function renderNodes() {
  document.getElementById('nodes').innerHTML = STATE.derivedNodes.map(n => {
    const p = n.payload;
    const ident = p.clusterKey ? p.clusterKey : (p.groupId || p.eventId || '');
    const actions = (n.kind === 'REFRACTORY_EVIDENCE' || n.kind === 'AMPLITUDE_DRIFT'
        || n.kind === 'MULTICHANNEL_PROPAGATION' || n.kind === 'CONCURRENT_GROUP')
      ? `<button data-acc="${n.nodeId}">接受证据</button>
         <button class="danger" data-rej="${n.nodeId}">否定</button>` : '';
    return `<div style="margin-bottom:6px">
      <span class="tag ${n.status}">${n.status}</span>
      <b>${n.kind}</b> ${ident}
      <div class="muted">${short(n.nodeId)} · 参数 ${short(n.paramVersion)}</div>
      <div class="muted">${summarize(n)}</div>${actions}</div>`;
  }).join('');
  document.querySelectorAll('[data-acc]').forEach(b => b.onclick = () =>
    adjudicate('accept', b.dataset.acc));
  document.querySelectorAll('[data-rej]').forEach(b => b.onclick = () =>
    adjudicate('reject', b.dataset.rej));
}

function summarize(n) {
  const p = n.payload;
  if (n.kind === 'AMPLITUDE_DRIFT')
    return `早期 ${p.drift.earlyMedianUv}uV → 晚期 ${p.drift.lateMedianUv}uV，斜率 ${p.drift.slopeUvPerMin} uV/min`;
  if (n.kind === 'REFRACTORY_EVIDENCE')
    return p.violations.map(v => `ISI ${v.isiSeconds}s < 不应期 ${v.refractorySeconds}s (${short(v.eventIdA)},${short(v.eventIdB)})`).join('；');
  if (n.kind === 'MULTICHANNEL_PROPAGATION')
    return `跨 ${p.channelCount} 通道，跨度 ${p.spanSeconds}s，成员 ${p.members.map(short).join(',')}`;
  if (n.kind === 'LOCAL_FAILURE')
    return `局部失效：${p.reason}；原始行保留：${(p.rawLine || '').slice(0, 60)}`;
  if (n.kind === 'CLUSTER_VIEW')
    return `${p.eventCount} 个事件，通道 [${p.channels}]，时间窗 [${p.timeWindow[0]},${p.timeWindow[1]})s；失效 ${(p.invalidReasons||[]).length}`;
  return '';
}

function renderLineage() {
  document.getElementById('lineage').innerHTML = STATE.lineage.map(e =>
    `<div><span class="tag ${e.relation === 'RENUMBER' ? 'EVIDENCE' : 'OK'}">${e.relation}</span>
      v${e.fromLabelVersion}:${e.fromClusterKey} → v${e.toLabelVersion}:${e.toClusterKey}
      ${e.sharedEventId ? '共享 ' + short(e.sharedEventId) : ''}</div>`).join('')
    || '<div class="muted">暂无谱系边</div>';
}

function renderHistory() {
  document.getElementById('history').innerHTML = STATE.history.map(h =>
    `<div class="hist" data-seq="${h.seq}">#${h.seq} <b>${h.kind}</b>
      <span class="muted">${h.at.slice(11, 19)}</span></div>`).join('');
  document.querySelectorAll('.hist').forEach(el => el.onclick = async () => {
    const snap = await (await fetch('/api/history/' + el.dataset.seq)).json();
    alert(`回到第 ${snap.seq} 个事件\n参数版本：${JSON.stringify(snap.params, null, 1)}\n`
        + `当时可见派生节点数：${snap.visibleNodes.length}（历史不被最新计算覆盖）`);
  });
}

async function postJson(url, body) {
  const r = await fetch(url, {method: 'POST', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(body)});
  const data = await r.json();
  if (!data.ok) { alert(`${data.failureType}\n` + JSON.stringify(data.diagnostics, null, 2)); return null; }
  showDownstream(data);
  await refresh();
  return data;
}
function showDownstream(d) {
  const el = document.getElementById('downstream');
  el.style.display = 'block';
  el.innerHTML = `状态 <b>${d.state}</b> · 受影响下游簇：${
    (d.affectedClusters || []).join(', ') || '（无）'}`;
}
async function adjudicate(verdict, nodeId) {
  await postJson('/api/adjudicate', {verdict, nodeId, comment: 'review-panel'});
}

document.querySelectorAll('[data-ls]').forEach(b => b.onclick = () =>
  postJson('/api/labels', {labelSetId: b.dataset.ls}));
document.querySelectorAll('[data-thr]').forEach(b => b.onclick = () =>
  postJson('/api/threshold', {version: Number(b.dataset.thr)}));
document.getElementById('winApply').onclick = () =>
  postJson('/api/window', {start: Number(winS.value), end: Number(winE.value)});
document.getElementById('winReset').onclick = () =>
  postJson('/api/window', {start: 0, end: 1e12});
document.getElementById('patchE062').onclick = () => {
  const id = STATE.events.find(e => e.eventId.endsWith(''))?.eventId;
  // e062 的稳定 id 由导入层生成；从状态里按原始引用定位
  const target = STATE.events.find(x => x.waveformId && x.timeSeconds === 17.5);
  if (target) postJson('/api/patch', {eventId: target.eventId, amplitude: '92', unit: 'uV'});
};

function short(s) { return typeof s === 'string' ? s.slice(0, 10) + '…' : s; }
refresh();
