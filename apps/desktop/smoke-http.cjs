const assert = require('node:assert/strict');
const crypto = require('node:crypto');
async function run({ dashboard, pet, server, waitFor }) {
  let tokens;
  async function request(endpoint, method = 'GET', body) {
    const response = await fetch(server.origin + '/v1/' + endpoint, { method,
      headers: { 'Content-Type': 'application/json', ...(tokens ? { Authorization: `Bearer ${tokens.accessToken}` } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
    assert.equal(response.ok, true, `HTTP ${response.status}: ${endpoint}`);
    return response.status === 204 ? null : response.json();
  }
  try {
    tokens = await request('auth/register', 'POST', { email: `desktop-${crypto.randomUUID()}@example.com`, password: 'isolated-desktop-test-2026', displayName: 'Desktop synthetic-camera test' });
    await dashboard.webContents.executeJavaScript(`localStorage.setItem('lookbuddy.auth.v1', ${JSON.stringify(JSON.stringify(tokens))})`);
    await dashboard.loadURL(server.origin + '/');
    await waitFor(dashboard, "!!document.querySelector('.dashboard')");
    await waitFor(pet, 'window.lookbuddyDesktop.state().then(s=>s.loggedIn && !s.running && !s.reminderId)');
    await dashboard.webContents.executeJavaScript("document.querySelector('.camera-toolbar .primary').click(); document.querySelector('.camera-toolbar .primary').click()");
    await waitFor(pet, 'window.lookbuddyDesktop.state().then(s=>s.running)');
    assert.equal(await dashboard.webContents.executeJavaScript("document.querySelector('video').srcObject.getVideoTracks().length"), 1);
    assert.equal(await pet.webContents.executeJavaScript("document.querySelector('video') === null"), true);
    dashboard.hide();
    await new Promise((resolve) => setTimeout(resolve, 3500));
    const observations = await request('expressions?namespace=live');
    assert.equal(observations.length >= 2, true, 'hidden owner must continue submitting observations');
    assert.equal(new Set(observations.map((value) => value.sessionId)).size, 1, 'one perception session');
    await pet.webContents.executeJavaScript("window.lookbuddyDesktop.command({type:'busy',busy:true})");
    await waitFor(pet, 'window.lookbuddyDesktop.state().then(s=>s.busy)');
    assert.equal((await request('preferences')).busy, true);
    await pet.webContents.executeJavaScript("window.lookbuddyDesktop.command({type:'pause'})");
    await waitFor(pet, 'window.lookbuddyDesktop.state().then(s=>!s.running)');
    assert.equal(await dashboard.webContents.executeJavaScript("document.querySelector('video').srcObject === null"), true);
    await new Promise((resolve) => setTimeout(resolve, 500));
    const stopped = (await request('expressions?namespace=live')).length;
    await new Promise((resolve) => setTimeout(resolve, 1200));
    assert.equal((await request('expressions?namespace=live')).length, stopped, 'pause stops ingestion');
    console.log(JSON.stringify({ desktopHttpSmoke: 'passed', syntheticCamera: true, checks: ['register-login', 'single-stream', 'hidden-ingestion', 'busy-persisted', 'pause-releases-camera', 'pause-stops-ingestion'] }));
  } finally {
    if (tokens) {
      await dashboard.webContents.executeJavaScript("window.lookbuddyDesktop.onCommand(()=>{}); document.querySelectorAll('button').forEach(b=>{if(b.textContent==='暂停并释放摄像头')b.click()})").catch(() => undefined);
      await request('account', 'DELETE');
    }
  }
}
module.exports = { run };
