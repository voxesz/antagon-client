const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { zipSync } = require('fflate');
const { resolveMods, installMods } = require('../electron/modern-mods.cjs');

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
  assert.equal(mods.length, 6);
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
