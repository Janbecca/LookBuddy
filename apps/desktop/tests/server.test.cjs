const { test } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const path = require('node:path');
const { startServer, backendUrl } = require('../server.cjs');
test('backend accepts loopback HTTP or remote HTTPS but no credential/base path', () => {
  assert.equal(backendUrl('http://127.0.0.1:8000').port, '8000');
  assert.equal(backendUrl('https://example.com').protocol, 'https:');
  for (const url of ['http://example.com', 'https://user:secret@example.com', 'https://example.com/api', 'file:///x']) assert.throws(() => backendUrl(url));
});
test('local server serves built assets and proxies API; rejects external origin and traversal', async () => {
  let auth;
  const backend = http.createServer((req, res) => { auth = req.headers.authorization; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ path: req.url, method: req.method })); });
  await new Promise((resolve) => backend.listen(0, '127.0.0.1', resolve));
  const server = await startServer({ root: path.join(__dirname, '../../web/dist'), backend: `http://127.0.0.1:${backend.address().port}`, port: 0 });
  try {
    const index = await fetch(server.origin); assert.equal(index.status, 200); assert.match(index.headers.get('content-security-policy'), /frame-ancestors 'none'/);
    const wasm = await fetch(server.origin + '/wasm/vision_wasm_internal.wasm', { method: 'HEAD' }); assert.equal(wasm.headers.get('content-type'), 'application/wasm');
    const api = await fetch(server.origin + '/v1/preferences?x=1', { headers: { authorization: 'Bearer test' } });
    assert.deepEqual(await api.json(), { path: '/v1/preferences?x=1', method: 'GET' }); assert.equal(auth, 'Bearer test');
    assert.equal((await fetch(server.origin + '/v1/preferences', { method: 'POST', headers: { origin: 'https://evil.example' } })).status, 403);
    assert.equal((await fetch(server.origin + '/%2e%2e%2fpackage.json')).status, 403);
    assert.equal((await fetch(server.origin + '/%5csecret')).status, 403);
    assert.equal((await fetch(server.origin + '/index.html', { method: 'POST' })).status, 405);
    assert.equal((await fetch(server.origin + '/missing')).status, 404);
  } finally { await server.close(); backend.closeAllConnections(); await new Promise((resolve) => backend.close(resolve)); }
});
