import React, { useState, useEffect, useRef } from 'react';
import './App.css';
import { CopyEngine, CopyFile, CopyStatus, GlobalCopyStats } from './CopyEngine';
import { FileAnalyzer, PerformanceMetrics } from './FileAnalyzer';

const App: React.FC = () => {
  const [files, setFiles] = useState<CopyFile[]>([]);
  const [metrics, setMetrics] = useState<PerformanceMetrics | null>(null);
  const [isRunning, setIsRunning] = useState(false);
  const [globalStats, setGlobalStats] = useState<GlobalCopyStats | null>(null);
  const [destination, setDestination] = useState<string>('');
  const engineRef = useRef<CopyEngine | null>(null);

  const handleSelectFiles = async () => {
    if (!window.electronAPI) return;
    const filePaths = await window.electronAPI.selectFiles();
    if (!filePaths) return;

    const newFiles: CopyFile[] = [];
    for (const filePath of filePaths) {
      const info = await window.electronAPI.getFileInfo(filePath);
      if (info) {
        newFiles.push({
          id: `${Date.now()}-${Math.random()}`,
          source: filePath,
          destination: destination ? `${destination}/${info.name}` : '',
          status: CopyStatus.PENDING,
          progress: 0,
          speed: 0,
          eta: 0,
          size: info.size,
          copiedBytes: 0
        });
      }
    }
    setFiles([...files, ...newFiles]);
  };

  const handleSelectDestination = async () => {
    if (!window.electronAPI) return;
    const dir = await window.electronAPI.selectDirectory();
    if (dir) setDestination(dir);
  };

  const handleAnalyzePerformance = async () => {
    if (!destination) {
      alert('Veuillez d\'abord sélectionner une destination');
      return;
    }
    const analyzer = new FileAnalyzer();
    const perf = await analyzer.benchmarkIOPerformance(destination);
    setMetrics(perf);
  };

  const handleStartCopy = async () => {
    if (!metrics || files.length === 0) return;
    setIsRunning(true);

    const engine = new CopyEngine(metrics.optimalBufferSize, metrics.recommendedThreads);
    engineRef.current = engine;

    files.forEach(file => engine.addFile(file.id, file.source, file.destination, file.size));

    await engine.start((file, global) => {
      setFiles(prev => prev.map(f => f.id === file.id ? file : f));
      setGlobalStats(global);
    });

    setIsRunning(false);
  };

  const handleCancel = () => {
    if (engineRef.current) {
      engineRef.current.cancel();
      setIsRunning(false);
    }
  };

  const handleClearQueue = () => {
    setFiles([]);
    setGlobalStats(null);
  };

  return (
    <div className="app">
      <header className="app-header">
        <h1>Smart Copy v1.0</h1>
        <p>Copie intelligente avec optimisation I/O</p>
      </header>

      <div className="app-content">
        {!metrics ? (
          <div className="setup-panel">
            <div className="form-group">
              <label>Destination</label>
              <div className="input-group">
                <input type="text" value={destination} readOnly placeholder="Sélectionnez une destination" />
                <button onClick={handleSelectDestination}>Parcourir</button>
              </div>
            </div>
            <button className="btn-primary" onClick={handleAnalyzePerformance}>
              Analyser les performances
            </button>
          </div>
        ) : (
          <>
            <div className="metrics-panel">
              <div className="metric-card">
                <span className="metric-label">Lecture</span>
                <span className="metric-value">{metrics.readSpeed.toFixed(1)} MB/s</span>
              </div>
              <div className="metric-card">
                <span className="metric-label">Écriture</span>
                <span className="metric-value">{metrics.writeSpeed.toFixed(1)} MB/s</span>
              </div>
              <div className="metric-card">
                <span className="metric-label">Buffer</span>
                <span className="metric-value">{(metrics.optimalBufferSize / 1024 / 1024).toFixed(0)} MB</span>
              </div>
              <div className="metric-card">
                <span className="metric-label">Threads</span>
                <span className="metric-value">{metrics.recommendedThreads}</span>
              </div>
            </div>

            <div className="file-selection">
              <button className="btn-primary" onClick={handleSelectFiles}>
                Ajouter des fichiers
              </button>
            </div>

            {files.length > 0 && (
              <>
                <div className="files-list">
                  {files.map(file => (
                    <div key={file.id} className="file-item">
                      <div className="file-header">
                        <span className="file-name">{file.source.split('/').pop()}</span>
                        <span className={`file-status ${file.status}`}>{file.status}</span>
                      </div>
                      <div className="progress-bar">
                        <div className="progress-fill" style={{ width: `${file.progress}%` }}></div>
                      </div>
                      <div className="file-stats">
                        <span>{file.progress.toFixed(1)}%</span>
                        <span>{file.speed.toFixed(1)} MB/s</span>
                        <span>ETA: {formatTime(file.eta)}</span>
                      </div>
                    </div>
                  ))}
                </div>

                {globalStats && (
                  <div className="global-stats">
                    <h3>Statistiques globales</h3>
                    <div className="stats-grid">
                      <div className="stat">
                        <span className="stat-label">Total copié</span>
                        <span className="stat-value">{(globalStats.totalBytesCopied / 1024 / 1024 / 1024).toFixed(2)} GB</span>
                      </div>
                      <div className="stat">
                        <span className="stat-label">Temps écoulé</span>
                        <span className="stat-value">{formatTime(globalStats.elapsedSeconds)}</span>
                      </div>
                      <div className="stat">
                        <span className="stat-label">Vitesse moyenne</span>
                        <span className="stat-value">{globalStats.averageSpeed.toFixed(1)} MB/s</span>
                      </div>
                      <div className="stat">
                        <span className="stat-label">Fichiers</span>
                        <span className="stat-value">{globalStats.filesCompleted}/{globalStats.totalFiles}</span>
                      </div>
                    </div>
                  </div>
                )}

                <div className="controls">
                  <button
                    className="btn-primary"
                    onClick={handleStartCopy}
                    disabled={isRunning}
                  >
                    {isRunning ? 'Copie en cours...' : 'Démarrer'}
                  </button>
                  <button
                    className="btn-danger"
                    onClick={handleCancel}
                    disabled={!isRunning}
                  >
                    Annuler
                  </button>
                  <button
                    className="btn-secondary"
                    onClick={handleClearQueue}
                  >
                    Réinitialiser
                  </button>
                </div>
              </>
            )}
          </>
        )}
      </div>
    </div>
  );
};

function formatTime(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
  return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`;
}

export default App;
