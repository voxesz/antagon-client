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
    await app.evaluate(({ BrowserWindow }) =>
      BrowserWindow.getAllWindows()[0].webContents.send('game:open-view', 'friends'),
    );
    await page.waitForFunction(() => document.querySelector('#view-friends').classList.contains('active'));
    await app.evaluate(({ BrowserWindow }) =>
      BrowserWindow.getAllWindows()[0].webContents.send('game:open-view', 'settings'),
    );
    await page.waitForFunction(() => document.querySelector('#view-settings').classList.contains('active'));
    await page.click('[data-view="store"]');
    assert.equal(await page.locator('#store-gate').isVisible(), true);
    assert.equal(await page.locator('#store-content').isVisible(), false);
    assert.equal(await page.locator('.wallet img').evaluate((img) => img.complete && img.naturalWidth > 0), true);
    await page.click('[data-view="play"]');
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
    await app.evaluate(({ ipcMain }) => {
      for (const channel of [
        'community:state',
        'admin:access',
        'admin:find',
        'store:state',
        'admin:catalog',
        'admin:createCape',
      ])
        ipcMain.removeHandler(channel);
      const me = {
        id: '670ffb62-9dfc-4276-8a22-8f51b90691bb',
        name: 'Voxesz',
        mcUuid: '670ffb629dfc42768a228f51b90691bb',
      };
      ipcMain.handle('community:state', () => ({ ok: true, value: { me, friends: [], incoming: [], outgoing: [] } }));
      ipcMain.handle('admin:access', () => ({ ok: true, value: { isAdmin: true, isOwner: true, isBanned: false } }));
      ipcMain.handle('admin:find', () => ({
        ok: true,
        value: {
          user_id: me.id,
          name: me.name,
          mc_uuid: me.mcUuid,
          is_admin: true,
          is_owner: true,
          is_banned: false,
          ban_reason: null,
          coins: 0,
          items: [],
        },
      }));
      ipcMain.handle('store:state', () => ({ ok: false, error: 'Catálogo não publicado.' }));
      const catalog = [
        { id: 'antagon_cape', name: 'Capa Antagon', kind: 'cape', price: 100, active: true, custom: false, owners: 3 },
        {
          id: 'antagon_logo_cape',
          name: 'Capa Logo Antagon',
          kind: 'cape',
          price: 100,
          active: false,
          custom: false,
          owners: 1,
        },
        { id: 'antagon_crown', name: 'Coroa Antagon', kind: 'hat', price: 250, active: true, custom: false, owners: 0 },
        {
          id: 'custom_0123456789abcdef',
          name: 'Capa Teste',
          kind: 'cape',
          price: 0,
          active: false,
          custom: true,
          owners: 1,
          mine: true,
        },
      ];
      ipcMain.handle('admin:catalog', () => ({ ok: true, value: catalog }));
      ipcMain.handle('admin:createCape', (_event, cape) => {
        globalThis.createdCape = { ...cape, png: Buffer.from(cape.png) };
        return { ok: true, value: 'custom_0123456789abcdef' };
      });
    });
    await page.reload();
    await page.waitForFunction(() => !document.querySelector('#admin-tab').hidden);
    await app.evaluate(({ BrowserWindow }) =>
      BrowserWindow.getAllWindows()[0].webContents.send('game:open-view', 'admin'),
    );
    await page.waitForFunction(() => document.querySelector('#view-admin').classList.contains('active'));
    await page.click('[data-view="store"]');
    await page.waitForFunction(() => document.querySelector('#store-items .store-item'));
    assert.match(await page.locator('#store-items').textContent(), /Capa Antagon/);
    assert.match(await page.locator('#store-items').textContent(), /Capa Logo Antagon/);
    assert.equal(await page.locator('#store-items button:not([disabled])').count(), 0);
    await page.screenshot({ path: path.join(root, 'build/store-preview.png') });
    await page.click('[data-view="admin"]');
    await page.fill('#admin-name', 'Voxesz');
    await page.click('#admin-search button');
    assert.equal(await page.locator('#admin-target-name').textContent(), 'Voxesz');
    await page.screenshot({ path: path.join(root, 'build/admin-preview.png') });

    await page.click('[data-admin-tab="catalog"]');
    await page.waitForFunction(() => document.querySelectorAll('#admin-catalog .catalog-row').length === 4);
    assert.equal(await page.locator('[data-catalog-action="delete"]').count(), 1);
    assert.equal(await page.locator('[data-catalog-action="take"][data-mine="1"]').count(), 1);
    assert.match(await page.locator('#admin-catalog').textContent(), /Fora da loja/);
    await page.waitForFunction(() => [...document.querySelectorAll('#admin-catalog img')].every((i) => i.complete));
    await page.screenshot({ path: path.join(root, 'build/admin-catalog.png') });
    await page.click('[data-admin-tab="editor"]');
    await page.fill('#cape-name', 'Capa Teste');
    await page.fill('#cape-text', 'ANTAGON');
    await page.setInputFiles('#cape-image', path.join(root, 'assets/antagon-coin.png'));
    await page.waitForFunction(() => !document.querySelector('#cape-image-clear').hidden);
    await page.click('[data-destination="player"]');
    assert.equal(await page.locator('#cape-target').isVisible(), true);
    await page.fill('#cape-target', 'Amigo');
    await page.screenshot({ path: path.join(root, 'build/cape-editor.png') });
    await page.click('#cape-create');
    await page.waitForFunction(() => /Capa criada/.test(document.querySelector('#toast').textContent));
    const created = await app.evaluate(
      () =>
        globalThis.createdCape && {
          ...globalThis.createdCape,
          png: [globalThis.createdCape.png.readUInt32BE(16), globalThis.createdCape.png.readUInt32BE(20)],
        },
    );
    assert.deepEqual(created, {
      name: 'Capa Teste',
      price: 100,
      destination: 'player',
      target: 'Amigo',
      png: [1024, 512],
    });

    await app.evaluate(({ ipcMain }) => {
      ipcMain.removeHandler('store:state');
      ipcMain.handle('store:state', () => ({
        ok: true,
        value: {
          balance: 350,
          catalog: [
            { id: 'antagon_cape', name: 'Capa Antagon', kind: 'cape', price: 100, active: true, custom: false },
            {
              id: 'antagon_logo_cape',
              name: 'Capa Logo Antagon',
              kind: 'cape',
              price: 100,
              active: false,
              custom: false,
            },
            { id: 'antagon_crown', name: 'Coroa Antagon', kind: 'hat', price: 250, active: true, custom: false },
          ],
          owned: ['antagon_logo_cape', 'antagon_crown'],
          equipped: { hat: 'antagon_crown' },
        },
      }));
    });
    await page.click('[data-view="store"]');
    await page.waitForFunction(() => document.querySelectorAll('#store-items .store-item').length === 2);
    assert.doesNotMatch(await page.locator('#store-items').textContent(), /Capa Logo Antagon/);
    await page.click('[data-store-tab="inventory"]');
    assert.equal(await page.locator('#store-inventory .store-item').count(), 2);
    assert.match(await page.locator('#store-inventory').textContent(), /Capa Logo Antagon/);
    assert.equal(await page.locator('#store-packs').isVisible(), false);
    await page.screenshot({ path: path.join(root, 'build/inventory-preview.png') });
    assert.equal(errors.length, 0, errors.join('\n'));
    console.log(
      'UI OK: loja, admin, navegação, animações suspensas, movimento reduzido, configurações rápidas, perfil e Discord.',
    );
  } finally {
    await app.close();
    await fs.rm(profile, { recursive: true, force: true });
  }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
