'use strict';

const { app, BrowserWindow, ipcMain, dialog, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const { CopyEngine } = require('./engine');

let win = null;
const engine = new CopyEngine();

function settingsFile() {
  return path.join(app.getPath('userData'), 'settings.json');
}

function loadSettings() {
  try {
    engine.setOptions(JSON.parse(fs.readFileSync(settingsFile(), 'utf8')));
  } catch {}
  engine.setStoreDir(app.getPath('userData'));
}

function saveSettings() {
  try {
    fs.mkdirSync(path.dirname(settingsFile()), { recursive: true });
    fs.writeFileSync(settingsFile(), JSON.stringify(engine.getOptions(), null, 2));
  } catch {}
}

engine.on('state', (state) => {
  if (win && !win.isDestroyed()) win.webContents.send('state', state);
});

function createWindow() {
  win = new BrowserWindow({
    width: 1200,
    height: 840,
    minWidth: 920,
    minHeight: 640,
    backgroundColor: '#0b0f17',
    title: 'Smart Copy',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));

  win.on('close', (e) => {
    if (!engine.running) return;
    const choice = dialog.showMessageBoxSync(win, {
      type: 'warning',
      buttons: ['Continuer la copie', 'Quitter quand même'],
      defaultId: 0,
      cancelId: 0,
      title: 'Copie en cours',
      message: 'Une copie est en cours. Quitter maintenant laissera le fichier en cours incomplet.',
    });
    if (choice === 0) e.preventDefault();
    else engine.cancel();
  });
  win.on('closed', () => {
    win = null;
  });
}

ipcMain.handle('ready', () => engine.emitState(true));

ipcMain.handle('pick-files', async () => {
  const r = await dialog.showOpenDialog(win, {
    title: 'Fichiers à copier',
    properties: ['openFile', 'multiSelections'],
  });
  if (!r.canceled && r.filePaths.length) await engine.addPaths(r.filePaths);
});

ipcMain.handle('pick-folder', async () => {
  const r = await dialog.showOpenDialog(win, {
    title: 'Dossiers à copier',
    properties: ['openDirectory', 'multiSelections'],
  });
  if (!r.canceled && r.filePaths.length) await engine.addPaths(r.filePaths);
});

ipcMain.handle('pick-dest', async () => {
  if (engine.running) return;
  const r = await dialog.showOpenDialog(win, {
    title: 'Dossier de destination',
    properties: ['openDirectory', 'createDirectory'],
  });
  if (!r.canceled && r.filePaths[0]) await engine.setDestination(r.filePaths[0]);
});

ipcMain.handle('add-paths', (_e, paths) => {
  if (Array.isArray(paths) && paths.length) return engine.addPaths(paths.map(String));
  return 0;
});

ipcMain.handle('set-options', (_e, opts) => {
  engine.setOptions(opts);
  saveSettings();
});
ipcMain.handle('analyze', () => engine.analyze());
ipcMain.handle('resume-session', () => engine.resumeSession());
ipcMain.handle('discard-session', () => engine.discardSession());
ipcMain.handle('clear-history', () => engine.clearHistory());
ipcMain.handle('start', () => engine.start());
ipcMain.handle('toggle-pause', () => engine.togglePause());
ipcMain.handle('cancel', () => engine.cancel());
ipcMain.handle('clear', () => engine.clear());
ipcMain.handle('clear-message', () => engine.clearMessage());
ipcMain.handle('open-dest', () => (engine.dest ? shell.openPath(engine.dest) : null));

app.whenReady().then(() => {
  loadSettings();
  createWindow();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
