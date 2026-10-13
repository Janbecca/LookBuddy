const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
async function waitFor(window, expression) {
  for (let attempt = 0; attempt < 300; attempt++) {
    if (await window.webContents.executeJavaScript(expression)) return;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error('Renderer did not become ready: ' + expression);
}
async function run({ dashboard, pet, server, app, httpSmoke }) {
  try {
    await waitFor(dashboard, "!!document.querySelector('.auth-shell') && window.lookbuddyDesktop?.role === 'dashboard'");
    await waitFor(pet, "!!document.querySelector('.pet-shell') && window.lookbuddyDesktop?.role === 'pet'");
    assert.equal(dashboard.webContents.getLastWebPreferences().nodeIntegration, false);
    assert.equal(pet.webContents.getLastWebPreferences().sandbox, true);
    assert.equal(pet.isAlwaysOnTop(), true);
    assert.equal(pet.getMediaSourceId().length > 0, true);
    for (const asset of ['/models/face_landmarker.task', '/models/pose_landmarker_lite.task', '/wasm/vision_wasm_internal.wasm']) {
      const response = await fetch(server.origin + asset, { method: 'HEAD' });
      assert.equal(response.status, 200);
      if (asset.endsWith('.wasm')) assert.equal(response.headers.get('content-type'), 'application/wasm');
    }
    assert.equal(await dashboard.webContents.executeJavaScript('window.lookbuddyModelProbe()'), true);
    assert.equal(await pet.webContents.executeJavaScript("navigator.mediaDevices.getUserMedia({video:true}).then(stream=>{stream.getTracks().forEach(t=>t.stop());return false},()=>true)"), true);
    await dashboard.webContents.executeJavaScript("window.__commands = []; window.lookbuddyDesktop.onCommand(c => window.__commands.push(c)); window.lookbuddyDesktop.publish({loggedIn:true,running:true,busy:false,status:'SMOKE_RUNNING',emotion:'joyful',posture:'normal',reminderId:'smoke-reminder',message:'SMOKE_MESSAGE',secret:'must_not_cross'})");
    await waitFor(pet, "document.body.textContent.includes('SMOKE_MESSAGE')");
    const state = await pet.webContents.executeJavaScript('window.lookbuddyDesktop.state()');
    assert.equal(state.secret, undefined);
    const artifactDir = path.join(app.isPackaged ? path.dirname(app.getPath('exe')) : __dirname, '.smoke');
    await fs.mkdir(artifactDir, { recursive: true });
    const preview = await pet.webContents.capturePage();
    await fs.writeFile(path.join(artifactDir, 'pet.png'), preview.toPNG());
    await pet.webContents.executeJavaScript("window.lookbuddyDesktop.command({type:'busy',busy:true})");
    await waitFor(dashboard, "window.__commands.some(c => c.type === 'busy' && c.busy === true)");
    assert.equal(await pet.webContents.executeJavaScript("window.lookbuddyDesktop.publish({}).then(()=>false,()=>true)"), true);
    assert.equal(await pet.webContents.executeJavaScript("window.lookbuddyDesktop.command({type:'ignored',reminderId:'stale'}).then(()=>false,()=>true)"), true);
    assert.equal(await pet.webContents.executeJavaScript("fetch('/v1/preferences').then(()=>false,()=>true)"), true);
    dashboard.close();
    assert.equal(dashboard.isDestroyed(), false);
    assert.equal(dashboard.isVisible(), false);
    await pet.webContents.executeJavaScript('window.lookbuddyDesktop.showDashboard()');
    assert.equal(dashboard.isVisible(), true);
    dashboard.hide();
    if (httpSmoke) await require('./smoke-http.cjs').run({ dashboard, pet, server, waitFor });
    console.log(JSON.stringify({ desktopSmoke: 'passed', checks: ['isolated-renderers', 'pet-render', 'assets', 'model-initialization', 'pet-camera-denied', 'state-sync', 'commands', 'ipc-role-validation', 'pet-api-denied', 'hide-and-reopen'], realCameraUsed: false }));
    app.quit();
  } catch (error) { console.error(error); app.exit(1); }
}
module.exports = { run };
