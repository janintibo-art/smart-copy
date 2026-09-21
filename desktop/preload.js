'use strict';

const { contextBridge, ipcRenderer, webUtils } = require('electron');

contextBridge.exposeInMainWorld('api', {
  ready: () => ipcRenderer.invoke('ready'),
  pickFiles: () => ipcRenderer.invoke('pick-files'),
  pickFolder: () => ipcRenderer.invoke('pick-folder'),
  pickDest: () => ipcRenderer.invoke('pick-dest'),
  addPaths: (paths) => ipcRenderer.invoke('add-paths', paths),
  pathForFile: (file) => {
    try {
      return webUtils.getPathForFile(file);
    } catch {
      return '';
    }
  },
  analyze: () => ipcRenderer.invoke('analyze'),
  start: () => ipcRenderer.invoke('start'),
  togglePause: () => ipcRenderer.invoke('toggle-pause'),
  cancel: () => ipcRenderer.invoke('cancel'),
  clear: () => ipcRenderer.invoke('clear'),
  clearMessage: () => ipcRenderer.invoke('clear-message'),
  openDest: () => ipcRenderer.invoke('open-dest'),
  onState: (callback) => ipcRenderer.on('state', (_e, state) => callback(state)),
});
