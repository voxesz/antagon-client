const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { zipSync } = require('fflate');
const { resolveMods, installMods, installCompanion, modernOptions } = require('../electron/modern-mods.cjs');

const version = (project, id = project, extra = {}) => ({
  project_id: project,
  id,
  name: project,
  version_number: id,
  version_type: 'release',
  game_versions: ['26.3'],
  loaders: ['fabric'],
  dependencies: [],
  ...extra,
});
function catalog(overrides = {}) {
  const data = {
    iris: [
      version('iris', 'iris-release', { dependencies: [{ dependency_type: 'required', version_id: 'sodium-pinned' }] }),
    ],
    sodium: [version('sodium', 'sodium-beta', { version_type: 'beta' }), version('sodium', 'sodium-pinned')],
    'fabric-api': [version('fabric-api')],
    lithium: [version('lithium')],
    'ferrite-core': [version('ferrite-core')],
    essential: [
      version('essential', 'essential-beta', { version_type: 'beta' }),
      version('essential', 'essential-stable'),
    ],
    'xaeros-minimap': [version('xaeros-minimap')],
    'xaeros-world-map': [version('xaeros-world-map')],
    litematica: [
      version('litematica', 'litematica-stable', {
        dependencies: [{ dependency_type: 'required', project_id: 'malilib' }],
      }),
    ],
    malilib: [version('malilib', 'malilib-beta', { version_type: 'beta' }), version('malilib', 'malilib-stable')],
    ...overrides,
  };
  return async (url) => {
    const parts = new URL(url).pathname.split('/');
    if (parts[2] === 'version') return version('sodium', 'sodium-pinned');
    return data[parts[3]] || [];
  };
}
test('Iris selects its required Sodium instead of the latest incompatible beta', async () => {
  const mods = await resolveMods('26.3', catalog());
  assert.equal(mods.find((mod) => mod.project_id === 'sodium').id, 'sodium-pinned');
  assert.equal(mods.find((mod) => mod.project_id === 'essential').id, 'essential-stable');
  assert.equal(mods.length, 10);
});
test('client-side map and Litematica include the stable MaLiLib dependency', async () => {
  const mods = await resolveMods('26.3', catalog());
  assert.ok(mods.some((mod) => mod.project_id === 'xaeros-minimap'));
  assert.ok(mods.some((mod) => mod.project_id === 'xaeros-world-map'));
  assert.equal(mods.find((mod) => mod.project_id === 'litematica').id, 'litematica-stable');
  assert.equal(mods.find((mod) => mod.project_id === 'malilib').id, 'malilib-stable');
  await assert.rejects(resolveMods('26.3', catalog({ 'xaeros-minimap': [] })), /xaeros-minimap/);
  await assert.rejects(resolveMods('26.3', catalog({ 'xaeros-world-map': [] })), /xaeros-world-map/);
  await assert.rejects(resolveMods('26.3', catalog({ litematica: [] })), /litematica/);
  await assert.rejects(
    resolveMods('26.3', catalog({ malilib: [version('malilib', 'wrong', { game_versions: ['26.2'] })] })),
    /malilib/,
  );
});
test('world map starts on J without replacing saved controls or duplicating options', () => {
  const original = 'fullscreen:false\nkey_key.forward:key.keyboard.w';
  const configured = modernOptions(original);
  assert.equal(configured, original + '\nkey_gui.xaero_open_map:key.keyboard.j\n');
  assert.equal(modernOptions(configured), configured);
  for (const key of ['key.keyboard.k', 'key.keyboard.unknown', 'key.keyboard.m']) {
    const custom = `fullscreen:true\r\nkey_gui.xaero_open_map:${key}\r\n`;
    assert.equal(modernOptions(custom), custom);
  }
  assert.equal(modernOptions(''), 'key_gui.xaero_open_map:key.keyboard.j\n');
});
test('a missing Iris release or failed dependency stops preparation', async () => {
  await assert.rejects(resolveMods('26.3', catalog({ iris: [] })), /iris/);
  await assert.rejects(resolveMods('26.3', catalog({ essential: [] })), /essential/);
  const get = catalog();
  await assert.rejects(
    resolveMods('26.3', async (url) => {
      if (url.includes('/v2/version/')) throw Error('Download indisponível');
      return get(url);
    }),
    /Download indisponível/,
  );
});
test('a dependency for another game version is rejected', async () => {
  const get = catalog();
  await assert.rejects(
    resolveMods('26.3', async (url) =>
      url.includes('/v2/version/') ? version('sodium', 'wrong', { game_versions: ['26.2'] }) : get(url),
    ),
    /incompatível/,
  );
});
test('an update replaces duplicate managed mods and preserves custom mods', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'antagon-mods-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  await fs.mkdir(path.join(root, 'mods'));
  const jar = (id) => zipSync({ 'fabric.mod.json': Buffer.from(JSON.stringify({ id })) });
  await fs.writeFile(path.join(root, 'mods/renamed-old-sodium.jar'), jar('sodium'));
  await fs.writeFile(path.join(root, 'mods/custom.jar'), jar('custom'));
  const mod = version('sodium', 'new', {
    files: [{ filename: 'sodium-new.jar', url: 'https://cdn.modrinth.com/new.jar', hashes: { sha1: 'hash' } }],
  });
  await installMods(root, [mod], async (url, target) => fs.writeFile(target, jar('sodium')));
  assert.deepEqual((await fs.readdir(path.join(root, 'mods'))).sort(), ['custom.jar', 'sodium-new.jar']);
  assert.equal((await fs.readdir(path.join(root, 'antagon-mod-backups'))).length, 1);
  const before = await fs.readFile(path.join(root, 'mods/sodium-new.jar'));
  await assert.rejects(
    installMods(root, [mod], async () => {
      throw Error('download failed');
    }),
    /download failed/,
  );
  assert.deepEqual(await fs.readFile(path.join(root, 'mods/sodium-new.jar')), before);
});

test('bundled Fabric companion updates renamed copies without touching third-party mods', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'antagon-companion-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const game = path.join(root, 'game');
  const assets = path.join(root, 'assets');
  await fs.mkdir(path.join(game, 'mods'), { recursive: true });
  await fs.mkdir(assets);
  const jar = (id, version) => zipSync({ 'fabric.mod.json': Buffer.from(JSON.stringify({ id, version })) });
  const update = jar('antagon_modern', 'new');
  await fs.writeFile(path.join(game, 'mods/renamed-antagon.jar'), jar('antagon_modern', 'old'));
  await fs.writeFile(path.join(game, 'mods/essential.jar'), jar('essential', '1.5.1'));
  await fs.writeFile(path.join(assets, 'antagon-modern-26.3.jar'), update);
  assert.equal(await installCompanion(game, assets, '26.3'), 'antagon-modern-26.3.jar');
  assert.deepEqual((await fs.readdir(path.join(game, 'mods'))).sort(), ['antagon-modern-26.3.jar', 'essential.jar']);
  assert.deepEqual(await fs.readFile(path.join(game, 'mods/antagon-modern-26.3.jar')), Buffer.from(update));
  await assert.rejects(installCompanion(game, assets, '26.4'), /Atualize o launcher/);
  await assert.rejects(installCompanion(game, assets, '../../elsewhere'), /inválida/);
});
