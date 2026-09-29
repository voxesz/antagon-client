const { _electron: electron } = require('playwright');
const path = require('node:path');
const fs = require('node:fs/promises');
const assert = require('node:assert/strict');
const { DEFAULTS } = require('../electron/settings.cjs');

(async () => {
  const root = path.resolve(__dirname, '..');
  await fs.mkdir(path.join(root, 'build'), { recursive: true });
  const profile = await fs.mkdtemp(path.join(root, 'build/ui-test-'));
  await fs.writeFile(path.join(profile, 'settings.json'), JSON.stringify({ ...DEFAULTS, discordPresence: false }));
  const app = await electron.launch({
    ...(process.env.ANTAGON_EXECUTABLE
      ? { executablePath: process.env.ANTAGON_EXECUTABLE, args: [] }
      : { args: [root] }),
    env: { ...process.env, ANTAGON_TEST_ROOT: profile },
  });
  const page = await app.firstWindow();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  try {
    await page.waitForFunction(() => document.querySelector('#version').textContent.length > 0);
    await page.evaluate(() => document.fonts.ready);
    await page.waitForFunction(() => document.querySelector('#map').dataset.animating === 'true');
    await page.screenshot({ path: path.join(root, 'docs/launcher.png') });
    await page.click('#background-switch');
    await page.waitForFunction(
      () =>
        document.querySelector('#ascii').dataset.animating === 'true' &&
        document.querySelector('#map').dataset.animating === 'false',
    );
    await page.click('[data-view="settings"]');
    await page.waitForFunction(() =>
      ['map', 'ascii'].every((id) => document.getElementById(id).dataset.animating === 'false'),
    );
    await page.locator('#fullscreen').check();
    await page.evaluate(() => {
      const memory = document.querySelector('#memory');
      memory.value = '5';
      memory.dispatchEvent(new Event('change'));
      const pack = document.querySelector('#pack-toggle');
      pack.checked = false;
      pack.dispatchEvent(new Event('change'));
    });
    await page.waitForFunction(async () => {
      const { settings } = await window.antagon.init();
      return settings.memory === 5 && settings.pack === false && settings.fullscreen;
    });
    await page.screenshot({ path: path.join(root, 'docs/settings.png') });
    await page.click('#profile-open');
    await page.fill('#nickname', 'a');
    await page.click('#save-nick');
    assert.match(await page.locator('#account-error').textContent(), /3 a 16/);
    await page.fill('#nickname', 'Antagon_Test');
    await page.click('#save-nick');
    await page.waitForFunction(() => document.querySelector('#profile-name').textContent === 'Antagon_Test');
    await page.click('[data-view="play"]');
    await page.waitForFunction(() => document.querySelector('#ascii').dataset.animating === 'true');
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.waitForFunction(() => document.querySelector('#ascii').dataset.animating === 'false');
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await page.waitForFunction(() => document.querySelector('#ascii').dataset.animating === 'true');
    await page.evaluate(() => {
      document.body.dataset.phase = 'running';
    });
    await page.waitForFunction(() => document.querySelector('#ascii').dataset.animating === 'false');
    await page.evaluate(() => {
      document.body.dataset.phase = 'idle';
    });
    await page.click('#background-switch');
    await page.waitForFunction(() => document.querySelector('#map').dataset.animating === 'true');
    await app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].setSize(1040, 720));
    await page.click('[data-view="settings"]');
    await page.locator('#discord-presence').scrollIntoViewIfNeeded();
    assert.equal(await page.locator('#discord-presence').isChecked(), false);
    assert.equal(await page.locator('#discord-state').textContent(), 'Desativado');
    const state = await page.evaluate(() => window.antagon.init());
    assert.equal(state.settings.nickname, 'Antagon_Test');
    assert.equal(state.settings.memory, 5);
    assert.equal(state.settings.pack, false);
    assert.equal(errors.length, 0, errors.join('\n'));
    console.log('UI OK: navegação, animações suspensas, movimento reduzido, configurações rápidas, perfil e Discord.');
  } finally {
    await app.close();
    await fs.rm(profile, { recursive: true, force: true });
  }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
