import * as fs from 'fs';
import * as path from 'path';
import { promisify } from 'util';

const stat = promisify(fs.stat);
const access = promisify(fs.access);

export interface StorageInfo {
  totalCapacity: number;
  availableCapacity: number;
  isSSD: boolean;
  isInternal: boolean;
  driveLabel: string;
}

export interface PerformanceMetrics {
  readSpeed: number;
  writeSpeed: number;
  optimalBufferSize: number;
  recommendedThreads: number;
}

export class FileAnalyzer {
  async analyzeStorage(filePath: string): Promise<StorageInfo> {
    try {
      const stats = await stat(filePath);
      const drive = path.parse(filePath).root;
      
      return {
        totalCapacity: 0,
        availableCapacity: 0,
        isSSD: this.detectSSD(drive),
        isInternal: true,
        driveLabel: drive
      };
    } catch (error) {
      throw new Error(`Failed to analyze storage: ${error}`);
    }
  }

  async benchmarkIOPerformance(dirPath: string, testSize: number = 10 * 1024 * 1024): Promise<PerformanceMetrics> {
    const testFile = path.join(dirPath, `.smartcopy_bench_${Date.now()}`);
    const testData = Buffer.alloc(1024 * 1024);
    
    try {
      const writeStart = Date.now();
      const writeStream = fs.createWriteStream(testFile);
      
      for (let i = 0; i < testSize / testData.length; i++) {
        writeStream.write(testData);
      }
      
      await new Promise((resolve) => writeStream.end(resolve));
      const writeTime = (Date.now() - writeStart) / 1000;

      const readStart = Date.now();
      const readStream = fs.createReadStream(testFile, { highWaterMark: 1024 * 1024 });
      
      await new Promise((resolve) => {
        readStream.on('data', () => {});
        readStream.on('end', resolve);
      });
      const readTime = (Date.now() - readStart) / 1000;

      const writeSpeedMBps = (testSize / 1024 / 1024) / writeTime;
      const readSpeedMBps = (testSize / 1024 / 1024) / readTime;

      const bufferSize = this.calculateOptimalBuffer(writeSpeedMBps);
      const threads = this.calculateThreadCount(readSpeedMBps);

      return {
        readSpeed: readSpeedMBps,
        writeSpeed: writeSpeedMBps,
        optimalBufferSize: bufferSize,
        recommendedThreads: threads
      };
    } finally {
      try {
        fs.unlinkSync(testFile);
      } catch {}
    }
  }

  private detectSSD(drive: string): boolean {
    return drive.match(/^[C-Z]:/) ? true : false;
  }

  private calculateOptimalBuffer(writeSpeedMBps: number): number {
    if (writeSpeedMBps > 200) return 16 * 1024 * 1024;
    if (writeSpeedMBps > 100) return 8 * 1024 * 1024;
    if (writeSpeedMBps > 50) return 4 * 1024 * 1024;
    return 2 * 1024 * 1024;
  }

  private calculateThreadCount(readSpeedMBps: number): number {
    if (readSpeedMBps > 300) return Math.max(1, Math.floor(require('os').cpus().length * 0.8));
    if (readSpeedMBps > 100) return Math.max(1, Math.floor(require('os').cpus().length / 2));
    return 2;
  }
}
