const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('lookbuddy', {
  readState: () => ipcRenderer.invoke('state:read'),
  saveState: (state) => ipcRenderer.send('state:save', state),
  readSyncState: () => ipcRenderer.invoke('sync:read'),
  onSyncUpdated: (callback) => {
    const listener = (_, snapshot) => callback(snapshot);
    ipcRenderer.on('sync:updated', listener);
    return () => ipcRenderer.removeListener('sync:updated', listener);
  },
  readGeometry: () => ipcRenderer.invoke('window:geometry'),
  resizeMode: (enabled) => ipcRenderer.send('resize:mode', enabled),
  resizeStart: (edge, point) => ipcRenderer.send('resize:start', edge, point),
  resizeMove: (point) => ipcRenderer.send('resize:move', point),
  resizeEnd: (point) => ipcRenderer.send('resize:end', point),
  resizeNudge: (edge, delta) => ipcRenderer.send('resize:nudge', edge, delta),
  resetSize: () => ipcRenderer.send('resize:reset'),
  onGeometry: (callback) => {
    const listener = (_, geometry) => callback(geometry);
    ipcRenderer.on('geometry', listener);
    return () => ipcRenderer.removeListener('geometry', listener);
  },
  dragStart: (point) => ipcRenderer.send('drag:start', point),
  dragMove: (point) => ipcRenderer.send('drag:move', point),
  dragEnd: (point) => ipcRenderer.send('drag:end', point),
  setHit: (hit) => ipcRenderer.send('window:hit', hit),
  onCursor: (callback) => {
    const listener = (_, position) => callback(position);
    ipcRenderer.on('cursor', listener);
    return () => ipcRenderer.removeListener('cursor', listener);
  },
  onAction: (callback) => {
    const listener = (_, action) => callback(action);
    ipcRenderer.on('action', listener);
    return () => ipcRenderer.removeListener('action', listener);
  },
});
