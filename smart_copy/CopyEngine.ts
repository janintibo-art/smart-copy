import * as fs from 'fs';
import * as path from 'path';
import { Transform } from 'stream';

export enum CopyStatus {
  PENDING = 'pending',
  IN_PROGRESS = 'in_progress',
  COMPLETED = 'completed',
  FAILED = 'failed',
  CANCELLED = 'cancelled'
}

export interface CopyFile {
  id: string;
  source: string;
  destination: string;
  status: CopyStatus;
  progress: number;
  speed: number;
  eta: number;
  size: number;
  copiedBytes: number;
}

export interface GlobalCopyStats {
  totalBytesCopied: number;
  elapsedSeconds: number;
  averageSpeed: number;
  filesCompleted: number;
  totalFiles: number;
}

export class CopyEngine {
  private queue: Map<string, CopyFile> = new Map();
  private isCancelled = false;
  private totalBytesCopied = 0;
  private startTime = 0;
  private readonly bufferSize: number;
  private readonly threads: number;

  constructor(bufferSize: number = 8 * 1024 * 1024, threads: number = 2) {
    this.bufferSize = bufferSize;
    this.threads = threads;
  }

  addFile(id: string, source: string, destination: string, size: number): CopyFile {
    const copyFile: CopyFile = {
      id,
      source,
      destination,
      status: CopyStatus.PENDING,
      progress: 0,
      speed: 0,
      eta: 0,
      size,
      copiedBytes: 0
    };
    this.queue.set(id, copyFile);
    return copyFile;
  }

  async start(onProgress: (file: CopyFile, global: GlobalCopyStats) => void): Promise<void> {
    this.isCancelled = false;
    this.startTime = Date.now();

    const files = Array.from(this.queue.values()).filter(f => f.status === CopyStatus.PENDING);

    for (const file of files) {
      if (this.isCancelled) break;
      await this.copyFile(file, onProgress);
    }
  }

  private async copyFile(file: CopyFile, onProgress: (file: CopyFile, global: GlobalCopyStats) => void): Promise<void> {
    return new Promise((resolve, reject) => {
      try {
        file.status = CopyStatus.IN_PROGRESS;
        const startMs = Date.now();
        let copiedBytes = 0;

        const input = fs.createReadStream(file.source, { highWaterMark: this.bufferSize });
        const output = fs.createWriteStream(file.destination);

        input.on('data', (chunk: Buffer) => {
          copiedBytes += chunk.length;
          this.totalBytesCopied += chunk.length;
          file.copiedBytes = copiedBytes;

          const elapsed = (Date.now() - startMs) / 1000;
          const speedMBps = (copiedBytes / 1024 / 1024) / elapsed;
          const remaining = file.size - copiedBytes;
          const eta = speedMBps > 0 ? (remaining / 1024 / 1024) / speedMBps : 0;

          file.progress = (copiedBytes / file.size) * 100;
          file.speed = speedMBps;
          file.eta = Math.ceil(eta);

          const global = this.getGlobalStats();
          onProgress(file, global);
        });

        input.on('error', (err) => {
          file.status = CopyStatus.FAILED;
          reject(err);
        });

        output.on('error', (err) => {
          file.status = CopyStatus.FAILED;
          reject(err);
        });

        output.on('finish', () => {
          file.status = CopyStatus.COMPLETED;
          file.progress = 100;
          const global = this.getGlobalStats();
          onProgress(file, global);
          resolve();
        });

        input.pipe(output);
      } catch (error) {
        file.status = CopyStatus.FAILED;
        reject(error);
      }
    });
  }

  cancel(): void {
    this.isCancelled = true;
  }

  getGlobalStats(): GlobalCopyStats {
    const elapsed = Math.max(1, (Date.now() - this.startTime) / 1000);
    const averageSpeed = (this.totalBytesCopied / 1024 / 1024) / elapsed;
    const completed = Array.from(this.queue.values()).filter(f => f.status === CopyStatus.COMPLETED).length;

    return {
      totalBytesCopied: this.totalBytesCopied,
      elapsedSeconds: Math.ceil(elapsed),
      averageSpeed: averageSpeed,
      filesCompleted: completed,
      totalFiles: this.queue.size
    };
  }
}
