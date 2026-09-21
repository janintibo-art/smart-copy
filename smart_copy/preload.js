const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  selectFiles: () => ipcRenderer.invoke('select-files'),
  selectDirectory: () => ipcRenderer.invoke('select-directory'),
  getFileInfo: (filePath) => ipcRenderer.invoke('get-file-info', filePath),
  onCopyProgress: (callback) => {
    ipcRenderer.on('copy-progress', (event, data) => callback(data));
  },
  onCopyComplete: (callback) => {
    ipcRenderer.on('copy-complete', (event, data) => callback(data));
  }
});
