const http = require('node:http');
const https = require('node:https');
const fs = require('node:fs');
const path = require('node:path');
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.wasm': 'application/wasm', '.json': 'application/json', '.svg': 'image/svg+xml' };
function backendUrl(value) {
  const url = new URL(value);
  if (url.username || url.password || url.search || url.hash || !['http:', 'https:'].includes(url.protocol)) throw new Error('Invalid backend URL');
  if (url.protocol === 'http:' && !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)) throw new Error('Remote backend requires HTTPS');
  if (url.pathname !== '/') throw new Error('Backend URL must be an origin');
  return url;
}
async function startServer({ root, backend, port = 5189 }) {
  const target = backendUrl(backend);
  const absoluteRoot = path.resolve(root);
  if (!fs.existsSync(path.join(absoluteRoot, 'index.html'))) throw new Error('Web build missing: run pnpm build:web');
  let origin;
  const server = http.createServer((req, res) => {
    if (req.headers.host !== new URL(origin).host || (req.headers.origin && req.headers.origin !== origin)) {
      res.writeHead(403); res.end('Forbidden origin'); return;
    }
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
    let url;
    try { url = new URL(req.url, origin); } catch { res.writeHead(400); res.end(); return; }
    if (url.pathname.startsWith('/v1/') || url.pathname === '/health') {
      const headers = { ...req.headers, host: target.host };
      delete headers.origin; delete headers.connection;
      const upstream = (target.protocol === 'https:' ? https : http).request(new URL(url.pathname + url.search, target), { method: req.method, headers, timeout: 30000 }, (reply) => {
        res.writeHead(reply.statusCode, { 'Content-Type': reply.headers['content-type'] || 'application/json' });
        reply.pipe(res);
      });
      upstream.on('timeout', () => upstream.destroy(new Error('Backend timeout')));
      upstream.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end('Backend unavailable'); });
      req.on('aborted', () => upstream.destroy());
      req.pipe(upstream); return;
    }
    if (!['GET', 'HEAD'].includes(req.method)) { res.writeHead(405); res.end(); return; }
    let decoded;
    try { decoded = decodeURIComponent(url.pathname); } catch { res.writeHead(400); res.end(); return; }
    if (decoded.includes('\\') || decoded.includes('\0')) { res.writeHead(403); res.end(); return; }
    const file = path.resolve(absoluteRoot, '.' + (decoded === '/' ? '/index.html' : decoded));
    if (!file.startsWith(absoluteRoot + path.sep)) { res.writeHead(403); res.end(); return; }
    fs.stat(file, (error, stat) => {
      if (error || !stat.isFile()) { res.writeHead(404); res.end('Not found'); return; }
      res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Content-Length': stat.size });
      if (req.method === 'HEAD') res.end(); else fs.createReadStream(file).on('error', () => res.destroy()).pipe(res);
    });
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', resolve);
  });
  origin = `http://127.0.0.1:${server.address().port}`;
  return { origin, close: () => new Promise((resolve) => { server.close(resolve); server.closeAllConnections(); }) };
}
module.exports = { startServer, backendUrl };
