const fs = require('node:fs');
const fsp = fs.promises;
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const { spawn, execFileSync } = require('node:child_process');
const { unzipSync } = require('fflate');
const FORGE_VERSION = '1.8.9-11.15.1.2318-1.8.9';
const FORGE_URL = `https://maven.minecraftforge.net/net/minecraftforge/forge/${FORGE_VERSION}/forge-${FORGE_VERSION}-universal.jar`;
const PLATFORM = process.platform === 'win32' ? 'windows' : 'osx';
const JAVA = {
  'osx-x64': {
    url: 'https://cdn.azul.com/zulu/bin/zulu8.96.0.205-ca-jre8.0.504-macosx_x64.tar.gz',
    sha256: '87c82660534472cf7cecb33cb30ec420fd8f863b12c1d5a2cef9970ed990d64f',
    directory: 'zulu8.96.0.205-ca-jre8.0.504-macosx_x64',
    executable: 'java',
  },
  osx: {
    url: 'https://cdn.azul.com/zulu/bin/zulu8.96.0.205-ca-jre8.0.504-macosx_aarch64.tar.gz',
    sha256: '2bf60d7d93268a4f37b3ec5e8b710ccab240e7217966e557939d4c39fc72a9cd',
    directory: 'zulu8.96.0.205-ca-jre8.0.504-macosx_aarch64',
    executable: 'java',
  },
  windows: {
    url: 'https://cdn.azul.com/zulu/bin/zulu8.96.0.205-ca-jre8.0.504-win_x64.zip',
    sha256: 'a4f32724c6d819c20372ac069fefa6e6c0319e1d79ba6ce4ee338d4c7e051a12',
    directory: 'zulu8.96.0.205-ca-jre8.0.504-win_x64',
    executable: 'javaw.exe',
  },
}[PLATFORM === 'osx' && process.arch !== 'arm64' ? 'osx-x64' : PLATFORM];
const NATIVE_FILE = PLATFORM === 'windows' ? /\.dll$/i : /\.(dylib|jnilib)$/;
const HOSTS = new Set([
  'piston-meta.mojang.com',
  'launchermeta.mojang.com',
  'piston-data.mojang.com',
  'launcher.mojang.com',
  'libraries.minecraft.net',
  'resources.download.minecraft.net',
  'maven.minecraftforge.net',
  'cdn.azul.com',
]);
function checkURL(url) {
  const u = new URL(url);
  if (u.protocol !== 'https:' || !HOSTS.has(u.hostname)) throw Error('Origem de download não permitida.');
  return u.href;
}
async function fetchOK(url) {
  const res = await fetch(checkURL(url), { signal: AbortSignal.timeout(12e4), redirect: 'error' });
  if (!res.ok)
    throw Error(
      `Download indisponível: ${path.basename(new URL(url).pathname)} em ${new URL(url).hostname} (HTTP ${res.status}).`,
    );
  return res;
}
async function valid(file, hash, algorithm = 'sha1') {
  if (!fs.existsSync(file)) return false;
  const actual = crypto
    .createHash(algorithm)
    .update(await fsp.readFile(file))
    .digest('hex');
  return !hash || (Array.isArray(hash) ? hash.includes(actual) : actual === hash);
}
async function download(url, file, hash, algorithm = 'sha1', emit = () => {}) {
  if (await valid(file, hash, algorithm)) return;
  await fsp.mkdir(path.dirname(file), { recursive: true });
  if (hash && /^[a-f0-9]{40}$/.test(path.basename(file))) {
    const cached = path.join(os.homedir(), '.lunarclient/shared/assets/objects', hash.slice(0, 2), hash);
    if (await valid(cached, hash)) {
      await fsp.copyFile(cached, file);
      return;
    }
  }
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const res = await fetchOK(url);
      const total = Number(res.headers.get('content-length'));
      const out = fs.createWriteStream(file + '.part');
      let bytes = 0;
      try {
        for await (const chunk of res.body) {
          bytes += chunk.length;
          if (!out.write(chunk)) await new Promise((resolve) => out.once('drain', resolve));
          if (total > 5e6) emit({ detail: path.basename(file), bytes, total });
        }
        await new Promise((resolve, reject) => {
          out.on('error', reject);
          out.end(resolve);
        });
      } catch (e) {
        out.destroy();
        throw e;
      }
      if (!(await valid(file + '.part', hash, algorithm))) throw Error('Checksum inválido: ' + path.basename(file));
      await fsp.rename(file + '.part', file);
      return;
    } catch (e) {
      if (attempt === 2) throw e;
    }
  }
}
function allowed(lib, platform = PLATFORM) {
  if (!lib.rules) return true;
  let ok = false;
  for (const rule of lib.rules) {
    let match = !rule.os || !rule.os.name || rule.os.name === platform;
    if (rule.os?.version) match = match && new RegExp(rule.os.version).test(os.release());
    if (rule.os?.arch) match = match && ['x86_64', 'amd64', 'x64'].includes(rule.os.arch);
    if (match) ok = rule.action === 'allow';
  }
  return ok;
}
async function extract(archive, destination) {
  if (!archive.endsWith('.zip')) {
    execFileSync('/usr/bin/tar', ['-xzf', archive, '-C', destination], { timeout: 12e4 });
    return;
  }
  const root = path.resolve(destination) + path.sep;
  for (const [name, data] of Object.entries(unzipSync(await fsp.readFile(archive)))) {
    const target = path.resolve(destination, name);
    if (!target.startsWith(root)) throw Error('Arquivo compactado inválido.');
    if (name.endsWith('/')) continue;
    await fsp.mkdir(path.dirname(target), { recursive: true });
    await fsp.writeFile(target, data);
  }
}
function mavenPath(name) {
  const [group, artifact, version, classifier] = name.split(':');
  if (!group || !artifact || !version) throw Error('Biblioteca inválida.');
  return `${group.replaceAll('.', '/')}/${artifact}/${version}/${artifact}-${version}${classifier ? '-' + classifier : ''}.jar`;
}
async function pool(items, fn, count = 8) {
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(count, items.length) }, async () => {
      while (next < items.length) {
        const i = next++;
        await fn(items[i], i);
      }
    }),
  );
}
function redact(line, account) {
  let s = String(line);
  if (account?.accessToken && account.accessToken !== '0') s = s.split(account.accessToken).join('[TOKEN]');
  return s.replace(/(--accessToken\s+)\S+/g, '$1[TOKEN]');
}
class Runtime {
  constructor(root, assets, emit = () => {}) {
    this.root = root;
    this.assets = assets;
    this.emit = emit;
    this.game = path.join(root, 'minecraft');
    this.child = null;
  }
  async prepare() {
    const stage = (message, percent) => this.emit({ phase: 'preparing', message, percent });
    await fsp.mkdir(this.game, { recursive: true });
    stage('Preparando Java 8', 3);
    const javaRoot = path.join(this.root, 'runtime');
    const find = (dir, depth = 0) => {
      if (depth > 6 || !fs.existsSync(dir)) return null;
      for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, e.name);
        if (e.isFile() && e.name === JAVA.executable && path.basename(dir) === 'bin') return p;
        if (e.isDirectory()) {
          const result2 = find(p, depth + 1);
          if (result2) return result2;
        }
      }
      return null;
    };
    let java = find(path.join(javaRoot, JAVA.directory));
    if (!java) {
      await fsp.rm(javaRoot, { recursive: true, force: true });
      await fsp.mkdir(javaRoot, { recursive: true });
      const archive = path.join(javaRoot, path.basename(JAVA.url));
      await download(JAVA.url, archive, JAVA.sha256, 'sha256', this.emit);
      await extract(archive, javaRoot);
      await fsp.rm(archive, { force: true });
      java = find(path.join(javaRoot, JAVA.directory));
      if (!java) throw Error('Java não foi encontrado após a instalação.');
    }
    stage('Verificando Minecraft 1.8.9', 12);
    const metadataFile = path.join(this.root, 'metadata/1.8.9.json');
    if (!fs.existsSync(metadataFile)) {
      const manifest = await (await fetchOK('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json')).json();
      const entry = manifest.versions.find((v) => v.id === '1.8.9');
      if (!entry) throw Error('Versão 1.8.9 não encontrada.');
      await download(entry.url, metadataFile, entry.sha1);
    }
    const meta = JSON.parse(await fsp.readFile(metadataFile));
    const logFile = path.join(this.root, 'metadata', meta.logging.client.file.id);
    await download(meta.logging.client.file.url, logFile, meta.logging.client.file.sha1);
    const client = path.join(this.game, 'versions/1.8.9/1.8.9.jar');
    await download(meta.downloads.client.url, client, meta.downloads.client.sha1);
    const forge = path.join(this.root, 'libraries/forge-universal.jar');
    const forgeHashFile = path.join(this.root, 'metadata/forge.sha1');
    if (!fs.existsSync(forgeHashFile)) {
      const text = await (await fetchOK(FORGE_URL + '.sha1')).text();
      if (!/^[a-f0-9]{40}/i.test(text.trim())) throw Error('Checksum Forge inválido.');
      await fsp.writeFile(forgeHashFile, text.trim().slice(0, 40));
    }
    await download(FORGE_URL, forge, (await fsp.readFile(forgeHashFile, 'utf8')).trim());
    const forgeJson = JSON.parse(
      Buffer.from(
        unzipSync(await fsp.readFile(forge), { filter: (e) => e.name === 'version.json' })['version.json'],
      ).toString(),
    );
    const libs = new Map();
    for (const lib of [...meta.libraries, ...forgeJson.libraries])
      if (allowed(lib) && lib.clientreq !== false && !lib.name.startsWith('net.minecraftforge:forge:'))
        libs.set(lib.name.split(':').slice(0, 2).join(':'), lib);
    const classpath = [forge];
    const nativeJars = [];
    stage('Preparando bibliotecas do jogo', 22);
    let libDone = 0;
    await pool(
      [...libs.values()],
      async (lib) => {
        const artifact = lib.downloads?.artifact;
        if (artifact || !lib.downloads) {
          const relative = artifact?.path || mavenPath(lib.name);
          const file = path.join(this.root, 'libraries', relative);
          const base = lib.url ? 'https://maven.minecraftforge.net/' : 'https://libraries.minecraft.net/';
          let hash = artifact?.sha1 || lib.checksums;
          if (lib.checksums && lib.url && !(await valid(file, hash))) {
            const checksumFile = file + '.sha1';
            if (!fs.existsSync(checksumFile)) {
              const res = await fetch(checkURL(base + relative + '.sha1'), {
                signal: AbortSignal.timeout(3e4),
                redirect: 'error',
              });
              const checksum = res.ok ? (await res.text()).trim().split(/\s/)[0] : '';
              if (checksum && !/^[a-f0-9]{40}$/i.test(checksum)) throw Error('Checksum Maven inválido.');
              await fsp.mkdir(path.dirname(file), { recursive: true });
              await fsp.writeFile(checksumFile, checksum);
            }
            const checksum = (await fsp.readFile(checksumFile, 'utf8')).trim();
            if (checksum) hash = [...lib.checksums, checksum];
          }
          await download(artifact?.url || base + relative, file, hash);
          classpath.push(file);
        }
        if (lib.natives?.[PLATFORM]) {
          const key = lib.natives[PLATFORM].replace('${arch}', '64');
          const item = lib.downloads.classifiers[key];
          const file = path.join(this.root, 'libraries', item.path);
          await download(item.url, file, item.sha1);
          nativeJars.push(file);
        }
        stage('Bibliotecas ' + ++libDone + '/' + libs.size, 22 + (libDone / libs.size) * 20);
      },
      5,
    );
    const natives = path.join(this.root, 'natives');
    await fsp.mkdir(natives, { recursive: true });
    for (const file of nativeJars) {
      const entries = unzipSync(await fsp.readFile(file), {
        filter: (e) => NATIVE_FILE.test(e.name) && !/twitch/i.test(e.name) && e.originalSize < 2e7,
      });
      for (const [name, data] of Object.entries(entries))
        await fsp.writeFile(path.join(natives, path.basename(name)), data);
    }
    if (PLATFORM === 'osx' && process.arch === 'arm64') {
      const arm = path.join(this.assets, 'natives-arm64');
      for (const name of fs.readdirSync(arm))
        if (NATIVE_FILE.test(name)) await fsp.copyFile(path.join(arm, name), path.join(natives, name));
    }
    const assetRoot = path.join(this.root, 'assets');
    const indexFile = path.join(assetRoot, 'indexes', meta.assetIndex.id + '.json');
    await download(meta.assetIndex.url, indexFile, meta.assetIndex.sha1);
    const index = JSON.parse(await fsp.readFile(indexFile));
    const objects = Object.entries(index.objects);
    let done = 0;
    stage('Baixando sons e recursos', 45);
    await pool(
      objects,
      async ([name, asset]) => {
        const rel = asset.hash.slice(0, 2) + '/' + asset.hash;
        const file = path.join(assetRoot, 'objects', rel);
        await download('https://resources.download.minecraft.net/' + rel, file, asset.hash);
        if (index.virtual) {
          const virtual = path.resolve(assetRoot, 'virtual', meta.assetIndex.id, name);
          if (!virtual.startsWith(path.resolve(assetRoot, 'virtual') + path.sep))
            throw Error('Caminho de recurso inválido.');
          await fsp.mkdir(path.dirname(virtual), { recursive: true });
          if (!fs.existsSync(virtual)) await fsp.copyFile(file, virtual);
        }
        if (++done % 20 === 0) stage('Recursos ' + done + '/' + objects.length, 45 + (done / objects.length) * 47);
      },
      10,
    );
    const modDir = path.join(this.game, 'mods');
    await fsp.mkdir(modDir, { recursive: true });
    await fsp.copyFile(path.join(this.assets, 'antagon-hud-0.1.0.jar'), path.join(modDir, 'antagon-hud-0.1.0.jar'));
    const result = {
      java,
      client,
      forge,
      classpath: [...classpath, client],
      natives,
      assetRoot,
      assetIndex: meta.assetIndex.id,
      mainClass: forgeJson.mainClass,
      logArgument: meta.logging.client.argument.replace('${path}', logFile),
    };
    await fsp.writeFile(path.join(this.root, 'installation.json'), JSON.stringify(result, null, 2));
    stage('Pronto para entrar', 100);
    return result;
  }
  async launch(settings, account, testOptions = {}) {
    if (this.child) throw Error('O jogo já está aberto.');
    const installation = await this.prepare();
    const pack = path.join(this.assets, 'Antagon Pack 8x.zip');
    if (settings.pack && fs.existsSync(pack)) {
      const dest = path.join(this.game, 'resourcepacks');
      await fsp.mkdir(dest, { recursive: true });
      await fsp.copyFile(pack, path.join(dest, 'Antagon Pack 8x.zip'));
    }
    const options = path.join(this.game, 'options.txt');
    if (!fs.existsSync(options))
      await fsp.writeFile(
        options,
        'guiScale:2\nmaxFps:240\nenableVsync:false\nrenderDistance:8\nfullscreen:false\nresourcePacks:[]\n',
      );
    let optionText = await fsp.readFile(options, 'utf8');
    let packs = [];
    try {
      packs = JSON.parse(optionText.match(/^resourcePacks:(.*)$/m)?.[1] || '[]');
    } catch {}
    packs = packs.filter((p) => p !== 'Antagon Pack 8x.zip');
    if (settings.pack) packs.push('Antagon Pack 8x.zip');
    optionText = optionText
      .replace(/^resourcePacks:.*$/m, 'resourcePacks:' + JSON.stringify(packs))
      .replace(/^fullscreen:.*$/m, 'fullscreen:' + settings.fullscreen);
    for (const [k, v] of [
      ['useVbo', 'true'],
      ['fboEnable', 'true'],
    ])
      if (!new RegExp('^' + k + ':', 'm').test(optionText))
        optionText = optionText.trimEnd() + '\n' + k + ':' + v + '\n';
    if (testOptions.smoke || testOptions.wallpaper) {
      optionText = optionText.replace(/^pauseOnLostFocus:.*$/m, 'pauseOnLostFocus:false');
      if (!optionText.includes('pauseOnLostFocus:')) optionText += '\npauseOnLostFocus:false\n';
    }
    await fsp.writeFile(options, optionText);
    const args = [
      `-Xmx${settings.memory}G`,
      '-Xms512M',
      '-XX:+UseG1GC',
      '-Dlog4j2.formatMsgNoLookups=true',
      installation.logArgument,
      '-Djava.library.path=' + installation.natives,
      '-Dfml.ignoreInvalidMinecraftCertificates=false',
      '-cp',
      installation.classpath.join(path.delimiter),
      installation.mainClass,
      '--username',
      account.name,
      '--version',
      'Antagon-1.8.9',
      '--gameDir',
      this.game,
      '--assetsDir',
      installation.assetRoot,
      '--assetIndex',
      installation.assetIndex,
      '--uuid',
      account.id,
      '--accessToken',
      account.accessToken,
      '--userProperties',
      '{}',
      '--userType',
      account.type,
      '--tweakClass',
      'net.minecraftforge.fml.common.launcher.FMLTweaker',
      '--width',
      '1280',
      '--height',
      '800',
    ];
    if (PLATFORM === 'osx') args.unshift('-Xdock:name=Antagon Client');
    if (settings.fullscreen) args.push('--fullscreen');
    if (testOptions.smoke) args.unshift('-Dantagon.smoke=true');
    if (testOptions.wallpaper) args.unshift('-Dantagon.wallpaper=' + testOptions.wallpaper);
    if (testOptions.width) {
      const i = args.indexOf('--width');
      args[i + 1] = String(testOptions.width);
      args[i + 3] = String(testOptions.height);
    }
    this.emit({ phase: 'launching', message: 'Abrindo Minecraft', percent: 100 });
    const child = spawn(installation.java, args, {
      cwd: this.game,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    this.child = child;
    const logDir = path.join(this.root, 'logs');
    await fsp.mkdir(logDir, { recursive: true });
    const log = fs.createWriteStream(path.join(logDir, 'game.log'), { flags: 'w', mode: 384 });
    for (const stream of [child.stdout, child.stderr])
      stream.on('data', (data) => {
        const text = redact(data, account);
        log.write(text);
        if (text.includes('[ANTAGON] HUD ready')) this.emit({ phase: 'running', message: 'No jogo', percent: 100 });
      });
    child.on('error', () => {
      this.child = null;
      log.end();
      this.emit({ phase: 'error', message: 'Não foi possível iniciar o Java.' });
    });
    child.on('exit', (code) => {
      this.child = null;
      log.end();
      this.emit({
        phase: code ? 'error' : 'idle',
        message: code ? 'O jogo fechou com erro. Consulte o diagnóstico.' : 'Jogo fechado',
        exitCode: code,
      });
    });
    return { pid: child.pid };
  }
}
module.exports = {
  Runtime,
  download,
  allowed,
  mavenPath,
  extract,
  offlineAccount: require('./settings.cjs').offlineAccount,
  redact,
  checkURL,
};
