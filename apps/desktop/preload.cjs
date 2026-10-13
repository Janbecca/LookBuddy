const { contextBridge, ipcRenderer } = require('electron');
const role = process.argv.includes('--lookbuddy-pet') ? 'pet' : 'dashboard';
function subscribe(channel, callback) {
  const listener = (_event, value) => callback(value);
  ipcRenderer.on(channel, listener);
  return () => ipcRenderer.removeListener(channel, listener);
}
contextBridge.exposeInMainWorld('lookbuddyDesktop', {
  role,
  smoke: process.argv.includes('--lookbuddy-smoke'),
  state: () => ipcRenderer.invoke('desktop:get-state'),
  onState: (callback) => subscribe('desktop:state', callback),
  onCommand: (callback) => subscribe('desktop:command', callback),
  publish: (value) => ipcRenderer.invoke('desktop:publish', value),
  command: (value) => ipcRenderer.invoke('desktop:command', value),
  showDashboard: () => ipcRenderer.invoke('desktop:show'),
  hideDashboard: () => ipcRenderer.invoke('desktop:hide'),
  quit: () => ipcRenderer.invoke('desktop:quit'),
});
