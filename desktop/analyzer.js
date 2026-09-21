'use strict';

const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const crypto = require('crypto');
const { execFile } = require('child_process');
const { fmtBytes } = require('./format');

const MiB = 1024 * 1024;
const CANDIDATES = [64 * 1024, 256 * 1024, 1 * MiB, 4 * MiB, 16 * MiB];

function powershellJson(command) {
  return new Promise((resolve) => {
    if (process.platform !== 'win32') return resolve(null);
    execFile(
      'powershell.exe',
      ['-NoProfile', '-NonInteractive', '-Command', command],
      { windowsHide: true, timeout: 20000 },
      (err, stdout) => {
        if (err) return resolve(null);
        try {
          resolve(JSON.parse(String(stdout).trim()));
        } catch {
          resolve(null);
        }
      }
    );
  });
}

/** Identifie le support : SSD NVMe / SATA, disque dur, USB, carte SD, réseau. */
async function describeDrive(target) {
  const resolved = path.resolve(target);
  const root = path.parse(resolved).root;
  const info = { root, label: root, kind: 'Type inconnu', bus: '', model: '', fs: '', totalBytes: 0, freeBytes: 0 };

  try {
    const st = await fsp.statfs(resolved);
    info.totalBytes = st.blocks * st.bsize;
    info.freeBytes = st.bavail * st.bsize;
  } catch {}

  const letter = root.replace(/[:\\/]/g, '');
  if (process.platform === 'win32' && /^[A-Za-z]$/.test(letter)) {
    const cmd =
      `$p = Get-Partition -DriveLetter ${letter} -ErrorAction SilentlyContinue; ` +
      `$v = Get-Volume -DriveLetter ${letter} -ErrorAction SilentlyContinue; ` +
      `if ($p) { $d = Get-PhysicalDisk | Where-Object { $_.DeviceId -eq [string]$p.DiskNumber } | Select-Object -First 1 } else { $d = $null }; ` +
      `[pscustomobject]@{ Model = [string]$d.FriendlyName; Media = [string]$d.MediaType; Bus = [string]$d.BusType; ` +
      `DriveType = [string]$v.DriveType; Label = [string]$v.FileSystemLabel; Fs = [string]$v.FileSystem } | ConvertTo-Json -Compress`;
    const r = await powershellJson(cmd);
    info.label = `${letter.toUpperCase()}:`;
    if (r) {
      info.model = r.Model || '';
      info.bus = r.Bus || '';
      info.fs = r.Fs || '';
      if (r.Label) info.label = `${r.Label} (${letter.toUpperCase()}:)`;
      const media = (r.Media || '').toUpperCase();
      const bus = (r.Bus || '').toUpperCase();
      const driveType = (r.DriveType || '').toUpperCase();
      if (bus === 'NVME') info.kind = 'SSD NVMe';
      else if (media === 'SSD') info.kind = bus === 'USB' ? 'SSD externe (USB)' : 'SSD SATA';
      else if (media === 'HDD') info.kind = bus === 'USB' ? 'Disque dur externe (USB)' : 'Disque dur (HDD)';
      else if (bus === 'SD' || bus === 'MMC') info.kind = 'Carte SD';
      else if (bus === 'USB') info.kind = 'Clé / disque USB';
      else if (driveType === 'NETWORK') info.kind = 'Lecteur réseau';
      else if (driveType === 'REMOVABLE') info.kind = 'Support amovible';
      else if (bus) info.kind = bus;
    }
  }
  return info;
}

function nowNs() {
  return process.hrtime.bigint();
}

function secondsSince(t0) {
  return Number(process.hrtime.bigint() - t0) / 1e9;
}

async function benchWrite(dir, block, totalBytes) {
  const file = path.join(dir, `.smartcopy_test_${process.pid}_${block}.tmp`);
  const buf = crypto.randomFillSync(Buffer.allocUnsafe(block));
  let fh;
  const t0 = nowNs();
  try {
    fh = await fsp.open(file, 'w');
    let written = 0;
    while (written < totalBytes) {
      const { bytesWritten } = await fh.write(buf, 0, block, null);
      written += bytesWritten;
    }
    await fh.sync();
  } finally {
    if (fh) await fh.close().catch(() => {});
  }
  const secs = secondsSince(t0);
  await fsp.unlink(file).catch(() => {});
  return totalBytes / MiB / Math.max(secs, 1e-6);
}

async function benchRead(file, maxBytes) {
  const buf = Buffer.allocUnsafe(4 * MiB);
  let fh;
  let total = 0;
  const t0 = nowNs();
  try {
    fh = await fsp.open(file, 'r');
    while (total < maxBytes) {
      const { bytesRead } = await fh.read(buf, 0, buf.length, total);
      if (!bytesRead) break;
      total += bytesRead;
    }
  } catch {
    return 0;
  } finally {
    if (fh) await fh.close().catch(() => {});
  }
  const secs = secondsSince(t0);
  return total ? total / MiB / Math.max(secs, 1e-6) : 0;
}

async function benchSmallFiles(dir, count) {
  const sub = path.join(dir, `.smartcopy_small_${process.pid}`);
  const data = Buffer.alloc(4096, 7);
  await fsp.mkdir(sub, { recursive: true });
  const t0 = nowNs();
  for (let i = 0; i < count; i++) await fsp.writeFile(path.join(sub, `f${i}.tmp`), data);
  const ms = (secondsSince(t0) * 1000) / count;
  await fsp.rm(sub, { recursive: true, force: true }).catch(() => {});
  return ms;
}

/**
 * sources : [{ path, size }]
 * Mesure l'écriture réelle (avec vidage du cache) pour chaque taille de bloc,
 * la lecture de la source, la latence des petits fichiers, puis choisit
 * la taille de bloc et le nombre de flux parallèles.
 */
async function analyze(sources, dest, onStep) {
  onStep('Identification des supports');
  const destination = await describeDrive(dest);
  const biggest = sources.length ? sources.reduce((a, b) => (b.size > a.size ? b : a)) : null;
  const source = biggest ? await describeDrive(biggest.path) : null;

  onStep("Estimation du débit d'écriture");
  const estimate = await benchWrite(dest, MiB, 16 * MiB);
  const chunk = 16 * MiB;
  const wanted = Math.min(256 * MiB, Math.max(16 * MiB, estimate * 1.2 * MiB));
  const perTest = Math.ceil(wanted / chunk) * chunk;

  const writeResults = [];
  for (const block of CANDIDATES) {
    onStep(`Test d'écriture : bloc de ${fmtBytes(block)}`);
    writeResults.push({ blockSize: block, mbPerSec: await benchWrite(dest, block, perTest) });
  }

  let sourceReadMBps = 0;
  if (biggest) {
    onStep('Test de lecture de la source');
    sourceReadMBps = await benchRead(biggest.path, 128 * MiB);
  }

  onStep('Test des petits fichiers (latence)');
  const smallFileLatencyMs = await benchSmallFiles(dest, 32);

  const max = Math.max(...writeResults.map((r) => r.mbPerSec));
  const bestBlock = writeResults
    .filter((r) => r.mbPerSec >= max * 0.95)
    .sort((a, b) => a.blockSize - b.blockSize)[0].blockSize;

  const sameDrive = !!source && source.root.toLowerCase() === destination.root.toLowerCase();
  const isHdd = (k) => /HDD|Disque dur/i.test(k || '');
  let workers;
  if (isHdd(destination.kind) || (source && isHdd(source.kind))) workers = sameDrive ? 1 : 2;
  else if (/NVMe|SSD/.test(destination.kind)) workers = smallFileLatencyMs > 2 ? 6 : 4;
  else workers = smallFileLatencyMs > 15 ? 3 : 2;

  return { source, destination, sourceReadMBps, writeResults, bestBlock, smallFileLatencyMs, workers, sameDrive };
}

module.exports = { analyze, describeDrive };
