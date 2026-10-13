// Keep Electron's official artifact URLs and npm-published checksums. Use the
// Windows system downloader when Node's fetch cannot connect through the proxy.
const path = require('node:path');
const fs = require('node:fs/promises');
const { createRequire } = require('node:module');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const run = promisify(execFile);
async function install() {
  if (process.platform !== 'win32' || process.arch !== 'x64') throw new Error('This fallback installer targets Windows x64');
  const electronPackage = require.resolve('electron/package.json');
  const electronRequire = createRequire(electronPackage);
  const { downloadArtifact } = electronRequire('@electron/get');
  const { version } = electronRequire('./package.json');
  const cacheRoot = path.join(__dirname, '.electron-cache');
  console.log(`Downloading/checking official Electron ${version} (Windows x64)`);
  const zip = await downloadArtifact({ version, platform: 'win32', arch: 'x64', artifactName: 'electron', cacheRoot,
    checksums: electronRequire('./checksums.json'),
    downloader: { download: async (url, destination) => {
      if (!url.startsWith('https://github.com/electron/electron/releases/')) throw new Error('Unexpected artifact source');
      const effectiveUrl = process.env.LOOKBUDDY_ELECTRON_MIRROR === 'npmmirror'
        ? `https://cdn.npmmirror.com/binaries/electron/${version}/${path.posix.basename(new URL(url).pathname)}` : url;
      await run('curl.exe', ['--fail', '--location', '--retry', '1', '--connect-timeout', '20', '--max-time', '180', '--output', destination, effectiveUrl], { windowsHide: true, maxBuffer: 1024 * 1024 });
    } },
  });
  const zipDir = path.join(__dirname, '.electron-zips');
  await fs.mkdir(zipDir, { recursive: true });
  await fs.copyFile(zip, path.join(zipDir, path.basename(zip)));
  const result = await run(process.execPath, [path.join(path.dirname(electronPackage), 'install.js')], { env: { ...process.env, electron_config_cache: cacheRoot }, windowsHide: true });
  console.log(result.stdout || 'Official installer extracted the verified runtime.');
}
install().catch((error) => { console.error(error.message); process.exitCode = 1; });
