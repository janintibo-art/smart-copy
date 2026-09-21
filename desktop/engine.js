'use strict';

const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const crypto = require('crypto');
const EventEmitter = require('events');
const { analyze, describeDrive } = require('./analyzer');
const { fmtBytes } = require('./format');

const MiB = 1024 * 1024;
const MIN_BLOCK = 256 * 1024;
const MAX_BLOCK = 32 * MiB;
const BIG_FILE = 32 * MiB;
const TUNE_WINDOW = 64 * MiB;
const HISTORY_SIZE = 240;

class CancelError extends Error {}

/** Ajuste la taille de bloc en continu d'après le débit mesuré. */
class Tuner {
  constructor(start) {
    this.current = Math.min(MAX_BLOCK, Math.max(MIN_BLOCK, start));
    this.direction = 1;
    this.windowBytes = 0;
    this.windowStart = Date.now();
    this.lastRate = 0;
  }

  onBytes(n) {
    this.windowBytes += n;
    const elapsed = Date.now() - this.windowStart;
    if (this.windowBytes < TUNE_WINDOW || elapsed < 700) return null;
    const rate = this.windowBytes / elapsed;
    if (this.lastRate > 0 && rate < this.lastRate * 0.97) this.direction = -this.direction;
    this.lastRate = rate;
    let next = this.direction > 0 ? this.current * 2 : this.current / 2;
    if (next > MAX_BLOCK || next < MIN_BLOCK) {
      this.direction = -this.direction;
      next = this.current;
    }
    this.current = next;
    this.windowBytes = 0;
    this.windowStart = Date.now();
    return this.current;
  }
}

async function openUnique(target) {
  const dir = path.dirname(target);
  const ext = path.extname(target);
  const base = path.basename(target, ext);
  for (let n = 0; n < 10000; n++) {
    const candidate = n === 0 ? target : path.join(dir, `${base} (${n})${ext}`);
    try {
      const fh = await fsp.open(candidate, 'wx');
      return { fh, file: candidate };
    } catch (e) {
      if (e.code !== 'EEXIST') throw e;
    }
  }
  throw new Error('trop de fichiers portant le même nom');
}

async function writeAll(fh, buf, len) {
  let off = 0;
  while (off < len) {
    const { bytesWritten } = await fh.write(buf, off, len - off, null);
    off += bytesWritten;
  }
}

const POLICIES = ['rename', 'overwrite', 'skip', 'sync'];
const MODES = ['copy', 'move'];

function samePath(a, b) {
  const x = path.resolve(a);
  const y = path.resolve(b);
  return process.platform === 'win32' ? x.toLowerCase() === y.toLowerCase() : x === y;
}

function isInside(root, p) {
  const rel = path.relative(root, p);
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
}

async function freeName(target) {
  const dir = path.dirname(target);
  const ext = path.extname(target);
  const base = path.basename(target, ext);
  for (let n = 1; n < 10000; n++) {
    const candidate = path.join(dir, `${base} (${n})${ext}`);
    if (!(await statOrNull(candidate))) return candidate;
  }
  throw new Error('trop de fichiers portant le même nom');
}

/** Après un déplacement : recrée à la destination les dossiers vidés (structure conservée,
 *  dossiers vides compris) puis supprime les dossiers source vides, du plus profond au moins profond. */
async function removeEmptyDirs(src, dst) {
  let entries;
  try {
    entries = await fsp.readdir(src, { withFileTypes: true });
  } catch {
    return;
  }
  for (const e of entries) {
    if (e.isDirectory()) await removeEmptyDirs(path.join(src, e.name), path.join(dst, e.name));
  }
  try {
    if ((await fsp.readdir(src)).length === 0) {
      await fsp.mkdir(dst, { recursive: true });
      await fsp.rmdir(src);
    }
  } catch {}
}

async function statOrNull(p) {
  try {
    return await fsp.stat(p);
  } catch {
    return null;
  }
}

function itemView(it) {
  return {
    id: it.id,
    name: it.name,
    rel: it.rel.join(' / '),
    size: it.size,
    copied: it.copied,
    verified: it.verified,
    hash: it.hash,
    status: it.status,
    error: it.error,
    moved: it.moved,
    instant: it.instant,
    warning: it.warning,
  };
}

class CopyEngine extends EventEmitter {
  constructor() {
    super();
    this.items = [];
    this.nextId = 1;
    this.firstPending = 0;
    this.dest = null;
    this.destInfo = null;
    this.analysis = null;
    this.analyzing = null;
    this.message = null;

    this.running = false;
    this.paused = false;
    this.cancelled = false;
    this.pauseWaiters = [];

    this.blockSize = 4 * MiB;
    this.workers = 2;
    this.liveWorkers = 0;
    this.active = 0;
    this.bigBusy = false;

    this.copied = 0;
    this.elapsedMs = 0;
    this.smoothed = 0;
    this.peak = 0;
    this.history = [];
    this.timer = null;
    this.lastTick = 0;
    this.lastBytes = 0;
    this.half = false;
    this.dirty = new Set();

    this.verify = true;
    this.policy = 'rename';
    this.mode = 'copy';
    this.sourceRoots = [];
    this.completing = false;
  }

  // ---------- Options ----------

  setOptions(opts) {
    if (opts && typeof opts.verify === 'boolean') this.verify = opts.verify;
    if (opts && POLICIES.includes(opts.policy)) this.policy = opts.policy;
    if (opts && MODES.includes(opts.mode) && !this.running) this.mode = opts.mode;
    this.emitState();
  }

  getOptions() {
    return { verify: this.verify, policy: this.policy, mode: this.mode };
  }

  // ---------- Destination ----------

  async setDestination(dir) {
    if (this.running) return;
    this.dest = dir;
    this.analysis = null;
    this.destInfo = null;
    this.emitState();
    this.destInfo = await describeDrive(dir);
    this.emitState();
  }

  // ---------- Ajout (possible pendant la copie) ----------

  async addPaths(paths) {
    const found = [];
    for (const p of paths) {
      try {
        const st = await fsp.stat(p);
        if (st.isDirectory()) {
          this.sourceRoots.push({ src: p, name: path.basename(p) });
          await this.walk(p, [path.basename(p)], found);
        }
        else if (st.isFile()) found.push(this.makeItem(p, st, []));
      } catch (e) {
        this.message = `Ignoré : ${p} (${e.code || e.message})`;
      }
    }
    for (const it of found) this.items.push(it);
    if (this.running) this.spawnWorkers();
    this.emitState(true);
    return found.length;
  }

  async walk(dir, rel, out) {
    let entries;
    try {
      entries = await fsp.readdir(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const full = path.join(dir, e.name);
      if (e.isDirectory()) await this.walk(full, rel.concat(e.name), out);
      else if (e.isFile()) {
        try {
          out.push(this.makeItem(full, await fsp.stat(full), rel));
        } catch {}
      }
    }
  }

  makeItem(p, st, rel) {
    return {
      id: this.nextId++,
      src: p,
      name: path.basename(p),
      size: st.size,
      atime: st.atime,
      mtime: st.mtime,
      rel,
      copied: 0,
      verified: 0,
      hash: null,
      status: 'pending',
      error: null,
      holdsBig: false,
      moved: false,
      instant: false,
      warning: null,
    };
  }

  // ---------- Analyse ----------

  async analyze() {
    if (!this.dest) {
      this.message = "Choisissez d'abord un dossier de destination.";
      return this.emitState();
    }
    if (this.analyzing || this.running) return;
    const sources = this.items.filter((i) => i.status === 'pending').map((i) => ({ path: i.src, size: i.size }));
    this.analyzing = 'Préparation…';
    this.emitState();
    try {
      const r = await analyze(sources, this.dest, (step) => {
        this.analyzing = step;
        this.emitState();
      });
      this.analysis = r;
      this.destInfo = r.destination;
      this.blockSize = r.bestBlock;
      this.workers = r.workers;
      this.message = `Analyse terminée : bloc de ${fmtBytes(r.bestBlock)}, ${r.workers} flux parallèles.`;
    } catch (e) {
      this.message = `Analyse impossible : ${e.message}`;
    }
    this.analyzing = null;
    this.emitState();
  }

  // ---------- Pilotage ----------

  async start() {
    if (this.running || this.analyzing) return;
    if (!this.dest) {
      this.message = "Choisissez d'abord un dossier de destination.";
      return this.emitState();
    }
    for (const it of this.items) {
      if (it.status === 'failed' || it.status === 'cancelled') {
        it.status = 'pending';
        it.error = null;
        it.verified = 0;
        this.dirty.add(it);
      }
    }
    this.firstPending = 0;
    if (!this.items.some((i) => i.status === 'pending')) {
      this.message = 'Rien à copier : ajoutez des fichiers.';
      return this.emitState();
    }
    if (this.mode === 'move' && this.sourceRoots.some((r) => isInside(r.src, this.dest))) {
      this.message = 'Impossible de déplacer un dossier dans lui-même : choisissez une autre destination.';
      return this.emitState();
    }
    if (!this.analysis) await this.analyze();

    this.running = true;
    this.paused = false;
    this.cancelled = false;
    this.message = null;
    this.lastTick = Date.now();
    this.lastBytes = this.copied;
    this.timer = setInterval(() => this.tick(), 250);
    this.spawnWorkers();
    if (this.liveWorkers === 0) this.complete();
    this.emitState();
  }

  async complete() {
    if (this.completing) return;
    this.completing = true;
    try {
      if (this.mode === 'move' && !this.cancelled) {
        for (const r of this.sourceRoots) await removeEmptyDirs(r.src, path.join(this.dest, r.name));
      }
    } catch {}
    this.completing = false;
    if (this.liveWorkers > 0) return;
    this.finish();
  }

  togglePause() {
    if (!this.running) return;
    this.paused = !this.paused;
    if (!this.paused) this.releasePause();
    this.emitState();
  }

  cancel() {
    if (!this.running) return;
    this.cancelled = true;
    this.paused = false;
    this.releasePause();
    this.emitState();
  }

  clear() {
    if (this.running) return;
    this.items = [];
    this.sourceRoots = [];
    this.firstPending = 0;
    this.copied = 0;
    this.elapsedMs = 0;
    this.peak = 0;
    this.smoothed = 0;
    this.history = [];
    this.message = null;
    this.dirty.clear();
    this.emitState(true);
  }

  clearMessage() {
    this.message = null;
    this.emitState();
  }

  releasePause() {
    const waiters = this.pauseWaiters;
    this.pauseWaiters = [];
    for (const w of waiters) w();
  }

  waitIfPaused() {
    if (!this.paused) return Promise.resolve();
    return new Promise((resolve) => this.pauseWaiters.push(resolve));
  }

  // ---------- Travailleurs ----------

  spawnWorkers() {
    if (!this.running || this.cancelled) return;
    while (this.liveWorkers < this.workers) {
      this.liveWorkers++;
      this.worker()
        .catch(() => {})
        .finally(() => {
          this.liveWorkers--;
          if (this.liveWorkers === 0 && this.running) this.complete();
        });
    }
  }

  async worker() {
    while (!this.cancelled) {
      const it = this.claim();
      if (!it) return;
      await this.copyOne(it);
    }
  }

  /** Un seul gros fichier à la fois ; les petits passent en parallèle pour masquer la latence. */
  claim() {
    while (this.firstPending < this.items.length && this.items[this.firstPending].status !== 'pending') {
      this.firstPending++;
    }
    for (let i = this.firstPending; i < this.items.length; i++) {
      const it = this.items[i];
      if (it.status !== 'pending') continue;
      if (it.size >= BIG_FILE) {
        if (this.bigBusy) continue;
        this.bigBusy = true;
        it.holdsBig = true;
      }
      it.status = 'copying';
      this.dirty.add(it);
      return it;
    }
    return null;
  }

  async copyOne(it) {
    let out = null;
    let ok = false;
    const move = this.mode === 'move';
    const verify = this.verify || move;
    this.active++;
    try {
      const dir = path.join(this.dest, ...it.rel);
      await fsp.mkdir(dir, { recursive: true });
      const wanted = path.join(dir, it.name);
      if (move && samePath(path.dirname(it.src), dir)) {
        it.status = 'skipped';
        ok = true;
        return;
      }
      const policy = this.policy;
      const existing = policy === 'rename' && !move ? null : await statOrNull(wanted);
      const exists = !!(existing && existing.isFile());

      if (exists && policy === 'skip') {
        it.status = 'skipped';
        ok = true;
        return;
      }
      if (exists && policy === 'sync' && existing.size === it.size) {
        this.setStatus(it, 'verifying');
        const a = await this.hashFile(it.src, null);
        const b = await this.hashFile(wanted, null);
        if (a === b) {
          it.hash = a;
          if (move) await this.deleteSource(it);
          it.status = 'skipped';
          ok = true;
          return;
        }
        this.setStatus(it, 'copying');
      }

      // Déplacement instantané sur le même disque : simple changement de dossier, aucune donnée recopiée.
      if (move) {
        const target = existing && (policy === 'rename' || !exists) ? await freeName(wanted) : wanted;
        try {
          await fsp.rename(it.src, target);
          it.instant = true;
          it.moved = true;
          it.status = 'done';
          ok = true;
          return;
        } catch {
          // autre disque (EXDEV) ou refus : copie + vérification + suppression
        }
      }

      if (exists && policy !== 'rename') {
        const tmp = `${wanted}.smartcopy-part`;
        out = { fh: await fsp.open(tmp, 'w'), file: tmp, finalName: wanted };
      } else {
        out = await openUnique(wanted);
      }

      let srcHash;
      try {
        if (it.holdsBig) await out.fh.truncate(it.size).catch(() => {});
        srcHash = await this.copyFile(it, out.fh, it.holdsBig);
      } finally {
        await out.fh.close().catch(() => {});
      }
      it.hash = srcHash;

      if (verify) {
        this.setStatus(it, 'verifying');
        const dstHash = await this.hashFile(out.file, (n) => {
          it.verified += n;
          this.dirty.add(it);
        });
        if (dstHash !== srcHash) throw new Error('empreinte différente : la copie est corrompue');
      }
      if (out.finalName) {
        await fsp.rename(out.file, out.finalName);
        out.file = out.finalName;
      }
      await fsp.utimes(out.file, it.atime, it.mtime).catch(() => {});
      ok = true;
      it.status = 'done';
      if (move) await this.deleteSource(it);
    } catch (e) {
      if (e instanceof CancelError || this.cancelled) it.status = 'cancelled';
      else {
        it.status = 'failed';
        it.error = e.code ? `${e.code} — ${e.message}` : e.message;
      }
    } finally {
      this.active--;
      if (it.holdsBig) {
        it.holdsBig = false;
        this.bigBusy = false;
      }
      if (!ok) {
        this.copied -= it.copied;
        it.copied = 0;
        it.verified = 0;
        if (out) await fsp.unlink(out.file).catch(() => {});
      }
      this.dirty.add(it);
    }
    this.spawnWorkers();
  }

  async deleteSource(it) {
    try {
      await fsp.unlink(it.src);
      it.moved = true;
    } catch (e) {
      if (e.code === 'EPERM' || e.code === 'EACCES') {
        try {
          await fsp.chmod(it.src, 0o666);
          await fsp.unlink(it.src);
          it.moved = true;
          return;
        } catch {}
      }
      it.warning = `Copié et vérifié, mais l'original n'a pas pu être supprimé (${e.code || e.message}).`;
    }
  }

  setStatus(it, status) {
    it.status = status;
    this.dirty.add(it);
  }

  /** Relit un fichier et calcule son empreinte SHA-256 (respecte pause et annulation). */
  async hashFile(file, onBytes) {
    const fh = await fsp.open(file, 'r');
    const hash = crypto.createHash('sha256');
    const buf = Buffer.allocUnsafe(4 * MiB);
    let pos = 0;
    try {
      for (;;) {
        await this.waitIfPaused();
        if (this.cancelled) throw new CancelError();
        const { bytesRead } = await fh.read(buf, 0, buf.length, pos);
        if (!bytesRead) break;
        hash.update(buf.subarray(0, bytesRead));
        pos += bytesRead;
        if (onBytes) onBytes(bytesRead);
      }
    } finally {
      await fh.close().catch(() => {});
    }
    return hash.digest('hex');
  }

  /** Copie en pipeline : lecture du bloc suivant pendant l'écriture du bloc courant,
   *  empreinte SHA-256 de la source calculée pendant l'écriture. */
  async copyFile(it, outFh, big) {
    const inFh = await fsp.open(it.src, 'r');
    const hash = crypto.createHash('sha256');
    let pendingRead = null;
    try {
      const cap = big ? MAX_BLOCK : Math.max(64 * 1024, Math.min(this.blockSize, it.size || 1));
      const bufs = [Buffer.allocUnsafe(cap), Buffer.allocUnsafe(cap)];
      const tuner = big ? new Tuner(this.blockSize) : null;
      const want = () => (tuner ? Math.min(tuner.current, cap) : cap);
      let pos = 0;
      let idx = 0;
      pendingRead = inFh.read(bufs[0], 0, want(), 0);
      for (;;) {
        await this.waitIfPaused();
        if (this.cancelled) throw new CancelError();
        const { bytesRead } = await pendingRead;
        pendingRead = null;
        if (bytesRead === 0) break;
        const current = bufs[idx];
        pos += bytesRead;
        idx ^= 1;
        pendingRead = inFh.read(bufs[idx], 0, want(), pos);
        const writing = writeAll(outFh, current, bytesRead);
        hash.update(current.subarray(0, bytesRead));
        await writing;
        it.copied += bytesRead;
        this.copied += bytesRead;
        this.dirty.add(it);
        if (tuner) {
          const next = tuner.onBytes(bytesRead);
          if (next) this.blockSize = next;
        }
      }
      if (big) {
        await outFh.truncate(pos);
        await outFh.sync();
      }
    } finally {
      if (pendingRead) await pendingRead.catch(() => {});
      await inFh.close().catch(() => {});
    }
    return hash.digest('hex');
  }

  // ---------- Statistiques ----------

  tick() {
    const now = Date.now();
    const dt = (now - this.lastTick) / 1000;
    this.lastTick = now;
    const inst = dt > 0 ? Math.max(0, (this.copied - this.lastBytes) / dt) : 0;
    this.lastBytes = this.copied;
    if (!this.paused) this.elapsedMs += dt * 1000;
    if (this.paused) this.smoothed = 0;
    else this.smoothed = this.smoothed === 0 ? inst : this.smoothed * 0.7 + inst * 0.3;
    if (this.smoothed > this.peak) this.peak = this.smoothed;
    this.half = !this.half;
    if (this.half) {
      this.history.push(this.smoothed);
      if (this.history.length > HISTORY_SIZE) this.history.shift();
    }
    this.emitState();
  }

  finish() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    this.running = false;
    this.smoothed = 0;
    const s = this.summary();
    const move = this.mode === 'move';
    const verb = move ? 'déplacés' : 'copiés';
    const skippedText = s.filesSkipped ? `, ${s.filesSkipped} ignorés` : '';
    const anyVerified = this.items.some((i) => i.status === 'done' && i.verified > 0);
    const verifiedText = anyVerified ? ' et vérifiés' : '';
    const warnText = s.filesWarned ? ` Attention : ${s.filesWarned} original(aux) non supprimé(s).` : '';
    if (this.cancelled) this.message = 'Transfert annulé. « Démarrer » reprend les fichiers restants.';
    else if (s.filesFailed) this.message = `Terminé : ${s.filesDone} ${verb}${skippedText}, ${s.filesFailed} en échec (« Démarrer » pour réessayer).${warnText}`;
    else this.message = `Terminé : ${s.filesDone} fichier(s) ${verb}${verifiedText}${skippedText}.${warnText}`;
    this.cancelled = false;
    this.emitState();
  }

  summary() {
    let total = 0;
    let done = 0;
    let failed = 0;
    let skipped = 0;
    let warned = 0;
    for (const it of this.items) {
      if (it.warning) warned++;
      if (it.status === 'skipped') {
        skipped++;
        continue;
      }
      if (!it.instant) total += it.size;
      if (it.status === 'done') done++;
      else if (it.status === 'failed') failed++;
    }
    const avg = this.elapsedMs > 0 ? this.copied / (this.elapsedMs / 1000) : 0;
    const rate = this.smoothed > 0 ? this.smoothed : avg;
    const etaMs = this.running && rate > 1 ? (Math.max(0, total - this.copied) / rate) * 1000 : -1;
    return {
      running: this.running,
      paused: this.paused,
      total,
      copied: this.copied,
      filesTotal: this.items.length,
      filesDone: done,
      filesFailed: failed,
      filesSkipped: skipped,
      filesWarned: warned,
      verify: this.verify,
      policy: this.policy,
      mode: this.mode,
      speed: this.running ? this.smoothed : 0,
      avg,
      peak: this.peak,
      elapsedMs: this.elapsedMs,
      etaMs,
      blockSize: this.blockSize,
      active: this.active,
      workers: this.workers,
      history: this.history,
      dest: this.dest,
      destInfo: this.destInfo,
      analysis: this.analysis,
      analyzing: this.analyzing,
      message: this.message,
    };
  }

  emitState(full = false) {
    const changes = [];
    for (const it of this.dirty) changes.push(itemView(it));
    this.dirty.clear();
    this.emit('state', {
      summary: this.summary(),
      changes,
      items: full ? this.items.map(itemView) : null,
    });
  }
}

module.exports = { CopyEngine };
