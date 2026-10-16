const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const { sceneAction } = require('./scenes.cjs');

const DEFAULT_PORT = 5211;
const MAX_BODY_BYTES = 64 * 1024;
const ONLINE_TIMEOUT_MS = 45_000;

function defaultTokenFile() {
  const base = process.env.LOCALAPPDATA || os.homedir();
  return path.join(base, 'LookBuddy', 'desktop-token');
}

function loadOrCreateToken(tokenFile = defaultTokenFile()) {
  const configured = String(process.env.LOOKBUDDY_DESKTOP_TOKEN || '').trim();
  if (configured) return configured;
  try {
    const existing = fs.readFileSync(tokenFile, 'utf8').trim();
    if (existing) return existing;
  } catch { /* first launch */ }
  const token = crypto.randomBytes(32).toString('base64url');
  fs.mkdirSync(path.dirname(tokenFile), { recursive: true });
  fs.writeFileSync(tokenFile, token, { encoding: 'utf8', mode: 0o600 });
  return token;
}

function safeText(value, maxLength) {
  return typeof value === 'string' ? value.trim().slice(0, maxLength) : '';
}

function authorized(request, token) {
  const supplied = Buffer.from(String(request.headers['x-lookbuddy-token'] || ''), 'utf8');
  const expected = Buffer.from(token, 'utf8');
  return supplied.length === expected.length && crypto.timingSafeEqual(supplied, expected);
}

function readJson(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let length = 0;
    request.on('data', chunk => {
      length += chunk.length;
      if (length > MAX_BODY_BYTES) {
        reject(Object.assign(new Error('payload too large'), { status: 413 }));
        request.destroy();
        return;
      }
      chunks.push(chunk);
    });
    request.on('end', () => {
      try {
        const value = chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {};
        if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('body must be an object');
        resolve(value);
      } catch {
        reject(Object.assign(new Error('invalid json body'), { status: 400 }));
      }
    });
    request.on('error', reject);
  });
}

function sendJson(response, status, payload) {
  const data = Buffer.from(JSON.stringify(payload), 'utf8');
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': data.length,
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type, X-LookBuddy-Token',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  });
  response.end(data);
}

function localAddresses() {
  const values = [];
  for (const interfaces of Object.values(os.networkInterfaces())) {
    for (const item of interfaces || []) {
      if (item.family === 'IPv4' && !item.internal) values.push(item.address);
    }
  }
  return values;
}

function createSyncServer(options = {}) {
  const token = options.token || loadOrCreateToken(options.tokenFile);
  const port = Number(options.port ?? process.env.LOOKBUDDY_DESKTOP_PORT ?? DEFAULT_PORT);
  const host = options.host || '0.0.0.0';
  const onUpdate = typeof options.onUpdate === 'function' ? options.onUpdate : () => {};
  const onCommand = typeof options.onCommand === 'function' ? options.onCommand : () => ({});
  let server;
  let revision = 0;
  let sceneState = { scene_action_id: '', scene_event_id: '', scene_message: '', scene_completed: false };
  let reminderTimer;
  let sceneSerial = 0;
  let sceneKey = '';
  const defaultAction = () => ['evening', 'evening_rain', 'night'].includes(appState.default_scene || appState.scene) ? 'night' : 'pet-1';
  let appState = {
    client_id: '',
    scene: 'idle',
    working: false,
    mood: 'calm',
    message: '',
    last_seen_at: null,
  };
  let desktopState = {
    visible: true,
    action_id: 'pet-1',
    paused: false,
    bubble: true,
    ...(options.initialDesktop || {}),
  };

  function isConnected() {
    return appState.last_seen_at !== null && Date.now() - appState.last_seen_at < ONLINE_TIMEOUT_MS;
  }

  function snapshot(extra = {}) {
    return {
      revision,
      connected: isConnected(),
      ...appState,
      last_seen_at: appState.last_seen_at === null ? null : appState.last_seen_at / 1000,
      ...desktopState,
      ...sceneState,
      action_id: sceneState.scene_action_id || desktopState.action_id,
      ...extra,
    };
  }

  function publish(extra = {}) {
    const next = snapshot(extra);
    onUpdate(next);
    return next;
  }

  function updateDesktop(patch) {
    let changed = false;
    for (const key of ['visible', 'paused', 'bubble']) {
      if (typeof patch?.[key] === 'boolean' && desktopState[key] !== patch[key]) {
        desktopState[key] = patch[key]; changed = true;
      }
    }
    if (typeof patch?.action_id === 'string' && patch.action_id && desktopState.action_id !== patch.action_id) {
      desktopState.action_id = patch.action_id.slice(0, 80); changed = true;
    }
    if (changed) revision += 1;
    return publish();
  }

  function syncFromApp(body) {
    const wasConnected = isConnected();
    const previous = { ...appState };
    const incomingMessage = safeText(body.message, 160);
    appState = {
      client_id: safeText(body.client_id, 80) || 'android',
      scene: safeText(body.scene, 80) || 'idle',
      working: Boolean(body.working),
      mood: safeText(body.mood, 40) || 'calm',
      default_scene: safeText(body.default_scene, 80),
      message: incomingMessage,
      completed: body.completed === true,
      last_seen_at: Date.now(),
    };
    appState.label = safeText(body.label || body.suggestedLabel, 40);
    clearTimeout(reminderTimer); reminderTimer = undefined;
    setScene(body, safeText(body.event_id || body.id, 80));
    const changed = ['client_id', 'scene', 'working', 'mood', 'message', 'completed', 'default_scene', 'label'].some(key => previous[key] !== appState[key]);
    if (changed || !wasConnected) revision += 1;
    return publish();
  }

  function setScene(body, eventId = '') {
    const action = sceneAction(body, defaultAction());
    const key = [body.scene || body.category || body.kind, body.label || body.mood || body.suggestedLabel].join(':');
    // Heartbeats must not restart an already playing scene.
    if (sceneKey === key && sceneState.scene_action_id === action && (!eventId || sceneState.scene_event_id === eventId)) {
      sceneState.scene_message = safeText(body.message, 160);
      sceneState.scene_completed = body.completed === true;
      return;
    }
    sceneKey = key;
    sceneState = { scene_action_id: action, scene_event_id: eventId || `scene:${++sceneSerial}`, scene_message: safeText(body.message, 160), scene_completed: body.completed === true };
    revision += 1;
  }

  async function handle(request, response) {
    const url = new URL(request.url || '/', 'http://127.0.0.1');
    if (request.method === 'OPTIONS') {
      sendJson(response, 204, {});
      return;
    }
    if (request.method === 'GET' && url.pathname === '/health') {
      sendJson(response, 200, { status: 'ok', service: 'lookbuddy-desktop', revision, addresses: localAddresses(), port });
      return;
    }
    if (!authorized(request, token)) {
      sendJson(response, 401, { error: 'unauthorized' });
      return;
    }
    if (request.method === 'GET' && url.pathname === '/v1/state') {
      sendJson(response, 200, { ok: true, desktop: snapshot() });
      return;
    }
    if (request.method !== 'POST' || !['/v1/sync', '/v1/reminders', '/v1/commands'].includes(url.pathname)) {
      sendJson(response, 404, { error: 'not found' });
      return;
    }
    try {
      const body = await readJson(request);
      if (url.pathname === '/v1/sync') {
        sendJson(response, 200, { ok: true, desktop: syncFromApp(body) });
        return;
      }
      if (url.pathname === '/v1/reminders') {
        const message = safeText(body.message, 160);
        clearTimeout(reminderTimer);
        setScene({ ...body, scene: body.scene || body.category || body.kind, message }, safeText(body.event_id || body.id, 80));
        // The next Home snapshot takes priority immediately; legacy notifications expire otherwise.
        reminderTimer = setTimeout(() => {
          reminderTimer = undefined;
          sceneKey = '';
          sceneState = { scene_action_id: defaultAction(), scene_event_id: `scene:${++sceneSerial}`, scene_message: appState.message, scene_completed: appState.completed === true };
          revision += 1;
          publish();
        }, options.reminderDurationMs ?? 8000);
        sendJson(response, 202, { ok: true, accepted: true, desktop: publish() });
        return;
      }
      const action = safeText(body.action, 20);
      if (!['show', 'hide'].includes(action)) {
        sendJson(response, 400, { error: 'action must be show or hide' });
        return;
      }
      const patch = onCommand(action) || {};
      sendJson(response, 200, { ok: true, desktop: updateDesktop(patch) });
    } catch (error) {
      if (!response.headersSent) sendJson(response, error.status || 400, { error: error.message || 'invalid request' });
    }
  }

  return {
    token,
    port,
    snapshot,
    updateDesktop,
    start() {
      if (server) return Promise.resolve(server.address());
      server = http.createServer((request, response) => {
        handle(request, response).catch(error => {
          console.error('Desktop sync request failed:', error.message);
          if (!response.headersSent) sendJson(response, 500, { error: 'internal error' });
        });
      });
      server.requestTimeout = 10_000;
      return new Promise((resolve, reject) => {
        const failed = error => { server = undefined; reject(error); };
        server.once('error', failed);
        server.listen(port, host, () => {
          server.off('error', failed);
          resolve(server.address());
        });
      });
    },
    close() {
      clearTimeout(reminderTimer);
      if (!server) return Promise.resolve();
      const active = server;
      server = undefined;
      return new Promise(resolve => active.close(resolve));
    },
  };
}

module.exports = { createSyncServer, defaultTokenFile, loadOrCreateToken, localAddresses };
