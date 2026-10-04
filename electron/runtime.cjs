const fs = require('node:fs');
const fsp = fs.promises;
const path = require('node:path');
const os = require('node:os');
const { spawn, execFile } = require('node:child_process');
const runFile = require('node:util').promisify(execFile);
const { hashFile, writeDownload } = require('./files.cjs');
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
  'repo.maven.apache.org',
  'cdn.azul.com',
  'meta.fabricmc.net',
  'maven.fabricmc.net',
  'api.modrinth.com',
  'cdn.modrinth.com',
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
  if (!hash) return true;
  const actual = await hashFile(file, algorithm);
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
      await writeDownload(res, file, {
        algorithm,
        expected: hash,
        progress: (bytes, total) => {
          if (total > 5e6) emit({ detail: path.basename(file), bytes, total });
        },
      });
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
    if (rule.os?.arch) {
      const architectures = process.arch === 'arm64' ? ['arm64', 'aarch64'] : ['x86_64', 'amd64', 'x64'];
      match = match && architectures.some((arch) => new RegExp(rule.os.arch).test(arch));
    }
    if (match) ok = rule.action === 'allow';
  }
  return ok;
}
async function extract(archive, destination) {
  if (!archive.endsWith('.zip')) {
    await runFile('/usr/bin/tar', ['-xzf', archive, '-C', destination], { timeout: 12e4 });
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
  async prepareModern() {
    const stage = (message, percent) => this.emit({ phase: 'preparing', message, percent });
    const manifest = await (await fetchOK('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json')).json();
    const releaseId = manifest.latest?.release;
    if (!/^26\./.test(releaseId || '')) throw Error('A versão estável 26.x do Minecraft não foi encontrada.');
    const entry = manifest.versions.find((version) => version.id === releaseId);
    if (!entry) throw Error('Metadados da versão 26.x não encontrados.');
    const metadataFile = path.join(this.root, 'metadata', `${releaseId}.json`);
    stage(`Preparando Minecraft ${releaseId}`, 5);
    await download(entry.url, metadataFile, entry.sha1, 'sha1', this.emit);
    const meta = JSON.parse(await fsp.readFile(metadataFile, 'utf8'));
    const runtimePlatform =
      PLATFORM === 'windows' ? 'windows-x64' : process.arch === 'arm64' ? 'mac-os-arm64' : 'mac-os';
    const runtimeManifest = await (
      await fetchOK(`https://launchermeta.mojang.com/v1/products/java-runtime/${runtimePlatform}.json`)
    ).json();
    const javaComponent = meta.javaVersion?.component;
    const javaEntry = runtimeManifest[javaComponent]?.[0];
    if (!javaEntry?.manifest?.url) throw Error(`Java ${meta.javaVersion?.majorVersion || ''} não está disponível.`);
    const javaDir = path.join(this.root, 'runtime', `${javaComponent}-${runtimePlatform}`);
    const findJava = (dir, depth = 0) => {
      if (depth > 8 || !fs.existsSync(dir)) return null;
      for (const item of fs.readdirSync(dir, { withFileTypes: true })) {
        const target = path.join(dir, item.name);
        if ((item.isFile() || item.isSymbolicLink()) && (item.name === 'java' || item.name === 'java.exe')) {
          try {
            if (fs.statSync(target).isFile()) return target;
          } catch {}
        }
        if (item.isDirectory()) {
          const found = findJava(target, depth + 1);
          if (found) return found;
        }
      }
      return null;
    };
    let java = findJava(javaDir);
    if (!java) {
      const javaManifest = await (await fetchOK(javaEntry.manifest.url)).json();
      const entries = Object.entries(javaManifest.files || {});
      let completed = 0;
      stage(`Baixando Java ${meta.javaVersion.majorVersion}`, 10);
      await pool(
        entries,
        async ([relative, file]) => {
          const target = path.resolve(javaDir, relative);
          if (!target.startsWith(path.resolve(javaDir) + path.sep)) throw Error('Caminho inválido no Java oficial.');
          if (file.type === 'directory') await fsp.mkdir(target, { recursive: true });
          else if (file.type === 'file') {
            await download(file.downloads.raw.url, target, file.downloads.raw.sha1);
            if (file.executable && PLATFORM !== 'windows') await fsp.chmod(target, 0o755);
          } else if (file.type === 'link' && typeof file.target === 'string') {
            const linkTarget = path.resolve(path.dirname(target), file.target);
            if (!linkTarget.startsWith(path.resolve(javaDir) + path.sep)) throw Error('Link inválido no Java oficial.');
            await fsp.mkdir(path.dirname(target), { recursive: true });
            await fsp.symlink(path.relative(path.dirname(target), linkTarget), target).catch((error) => {
              if (error.code !== 'EEXIST') throw error;
            });
          }
          if (++completed % 20 === 0)
            stage(`Java ${completed}/${entries.length}`, 10 + (completed / entries.length) * 8);
        },
        10,
      );
      java = findJava(javaDir);
      if (!java) throw Error('O Java necessário não foi instalado corretamente.');
    }

    const profileUrl = `https://meta.fabricmc.net/v2/versions/loader/${encodeURIComponent(releaseId)}/profile/json`;
    const profile = await (await fetchOK(profileUrl)).json();
    if (profile.inheritsFrom !== releaseId || !profile.mainClass)
      throw Error('Perfil Fabric incompatível com Minecraft.');
    const game = path.join(this.root, `minecraft-${releaseId}`);
    await fsp.mkdir(game, { recursive: true });
    const client = path.join(game, 'versions', releaseId, `${releaseId}.jar`);
    await download(meta.downloads.client.url, client, meta.downloads.client.sha1);

    const classpath = [client];
    const nativeJars = [];
    const libraries = new Map();
    for (const lib of [...(meta.libraries || []), ...(profile.libraries || [])])
      if (allowed(lib)) libraries.set(lib.name, lib);
    stage('Preparando bibliotecas Fabric', 20);
    let libDone = 0;
    await pool(
      [...libraries.values()],
      async (lib) => {
        const artifact = lib.downloads?.artifact;
        if (artifact || !lib.downloads) {
          const relative = artifact?.path || mavenPath(lib.name);
          const file = path.join(this.root, 'libraries', relative);
          const base = lib.url || 'https://libraries.minecraft.net/';
          const url = artifact?.url || new URL(relative, base.endsWith('/') ? base : base + '/').href;
          await download(url, file, artifact?.sha1 || lib.checksums);
          classpath.push(file);
        }
        const classifier = lib.natives?.[PLATFORM];
        if (classifier && lib.downloads?.classifiers) {
          const key = classifier.replace('${arch}', process.arch === 'arm64' ? 'arm64' : '64');
          const native = lib.downloads.classifiers[key];
          if (native) {
            const file = path.join(this.root, 'libraries', native.path);
            await download(native.url, file, native.sha1);
            nativeJars.push(file);
          }
        }
        stage(`Bibliotecas ${++libDone}/${libraries.size}`, 20 + (libDone / libraries.size) * 25);
      },
      8,
    );
    const natives = path.join(game, 'natives');
    await fsp.mkdir(natives, { recursive: true });
    for (const file of nativeJars) {
      const entries = unzipSync(await fsp.readFile(file), {
        filter: (item) => NATIVE_FILE.test(item.name) && !/twitch/i.test(item.name) && item.originalSize < 2e7,
      });
      for (const [name, data] of Object.entries(entries))
        await fsp.writeFile(path.join(natives, path.basename(name)), data);
    }

    const assetRoot = path.join(this.root, 'assets');
    const indexFile = path.join(assetRoot, 'indexes', `${meta.assetIndex.id}.json`);
    await download(meta.assetIndex.url, indexFile, meta.assetIndex.sha1);
    const index = JSON.parse(await fsp.readFile(indexFile, 'utf8'));
    let assetDone = 0;
    const assets = Object.entries(index.objects || {});
    stage('Baixando recursos do Minecraft', 48);
    await pool(
      assets,
      async ([, asset]) => {
        const relative = `${asset.hash.slice(0, 2)}/${asset.hash}`;
        await download(
          `https://resources.download.minecraft.net/${relative}`,
          path.join(assetRoot, 'objects', relative),
          asset.hash,
        );
        if (++assetDone % 50 === 0)
          stage(`Recursos ${assetDone}/${assets.length}`, 48 + (assetDone / assets.length) * 38);
      },
      12,
    );

    const modsDir = path.join(game, 'mods');
    await fsp.mkdir(modsDir, { recursive: true });
    const modrinth = async (slug, required = false) => {
      const query = new URLSearchParams({
        game_versions: JSON.stringify([releaseId]),
        loaders: JSON.stringify(['fabric']),
      });
      const response = await fetch(`https://api.modrinth.com/v2/project/${slug}/version?${query}`, {
        signal: AbortSignal.timeout(4e4),
        redirect: 'error',
      });
      if (!response.ok) {
        if (required) throw Error(`O mod ${slug} ainda não tem uma versão Fabric para ${releaseId}.`);
        return;
      }
      const versions = await response.json();
      const artifact = versions[0]?.files?.find((item) => item.primary) || versions[0]?.files?.[0];
      if (!artifact) {
        if (required) throw Error(`Arquivo do mod ${slug} não encontrado.`);
        return;
      }
      const filename = path.basename(new URL(artifact.url).pathname);
      await download(artifact.url, path.join(modsDir, filename), artifact.hashes?.sha1);
    };
    stage(`Instalando mods Fabric para ${releaseId}`, 88);
    await modrinth('sodium', true);
    await Promise.all(['fabric-api', 'iris', 'lithium', 'ferrite-core'].map((slug) => modrinth(slug).catch(() => {})));

    const logFile = path.join(this.root, 'metadata', meta.logging?.client?.file?.id || 'client-logging.xml');
    if (meta.logging?.client?.file?.url)
      await download(meta.logging.client.file.url, logFile, meta.logging.client.file.sha1);
    const result = {
      java,
      client,
      classpath,
      natives,
      assetRoot,
      assetIndex: meta.assetIndex.id,
      mainClass: profile.mainClass,
      logArgument: meta.logging?.client?.argument?.replace('${path}', logFile) || '',
      game,
      version: releaseId,
      metadata: meta,
      profile,
      mods: fs.readdirSync(modsDir).filter((file) => file.endsWith('.jar')),
    };
    await fsp.writeFile(
      path.join(game, 'installation.json'),
      JSON.stringify({ ...result, metadata: undefined, profile: undefined }, null, 2),
    );
    stage(`Minecraft ${releaseId} pronto com Fabric`, 100);
    return result;
  }
  async launch(settings, account, testOptions = {}) {
    if (this.child) throw Error('O jogo já está aberto.');
    const modern = settings.gameVersion === 'latest-26';
    const installation = modern ? await this.prepareModern() : await this.prepare();
    const game = installation.game || this.game;
    const options = path.join(game, 'options.txt');
    if (!fs.existsSync(options))
      await fsp.writeFile(
        options,
        'guiScale:2\nmaxFps:240\nenableVsync:false\nrenderDistance:8\nfullscreen:false\nresourcePacks:[]\n',
      );
    let optionText = await fsp.readFile(options, 'utf8');
    optionText = optionText.replace(/^fullscreen:.*$/m, () => 'fullscreen:' + settings.fullscreen);
    if (!modern)
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
    let args;
    if (modern) {
      const nativePlatform = process.platform === 'darwin' ? 'osx' : process.platform === 'win32' ? 'windows' : 'linux';
      const features = { is_demo_user: false, has_custom_resolution: true, has_quick_plays_support: false };
      const permitted = (rules = []) => {
        if (!rules.length) return true;
        let result = false;
        for (const rule of rules) {
          let match = !rule.os || !rule.os.name || rule.os.name === nativePlatform;
          if (match && rule.os?.arch) {
            const archNames = process.arch === 'arm64' ? ['arm64', 'aarch64'] : ['x86_64', 'amd64', 'x64'];
            match = archNames.some((arch) => new RegExp(rule.os.arch).test(arch));
          }
          if (match && rule.features)
            match = Object.entries(rule.features).every(([key, value]) => features[key] === value);
          if (match) result = rule.action === 'allow';
        }
        return result;
      };
      const flatten = (items = []) =>
        items.flatMap((item) => {
          if (typeof item === 'string') return [item];
          if (!item || typeof item !== 'object' || !permitted(item.rules || [])) return [];
          return Array.isArray(item.value) ? item.value : [item.value];
        });
      const values = {
        auth_player_name: account.name,
        version_name: installation.version,
        game_directory: game,
        assets_root: installation.assetRoot,
        assets_index_name: installation.assetIndex,
        auth_uuid: account.id,
        auth_access_token: account.accessToken,
        auth_xuid: '',
        clientid: '',
        user_type: account.type === 'msa' ? 'msa' : account.type || 'legacy',
        version_type: 'release',
        natives_directory: installation.natives,
        launcher_name: 'Antagon Client',
        launcher_version: '0.1.14',
        classpath: installation.classpath.join(path.delimiter),
        classpath_separator: path.delimiter,
        library_directory: path.join(this.root, 'libraries'),
        resolution_width: '1280',
        resolution_height: '800',
      };
      const substitute = (value) => value.replace(/\$\{([^}]+)\}/g, (all, key) => values[key] ?? all);
      const base = installation.metadata.arguments || {};
      const fabric = installation.profile.arguments || {};
      const jvm = [
        `-Xmx${settings.memory}G`,
        '-Xms512M',
        ...flatten(base.jvm),
        ...flatten(fabric.jvm),
        '-Djava.library.path=' + installation.natives,
        '-cp',
        values.classpath,
      ].map(substitute);
      if (installation.logArgument) jvm.push(substitute(installation.logArgument));
      const gameArgs = [...flatten(base.game), ...flatten(fabric.game)].map(substitute);
      if (!gameArgs.includes('--gameDir')) gameArgs.push('--gameDir', game);
      args = [...jvm, installation.mainClass, ...gameArgs];
      if (settings.fullscreen && !gameArgs.includes('--fullscreen')) args.push('--fullscreen');
      if (PLATFORM === 'osx') args.unshift('-Xdock:name=Antagon Client');
    } else {
      args = [
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
        game,
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
    }
    const server = /^([A-Za-z0-9.-]{1,253})(?::(\d{1,5}))?$/.exec(testOptions.server || '');
    if (server) args.push('--server', server[1], '--port', server[2] || '25565');
    if (testOptions.smoke) args.unshift('-Dantagon.smoke=true');
    if (testOptions.wallpaper) args.unshift('-Dantagon.wallpaper=' + testOptions.wallpaper);
    if (testOptions.width) {
      const i = args.indexOf('--width');
      args[i + 1] = String(testOptions.width);
      args[i + 3] = String(testOptions.height);
    }
    this.emit({ phase: 'launching', message: 'Abrindo Minecraft', percent: 100 });
    const logDir = path.join(this.root, 'logs');
    await fsp.mkdir(logDir, { recursive: true });
    if (!modern) {
      await fsp.rm(path.join(game, 'antagon-status.txt'), { force: true });
      await Promise.all([
        fsp.rm(path.join(game, 'antagon-players.txt'), { force: true }),
        fsp.rm(path.join(game, 'antagon-cosmetics.properties'), { force: true }),
        fsp.rm(path.join(game, 'antagon-ui-request.txt'), { force: true }),
      ]);
      await fsp.writeFile(
        path.join(game, 'antagon-session.properties'),
        'admin=' + (testOptions.admin === true ? 'true' : 'false') + '\n',
        { mode: 0o600 },
      );
    }
    if (!modern && testOptions.beforeSpawn) await testOptions.beforeSpawn();
    const file = await fsp.open(path.join(logDir, 'game.log'), 'w', 0o600);
    const log = file.createWriteStream();
    const child = spawn(installation.java, args, {
      cwd: game,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    this.child = child;
    log.on('error', () => {
      child.kill();
      this.emit({ phase: 'error', message: 'Não foi possível gravar o diagnóstico da partida.' });
    });
    for (const stream of [child.stdout, child.stderr])
      stream.on('data', (data) => {
        const text = redact(data, account);
        log.write(text);
        if (text.includes('[ANTAGON] HUD ready') || (modern && text.trim()))
          this.emit({ phase: 'running', message: 'No jogo', percent: 100 });
      });
    child.on('error', () => {
      this.child = null;
      log.end();
      this.emit({ phase: 'error', message: 'Não foi possível iniciar o Java.' });
    });
    child.on('close', (code) => {
      this.child = null;
      log.end();
      this.emit({
        phase: code ? 'error' : 'idle',
        message: code ? 'O jogo fechou com erro. Consulte o diagnóstico.' : 'Jogo fechado',
        exitCode: code,
      });
    });
    await new Promise((resolve, reject) => {
      child.once('spawn', resolve);
      child.once('error', reject);
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
