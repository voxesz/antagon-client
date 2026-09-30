const fs = require('node:fs');
const fsp = fs.promises;
const path = require('node:path');
const os = require('node:os');
const { spawn, execFile } = require('node:child_process');
const runFile = require('node:util').promisify(execFile);
const { writeDownload } = require('./files.cjs');
const { extract } = require('./runtime.cjs');

const REPO = 'voxesz/antagon-client';
const ASSET = process.platform === 'win32' ? 'Antagon-Client-Windows.zip' : 'Antagon-Client-macOS.zip';
const HOSTS = new Set(['github.com', 'objects.githubusercontent.com', 'release-assets.githubusercontent.com']);

const MAC_SWAP = `while kill -0 "$1" 2>/dev/null; do sleep 0.3; done
rm -rf "$2.old"
if mv "$2" "$2.old" && mv "$3" "$2"; then rm -rf "$2.old"; else [ -d "$2" ] || mv "$2.old" "$2"; fi
open "$2"
`;

const WINDOWS_SWAP = `@echo off
set "LOG=%TEMP%\\antagon-update.log"
echo %date% %time% start > "%LOG%"
set /a n=0
:wait
set /a n+=1
if %n% gtr 120 goto copy
tasklist /FI "PID eq %~1" /NH 2>nul | find "%~1" >nul && goto sleep
tasklist /FI "IMAGENAME eq Antagon Client.exe" /NH 2>nul | find /I "Antagon Client.exe" >nul && goto sleep
goto copy
:sleep
ping -n 2 127.0.0.1 >nul
goto wait
:copy
set /a t=0
:retry
set /a t+=1
robocopy "%~2" "%~3" /E /R:2 /W:1 /NP /NFL /NDL /LOG+:"%LOG%" >nul
if not errorlevel 8 goto done
if %t% geq 15 goto done
ping -n 3 127.0.0.1 >nul
goto retry
:done
echo %date% %time% robocopy %errorlevel% after %t% tries >> "%LOG%"
start "" "%~4"
`;

function newer(latest, current) {
  const a = latest.replace(/^v/, '').split('.').map(Number);
  const b = current.split('.').map(Number);
  for (let i = 0; i < 3; i++) if ((a[i] || 0) !== (b[i] || 0)) return (a[i] || 0) > (b[i] || 0);
  return false;
}

async function check(current) {
  const res = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, {
    headers: { accept: 'application/vnd.github+json' },
    signal: AbortSignal.timeout(1e4),
  });
  if (!res.ok) throw Error('GitHub indisponível.');
  const release = await res.json();
  const asset = release.assets?.find((a) => a.name === ASSET);
  if (!asset || !/^sha256:[a-f0-9]{64}$/.test(asset.digest || '') || !newer(release.tag_name, current)) return null;
  return {
    version: release.tag_name.replace(/^v/, ''),
    url: asset.browser_download_url,
    sha256: asset.digest.slice(7),
    size: asset.size,
  };
}

async function download(update, file, progress) {
  const res = await fetch(update.url, { signal: AbortSignal.timeout(6e5) });
  if (!res.ok || !HOSTS.has(new URL(res.url).hostname)) throw Error('Não foi possível baixar a atualização.');
  await writeDownload(res, file, {
    algorithm: 'sha256',
    expected: update.sha256,
    progress: (bytes) => progress(Math.min(100, Math.round((bytes / update.size) * 100))),
  });
}

async function install(update, progress) {
  const dir = await fsp.mkdtemp(path.join(os.tmpdir(), 'antagon-update-'));
  const zip = path.join(dir, ASSET);
  const unpacked = path.join(dir, 'new');
  await download(update, zip, progress);
  await fsp.mkdir(unpacked);
  const script = path.join(dir, process.platform === 'win32' ? 'swap.cmd' : 'swap.sh');

  if (process.platform === 'win32') {
    await extract(zip, unpacked);
    const fresh = path.join(unpacked, 'Antagon Client-win32-x64');
    if (!fs.existsSync(path.join(fresh, 'Antagon Client.exe'))) throw Error('Atualização inválida.');
    await fsp.writeFile(script, WINDOWS_SWAP.replaceAll('\n', '\r\n'));
    const args = [script, String(process.pid), fresh, path.dirname(process.execPath), process.execPath];
    spawn(process.env.ComSpec || 'cmd.exe', ['/d', '/s', '/c', `"${args.map((a) => `"${a}"`).join(' ')}"`], {
      detached: true,
      stdio: 'ignore',
      windowsHide: true,
      windowsVerbatimArguments: true,
    }).unref();
  } else {
    await runFile('/usr/bin/ditto', ['-x', '-k', zip, unpacked]);
    const fresh = path.join(unpacked, 'Antagon Client.app');
    if (!fs.existsSync(fresh)) throw Error('Atualização inválida.');
    await fsp.writeFile(script, MAC_SWAP);
    const bundle = path.resolve(process.execPath, '../../..');
    spawn('/bin/bash', [script, String(process.pid), bundle, fresh], { detached: true, stdio: 'ignore' }).unref();
  }
}

module.exports = { check, install, newer };
