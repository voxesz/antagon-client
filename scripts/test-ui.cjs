const { _electron: electron } = require('playwright');
const path = require('node:path');
const fs = require('node:fs');
const assert = require('node:assert/strict');
(async () => {
  const root = path.resolve(__dirname, '..');
  const app = await electron.launch({
    args: [root],
    env: { ...process.env, ANTAGON_TEST_ROOT: path.join(root, 'build/ui-test-profile') },
  });
  const page = await app.firstWindow();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  try {
    await page.waitForSelector('#launch');
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: path.join(root, 'docs/launcher.png') });
    await page.click('[data-view="settings"]');
    await page.locator('#fullscreen').check();
    await page.screenshot({ path: path.join(root, 'docs/settings.png') });
    await page.click('#profile-open');
    await page.fill('#nickname', 'Antagon_Test');
    await page.click('#save-nick');
    await page.waitForFunction(() => document.querySelector('#profile-name').textContent === 'Antagon_Test');
    const state = await page.evaluate(() => window.antagon.init());
    assert.equal(state.settings.nickname, 'Antagon_Test');
    assert.equal(state.settings.fullscreen, true);
    assert.equal(errors.length, 0, errors.join('\n'));
    console.log('UI OK: navegação, configurações, perfil offline, persistência e ausência de erros.');
  } finally {
    await app.close();
  }
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
