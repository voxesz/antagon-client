const fs = require('node:fs/promises');
const path = require('node:path');
const { unzipSync } = require('fflate');

const PROJECTS = [
  'iris',
  'sodium',
  'fabric-api',
  'lithium',
  'ferrite-core',
  'essential',
  'xaeros-minimap',
  'xaeros-world-map',
  'litematica',
];

function modernOptions(options) {
  // World Map and Litematica both use M by default. Seed J once and keep user choices on later launches.
  if (/^key_gui\.xaero_open_map:/m.test(options)) return options;
  return options + (options && !options.endsWith('\n') ? '\n' : '') + 'key_gui.xaero_open_map:key.keyboard.j\n';
}

async function resolveMods(gameVersion, getJSON) {
  const selected = new Map();
  const query = new URLSearchParams({ game_versions: JSON.stringify([gameVersion]), loaders: '["fabric"]' });
  const compatible = (version) => version?.game_versions?.includes(gameVersion) && version.loaders?.includes('fabric');
  const add = async (version) => {
    if (!compatible(version))
      throw Error(`Mod incompatível com Minecraft ${gameVersion}: ${version?.name || 'desconhecido'}.`);
    const previous = selected.get(version.project_id);
    if (previous) {
      if (previous.id !== version.id) throw Error(`Dependências conflitantes para ${version.name}.`);
      return;
    }
    selected.set(version.project_id, version);
    for (const dependency of version.dependencies || []) {
      if (dependency.dependency_type !== 'required') continue;
      if (dependency.version_id) {
        await add(await getJSON(`https://api.modrinth.com/v2/version/${encodeURIComponent(dependency.version_id)}`));
      } else if (dependency.project_id) {
        await project(dependency.project_id);
      } else throw Error(`Dependência sem versão disponível para ${version.name}.`);
    }
  };
  const project = async (slug) => {
    const versions = await getJSON(`https://api.modrinth.com/v2/project/${encodeURIComponent(slug)}/version?${query}`);
    // Iris can pin a specific Sodium release. Keep that selection instead of replacing it with a newer beta.
    if (versions.some((version) => selected.has(version.project_id))) return;
    const version = versions.find((item) => item.version_type === 'release' && compatible(item));
    if (!version) throw Error(`O mod ${slug} ainda não tem uma versão estável Fabric para Minecraft ${gameVersion}.`);
    await add(version);
  };
  for (const slug of PROJECTS) await project(slug);
  return [...selected.values()];
}

async function installMods(game, versions, download) {
  const mods = path.join(game, 'mods');
  const stage = path.join(game, '.antagon-mods');
  await fs.mkdir(mods, { recursive: true });
  await fs.mkdir(stage, { recursive: true });
  const installed = [];
  // Download and verify the complete set before replacing any existing mods.
  for (const version of versions) {
    const artifact = version.files?.find((file) => file.primary) || version.files?.[0];
    if (
      !artifact?.hashes?.sha1 ||
      !artifact.filename?.endsWith('.jar') ||
      path.basename(artifact.filename) !== artifact.filename
    )
      throw Error(`Arquivo inválido para o mod ${version.name}.`);
    const target = path.join(stage, artifact.filename);
    await download(artifact.url, target, artifact.hashes.sha1);
    const entry = unzipSync(await fs.readFile(target), { filter: (file) => file.name === 'fabric.mod.json' })[
      'fabric.mod.json'
    ];
    if (!entry) throw Error(`O mod ${version.name} não é um mod Fabric válido.`);
    const metadata = JSON.parse(Buffer.from(entry).toString('utf8'));
    if (!metadata.id) throw Error(`Identidade inválida para o mod ${version.name}.`);
    installed.push({
      project: version.project_id,
      version: version.version_number,
      id: metadata.id,
      file: artifact.filename,
    });
  }
  const ids = new Set(installed.map((mod) => mod.id));
  const files = new Set(installed.map((mod) => mod.file));
  for (const file of await fs.readdir(mods)) {
    if (!file.endsWith('.jar') || files.has(file)) continue;
    let id;
    try {
      const entry = unzipSync(await fs.readFile(path.join(mods, file)), {
        filter: (item) => item.name === 'fabric.mod.json',
      })['fabric.mod.json'];
      if (entry) id = JSON.parse(Buffer.from(entry).toString('utf8')).id;
    } catch {
      continue;
    }
    if (!ids.has(id)) continue;
    // Keep previous versions outside Fabric's mod directory so updates do not load duplicate mods.
    const backup = path.join(game, 'antagon-mod-backups');
    await fs.mkdir(backup, { recursive: true });
    await fs.rename(path.join(mods, file), path.join(backup, `${Date.now()}-${file}`));
  }
  for (const mod of installed) await fs.copyFile(path.join(stage, mod.file), path.join(mods, mod.file));
  await fs.writeFile(path.join(game, 'antagon-mods.json'), JSON.stringify(installed, null, 2));
  return installed;
}

async function installCompanion(game, assets, gameVersion) {
  if (!/^[0-9][\w.-]*$/.test(gameVersion)) throw Error('Versão inválida para o módulo Antagon.');
  const filename = `antagon-modern-${gameVersion}.jar`;
  const source = path.join(assets, filename);
  const data = await fs.readFile(source).catch(() => {
    throw Error(`O módulo Antagon para Minecraft ${gameVersion} não está disponível. Atualize o launcher.`);
  });
  const readId = (bytes) => {
    const entry = unzipSync(bytes, { filter: (item) => item.name === 'fabric.mod.json' })['fabric.mod.json'];
    return entry && JSON.parse(Buffer.from(entry).toString('utf8')).id;
  };
  if (readId(data) !== 'antagon_modern') throw Error('Módulo Fabric do Antagon inválido.');
  const mods = path.join(game, 'mods');
  await fs.mkdir(mods, { recursive: true });
  for (const file of await fs.readdir(mods)) {
    if (!file.endsWith('.jar') || file === filename) continue;
    let id;
    try {
      id = readId(await fs.readFile(path.join(mods, file)));
    } catch {
      continue;
    }
    if (id !== 'antagon_modern') continue;
    const backup = path.join(game, 'antagon-mod-backups');
    await fs.mkdir(backup, { recursive: true });
    await fs.rename(path.join(mods, file), path.join(backup, `${Date.now()}-${file}`));
  }
  await fs.writeFile(path.join(mods, filename), data);
  return filename;
}

module.exports = { resolveMods, installMods, installCompanion, modernOptions };
