const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { CosmeticsBridge } = require('../electron/cosmetics-bridge.cjs');
const self = '1'.repeat(32),
  other = '2'.repeat(32);
function setup(t, image = async () => {}) {
  const game = fs.mkdtempSync(path.join(os.tmpdir(), 'antagon-cosmetics-'));
  t.after(() => fs.rmSync(game, { recursive: true, force: true }));
  let now = 100000,
    calls = [];
  const community = {
    me: { id: 'owner', mcUuid: self },
    visibleCosmetics: async (ids) => {
      calls.push([...ids]);
      return ids.map((id) => ({
        mc_uuid: id,
        active_client: true,
        cape: 'custom_1234567890abcdef',
        hat: 'antagon_crown',
        is_admin: id === self,
      }));
    },
  };
  return {
    game,
    community,
    calls,
    bridge: new CosmeticsBridge(game, community, image, () => now),
    advance: (ms) => (now += ms),
    snapshot: () => fs.readFileSync(path.join(game, 'antagon-cosmetics.properties'), 'utf8'),
  };
}
test('own cosmetics preload before the roster exists and new players fetch without waiting 15 seconds', async (t) => {
  const s = setup(t);
  await s.bridge.sync({ ownOnly: true, waitForImages: true });
  assert.match(s.snapshot(), /client,cape:custom_1234567890abcdef,hat:antagon_crown,admin/);
  s.advance(250);
  fs.writeFileSync(path.join(s.game, 'antagon-players.txt'), other);
  await s.bridge.sync({ waitForImages: true });
  assert.equal(s.calls.length, 2);
  assert(s.snapshot().includes(other));
  s.advance(250);
  await s.bridge.sync();
  assert.equal(s.calls.length, 2, 'unchanged roster should not flood the API');
});
test('slow cape download does not delay tags and the preloader waits for the actual texture', async (t) => {
  let release;
  const image = new Promise((resolve) => (release = resolve));
  const s = setup(t, () => image);
  const pending = s.bridge.sync({ ownOnly: true, waitForImages: true });
  await new Promise((resolve) => setImmediate(resolve));
  assert(s.snapshot().includes('admin'));
  assert.equal(s.bridge.busy, true);
  release();
  await pending;
});
test('temporary network errors preserve fresh cached cosmetics but cannot keep expired or logged-out identities', async (t) => {
  const s = setup(t);
  await s.bridge.sync({ waitForImages: true });
  s.community.visibleCosmetics = async () => {
    throw Error('offline');
  };
  s.advance(16000);
  await assert.rejects(s.bridge.sync(), /offline/);
  assert(s.snapshot().includes(self));
  s.advance(121000);
  await assert.rejects(s.bridge.sync(), /offline/);
  assert.equal(s.snapshot(), '\n');
  s.community.me = { id: 'different', mcUuid: other };
  await assert.rejects(s.bridge.sync(), /offline/);
  assert(!s.snapshot().includes(self));
});

test('equipping during a lookup schedules a fresh lookup on the next tick', async (t) => {
  const s = setup(t);
  const original = s.community.visibleCosmetics;
  let release;
  s.community.visibleCosmetics = () => new Promise((resolve) => (release = resolve));
  const first = s.bridge.sync({ ownOnly: true });
  await s.bridge.sync({ force: true });
  release([]);
  await first;
  s.community.visibleCosmetics = original;
  await s.bridge.sync({ ownOnly: true, waitForImages: true });
  assert.equal(s.calls.length, 1);
  assert(s.snapshot().includes('cape:'));
});
