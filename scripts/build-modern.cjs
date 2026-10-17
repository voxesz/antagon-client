const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { execFileSync } = require('node:child_process');
const { unzipSync, zipSync } = require('fflate');
const { download, checkURL, allowed, mavenPath } = require('../electron/runtime.cjs');
const project = path.resolve(__dirname, '..');
const version = '26.3';
const cache = path.join(project, 'build/modern-compile');
const installed = path.join(
  process.platform === 'win32' ? process.env.APPDATA : path.join(os.homedir(), 'Library/Application Support'),
  'Antagon Client',
);
async function json(url) {
  const response = await fetch(checkURL(url), { signal: AbortSignal.timeout(120000), redirect: 'error' });
  if (!response.ok) throw Error(`Falha ao consultar dependências do módulo Fabric: HTTP ${response.status}`);
  return response.json();
}
async function main() {
  fs.mkdirSync(cache, { recursive: true });
  let installation;
  try {
    const data = JSON.parse(fs.readFileSync(path.join(installed, `minecraft-${version}/installation.json`)));
    if (data.version === version && data.classpath.every((file) => fs.existsSync(file))) installation = data;
  } catch {}
  let classpath = installation?.classpath;
  if (!classpath) {
    const manifest = await json('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json');
    const entry = manifest.versions.find((item) => item.id === version);
    if (!entry) throw Error(`Metadados de Minecraft ${version} não encontrados.`);
    const metadata = path.join(cache, 'minecraft.json');
    await download(entry.url, metadata, entry.sha1);
    const meta = JSON.parse(fs.readFileSync(metadata));
    const profile = await json(`https://meta.fabricmc.net/v2/versions/loader/${version}/0.19.5/profile/json`);
    const client = path.join(cache, `minecraft-${version}.jar`);
    await download(meta.downloads.client.url, client, meta.downloads.client.sha1);
    classpath = [client];
    for (const lib of [...meta.libraries, ...profile.libraries]) {
      if (!allowed(lib) || lib.name.includes(':natives-')) continue;
      const artifact = lib.downloads?.artifact;
      const relative = artifact?.path || mavenPath(lib.name);
      const target = path.join(cache, 'libraries', relative);
      await download(
        artifact?.url || new URL(relative, lib.url || 'https://libraries.minecraft.net/').href,
        target,
        artifact?.sha1 || lib.sha1,
      );
      classpath.push(target);
    }
  }
  let api = path.join(installed, `minecraft-${version}/mods/fabric-api-0.163.0+26.3.jar`);
  if (!fs.existsSync(api)) {
    const release = await json('https://api.modrinth.com/v2/version/bq3SsNGk');
    const artifact = release.files.find((file) => file.primary);
    api = path.join(cache, artifact.filename);
    await download(artifact.url, api, artifact.hashes.sha1);
  }
  const apiJars = [];
  for (const [name, data] of Object.entries(
    unzipSync(fs.readFileSync(api), { filter: (item) => item.name.endsWith('.jar') }),
  )) {
    const file = path.join(cache, path.basename(name));
    fs.writeFileSync(file, data);
    apiJars.push(file);
  }
  classpath = [...new Set([...classpath, ...apiJars])];
  const classes = path.join(cache, 'classes');
  fs.rmSync(classes, { recursive: true, force: true });
  fs.mkdirSync(classes, { recursive: true });
  const filesIn = (directory) =>
    fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
      const file = path.join(directory, entry.name);
      return entry.isDirectory() ? filesIn(file) : [file];
    });
  const javaHome =
    process.env.ANTAGON_MODERN_JAVA_HOME ||
    (process.platform === 'darwin'
      ? execFileSync('/usr/libexec/java_home', ['-v', '25+'], { encoding: 'utf8' }).trim()
      : process.env.JAVA_HOME);
  const javac = javaHome ? path.join(javaHome, 'bin', process.platform === 'win32' ? 'javac.exe' : 'javac') : 'javac';
  const sources = path.join(project, 'modern-mod-src/studio/antagon/modern');
  execFileSync(
    javac,
    [
      '--release',
      '25',
      '-proc:none',
      '-encoding',
      'UTF-8',
      '-cp',
      classpath.join(path.delimiter),
      '-d',
      classes,
      ...filesIn(sources).filter((file) => file.endsWith('.java')),
    ],
    { stdio: 'inherit' },
  );
  const entries = {};
  for (const file of filesIn(classes))
    if (file.endsWith('.class'))
      entries[path.relative(classes, file).split(path.sep).join('/')] = fs.readFileSync(file);
  entries['fabric.mod.json'] = Buffer.from(
    JSON.stringify({
      schemaVersion: 1,
      id: 'antagon_modern',
      name: 'Antagon Capes & Toggle Sprint',
      version: require('../package.json').version,
      environment: 'client',
      license: 'MIT',
      entrypoints: { client: ['studio.antagon.modern.AntagonModern'] },
      mixins: ['antagon-modern.mixins.json'],
      depends: { minecraft: '~26.3', java: '>=25', fabricloader: '>=0.19.5', 'fabric-api': '>=0.163.0' },
    }),
  );
  entries['antagon-modern.mixins.json'] = Buffer.from(
    JSON.stringify({
      required: true,
      package: 'studio.antagon.modern.mixin',
      compatibilityLevel: 'JAVA_25',
      client: ['CapeSkinMixin', 'SprintInputMixin'],
      injectors: { defaultRequire: 1 },
    }),
  );
  entries['assets/antagon/lang/en_us.json'] = Buffer.from(
    JSON.stringify({
      'key.category.antagon.controls': 'Antagon Client',
      'key.antagon.toggle_sprint': 'Toggle Sprint',
      'antagon.sprint.enabled': 'Toggle Sprint enabled',
      'antagon.sprint.disabled': 'Toggle Sprint disabled',
      'antagon.sprint.hud': '[Sprint toggled]',
    }),
  );
  entries['assets/antagon/lang/pt_br.json'] = Buffer.from(
    JSON.stringify({
      'key.category.antagon.controls': 'Antagon Client',
      'key.antagon.toggle_sprint': 'Alternar corrida (Toggle Sprint)',
      'antagon.sprint.enabled': 'Toggle Sprint ativado',
      'antagon.sprint.disabled': 'Toggle Sprint desativado',
      'antagon.sprint.hud': '[Sprint alternado]',
    }),
  );
  for (const [id, file] of [
    ['antagon_cape', 'cape.png'],
    ['antagon_logo_cape', 'logo-cape.png'],
  ])
    entries[`assets/antagon/textures/capes/${id}.png`] = fs.readFileSync(
      path.join(project, 'mod-src/resources/assets/antagon', file),
    );
  entries['LICENSE'] = fs.readFileSync(path.join(project, 'LICENSE'));
  fs.writeFileSync(path.join(project, `assets/antagon-modern-${version}.jar`), zipSync(entries));
  fs.writeFileSync(path.join(cache, 'classpath.json'), JSON.stringify(classpath, null, 2));
  console.log(`Módulo Fabric ${version} compilado: capas Antagon e Toggle Sprint.`);
}
main().catch((error) => {
  console.error(error.status ? 'Falha ao compilar o módulo Fabric.' : error.message);
  process.exitCode = 1;
});
