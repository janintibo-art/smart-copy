'use strict';

const $ = (id) => document.getElementById(id);
const api = window.api;

const STATUS = {
  pending: 'En attente',
  copying: 'Copie',
  verifying: 'Vérification',
  done: 'Copié',
  skipped: 'Ignoré (déjà présent)',
  failed: 'Échec',
  cancelled: 'Annulé',
};

const POLICY_HELP = {
  rename: 'Garde les deux fichiers : la copie devient « nom (1) ».',
  overwrite: "Remplace le fichier existant, seulement une fois la nouvelle copie terminée (et vérifiée si l'option est active).",
  skip: 'Ne copie pas un fichier si un fichier du même nom existe déjà.',
  sync: 'Ignore les fichiers identiques (contenu comparé par empreinte), remplace les autres.',
};

const rows = new Map();
let history = [];

function fmtBytes(b) {
  const v = Number(b) || 0;
  if (v >= 1024 ** 3) return (v / 1024 ** 3).toFixed(2).replace('.', ',') + ' Go';
  if (v >= 1024 ** 2) return (v / 1024 ** 2).toFixed(1).replace('.', ',') + ' Mo';
  if (v >= 1024) return (v / 1024).toFixed(0) + ' Ko';
  return Math.round(v) + ' o';
}
const fmtSpeed = (b) => fmtBytes(b) + '/s';
const fmtMBps = (v) => Math.round(v || 0) + ' Mo/s';

function fmtDur(ms) {
  if (ms == null || ms < 0) return '--:--';
  const t = Math.floor(ms / 1000);
  const h = Math.floor(t / 3600);
  const m = Math.floor((t % 3600) / 60);
  const s = t % 60;
  const p = (n) => String(n).padStart(2, '0');
  return h > 0 ? `${h}:${p(m)}:${p(s)}` : `${p(m)}:${p(s)}`;
}

function show(el, visible) {
  el.classList.toggle('hidden', !visible);
}

// ---------- File d'attente ----------

function makeRow(it) {
  const row = document.createElement('div');
  row.className = 'item';
  const main = document.createElement('div');
  main.className = 'item-main';
  const name = document.createElement('div');
  name.className = 'item-name';
  name.textContent = it.name;
  name.title = it.name;
  const rel = document.createElement('div');
  rel.className = 'item-path';
  rel.textContent = it.rel || '';
  main.append(name, rel);
  const size = document.createElement('div');
  size.className = 'item-size';
  const status = document.createElement('div');
  status.className = 'item-status';
  const bar = document.createElement('div');
  bar.className = 'bar small';
  const fill = document.createElement('div');
  fill.className = 'fill';
  bar.append(fill);
  const hash = document.createElement('div');
  hash.className = 'item-hash hidden';
  row.append(main, size, status, bar, hash);
  const rec = { data: it, row, size, status, fill, hash };
  rows.set(it.id, rec);
  paint(rec);
  return row;
}

function paint(rec) {
  const it = rec.data;
  rec.row.dataset.status = it.status;
  const verifying = it.status === 'verifying';
  const done = it.copied || 0;
  let f;
  if (verifying) f = it.size > 0 ? Math.min(1, (it.verified || 0) / it.size) : 0;
  else f = it.size > 0 ? Math.min(1, done / it.size) : it.status === 'done' ? 1 : 0;
  if (it.status === 'copying') rec.size.textContent = `${fmtBytes(done)} / ${fmtBytes(it.size)}`;
  else if (verifying) rec.size.textContent = `Relecture ${fmtBytes(it.verified || 0)} / ${fmtBytes(it.size)}`;
  else rec.size.textContent = fmtBytes(it.size);
  let label = STATUS[it.status] || it.status;
  if (it.status === 'copying') label = `${(f * 100).toFixed(0)} %`;
  if (verifying) label = `Vérif. ${(f * 100).toFixed(0)} %`;
  if (it.status === 'done' && it.verified > 0) label = 'Copié · vérifié';
  if (it.status === 'failed' && it.error) label = `Échec : ${it.error}`;
  const showHash = (it.status === 'done' || it.status === 'skipped') && it.hash;
  show(rec.hash, !!showHash);
  rec.hash.textContent = showHash ? `SHA-256 ${it.hash}` : '';
  rec.status.textContent = label;
  rec.status.title = label;
  rec.fill.style.width = (f * 100).toFixed(1) + '%';
}

function rebuildQueue(items) {
  rows.clear();
  const q = $('queue');
  q.textContent = '';
  const frag = document.createDocumentFragment();
  for (const it of items) frag.appendChild(makeRow(it));
  q.appendChild(frag);
}

// ---------- Graphiques ----------

function drawChart() {
  const canvas = $('chart');
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth;
  const h = canvas.clientHeight;
  if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
  }
  const ctx = canvas.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);

  show($('chartEmpty'), history.length < 2);
  if (history.length < 2) {
    $('chartMax').textContent = '';
    return;
  }

  const padX = 10;
  const padY = 14;
  const iw = w - padX * 2;
  const ih = h - padY * 2;
  const max = Math.max(1, ...history);
  $('chartMax').textContent = 'max ' + fmtSpeed(max);

  ctx.strokeStyle = 'rgba(255,255,255,0.07)';
  ctx.lineWidth = 1;
  for (let k = 1; k <= 3; k++) {
    const y = padY + (ih * k) / 4;
    ctx.beginPath();
    ctx.moveTo(padX, y);
    ctx.lineTo(w - padX, y);
    ctx.stroke();
  }

  const step = iw / 239;
  const startX = padX + iw - step * (history.length - 1);
  const pts = history.map((v, i) => [startX + i * step, padY + ih - (v / max) * ih]);

  const grad = ctx.createLinearGradient(0, padY, 0, padY + ih);
  grad.addColorStop(0, 'rgba(61,220,151,0.35)');
  grad.addColorStop(1, 'rgba(61,220,151,0)');
  ctx.beginPath();
  ctx.moveTo(pts[0][0], padY + ih);
  for (const [x, y] of pts) ctx.lineTo(x, y);
  ctx.lineTo(pts[pts.length - 1][0], padY + ih);
  ctx.closePath();
  ctx.fillStyle = grad;
  ctx.fill();

  ctx.beginPath();
  pts.forEach(([x, y], i) => (i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y)));
  ctx.strokeStyle = '#3ddc97';
  ctx.lineWidth = 2;
  ctx.lineJoin = 'round';
  ctx.stroke();
}

function renderBlockChart(results, best) {
  const box = $('blockChart');
  box.textContent = '';
  const max = Math.max(0.001, ...results.map((r) => r.mbPerSec));
  for (const r of results) {
    const row = document.createElement('div');
    row.className = 'block-row' + (r.blockSize === best ? ' best' : '');
    const label = document.createElement('div');
    label.textContent = fmtBytes(r.blockSize);
    const bar = document.createElement('div');
    bar.className = 'bar';
    const fill = document.createElement('div');
    fill.className = 'fill ' + (r.blockSize === best ? 'orange' : 'blue');
    fill.style.width = ((r.mbPerSec / max) * 100).toFixed(1) + '%';
    bar.append(fill);
    const val = document.createElement('div');
    val.className = 'val';
    val.textContent = fmtMBps(r.mbPerSec);
    row.append(label, bar, val);
    box.append(row);
  }
}

// ---------- Résumé ----------

function driveLine(prefix, d) {
  if (!d) return '';
  const parts = [d.label, d.kind];
  if (d.model) parts.push(d.model);
  if (d.fs) parts.push(d.fs);
  return `${prefix} : ${parts.filter(Boolean).join(' — ')}`;
}

function renderSummary(s) {
  // Destination
  $('destName').textContent = s.dest || 'Aucun dossier choisi';
  $('destName').title = s.dest || '';
  const d = s.destInfo;
  $('destKind').textContent = d ? [d.kind, d.model].filter(Boolean).join(' — ') : '';
  if (d && d.totalBytes > 0) {
    $('destFree').textContent = `Libre : ${fmtBytes(d.freeBytes)} sur ${fmtBytes(d.totalBytes)}`;
    $('destBar').style.width = ((1 - d.freeBytes / d.totalBytes) * 100).toFixed(1) + '%';
  } else {
    $('destFree').textContent = '';
    $('destBar').style.width = '0%';
  }
  $('btnDest').disabled = s.running;
  $('btnOpenDest').disabled = !s.dest;

  // Analyse
  const a = s.analysis;
  show($('analysisStep'), !!s.analyzing);
  $('analysisStep').textContent = s.analyzing ? `En cours : ${s.analyzing}` : '';
  show($('analysisIntro'), !a && !s.analyzing);
  show($('analysisResult'), !!a && !s.analyzing);
  if (a) {
    $('aRead').textContent = a.sourceReadMBps > 0 ? fmtMBps(a.sourceReadMBps) : '—';
    $('aWrite').textContent = fmtMBps(Math.max(...a.writeResults.map((r) => r.mbPerSec)));
    $('aBlock').textContent = fmtBytes(a.bestBlock);
    $('aLatency').textContent = a.smallFileLatencyMs.toFixed(1).replace('.', ',') + ' ms';
    $('aWorkers').textContent = String(a.workers);
    $('aSource').textContent = driveLine('Source', a.source);
    $('aDest').textContent = driveLine('Destination', a.destination);
    show($('aSame'), !!a.sameDrive);
    renderBlockChart(a.writeResults, a.bestBlock);
  }
  $('btnAnalyze').textContent = a ? "Relancer l'analyse" : 'Analyser';
  $('btnAnalyze').disabled = !s.dest || s.running || !!s.analyzing;

  // Progression
  const frac = s.total > 0 ? Math.min(1, s.copied / s.total) : 0;
  $('pct').textContent = (frac * 100).toFixed(1).replace('.', ',');
  $('mainBar').style.width = (frac * 100).toFixed(2) + '%';
  $('bytesLine').textContent = `${fmtBytes(s.copied)} sur ${fmtBytes(s.total)}`;
  $('sSpeed').textContent = fmtSpeed(s.speed);
  $('sAvg').textContent = fmtSpeed(s.avg);
  $('sPeak').textContent = fmtSpeed(s.peak);
  $('sEta').textContent = fmtDur(s.etaMs);
  $('sElapsed').textContent = fmtDur(s.elapsedMs);
  $('sFiles').textContent = `${s.filesDone} / ${s.filesTotal}`;
  $('sBlock').textContent = fmtBytes(s.blockSize);
  $('sActive').textContent = `${s.active} / ${s.workers}`;
  $('sFailed').textContent = String(s.filesFailed);
  $('sSkipped').textContent = String(s.filesSkipped);
  $('optVerify').checked = !!s.verify;
  for (const b of document.querySelectorAll('#optPolicy button')) {
    b.classList.toggle('active', b.dataset.policy === s.policy);
  }
  $('policyHelp').textContent = POLICY_HELP[s.policy] || '';
  $('sFailed').className = s.filesFailed ? 'accent-orange' : '';

  const status = $('status');
  status.className = 'status';
  if (s.running && s.paused) {
    status.textContent = 'En pause';
    status.classList.add('paused');
  } else if (s.running) {
    status.textContent = 'Copie en cours';
    status.classList.add('running');
  } else if (s.filesTotal > 0 && s.filesDone + s.filesSkipped === s.filesTotal) {
    status.textContent = 'Terminé';
    status.classList.add('done');
  } else {
    status.textContent = 'Prêt';
  }

  show($('btnStart'), !s.running);
  show($('btnClear'), !s.running);
  show($('btnPause'), s.running);
  show($('btnCancel'), s.running);
  $('btnStart').disabled = !s.dest || s.filesDone + s.filesSkipped === s.filesTotal || !!s.analyzing;
  $('btnClear').disabled = s.filesTotal === 0;
  $('btnPause').textContent = s.paused ? 'Reprendre' : 'Pause';

  show($('message'), !!s.message);
  $('messageText').textContent = s.message || '';

  $('queueCount').textContent = s.filesTotal ? `(${s.filesTotal})` : '';
  show($('queueHint'), s.filesTotal === 0 || s.running);

  history = s.history || [];
  drawChart();
}

// ---------- Événements ----------

api.onState((state) => {
  if (state.items) rebuildQueue(state.items);
  for (const c of state.changes) {
    const rec = rows.get(c.id);
    if (!rec) continue;
    Object.assign(rec.data, c);
    paint(rec);
  }
  renderSummary(state.summary);
});

$('btnFiles').addEventListener('click', () => api.pickFiles());
$('btnFolder').addEventListener('click', () => api.pickFolder());
$('btnDest').addEventListener('click', () => api.pickDest());
$('btnOpenDest').addEventListener('click', () => api.openDest());
$('btnAnalyze').addEventListener('click', () => api.analyze());
$('btnStart').addEventListener('click', () => api.start());
$('btnPause').addEventListener('click', () => api.togglePause());
$('btnCancel').addEventListener('click', () => api.cancel());
$('btnClear').addEventListener('click', () => api.clear());
$('btnMsgOk').addEventListener('click', () => api.clearMessage());
$('optVerify').addEventListener('change', (e) => api.setOptions({ verify: e.target.checked }));
for (const b of document.querySelectorAll('#optPolicy button')) {
  b.addEventListener('click', () => api.setOptions({ policy: b.dataset.policy }));
}

let dragDepth = 0;
document.addEventListener('dragenter', (e) => {
  e.preventDefault();
  dragDepth++;
  document.body.classList.add('dragging');
});
document.addEventListener('dragover', (e) => e.preventDefault());
document.addEventListener('dragleave', () => {
  dragDepth = Math.max(0, dragDepth - 1);
  if (dragDepth === 0) document.body.classList.remove('dragging');
});
document.addEventListener('drop', (e) => {
  e.preventDefault();
  dragDepth = 0;
  document.body.classList.remove('dragging');
  const paths = Array.from(e.dataTransfer.files)
    .map((f) => api.pathForFile(f))
    .filter(Boolean);
  if (paths.length) api.addPaths(paths);
});

window.addEventListener('resize', drawChart);
api.ready();
