const { app, BrowserWindow, ipcMain, Menu, Tray, nativeImage, screen, globalShortcut } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const { DEFAULT_SIZE, EDGES, limitsFor, normalizeSize, resizeBounds } = require('./resize.cjs');
const { createWindowsWindow } = require('./windows.cjs');
const { createSyncServer, localAddresses } = require('./sync-server.cjs');
const pets = JSON.parse(fs.readFileSync(path.join(__dirname, '../public/pets/manifest.json'), 'utf8'));
const smoke = process.argv.includes('--smoke') || process.argv.includes('--scene-smoke');
if (smoke) {
  const profile = path.join(app.getPath('temp'), `lookbuddy-smoke-${process.pid}`);
  fs.mkdirSync(profile, { recursive: true });
  app.setPath('userData', profile);
  app.setPath('sessionData', profile);
}
const { migratePreferences } = require('./preferences.cjs');
let win, nativeWindow, tray, cursorTimer, dragSession, dragTimeout, saveTimer, resizeSession, resizeTimeout, topmostTimer, syncServer;
let syncState = null;
let resizeMode = false;
let userHidden = false, petReady = false, menuOpen = false;
let state = { index: 0, width: DEFAULT_SIZE, bubble: true, auto: false, paused: false, sizeVersion: 1 };
const stateFile = () => path.join(app.getPath('userData'), 'preferences.json');
const save = () => {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    try { fs.writeFileSync(stateFile(), JSON.stringify(state)); } catch (error) { console.error('Cannot save preferences:', error.message); }
  }, 200);
};
function fit(bounds) {
  const area = screen.getDisplayMatching(bounds).workArea;
  return { ...bounds, x: Math.max(area.x, Math.min(bounds.x, area.x + area.width - bounds.width)), y: Math.max(area.y, Math.min(bounds.y, area.y + area.height - bounds.height)) };
}
function movePosition(x, y) {
  const bounds = win.getBounds();
  const nextX = Math.round(x), nextY = Math.round(y);
  if (bounds.x !== nextX || bounds.y !== nextY) {
    if (nativeWindow) nativeWindow.move(nextX, nextY);
    else win.setPosition(nextX, nextY);
  }
}
function clampPosition() {
  const bounds = fit(win.getBounds());
  movePosition(bounds.x, bounds.y);
}
function moveDrag(point) {
  if (!dragSession || !point || !Number.isFinite(point.x) || !Number.isFinite(point.y)) return;
  const { start, pointer } = dragSession;
  const dx = point.x - pointer.x, dy = point.y - pointer.y;
  if (!dragSession.moved && Math.hypot(dx, dy) < 3) return;
  dragSession.moved = true;
  movePosition(start.x + dx, start.y + dy);
}
function endDrag(point) {
  moveDrag(point);
  const moved = dragSession?.moved;
  clearTimeout(dragTimeout); dragSession = null;
  if (moved && win && !win.isDestroyed()) {
    clampPosition();
    // Direct Win32 moves do not reliably emit Electron's `moved` event.
    const { x, y } = win.getBounds(); state.x = x; state.y = y;
    if (!smoke) save();
  }
}
function keepOnTop() {
  if (!petReady || userHidden || menuOpen || !win || win.isDestroyed()) return;
  if (win.isMinimized()) win.restore();
  if (!win.isVisible()) win.showInactive();
  if (!win.isAlwaysOnTop()) win.setAlwaysOnTop(true, 'screen-saver');
  if (nativeWindow) nativeWindow.raise();
  else win.moveTop(); // Raise the pet without taking keyboard focus from the active app.
}
function geometry() {
  const bounds = win.getBounds();
  return { width: bounds.width, height: bounds.height, limits: limitsFor(screen.getDisplayMatching(bounds).workArea), editing: resizeMode };
}
function moveResize(point) {
  if (!resizeSession || !point || !Number.isFinite(point.x) || !Number.isFinite(point.y)) return;
  const { start, edge, pointer, area } = resizeSession;
  const next = resizeBounds(start, edge, { x: point.x - pointer.x, y: point.y - pointer.y }, area);
  const current = win.getBounds();
  if (['x', 'y', 'width', 'height'].some(key => next[key] !== current[key])) win.setBounds(next);
}
function endResize(point) { moveResize(point); clearTimeout(resizeTimeout); resizeSession = null; }
function setResizeMode(enabled) {
  endDrag(); endResize(); resizeMode = Boolean(enabled);
  // The entire selection accepts input while editing so every edge can be grabbed.
  win.setIgnoreMouseEvents(false);
  if (resizeMode) win.focus();
  win.webContents.send('action', { type: 'resize-mode', enabled: resizeMode });
  win.webContents.send('geometry', geometry());
}
function desktopStatus() {
  return {
    visible: Boolean(win && !win.isDestroyed() && win.isVisible() && !userHidden),
    action_id: syncState?.scene_action_id || pets[state.index]?.id || 'pet-1',
    paused: state.paused,
    bubble: state.bubble,
  };
}
function publishDesktopState() {
  if (syncServer) syncState = syncServer.updateDesktop(desktopStatus());
}
function hide() { userHidden = true; setResizeMode(false); win.hide(); publishDesktopState(); }
function resetSize() {
  endResize();
  const bounds = win.getBounds();
  const size = normalizeSize(DEFAULT_SIZE, screen.getDisplayMatching(bounds).workArea);
  win.setBounds(fit({ x: bounds.x + bounds.width - size, y: bounds.y + bounds.height - size, width: size, height: size }));
}
function show() { userHidden = false; win.showInactive(); keepOnTop(); publishDesktopState(); }
function action(value) { win.webContents.send('action', value); }
function createMenu() {
  const pets = JSON.parse(fs.readFileSync(path.join(__dirname, '../dist/pets/manifest.json'), 'utf8'));
  return Menu.buildFromTemplate([
    { id: 'show', label: '显示宠物', click: show },
    { id: 'hide', label: '隐藏宠物', click: hide },
    { type: 'separator' },
    { id: 'select', label: '切换动作', submenu: pets.map((pet, index) => ({ label: pet.name, type: 'radio', checked: state.index === index, click: () => { show(); action({ type: 'select', index }); } })) },
    { id: 'previous', label: '上一个动作', click: () => action({ type: 'previous' }) },
    { id: 'next', label: '下一个动作', click: () => action({ type: 'next' }) },
    { id: 'pause', label: state.paused ? '继续播放' : '暂停动画', click: () => action({ type: 'pause' }) },
    { type: 'separator' },
    { id: 'bubble', label: '显示气泡', type: 'checkbox', checked: state.bubble, click: () => action({ type: 'bubble' }) },
    { id: 'auto', label: '自动轮播（15 秒）', type: 'checkbox', checked: state.auto, click: () => action({ type: 'auto' }) },
    { id: 'resize', label: resizeMode ? '完成大小调整' : '调整大小…', click: () => { show(); setResizeMode(!resizeMode); } },
    { id: 'reset', label: '恢复默认大小', click: resetSize },
    { type: 'separator' },
    { id: 'quit', label: '退出 LookBuddy', click: () => app.quit() },
  ]);
}
function openMenu() {
  menuOpen = true;
  createMenu().popup({ ...(win.isVisible() ? { window: win } : {}), callback: () => { menuOpen = false; keepOnTop(); } });
}
if (!smoke && !app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', (_, argv) => { if (win) { show(); if (argv.includes('--adjust-size')) setResizeMode(true); } });
  app.whenReady().then(async () => {
    app.setAppUserModelId('com.lookbuddy.desktop');
    if (!smoke) {
      try {
        const saved = JSON.parse(fs.readFileSync(stateFile(), 'utf8'));
        state = { ...state, ...migratePreferences(saved) };
        delete state.size;
      } catch { /* first launch */ }
    }
    const area = screen.getPrimaryDisplay().workArea;
    const restoredArea = Number.isFinite(state.x) && Number.isFinite(state.y) ? screen.getDisplayMatching({ x: state.x, y: state.y, width: state.width, height: state.width }).workArea : area;
    const width = normalizeSize(state.width, restoredArea), height = width;
    state.width = width;
    const position = fit({ width, height, x: Number.isFinite(state.x) ? state.x : area.x + area.width - width - 40, y: Number.isFinite(state.y) ? state.y : area.y + area.height - height - 28 });
    win = new BrowserWindow({ ...position, title: 'LookBuddy', frame: false, thickFrame: false, transparent: true, backgroundColor: '#00000000', hasShadow: false, alwaysOnTop: true, skipTaskbar: true, resizable: false, minimizable: false, maximizable: false, fullscreenable: false, show: false, webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true, backgroundThrottling: false } });
    nativeWindow = createWindowsWindow(win, screen);
    win.setAlwaysOnTop(true, 'screen-saver');
    win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
    win.webContents.on('will-navigate', (event) => event.preventDefault());
    win.webContents.on('render-process-gone', (_, details) => console.error('Renderer exited:', details.reason));
    ipcMain.handle('state:read', () => state);
    ipcMain.handle('window:geometry', geometry);
    ipcMain.handle('sync:read', () => syncState);
    if (!smoke) {
      syncServer = createSyncServer({
        initialDesktop: desktopStatus(),
        onUpdate: next => {
          syncState = next;
          if (petReady && win && !win.isDestroyed()) win.webContents.send('sync:updated', next);
        },
        onCommand: command => {
          if (command === 'show') show();
          else if (command === 'hide') hide();
          return desktopStatus();
        },
      });
      syncState = syncServer.snapshot();
      try {
        await syncServer.start();
        const addresses = localAddresses();
        console.log(`[LookBuddy LAN] http://127.0.0.1:${syncServer.port}`);
        for (const address of addresses) console.log(`[LookBuddy LAN] http://${address}:${syncServer.port}`);
      } catch (error) {
        console.error(`LookBuddy LAN sync unavailable: ${error.message}`);
      }
    }
    ipcMain.on('state:save', (_, next) => {
      if (Number.isInteger(next.index) && next.index >= 0 && next.index < pets.length) state.index = next.index;
      for (const key of ['bubble', 'auto', 'paused']) if (typeof next[key] === 'boolean') state[key] = next[key];
      publishDesktopState();
      if (!smoke) save();
    });
    ipcMain.on('resize:mode', (_, enabled) => setResizeMode(enabled));
    ipcMain.on('resize:reset', resetSize);
    ipcMain.on('resize:start', (_, edge, point) => {
      if (!resizeMode || !EDGES.has(edge) || !point || !Number.isFinite(point.x) || !Number.isFinite(point.y)) return;
      endDrag(); endResize(); win.setIgnoreMouseEvents(false);
      const start = win.getBounds();
      const area = screen.getDisplayMatching(start).workArea;
      resizeSession = { start, edge, pointer: point, area };
      resizeTimeout = setTimeout(endResize, 60000);
    });
    ipcMain.on('resize:move', (_, point) => moveResize(point));
    ipcMain.on('resize:end', (_, point) => endResize(point));
    ipcMain.on('resize:nudge', (_, edge, delta) => {
      if (!resizeMode || !EDGES.has(edge) || !Number.isFinite(delta)) return;
      const start = win.getBounds();
      const amount = Math.max(-20, Math.min(20, delta));
      win.setBounds(resizeBounds(start, edge, { x: edge.includes('w') ? -amount : edge.includes('e') ? amount : 0, y: edge.includes('n') ? -amount : edge.includes('s') ? amount : 0 }, screen.getDisplayMatching(start).workArea));
    });
    ipcMain.on('window:hit', (_, hit) => { if (!resizeMode && !resizeSession && !dragSession) win.setIgnoreMouseEvents(!Boolean(hit), { forward: true }); });
    ipcMain.on('drag:start', (_, point) => {
      if (!point || !Number.isFinite(point.x) || !Number.isFinite(point.y)) return;
      endDrag(); endResize();
      dragSession = { start: win.getBounds(), pointer: point, moved: false };
      win.setIgnoreMouseEvents(false);
      keepOnTop();
      dragTimeout = setTimeout(endDrag, 15000);
    });
    ipcMain.on('drag:move', (_, point) => moveDrag(point));
    ipcMain.on('drag:end', (_, point) => endDrag(point));
    win.on('moved', () => {
      const { x, y } = win.getBounds(); state.x = x; state.y = y;
      if (!smoke) save();
    });
    win.on('resize', () => {
      state.width = win.getBounds().width;
      win.webContents.send('geometry', geometry());
      if (!smoke) save();
    });
    win.on('blur', () => { endDrag(); endResize(); setImmediate(keepOnTop); });
    win.on('show', keepOnTop);
    win.on('restore', keepOnTop);
    win.on('focus', keepOnTop);
    win.on('always-on-top-changed', (_, enabled) => { if (!enabled) setImmediate(keepOnTop); });
    win.on('will-resize', event => event.preventDefault());
    screen.on('display-metrics-changed', () => {
      endDrag(); endResize();
      const bounds = win.getBounds();
      const size = normalizeSize(bounds.width, screen.getDisplayMatching(bounds).workArea);
      if (size !== bounds.width || size !== bounds.height) win.setBounds(fit({ ...bounds, width: size, height: size }));
      else clampPosition();
      win.webContents.send('geometry', geometry());
      keepOnTop();
    });
    await win.loadFile(path.join(__dirname, '../dist/index.html'));
    petReady = true;
    show();
    if (!smoke) {
      if (process.argv.includes('--adjust-size')) setResizeMode(true);
      tray = new Tray(nativeImage.createFromPath(path.join(__dirname, '../dist/pets/tray.png')));
      tray.setToolTip('LookBuddy · 单击 / 右键打开设置，双击显示宠物');
      tray.on('double-click', show);
      tray.on('right-click', openMenu);
      tray.on('click', openMenu);
      globalShortcut.register('CommandOrControl+Shift+L', () => win.isVisible() ? hide() : show());
      globalShortcut.register('CommandOrControl+Shift+Right', () => action({ type: 'next' }));
      globalShortcut.register('CommandOrControl+Shift+S', () => { show(); setResizeMode(!resizeMode); });
      cursorTimer = setInterval(() => {
        if (!win.isVisible() || dragSession || resizeMode || resizeSession) return;
        const cursor = screen.getCursorScreenPoint(), bounds = win.getBounds();
        win.webContents.send('cursor', { x: cursor.x - bounds.x, y: cursor.y - bounds.y });
      }, 70);
      topmostTimer = setInterval(keepOnTop, 1000);
    } else {
      try {
        if (process.argv.includes('--scene-smoke')) {
          await new Promise(resolve=>setTimeout(resolve,500));
          const result=await require('./scene-smoke.cjs').verifyScenes(win,pets,path.join(__dirname,'../artifacts'));
          console.log('SCENE SMOKE PASS',JSON.stringify(result));
          app.exit(0);
          return;
        }
        // Validate every imported animation and filename bubble in the real Electron renderer.
        await new Promise(resolve => setTimeout(resolve, 800));
        const clickRegression = await win.webContents.executeJavaScript(`(async () => {
          const before = await window.lookbuddy.readGeometry();
          for (let i=0;i<12;i++) { window.lookbuddy.dragStart({x:200,y:200}); window.lookbuddy.dragEnd({x:200,y:200}); await new Promise(r=>setTimeout(r,20)); }
          const after = await window.lookbuddy.readGeometry();
          return {before:before.width,after:after.width};
        })()`);
        if (clickRegression.before !== clickRegression.after) throw new Error('Click changed size: '+JSON.stringify(clickRegression));
        const dragRegression = await win.webContents.executeJavaScript(`(async () => {
          const before = await window.lookbuddy.readGeometry();
          for (let i=0;i<12;i++) {
            window.lookbuddy.dragStart({x:200,y:200});
            window.lookbuddy.dragMove({x:180,y:190});
            window.lookbuddy.dragEnd({x:180,y:190});
            await new Promise(r=>setTimeout(r,10));
            window.lookbuddy.dragStart({x:180,y:190});
            window.lookbuddy.dragMove({x:200,y:200});
            window.lookbuddy.dragEnd({x:200,y:200});
            await new Promise(r=>setTimeout(r,10));
          }
          // These inputs must be ignored until the user explicitly enters resize mode.
          window.lookbuddy.resizeStart('se',{x:200,y:200});
          window.lookbuddy.resizeMove({x:260,y:260});
          window.lookbuddy.resizeEnd({x:260,y:260});
          window.lookbuddy.resizeNudge('se',20);
          await new Promise(r=>setTimeout(r,20));
          const after = await window.lookbuddy.readGeometry();
          return {before:before.width,after:after.width,height:after.height,editing:after.editing};
        })()`);
        if (dragRegression.before !== dragRegression.after || dragRegression.after !== dragRegression.height || dragRegression.editing) throw new Error('Ordinary drag resized window: '+JSON.stringify(dragRegression));
        const overlappingApp = new BrowserWindow({ x: win.getBounds().x, y: win.getBounds().y, width: 500, height: 500, show: true, title: 'LookBuddy layer check' });
        await overlappingApp.loadURL('data:text/html,<title>LookBuddy layer check</title><body style="background:gray">Normal application window</body>');
        overlappingApp.focus();
        keepOnTop();
        await new Promise(resolve=>setTimeout(resolve,60));
        if (!win.isAlwaysOnTop() || win.isResizable()) throw new Error('Topmost/fixed-size flag failed');
        const topmost = { aboveNormalWindow: nativeWindow ? nativeWindow.isAbove(overlappingApp) : win.isAlwaysOnTop(), stealsFocus:win.isFocused() };
        if (!topmost.aboveNormalWindow) throw new Error('Pet is below the overlapping app in native z-order');
        if (topmost.stealsFocus) throw new Error('Raising pet stole keyboard focus');
        overlappingApp.setAlwaysOnTop(true);
        keepOnTop();
        if (nativeWindow && !nativeWindow.isAbove(overlappingApp)) throw new Error('Pet did not rise above another topmost window');
        topmost.aboveAnotherTopmostWindow = true;
        overlappingApp.destroy();
        win.setAlwaysOnTop(false);
        await new Promise(resolve=>setTimeout(resolve,60));
        if (!win.isAlwaysOnTop()) throw new Error('Topmost did not recover');
        const names = [];
        for (let index = 0; index < pets.length; index++) {
          names.push(await win.webContents.executeJavaScript(`(async () => { const image = document.querySelector('.pet-image'); await image.decode(); if (!image.naturalWidth) throw new Error('Image failed to load'); return document.querySelector('.pet-image').alt; })()`));
          createMenu().getMenuItemById('next').click();
          await new Promise(resolve => setTimeout(resolve, 80));
        }
        createMenu().getMenuItemById('previous').click();
        await new Promise(resolve => setTimeout(resolve, 80));
        if (state.index !== pets.length - 1) throw new Error('Tray previous action failed');
        createMenu().getMenuItemById('next').click();
        await new Promise(resolve => setTimeout(resolve, 80));
        createMenu().getMenuItemById('pause').click();
        await new Promise(resolve => setTimeout(resolve, 80));
        const paused = await win.webContents.executeJavaScript(`document.querySelector('.pet-image').src.endsWith('.png')`);
        if (createMenu().getMenuItemById('pause').label !== '继续播放') throw new Error('Tray playback state did not update');
        createMenu().getMenuItemById('pause').click();
        await new Promise(resolve => setTimeout(resolve, 80));
        createMenu().getMenuItemById('bubble').click();
        await new Promise(resolve => setTimeout(resolve, 80));
        if (await win.webContents.executeJavaScript(`Boolean(document.querySelector('.bubble'))`)) throw new Error('Tray bubble toggle failed');
        createMenu().getMenuItemById('bubble').click();
        await new Promise(resolve => setTimeout(resolve, 80));
        createMenu().getMenuItemById('hide').click();
        keepOnTop();
        if (win.isVisible()) throw new Error('Tray hide failed');
        createMenu().getMenuItemById('show').click();
        if (!win.isVisible()) throw new Error('Tray restore failed');
        const dailyControls = await win.webContents.executeJavaScript(`document.querySelectorAll('main.companion button, .toolbar, .hint-row').length`);
        if (dailyControls !== 0) throw new Error('Desktop still contains settings controls');
        createMenu().getMenuItemById('resize').click();
        const result = await win.webContents.executeJavaScript(`(async () => {
          const sleep = ms => new Promise(r => setTimeout(r, ms));
          for (let i = 0; i < 40 && !document.querySelector('.pet-image'); i++) await sleep(100);
          if (!document.querySelector('.pet-image')) throw new Error(document.body.innerText || 'No pet rendered');
          const image = document.querySelector('.pet-image'); await image.decode();
          const canvas = document.createElement('canvas'); canvas.width = image.naturalWidth; canvas.height = image.naturalHeight;
          const ctx = canvas.getContext('2d'); ctx.drawImage(image, 0, 0);
          const pixels = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
          let transparent = 0; for (let i=3; i<pixels.length; i+=4) if(pixels[i]===0) transparent++;
          await sleep(100);
          if (document.querySelectorAll('[data-resize-edge]').length !== 8) throw new Error('Resize handles missing');
          const original = document.querySelector('.pet-image').getBoundingClientRect();
          const before = await window.lookbuddy.readGeometry();
          // Exercise the same native sizing path used by edge dragging.
          window.lookbuddy.resizeNudge('nw', -20); await sleep(150);
          const after = await window.lookbuddy.readGeometry();
          const scaled = document.querySelector('.pet-image').getBoundingClientRect();
          // Windows physical-pixel rounding at 125/150% can differ by one DIP.
          if (Math.abs(after.width - (before.width - 20)) > 1 || after.width !== after.height) throw new Error('Native resize/aspect mismatch: '+JSON.stringify({before,after}));
          if (Math.abs(original.width/original.height - scaled.width/scaled.height) > 0.001) throw new Error('Pet proportions changed');
          const factorX = scaled.width / original.width, factorY = scaled.height / original.height;
          if (Math.abs(factorX - factorY) > 0.001) throw new Error('Non-uniform scaling');
          // Drive both threshold clamps through the bridge/native window.
          for (let i=0; i<60; i++) window.lookbuddy.resizeNudge('nw', -20);
          await sleep(150);
          const minimum = await window.lookbuddy.readGeometry();
          if (Math.abs(minimum.width - minimum.limits.min) > 1) throw new Error('Minimum clamp failed: '+JSON.stringify(minimum));
          for (let i=0; i<60; i++) window.lookbuddy.resizeNudge('nw', 20);
          await sleep(150);
          const maximum = await window.lookbuddy.readGeometry();
          if (maximum.width > maximum.limits.max + 1 || maximum.width !== maximum.height || maximum.width <= minimum.width) throw new Error('Maximum clamp failed');
          window.lookbuddy.resetSize(); await sleep(150);
          window.lookbuddy.resizeMode(false); await sleep(100);
          if (document.querySelector('.resize-overlay')) throw new Error('Resize mode did not finish');
          return { transparentPixels: transparent, width: window.innerWidth, height: window.innerHeight, resize: { before:before.width, after:after.width, min:minimum.width, max:maximum.width, ratioPreserved:true, handles:8 } };
        })()`);
        Object.assign(result, { names, paused, desktopControls: dailyControls, trayActions: 'passed', clickRegression, dragRegression, topmost });
        const expected = pets.map(pet => pet.name);
        if (JSON.stringify(result.names) !== JSON.stringify(expected) || !result.paused || !result.transparentPixels) throw new Error(JSON.stringify(result));
        const directory = path.join(__dirname, '../artifacts'); fs.mkdirSync(directory, { recursive: true });
        const scenes = [];
        for (const pet of pets.filter(pet => pet.bounds)) {
          const snapshot = {scene_action_id:pet.id, scene_event_id:`smoke:${pet.id}`, message:pet.name};
          win.webContents.send('sync:updated', snapshot);
          await new Promise(resolve => setTimeout(resolve, 150));
          const rendered = await win.webContents.executeJavaScript(`(async () => {
            const image=document.querySelector('.pet-image'); await image.decode();
            const crop=document.querySelector('.pet-crop').getBoundingClientRect();
            if (!image.naturalWidth || crop.width > window.innerWidth || crop.height > window.innerHeight) throw new Error('Scene did not fit');
            return {name:image.alt,src:image.src,width:crop.width,height:crop.height};
          })()`);
          if (rendered.name !== pet.name) throw new Error('Remote scene mismatch');
          scenes.push(rendered);
          fs.writeFileSync(path.join(directory, `scene-${pet.id}.png`), (await win.webContents.capturePage()).toPNG());
          if (pet.id === 'fatigue') {
            await new Promise(resolve => setTimeout(resolve, pet.durationMs + 100));
            if (!await win.webContents.executeJavaScript(`document.querySelector('.pet-image').src.endsWith('-last.png')`)) throw new Error('Scene did not hold its last frame');
          }
          win.webContents.send('sync:updated', snapshot);
          await new Promise(resolve => setTimeout(resolve, 30));
        }
        result.scenes = scenes;
        result.scenePlayback = 'once, then last frame';
        action({type:'select',index:0});
        await new Promise(resolve => setTimeout(resolve, 150));
        fs.writeFileSync(path.join(directory, 'smoke.json'), JSON.stringify(result, null, 2));
        fs.writeFileSync(path.join(directory, 'desktop-preview.png'), (await win.webContents.capturePage()).toPNG());
        setResizeMode(true);
        await new Promise(resolve => setTimeout(resolve, 150));
        fs.writeFileSync(path.join(directory, 'resize-preview.png'), (await win.webContents.capturePage()).toPNG());
        console.log('SMOKE PASS', JSON.stringify(result)); app.exit(0);
      } catch (error) { console.error(error); app.exit(1); }
    }
  }).catch(error => { console.error(error); app.exit(1); });
}
app.on('will-quit', () => { endDrag(); endResize(); clearInterval(cursorTimer); clearInterval(topmostTimer); globalShortcut.unregisterAll(); clearTimeout(saveTimer); if (syncServer) syncServer.close().catch(() => {}); if (win && !smoke) { try { fs.writeFileSync(stateFile(), JSON.stringify(state)); } catch {} } });
