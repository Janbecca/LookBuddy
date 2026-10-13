const fs = require('node:fs/promises');
const path = require('node:path');
const { packager } = require('@electron/packager');
const crypto = require('node:crypto');
const { createReadStream } = require('node:fs');
async function run() {
  const stage = await fs.mkdtemp(path.join(__dirname, '.package-stage-'));
  try {
    for (const file of ['main.cjs', 'preload.cjs', 'state.cjs', 'server.cjs', 'smoke.cjs', 'smoke-http.cjs']) await fs.copyFile(path.join(__dirname, file), path.join(stage, file));
    await fs.writeFile(path.join(stage, 'package.json'), JSON.stringify({ name: 'lookbuddy-desktop', version: '0.1.0', main: 'main.cjs', description: 'LookBuddy desktop debugging container', author: 'LookBuddy', license: 'UNLICENSED' }));
    await fs.cp(path.join(__dirname, '../web/dist'), path.join(stage, 'web'), { recursive: true });
    const version = require('electron/package.json').version;
    const zipDir = path.join(__dirname, '.electron-zips');
    const zipName = `electron-v${version}-win32-x64.zip`;
    let cachedZip = false;
    try { await fs.access(path.join(zipDir, zipName)); cachedZip = true; } catch { /* normal installer cache */ }
    if (cachedZip) {
      const hash = crypto.createHash('sha256');
      for await (const chunk of createReadStream(path.join(zipDir, zipName))) hash.update(chunk);
      const expected = require('electron/checksums.json')[zipName];
      if (hash.digest('hex') !== expected) throw new Error('Electron ZIP checksum mismatch');
    }
    const result = await packager({ dir: stage, name: 'LookBuddy', out: path.join(__dirname, 'release'), platform: 'win32', arch: 'x64', electronVersion: version, ...(cachedZip ? { electronZipDir: zipDir } : {}), asar: true, overwrite: false });
    console.log('Windows portable build:', result.join(', '));
  } finally {
    const resolved = path.resolve(stage);
    if (path.dirname(resolved) !== __dirname || !path.basename(resolved).startsWith('.package-stage-')) throw new Error('Unsafe staging cleanup');
    await fs.rm(resolved, { recursive: true, force: true });
  }
}
run().catch((error) => { console.error(error); process.exitCode = 1; });
