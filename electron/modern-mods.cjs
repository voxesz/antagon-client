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

function versionAtLeast(version, minimum) {
  const numbers = (value) => (String(value).match(/\d+/g) || []).slice(0, 3).map(Number);
  const actual = numbers(version);
  const required = numbers(minimum);
  for (let i = 0; i < 3; i++) {
    const difference = (actual[i] || 0) - (required[i] || 0);
    if (difference) return difference > 0;
  }
  return true;
}

async function installInventoryCompanion(game, assets, gameVersion, loaderVersion, installedMods) {
  if (gameVersion !== '26.3') throw Error('Antagon Inventory requer Minecraft 26.3.');
  // Modrinth uses opaque project IDs (P7dR8mSH), while the installed JAR declares fabric-api.
  const fabricApi = installedMods.find((mod) => mod.id === 'fabric-api');
  if (!fabricApi) throw Error('Fabric API não foi preparado para o Antagon Inventory.');
  const fabricApiVersion = fabricApi.version;
  const filename = `antagon-inventory-${gameVersion}.jar`;
  const source = path.join(assets, filename);
  const data = await fs.readFile(source).catch(() => {
    throw Error(`O Antagon Inventory para Minecraft ${gameVersion} não está disponível. Atualize o launcher.`);
  });
  const entry = unzipSync(data, { filter: (item) => item.name === 'fabric.mod.json' })['fabric.mod.json'];
  if (!entry) throw Error('O JAR do Antagon Inventory é inválido.');
  const metadata = JSON.parse(Buffer.from(entry).toString('utf8'));
  if (metadata.id !== 'antagon_inventory' || metadata.environment !== 'client')
    throw Error('O módulo Antagon Inventory é inválido.');
  const requiredLoader = String(metadata.depends?.fabricloader || '').match(/>=\s*([\d.]+)/)?.[1];
  const requiredApi = String(metadata.depends?.['fabric-api'] || '').match(/>=\s*([\d.]+)/)?.[1];
  if (!requiredLoader || !versionAtLeast(loaderVersion, requiredLoader))
    throw Error(`O Antagon Inventory requer Fabric Loader ${requiredLoader || 'compatível'} ou superior.`);
  if (!requiredApi || !versionAtLeast(fabricApiVersion, requiredApi))
    throw Error(`O Antagon Inventory requer Fabric API ${requiredApi || 'compatível'} ou superior.`);

  const mods = path.join(game, 'mods');
  await fs.mkdir(mods, { recursive: true });
  const target = path.join(mods, filename);
  if (await fs.readFile(target).then((current) => current.equals(data)).catch(() => false)) return filename;
  const archived = [];
  const archive = async (file) => {
    const backup = path.join(game, 'antagon-mod-backups');
    await fs.mkdir(backup, { recursive: true });
    const from = path.join(mods, file);
    const to = path.join(backup, `${Date.now()}-${file}`);
    await fs.rename(from, to);
    archived.push({ from, to });
  };
  if (await fs.access(target).then(() => true).catch(() => false)) await archive(filename);
  for (const file of await fs.readdir(mods)) {
    if (!file.endsWith('.jar')) continue;
    try {
      const modEntry = unzipSync(await fs.readFile(path.join(mods, file)), {
        filter: (item) => item.name === 'fabric.mod.json',
      })['fabric.mod.json'];
      if (modEntry && JSON.parse(Buffer.from(modEntry).toString('utf8')).id === 'antagon_inventory') await archive(file);
    } catch {}
  }
  const pending = `${target}.installing`;
  await fs.writeFile(pending, data);
  try {
    const check = unzipSync(await fs.readFile(pending), {
      filter: (item) => item.name === 'fabric.mod.json',
    })['fabric.mod.json'];
    if (!check || JSON.parse(Buffer.from(check).toString('utf8')).id !== 'antagon_inventory')
      throw Error('Falha ao validar o JAR do Antagon Inventory.');
    await fs.rename(pending, target);
  } catch (error) {
    await fs.rm(pending, { force: true });
    for (const item of archived.reverse()) await fs.rename(item.to, item.from).catch(() => {});
    throw error;
  }
  return filename;
}

// Baritone (patched for Essential, see scripts/patch-baritone.java) only for admins and invited players.
// Revoking removes the launcher's copy; a Baritone the player installed by hand is left alone.
async function installBot(game, assets, gameVersion, allowed) {
  const filename = `baritone-${gameVersion}.jar`;
  const mods = path.join(game, 'mods');
  const data = allowed && (await fs.readFile(path.join(assets, filename)).catch(() => null));
  if (!data) return fs.rm(path.join(mods, filename), { force: true });
  for (const file of await fs.readdir(mods)) {
    if (!file.endsWith('.jar') || file === filename) continue;
    try {
      const entry = unzipSync(await fs.readFile(path.join(mods, file)), {
        filter: (item) => item.name === 'fabric.mod.json',
      })['fabric.mod.json'];
      if (!entry || JSON.parse(Buffer.from(entry).toString('utf8')).id !== 'baritone') continue;
    } catch {
      continue;
    }
    // Two Baritone jars would stop Fabric from starting.
    const backup = path.join(game, 'antagon-mod-backups');
    await fs.mkdir(backup, { recursive: true });
    await fs.rename(path.join(mods, file), path.join(backup, `${Date.now()}-${file}`));
  }
  await fs.writeFile(path.join(mods, filename), data);
}

module.exports = { resolveMods, installMods, installCompanion, installInventoryCompanion, installBot, modernOptions };
