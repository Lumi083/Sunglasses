const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('sunglasses', {
  getSettings: () => ipcRenderer.invoke('settings:get'),
  updateSettings: (changes) => ipcRenderer.invoke('settings:update', changes),
  confirmOpacity: () => ipcRenderer.invoke('opacity:confirm'),
  cancelOpacity: () => ipcRenderer.invoke('opacity:cancel'),
  onSettingsChanged: (listener) => {
    const handler = (_event, settings) => listener(settings);
    ipcRenderer.on('settings-changed', handler);
    return () => ipcRenderer.removeListener('settings-changed', handler);
  }
});
