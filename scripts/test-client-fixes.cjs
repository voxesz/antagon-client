const { _electron: electron } = require('playwright');
const fs = require('node:fs/promises');
const path = require('node:path');
const assert = require('node:assert/strict');
const { DEFAULTS } = require('../electron/settings.cjs');
(async () => {
  const root = path.resolve(__dirname, '..');
  const profile = await fs.mkdtemp(path.join(root, 'build/client-fixes-'));
  await fs.writeFile(path.join(profile, 'settings.json'), JSON.stringify({ ...DEFAULTS, discordPresence: false }));
  const app = await electron.launch({
    ...(process.env.ANTAGON_EXECUTABLE
      ? { executablePath: process.env.ANTAGON_EXECUTABLE, args: [] }
      : { args: [root] }),
    env: { ...process.env, ANTAGON_TEST_ROOT: profile },
  });
  try {
    const page = await app.firstWindow(),
      errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await app.evaluate(({ ipcMain, BrowserWindow }) => {
      globalThis.fixCatalog = [
        {
          id: 'antagon_cape',
          name: 'Capa Antagon',
          kind: 'cape',
          price: 100,
          active: true,
          custom: false,
          owners: 4,
          mine: true,
        },
        {
          id: 'antagon_crown',
          name: 'Coroa Antagon',
          kind: 'hat',
          price: 250,
          active: true,
          custom: false,
          owners: 2,
          mine: true,
        },
      ];
      globalThis.fixSends = 0;
      globalThis.fixDeleted = [];
      const mock = (name, fn) => {
        ipcMain.removeHandler(name);
        ipcMain.handle(name, async (_, ...args) => ({ ok: true, value: await fn(...args) }));
      };
      mock('community:state', () => ({
        me: { id: 'me', name: 'Teste' },
        friends: [{ id: 'friend', name: 'Amigo', online: true, activity: 'launcher' }],
        incoming: [],
        outgoing: [],
      }));
      mock('admin:access', () => ({ isAdmin: true, isOwner: true }));
      mock('admin:catalog', () => globalThis.fixCatalog);
      mock('store:featured', () => globalThis.fixCatalog.filter((item) => item.active && item.featured));
      mock('admin:update', (id, input) => {
        globalThis.fixUpdate = { id, ...input };
        Object.assign(
          globalThis.fixCatalog.find((c) => c.id === id),
          input,
        );
        return globalThis.fixCatalog;
      });
      mock('admin:delete', (id) => {
        globalThis.fixDeleted.push(id);
        globalThis.fixCatalog = globalThis.fixCatalog.filter((c) => c.id !== id);
        return globalThis.fixCatalog;
      });
      mock('store:state', () => ({
        catalog: globalThis.fixCatalog,
        owned: ['antagon_cape', 'antagon_crown'],
        equipped: {},
        balance: 0,
      }));
      mock('community:messages', () => [
        { id: 9, sender: 'friend', recipient: 'me', body: 'Oi', created_at: '2026-10-03T00:00:00Z' },
      ]);
      mock('community:send', (recipient, body) => {
        globalThis.fixSends++;
        const message = { id: 10, sender: 'me', recipient, body, created_at: '2026-10-03T00:00:00Z' };
        BrowserWindow.getAllWindows()[0].webContents.send('community:event', {
          type: 'message',
          payload: { ...message, id: String(message.id) },
        });
        return message;
      });
    });
    await page.reload();
    await page.click('[data-view="friends"]');
    await page.click('[data-person="friend"]');
    await page.waitForSelector('#messages .message');
    await page.fill('#chat-input', 'Mensagem única');
    await page.locator('#chat-input').press('Enter');
    await page.waitForFunction(() => document.querySelectorAll('#messages .message').length === 2);
    assert.equal(await page.locator('#messages .mine').count(), 1);
    assert.equal(await app.evaluate(() => globalThis.fixSends), 1);
    await page.click('[data-view="admin"]');
    await page.click('[data-admin-tab="catalog"]');
    await page.click('[data-catalog-action="edit"][data-catalog-item="antagon_crown"]');
    await page.fill('#catalog-edit-name', 'Coroa Rubra');
    await page.fill('#catalog-edit-price', '375');
    await page.check('#catalog-edit-featured');
    await page.screenshot({ path: path.join(root, 'build/catalog-edit.png') });
    await page.click('#catalog-edit-form button[type="submit"]');
    await page.waitForFunction(() => !document.querySelector('#catalog-edit-dialog').open);
    assert.deepEqual(await app.evaluate(() => globalThis.fixUpdate), {
      id: 'antagon_crown',
      name: 'Coroa Rubra',
      price: 375,
      active: true,
      featured: true,
    });
    const crown = page.locator('.catalog-row').filter({ hasText: 'Coroa Rubra' });
    assert.match(await crown.textContent(), /375 ANTAGOIN\$/);
    assert.match(await crown.textContent(), /Destaque/);
    await page.click('[data-view="play"]');
    await page.waitForSelector('[data-featured-item="antagon_crown"]');
    assert.match(await page.locator('#featured-items').textContent(), /Coroa Rubra/);
    assert.match(await page.locator('#featured-items').textContent(), /375/);
    await page.click('[data-featured-item="antagon_crown"]');
    await page.waitForFunction(() => document.activeElement?.dataset.storeItem === 'antagon_crown');
    await page.click('[data-view="admin"]');
    await page.click('[data-catalog-action="edit"][data-catalog-item="antagon_crown"]');
    assert.equal(await page.locator('#catalog-edit-featured').isChecked(), true);
    await page.uncheck('#catalog-edit-active');
    await page.click('#catalog-edit-form button[type="submit"]');
    await page.waitForFunction(() => !document.querySelector('#catalog-edit-dialog').open);
    await page.click('[data-view="play"]');
    await page.waitForFunction(() => document.querySelectorAll('.featured-card').length === 0);
    await page.click('[data-view="admin"]');
    page.on('dialog', (dialog) => dialog.accept());
    for (const id of ['antagon_crown', 'antagon_cape']) {
      await page.click(`[data-catalog-action="delete"][data-catalog-item="${id}"]`);
      await page.waitForFunction((item) => !document.querySelector(`[data-catalog-item="${item}"]`), id);
    }
    assert.deepEqual(await app.evaluate(() => globalThis.fixDeleted), ['antagon_crown', 'antagon_cape']);
    assert.deepEqual(errors, []);
    console.log(
      'Client fixes UI OK: one message per send, edit name/price/visibility, delete built-in cape and crown. All operations used local fixtures.',
    );
  } finally {
    await app.close();
  }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
