const fs = require('node:fs');
const fsp = fs.promises;
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const { spawn, execFileSync } = require('node:child_process');
const { extract } = require('./runtime.cjs');

const REPO = 'voxesz/antagon-client';
const ASSET = process.platform === 'win32' ? 'Antagon-Client-Windows.zip' : 'Antagon-Client-macOS.zip';
const HOSTS = new Set(['github.com', 'objects.githubusercontent.com', 'release-assets.githubusercontent.com']);

const MAC_SWAP = `while kill -0 "$1" 2>/dev/null; do sleep 0.3; done
rm -rf "$2.old"
if mv "$2" "$2.old" && mv "$3" "$2"; then rm -rf "$2.old"; else [ -d "$2" ] || mv "$2.old" "$2"; fi
open "$2"
`;

const WINDOWS_SWAP = `param($ProcessId, $Source, $Target, $Executable)
while (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue) { Start-Sleep -Milliseconds 300 }
robocopy $Source $Target /MIR /R:5 /W:1 | Out-Null
Start-Process -FilePath $Executable
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
  if (!res.ok) return null;
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
  const hash = crypto.createHash('sha256');
  const out = fs.createWriteStream(file);
  let bytes = 0;
  for await (const chunk of res.body) {
    hash.update(chunk);
    bytes += chunk.length;
    if (!out.write(chunk)) await new Promise((resolve) => out.once('drain', resolve));
    progress(Math.round((bytes / update.size) * 100));
  }
  await new Promise((resolve, reject) => out.end((error) => (error ? reject(error) : resolve())));
  if (hash.digest('hex') !== update.sha256) throw Error('A atualização baixada está corrompida. Tente de novo.');
}

async function install(update, progress) {
  const dir = await fsp.mkdtemp(path.join(os.tmpdir(), 'antagon-update-'));
  const zip = path.join(dir, ASSET);
  const unpacked = path.join(dir, 'new');
  await download(update, zip, progress);
  await fsp.mkdir(unpacked);
  const script = path.join(dir, process.platform === 'win32' ? 'swap.ps1' : 'swap.sh');

  if (process.platform === 'win32') {
    await extract(zip, unpacked);
    const fresh = path.join(unpacked, 'Antagon Client-win32-x64');
    if (!fs.existsSync(path.join(fresh, 'Antagon Client.exe'))) throw Error('Atualização inválida.');
    await fsp.writeFile(script, WINDOWS_SWAP);
    const args = ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-WindowStyle', 'Hidden', '-File', script];
    spawn('powershell.exe', [...args, String(process.pid), fresh, path.dirname(process.execPath), process.execPath], {
      detached: true,
      stdio: 'ignore',
      windowsHide: true,
    }).unref();
  } else {
    execFileSync('/usr/bin/ditto', ['-x', '-k', zip, unpacked]);
    const fresh = path.join(unpacked, 'Antagon Client.app');
    if (!fs.existsSync(fresh)) throw Error('Atualização inválida.');
    await fsp.writeFile(script, MAC_SWAP);
    const bundle = path.resolve(process.execPath, '../../..');
    spawn('/bin/bash', [script, String(process.pid), bundle, fresh], { detached: true, stdio: 'ignore' }).unref();
  }
}

module.exports = { check, install, newer };
