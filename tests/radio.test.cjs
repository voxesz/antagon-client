const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { RadioBridge } = require('../electron/radio-bridge.cjs');
const { RadioService, audioFile } = require('../electron/radio.cjs');

const tracks = [
  { id: 'a', title: 'A', artist: 'Artist', durationMs: 10000, url: 'https://example.test/a.mp3' },
  { id: 'b', title: 'B', artist: 'Artist', durationMs: 20000, url: 'https://example.test/b.mp3' },
];
const collection = {
  id: 'antagon',
  mode: 'radio',
  name: 'Rádio Antagon',
  trackIds: ['a', 'b'],
  schedules: [{ startsAt: 100000, trackIds: ['a', 'b'] }],
};

test('live schedule has exact track boundaries, cycle wrap and future program changes', async () => {
  const { livePosition } = await import('../ui/radio-model.mjs');
  const map = new Map(tracks.map((t) => [t.id, t]));
  assert.equal(livePosition(collection, map, 99999), null);
  assert.equal(livePosition(collection, map, 100000).track.id, 'a');
  assert.equal(livePosition(collection, map, 109999).offset, 9.999);
  assert.equal(livePosition(collection, map, 110000).track.id, 'b');
  assert.equal(livePosition(collection, map, 130000).offset, 0);
  assert.equal(livePosition(collection, map, 130000).track.id, 'a');
  assert.notEqual(livePosition(collection, map, 100000).key, livePosition(collection, map, 130000).key);
  const updated = { ...collection, schedules: [...collection.schedules, { startsAt: 145000, trackIds: ['a'] }] };
  assert.equal(livePosition(updated, map, 144999).track.id, 'b');
  assert.equal(livePosition(updated, map, 145000).track.id, 'a');
  assert.equal(livePosition(updated, map, 145000).offset, 0);
  assert.equal(
    livePosition(collection, new Map([['b', tracks[1]]]), 111000),
    null,
    'a missing song must not shorten the clock for other listeners',
  );
});

class FakeAudio extends EventTarget {
  currentTime = 0;
  duration = 20;
  paused = true;
  ended = false;
  load() {
    this.currentTime = 0;
    this.paused = true;
  }
  play() {
    this.paused = false;
    this.dispatchEvent(new Event('playing'));
    return Promise.resolve();
  }
  pause() {
    this.paused = true;
  }
  getAttribute(name) {
    return this[name];
  }
  removeAttribute(name) {
    delete this[name];
  }
  loaded() {
    this.dispatchEvent(new Event('loadedmetadata'));
  }
}

test('listeners with different clocks join together; live resume catches up and cannot seek/skip', async () => {
  const { RadioPlayer } = await import('../ui/radio-player.mjs');
  let elapsed = 0;
  const one = new RadioPlayer(new FakeAudio(), { clock: () => elapsed + 4000 });
  const two = new RadioPlayer(new FakeAudio(), { clock: () => elapsed + 940000 });
  for (const p of [one, two]) {
    p.setCatalog({ serverTime: 105000, collections: [collection], tracks });
    p.select('antagon');
    p.audio.loaded();
    assert.equal(p.audio.currentTime, 5);
  }
  one.next();
  one.seek(1);
  assert.equal(one.track.id, 'a');
  assert.equal(one.audio.currentTime, 5);
  one.toggle();
  elapsed = 8000;
  two.tick();
  two.audio.loaded();
  one.toggle();
  one.audio.loaded();
  assert.equal(one.track.id, 'b');
  assert.equal(two.track.id, 'b');
  assert.equal(one.audio.currentTime, 3);
  assert.equal(two.audio.currentTime, 3);
  elapsed = 100000;
  one.tick();
  assert.equal(one.state, 'error');
  assert.equal(one.audio.paused, true);
  assert.match(one.error, /conexão/);
});

test('playlist pause preserves position, controls stay independent and final song stops', async () => {
  const { RadioPlayer } = await import('../ui/radio-player.mjs');
  const p = new RadioPlayer(new FakeAudio());
  const playlist = { id: 'mine', mode: 'playlist', name: 'Mine', trackIds: ['a', 'b'] };
  p.setCatalog({ serverTime: 105000, collections: [playlist], tracks });
  p.select('mine');
  p.audio.loaded();
  p.seek(7);
  p.toggle();
  assert.equal(p.audio.paused, true);
  p.toggle();
  assert.equal(p.audio.currentTime, 7);
  p.next();
  p.audio.loaded();
  assert.equal(p.track.id, 'b');
  p.audio.dispatchEvent(new Event('ended'));
  assert.equal(p.wantPlay, false);
  p.next(-1);
  assert.equal(p.track.id, 'a');
  p.setCatalog({ serverTime: 110000, collections: [], tracks });
  assert.equal(p.selected, null);
  assert.equal(p.audio.paused, true);
});

test('audio errors stop playback until explicitly retried; old play promises cannot cancel new audio', async () => {
  const { RadioPlayer } = await import('../ui/radio-player.mjs');
  const audio = new FakeAudio();
  let reject;
  audio.play = () =>
    new Promise((_, r) => {
      reject = r;
    });
  const p = new RadioPlayer(audio, { clock: () => 0 });
  p.setCatalog({ serverTime: 105000, collections: [collection], tracks });
  p.select('antagon');
  audio.loaded();
  const oldReject = reject;
  p.select('antagon');
  audio.loaded();
  oldReject(Error('Old request failed'));
  await Promise.resolve();
  assert.equal(p.wantPlay, true);
  audio.dispatchEvent(new Event('error'));
  assert.equal(p.wantPlay, false);
  p.tick();
  assert.equal(p.wantPlay, false);
  p.toggle();
  assert.equal(p.wantPlay, true);
});

test('bridge expires stale player state, preserves accents and discards invalid game commands', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'antagon-radio-'));
  try {
    const commands = [];
    const bridge = new RadioBridge(
      directory,
      (c) => commands.push(c),
      () => null,
    );
    bridge.report(
      { state: 'playing', collectionId: 'antagon', trackId: 'a', position: 3, volume: 500 },
      { tracks, collections: [collection] },
    );
    await fs.writeFile(path.join(directory, 'antagon-radio-cmd-1.txt'), 'volume\t55');
    await fs.writeFile(path.join(directory, 'antagon-radio-cmd-2.txt'), 'volume\t999');
    await fs.writeFile(path.join(directory, 'antagon-radio-cmd-3.txt'), 'play\tfile:///private');
    await bridge.sync();
    assert.deepEqual(commands, [{ action: 'volume', value: 55 }]);
    let contents = await fs.readFile(path.join(directory, 'antagon-radio-state.properties'), 'utf8');
    assert.match(contents, /collection=Rádio Antagon/);
    assert.match(contents, /volume=100/);
    bridge.reportedAt = 0;
    await bridge.sync();
    contents = await fs.readFile(path.join(directory, 'antagon-radio-state.properties'), 'utf8');
    assert.match(contents, /state=off/);
    assert.equal((await fs.readdir(directory)).filter((f) => f.includes('cmd')).length, 0);
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});

test('service checks admin before uploading and rejects disguised media', async () => {
  let uploaded = false;
  const service = new RadioService({
    me: { id: 'x' },
    requireActive: async () => ({ isAdmin: false }),
    db: {
      storage: {
        from: () => ({
          upload: () => {
            uploaded = true;
          },
        }),
      },
    },
  });
  await assert.rejects(service.addTrack({}), /administradores/);
  assert.equal(uploaded, false);
  assert.throws(() => audioFile(Buffer.from('<html>not music</html>'), 'mp3'), /não corresponde/);
  assert.throws(() => audioFile(Buffer.from('ID3testaudio'), 'html'), /MP3/);
});
