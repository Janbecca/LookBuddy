const { app, BrowserWindow, ipcMain, dialog, screen } = require('electron');
const path = require('node:path');
const { startServer } = require('./server.cjs');
const { initialState, sanitizeState, sanitizeCommand } = require('./state.cjs');
const httpSmoke = process.argv.includes('--smoke-http');
const smoke = process.argv.includes('--smoke') || httpSmoke;
if (httpSmoke) app.commandLine.appendSwitch('use-fake-device-for-media-stream');
let dashboard, pet, server, quitting = false, state = initialState();
if (smoke) app.setPath('userData', path.join(app.getPath('temp'), `lookbuddy-smoke-${process.pid}`));
if (!smoke && !app.requestSingleInstanceLock()) { app.quit(); } else {
  app.on('second-instance', () => { if (dashboard) showDashboard(); });
  app.whenReady().then(boot).catch((error) => {
    console.error(error.message);
    if (!smoke) dialog.showErrorBox('LookBuddy 启动失败', error.message);
    app.exit(1);
  });
}
function trusted(event, role) {
  const window = role === 'dashboard' ? dashboard : pet;
  if (!window || event.sender !== window.webContents || event.senderFrame !== event.sender.mainFrame) throw new Error('Untrusted IPC sender');
  const url = new URL(event.senderFrame.url);
  if (url.origin !== server.origin || url.pathname !== '/') throw new Error('Untrusted IPC origin');
}
function broadcast() {
  if (pet && !pet.isDestroyed()) pet.webContents.send('desktop:state', state);
}
function showDashboard() {
  if (dashboard.isMinimized()) dashboard.restore();
  dashboard.show();
  // Windows STARTUPINFO may suppress the first ShowWindow when a launcher used
  // Hidden; an explicit user reopen must still bring back the owner window.
  if (!dashboard.isVisible()) dashboard.show();
  dashboard.focus();
}
async function boot() {
  const port = smoke ? 0 : Number(process.env.LOOKBUDDY_DESKTOP_PORT || 5189);
  if (!Number.isInteger(port) || port < 0 || port > 65535 || (!smoke && port === 0)) throw new Error('Invalid desktop port');
  server = await startServer({ root: path.join(__dirname, app.isPackaged ? 'web' : '../web/dist'), backend: process.env.LOOKBUDDY_BACKEND_URL || 'http://127.0.0.1:8000', port });
  const options = (role) => ({
    preload: path.join(__dirname, 'preload.cjs'), nodeIntegration: false, contextIsolation: true, sandbox: true,
    backgroundThrottling: false, partition: role === 'pet' ? 'lookbuddy-pet' : (smoke ? 'lookbuddy-smoke' : 'persist:lookbuddy'),
    additionalArguments: [...(role === 'pet' ? ['--lookbuddy-pet'] : []), ...(smoke ? ['--lookbuddy-smoke'] : [])],
  });
  dashboard = new BrowserWindow({ title: 'LookBuddy 调试 · 隐藏窗口仍会陪伴', width: 1160, height: 820, show: !smoke, autoHideMenuBar: true, webPreferences: options('dashboard') });
  const area = screen.getPrimaryDisplay().workArea;
  pet = new BrowserWindow({ title: 'LookBuddy 桌宠', width: 340, height: 360, x: area.x + area.width - 360, y: area.y + area.height - 380, frame: false, transparent: true, resizable: false, alwaysOnTop: true, show: !smoke, webPreferences: options('pet') });
  for (const [window, role] of [[dashboard, 'dashboard'], [pet, 'pet']]) {
    window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
    window.webContents.on('will-navigate', (event) => event.preventDefault());
    const ses = window.webContents.session;
    const cameraAllowed = (contents, permission, details) => contents === dashboard.webContents && role === 'dashboard' && permission === 'media' && new URL(contents.getURL()).origin === server.origin && (details.mediaType !== 'audio');
    ses.setPermissionCheckHandler((contents, permission, _origin, details) => cameraAllowed(contents, permission, details));
    ses.setPermissionRequestHandler(async (contents, permission, callback, details) => {
      if ((smoke && !httpSmoke) || !cameraAllowed(contents, permission, details) || !details.mediaTypes?.includes('video') || details.mediaTypes.some((type) => type !== 'video')) { callback(false); return; }
      if (httpSmoke) { callback(true); return; } // Chromium fake device only in this isolated test mode.
      const result = await dialog.showMessageBox(dashboard, { type: 'question', buttons: ['允许本地摄像头', '取消'], defaultId: 1, cancelId: 1, message: '允许 LookBuddy 读取摄像头吗？', detail: '画面在本机处理，仅上传语义状态。隐藏调试窗口后继续；暂停或退出后释放摄像头。' });
      callback(result.response === 0 && !quitting);
    });
    ses.webRequest.onBeforeRequest((details, callback) => {
      let allowed = false;
      try {
        const url = new URL(details.url);
        allowed = url.origin === server.origin && !(role === 'pet' && (url.pathname.startsWith('/v1/') || url.pathname === '/health'));
      } catch { /* malformed URL */ }
      callback({ cancel: !allowed });
    });
    window.webContents.on('render-process-gone', () => { state = initialState(); state.status = '感知进程已停止，请重新打开'; broadcast(); if (!quitting) app.quit(); });
  }
  dashboard.on('close', (event) => { if (!quitting) { event.preventDefault(); dashboard.hide(); } });
  pet.on('close', () => { if (!quitting) app.quit(); });
  ipcMain.handle('desktop:get-state', (event) => { trusted(event, event.sender === dashboard.webContents ? 'dashboard' : 'pet'); return state; });
  ipcMain.handle('desktop:publish', (event, value) => { trusted(event, 'dashboard'); state = sanitizeState(value); broadcast(); return true; });
  ipcMain.handle('desktop:command', (event, value) => { trusted(event, 'pet'); const command = sanitizeCommand(value, state); dashboard.webContents.send('desktop:command', command); return true; });
  ipcMain.handle('desktop:show', (event) => { trusted(event, event.sender === dashboard.webContents ? 'dashboard' : 'pet'); showDashboard(); });
  ipcMain.handle('desktop:hide', (event) => { trusted(event, 'dashboard'); dashboard.hide(); });
  ipcMain.handle('desktop:quit', (event) => { trusted(event, event.sender === dashboard.webContents ? 'dashboard' : 'pet'); app.quit(); });
  await Promise.all([dashboard.loadURL(server.origin + '/'), pet.loadURL(server.origin + '/?desktop=pet')]);
  if (smoke) await require('./smoke.cjs').run({ dashboard, pet, server, app, httpSmoke });
}
app.on('before-quit', () => { quitting = true; state = initialState(); });
app.on('will-quit', () => { void server?.close(); });
app.on('window-all-closed', () => app.quit());
