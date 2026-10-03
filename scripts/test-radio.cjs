const { _electron: electron } = require('playwright');
const fs = require('node:fs/promises');
const path = require('node:path');
const assert = require('node:assert/strict');
const { DEFAULTS } = require('../electron/settings.cjs');

function wav(seconds = 20) {
  const rate = 8000,
    size = seconds * rate * 2;
  const b = Buffer.alloc(44 + size);
  b.write('RIFF');
  b.writeUInt32LE(36 + size, 4);
  b.write('WAVEfmt ', 8);
  b.writeUInt32LE(16, 16);
  b.writeUInt16LE(1, 20);
  b.writeUInt16LE(1, 22);
  b.writeUInt32LE(rate, 24);
  b.writeUInt32LE(rate * 2, 28);
  b.writeUInt16LE(2, 32);
  b.writeUInt16LE(16, 34);
  b.write('data', 36);
  b.writeUInt32LE(size, 40);
  for (let i = 0; i < size / 2; i++)
    b.writeInt16LE(Math.round(Math.sin((i / rate) * 440 * Math.PI * 2) * 300), 44 + i * 2);
  return b;
}

(async () => {
  const root = path.resolve(__dirname, '..');
  const profile = await fs.mkdtemp(path.join(root, 'build/radio-test-'));
  await fs.writeFile(path.join(profile, 'settings.json'), JSON.stringify({ ...DEFAULTS, discordPresence: false }));
  await fs.writeFile(path.join(profile, 'test.wav'), wav(2));
  const app = await electron.launch({ args: [root], env: { ...process.env, ANTAGON_TEST_ROOT: profile } });
  const page = await app.firstWindow();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  try {
    await app.evaluate(({ ipcMain, BrowserWindow }) => {
      BrowserWindow.getAllWindows()[0].webContents.setAudioMuted(true);
      const tracks = ['A', 'B'].map((letter, i) => ({
        id: `00000000-0000-4000-8000-00000000000${i}`,
        title: `Faixa de teste ${letter}`,
        artist: 'Teste local',
        genre: i ? 'Phonk' : 'Lo-fi',
        durationMs: 20000,
        url: `https://pnlemlqvuqftantunwcw.supabase.co/storage/v1/object/public/radio-media/test-${letter}.wav`,
      }));
      const collection = (id, name, genre, mode) => ({
        id,
        name,
        genre,
        mode,
        description: 'Rádio com estilos variados.',
        trackIds: tracks.map((t) => t.id),
        coverUrl: '',
        schedules: mode === 'radio' ? [{ startsAt: Date.now() - 3000, trackIds: tracks.map((t) => t.id) }] : [],
      });
      globalThis.radioFixture = {
        tracks,
        collections: [
          collection('antagon', 'Rádio Antagon', 'Mix', 'radio'),
          collection('lofi', 'Lo-fi Radio', 'Lo-fi', 'radio'),
          collection('phonk', 'Phonk Radio', 'Phonk', 'radio'),
          collection('electronic', 'Electronic Radio', 'Eletrônica', 'radio'),
          collection('playlist', 'Depois da meia-noite', 'Lo-fi', 'playlist'),
        ],
      };
      const mock = (channel, fn) => {
        ipcMain.removeHandler(channel);
        ipcMain.handle(channel, async (_, ...args) => ({ ok: true, value: await fn(...args) }));
      };
      mock('radio:catalog', () => ({ ...globalThis.radioFixture, serverTime: Date.now() }));
      mock('radio:saveCollection', (value) => {
        globalThis.radioSaved = value;
        const saved = { ...value, id: value.id || 'new-playlist', schedules: [] };
        globalThis.radioFixture.collections = globalThis.radioFixture.collections
          .filter((c) => c.id !== saved.id)
          .concat(saved);
        return { id: saved.id };
      });
      mock('radio:addTrack', (value) => {
        globalThis.radioUploaded = {
          bytes: value.audio.byteLength,
          durationMs: value.durationMs,
          extension: value.extension,
        };
        return 'uploaded';
      });
      mock('community:state', () => ({
        me: { id: 'test', name: 'Test', mcUuid: 'test' },
        friends: [],
        incoming: [],
        outgoing: [],
      }));
      mock('admin:access', () => ({ isAdmin: true, isOwner: true }));
      mock('admin:catalog', () => []);
    });
    await page.route(
      'https://pnlemlqvuqftantunwcw.supabase.co/storage/v1/object/public/radio-media/test-*.wav',
      (route) => route.fulfill({ status: 200, contentType: 'audio/wav', body: wav() }),
    );
    await page.reload();
    await page.waitForFunction(() => document.querySelector('#version').textContent.length > 0);
    await page.click('[data-view="radio"]');
    await page.waitForFunction(() => document.querySelectorAll('.radio-card').length === 4);
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: path.join(root, 'docs/radio.png') });
    await page.click('#radio-hero [data-radio-play]');
    await page.waitForFunction(
      () =>
        !document.querySelector('#antagon-audio').paused && document.querySelector('#antagon-audio').currentTime > 1,
    );
    assert.equal(await page.locator('#music-seek').isDisabled(), true);
    assert.equal(await page.locator('#music-next').isDisabled(), true);
    await page.click('[data-view="play"]');
    assert.equal(await page.locator('.music-player').isVisible(), true);
    assert.equal(await page.locator('#antagon-audio').evaluate((audio) => audio.paused), false);
    await page.click('[data-view="radio"]');
    await page.click('[data-radio-mode="playlist"]');
    await page.click('[data-radio-open="playlist"]');
    await page.click('[data-track-index="0"]');
    await page.waitForFunction(() => !document.querySelector('#antagon-audio').paused);
    assert.equal(await page.locator('#music-title').textContent(), 'Faixa de teste A');
    assert.equal(await page.locator('#music-seek').isDisabled(), false);
    await page.click('#music-toggle');
    assert.equal(await page.locator('#antagon-audio').evaluate((audio) => audio.paused), true);
    await page.click('#music-next');
    await page.waitForFunction(
      () =>
        document.querySelector('#music-title').textContent === 'Faixa de teste B' &&
        !document.querySelector('#antagon-audio').paused,
    );
    await page.screenshot({ path: path.join(root, 'docs/radio-playlist.png') });
    await page.click('#music-stop');
    assert.equal(await page.locator('.music-player').isVisible(), false);
    await page.click('#radio-manage');
    await page.waitForFunction(() => document.querySelectorAll('.radio-library-item').length === 2);
    await page.click('[data-radio-new="playlist"]');
    await page.fill('#radio-edit-name', 'Teste integração');
    await page.fill('#radio-edit-genre', 'Rock');
    await page.locator('[data-radio-add]').first().click();
    await page.locator('[data-radio-add]').last().click();
    await page.click('[data-queue-up="1"]');
    await page.click('#radio-edit-save');
    await page.waitForFunction(() => document.querySelector('#radio-edit-select').value === 'new-playlist');
    const saved = await app.evaluate(() => globalThis.radioSaved);
    assert.equal(saved.name, 'Teste integração');
    assert.deepEqual(saved.trackIds, ['00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000000']);
    await page.screenshot({ path: path.join(root, 'docs/radio-admin.png') });
    await page.click('#radio-upload-open');
    await page.setInputFiles('#radio-upload-audio', path.join(profile, 'test.wav'));
    await page.waitForFunction(() => document.querySelector('#radio-upload-status').textContent.includes('Pronta'));
    await page.fill('#radio-upload-artist', 'Teste local');
    await page.fill('#radio-upload-genre', 'Lo-fi');
    await page.click('#radio-upload-submit');
    await page.waitForFunction(() => !document.querySelector('#radio-upload-dialog').open);
    const uploaded = await app.evaluate(() => globalThis.radioUploaded);
    assert.equal(uploaded.durationMs, 2000);
    assert.equal(uploaded.extension, 'wav');
    assert.equal(uploaded.bytes, 32044);
    await app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].setSize(1040, 720));
    await page.click('[data-view="radio"]');
    await page.click('[data-radio-mode="radio"]');
    await page.screenshot({ path: path.join(root, 'build/radio-small.png') });
    const clipped = await page.evaluate(
      () =>
        document.querySelector('nav').getBoundingClientRect().right >
        document.querySelector('#profile-open').getBoundingClientRect().left,
    );
    assert.equal(clipped, false);
    assert.deepEqual(errors, []);
    console.log(
      'Radio UI OK: real audio decoding, live join, playlist controls, persistent player, admin queue and file upload.',
    );
  } finally {
    await app.close();
  }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
