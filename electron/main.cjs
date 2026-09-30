const { app, BrowserWindow, ipcMain, safeStorage, shell, dialog } = require('electron');
const { unzipSync } = require('fflate');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { pathToFileURL } = require('node:url');
const { Auth } = require('msmc');
const { Runtime } = require('./runtime.cjs');
const { atomicWrite: atomic, readTail } = require('./files.cjs');
const updater = require('./updater.cjs');
const { Community } = require('./community.cjs');
const { DiscordPresence } = require('./discord.cjs');
const { DEFAULTS, validateSettings, offlineAccount } = require('./settings.cjs');
app.setName('Antagon Client');
const root = process.env.ANTAGON_TEST_ROOT || path.join(app.getPath('appData'), 'Antagon Client');
app.setPath('userData', root);
const ui = path.resolve(__dirname, '../ui/index.html');
const assets = path.resolve(__dirname, '../assets');
let win,
  settings,
  account = null,
  sessionAccount = null,
  busy = false;
let state = { phase: 'idle', message: 'Pronto', percent: 0 };
function loadSettings() {
  try {
    return validateSettings(JSON.parse(fs.readFileSync(path.join(root, 'settings.json'), 'utf8')));
  } catch {
    return structuredClone(DEFAULTS);
  }
}
function saveSettings(next) {
  const validated = validateSettings(next);
  atomic(path.join(root, 'settings.json'), JSON.stringify(validated, null, 2));
  settings = validated;
  discord.configure(settings);
  gameActivity();
  return settings;
}
const modsDir = path.join(root, 'minecraft/mods');
function optifine() {
  try {
    return fs.readdirSync(modsDir).find((f) => /^optifine.*\.jar$/i.test(f)) || null;
  } catch {
    return null;
  }
}
async function installOptifine() {
  const pick = await dialog.showOpenDialog(win, {
    title: 'Escolha o OptiFine 1.8.9',
    defaultPath: app.getPath('downloads'),
    properties: ['openFile'],
    filters: [{ name: 'OptiFine', extensions: ['jar'] }],
  });
  if (pick.canceled || !pick.filePaths[0]) return optifine();
  const file = pick.filePaths[0];
  const names = [];
  try {
    unzipSync(fs.readFileSync(file), {
      filter: (e) => {
        names.push(e.name);
        return false;
      },
    });
  } catch {
    throw Error('Esse arquivo não é um .jar válido.');
  }
  if (!names.some((n) => n.startsWith('optifine/')) || !/1\.8\.9/.test(path.basename(file)))
    throw Error('Escolha o OptiFine para Minecraft 1.8.9 (OptiFine_1.8.9_HD_U_M5.jar).');
  fs.mkdirSync(modsDir, { recursive: true });
  for (const old of fs.readdirSync(modsDir)) if (/^optifine.*\.jar$/i.test(old)) fs.unlinkSync(path.join(modsDir, old));
  fs.copyFileSync(file, path.join(modsDir, path.basename(file)));
  return optifine();
}
function wallpaper() {
  for (const ext of ['png', 'jpg', 'jpeg', 'webp']) {
    const f = path.join(root, 'wallpaper.' + ext);
    if (fs.existsSync(f))
      return 'data:image/' + (ext === 'jpg' ? 'jpeg' : ext) + ';base64,' + fs.readFileSync(f).toString('base64');
  }
  return null;
}
function clearWallpaper() {
  for (const ext of ['png', 'jpg', 'jpeg', 'webp']) fs.rmSync(path.join(root, 'wallpaper.' + ext), { force: true });
}
function emit(update) {
  state = { ...state, ...update };
  if (win && !win.isDestroyed()) win.webContents.send('game:state', state);
}
const runtime = new Runtime(root, assets, emit);
const discord = new DiscordPresence((event) => {
  if (win && !win.isDestroyed()) win.webContents.send('discord:state', event);
});
const startedAt = Date.now();
let activityReading = false;
let community;
let communityReady, connectCommunity;
let cosmeticsReading = false;
let gameActionReading = false;
function communityEvent(event) {
  if (win && !win.isDestroyed()) win.webContents.send('community:event', event);
  if (
    event.type === 'message' &&
    gameUi.chat &&
    [event.payload?.sender, event.payload?.recipient].includes(gameUi.chat)
  )
    gameUi.messages.push(event.payload);
  gameUi.dirty = true;
}
const gameUi = {
  chat: null,
  messages: [],
  people: null,
  store: null,
  notice: '',
  dirty: true,
  busy: false,
  peopleAt: 0,
};
const clean = (text) =>
  String(text ?? '').replace(/[\\\t\r\n]/g, (c) => ({ '\\': '\\\\', '\t': '\\t', '\r': '', '\n': '\\n' })[c]);
const UUID = /^[0-9a-f-]{36}$/;
async function gameUiCommand(line) {
  const [action, a = '', b = ''] = line.split('\t');
  const body = b.replace(/\\(\\|t|n)/g, (m, c) => (c === 'n' ? '\n' : c === 't' ? '\t' : '\\'));
  if (action === 'chat' && UUID.test(a)) {
    gameUi.chat = a;
    gameUi.messages = await community.messages(a);
  } else if (action === 'send' && UUID.test(a)) await community.send(a, body);
  else if (action === 'accept' && UUID.test(a)) await community.accept(a);
  else if (action === 'remove' && UUID.test(a)) await community.remove(a);
  else if (action === 'add') gameUi.notice = await community.add(a.trim());
  else if (action === 'store') gameUi.store = await community.store();
  else if (action === 'buy') gameUi.store = await community.purchase(a);
  else if (action === 'equip') gameUi.store = await community.equip(a === 'none' ? null : a);
  else if (action === 'coins') await shell.openExternal(await community.checkout(a));
  else if (action === 'admin')
    return fs.promises.writeFile(path.join(root, 'minecraft/antagon-ui-request.txt'), 'admin');
  gameUi.peopleAt = 0;
}
async function gameUiSync() {
  if (gameUi.busy || !runtime.child) return;
  gameUi.busy = true;
  const game = path.join(root, 'minecraft');
  try {
    const commands = (await fs.promises.readdir(game)).filter((f) => /^antagon-ui-cmd-\d+\.txt$/.test(f)).sort();
    for (const file of commands) {
      const line = (await fs.promises.readFile(path.join(game, file), 'utf8')).trim();
      await fs.promises.rm(path.join(game, file), { force: true });
      if (!community?.me) continue;
      try {
        await gameUiCommand(line.slice(0, 1200));
      } catch (error) {
        gameUi.notice = error.message;
      }
      gameUi.dirty = true;
    }
    if (community?.me && (gameUi.dirty || Date.now() - gameUi.peopleAt > 15e3)) {
      gameUi.people = await community.state();
      gameUi.peopleAt = Date.now();
    }
    gameUi.dirty = false;
    const people = gameUi.people;
    const lines = [`updated=${Date.now()}`, `online=${community?.me ? 1 : 0}`];
    if (community?.me && people) {
      lines.push(`me=${clean(community.me.name)}`, `notice=${clean(gameUi.notice)}`);
      for (const f of people.friends)
        lines.push(`friend=${[f.id, f.name, f.online ? 1 : 0, f.activity, f.server || '', 0].map(clean).join('\t')}`);
      for (const p of people.incoming) lines.push(`incoming=${clean(p.id)}\t${clean(p.name)}`);
      for (const p of people.outgoing) lines.push(`outgoing=${clean(p.id)}\t${clean(p.name)}`);
      if (gameUi.chat) {
        lines.push(`chat=${gameUi.chat}`);
        for (const m of gameUi.messages.slice(-40)) {
          const time = new Date(m.created_at).toTimeString().slice(0, 5);
          lines.push(`msg=${m.sender === community.me.id ? 1 : 0}\t${time}\t${clean(m.body)}`);
        }
      }
      if (gameUi.store) {
        lines.push(`balance=${gameUi.store.balance}`);
        for (const item of gameUi.store.catalog)
          lines.push(
            `item=${[item.id, item.name, item.price, gameUi.store.owned.includes(item.id) ? 1 : 0, gameUi.store.equipped?.cape === item.id ? 1 : 0].map(clean).join('\t')}`,
          );
      }
    }
    atomic(path.join(game, 'antagon-ui-state.txt'), lines.join('\n') + '\n');
  } catch {
  } finally {
    gameUi.busy = false;
  }
}
async function gameActivity() {
  if (activityReading) return;
  activityReading = true;
  try {
    let activity = runtime.child ? 'menu' : 'launcher',
      server = null;
    if (runtime.child) {
      const status = await fs.promises
        .readFile(path.join(root, 'minecraft/antagon-status.txt'), 'utf8')
        .catch(() => 'menu');
      const parts = status.trim().split(' ');
      if (['menu', 'singleplayer', 'server', 'playing'].includes(parts[0])) {
        activity = parts[0];
        server = parts[1] || null;
      }
    }
    discord.setActivity({ activity, server, shareServer: settings.shareServer, startedAt });
    if (community?.me)
      await community.setActivity(activity === 'server' && !settings.shareServer ? 'playing' : activity, server);
  } catch {
  } finally {
    activityReading = false;
  }
}
const CAPES = ['antagon_cape', 'antagon_logo_cape'];
async function gameCosmetics() {
  if (cosmeticsReading || !runtime.child || !community?.me) return;
  cosmeticsReading = true;
  try {
    const game = path.join(root, 'minecraft');
    const roster = await fs.promises.readFile(path.join(game, 'antagon-players.txt'), 'utf8').catch(() => '');
    const rows = await community.visibleCosmetics(roster.split(/\s+/));
    const lines = rows
      .filter((row) => row.active_client && /^[0-9a-f]{32}$/.test(row.mc_uuid))
      .map((row) => `${row.mc_uuid}=${CAPES.includes(row.cape) ? row.cape : 'badge'}${row.is_admin ? '_admin' : ''}`);
    atomic(path.join(game, 'antagon-cosmetics.properties'), lines.join('\n') + '\n');
  } catch {
    // Retain the previous snapshot during a temporary network failure.
  } finally {
    cosmeticsReading = false;
  }
}
async function gameAction() {
  if (gameActionReading || !runtime.child) return;
  gameActionReading = true;
  const file = path.join(root, 'minecraft/antagon-ui-request.txt');
  try {
    const view = (await fs.promises.readFile(file, 'utf8')).trim();
    await fs.promises.unlink(file);
    if (!['settings', 'friends', 'store', 'admin'].includes(view)) return;
    if (view === 'admin' && !(await community?.access())?.isAdmin) return;
    if (!win || win.isDestroyed()) return;
    if (win.isMinimized()) win.restore();
    win.show();
    if (process.platform === 'darwin') app.focus({ steal: true });
    win.focus();
    win.webContents.send('game:open-view', view);
  } catch (error) {
    if (error.code !== 'ENOENT') console.error('[ANTAGON] Menu shortcut:', error.message);
  } finally {
    gameActionReading = false;
  }
}
let refreshingAccount;
function microsoftSession() {
  if (!refreshingAccount)
    refreshingAccount = refreshMicrosoftSession().finally(() => {
      refreshingAccount = null;
    });
  return refreshingAccount;
}
async function refreshMicrosoftSession() {
  if (!account) throw Error('Entre na sua conta Microsoft primeiro.');
  try {
    const xbox = await new Auth('select_account').refresh(account.refresh);
    const mc = await xbox.getMinecraft();
    if (mc.isDemo()) throw Error();
    persistAccount(mc, xbox);
    return sessionAccount;
  } catch {
    throw Error('Sua sessão expirou. Entre novamente com Microsoft.');
  }
}
async function startGame(server) {
  if (busy || runtime.child) throw Error('O jogo já está aberto ou sendo preparado.');
  busy = true;
  try {
    let active;
    if (settings.mode === 'offline') active = offlineAccount(settings.nickname);
    else {
      emit({ phase: 'preparing', message: 'Autenticando sua conta', percent: 0 });
      active = await microsoftSession();
    }
    await communityReady;
    let access = { isAdmin: false };
    if (settings.mode === 'microsoft') {
      if (community?.me?.mcUuid !== active.id) await connectCommunity();
      if (!community?.me) throw Error('Não foi possível verificar sua conta no Antagon Client.');
      access = await community.requireActive();
    }
    const result = await runtime.launch(settings, active, { server, admin: access.isAdmin });
    gameCosmetics();
    runtime.child?.once('exit', () => setTimeout(gameActivity, 500));
    return result;
  } catch (e) {
    emit({ phase: 'error', message: e.message });
    throw e;
  } finally {
    busy = false;
  }
}
function accountInfo() {
  return account ? { name: account.name, id: account.id } : null;
}
function loadAccount() {
  try {
    if (!safeStorage.isEncryptionAvailable()) return;
    const raw = JSON.parse(safeStorage.decryptString(fs.readFileSync(path.join(root, 'account.enc'))));
    account = raw;
  } catch {
    account = null;
  }
}
function persistAccount(mc, xbox) {
  account = { name: mc.profile.name, id: mc.profile.id, refresh: xbox.save() };
  sessionAccount = { name: account.name, id: account.id, accessToken: mc.mcToken, type: 'msa' };
  if (safeStorage.isEncryptionAvailable())
    atomic(path.join(root, 'account.enc'), safeStorage.encryptString(JSON.stringify(account)));
  return accountInfo();
}
async function login() {
  const auth = new Auth('select_account');
  const nonce = crypto.randomBytes(24).toString('hex');
  const popup = new BrowserWindow({
    width: 520,
    height: 740,
    parent: win,
    modal: true,
    title: 'Entrar com Microsoft — Antagon Client',
    autoHideMenuBar: true,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
      partition: 'antagon-auth-' + nonce,
    },
  });
  popup.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  const code = await new Promise((resolve, reject) => {
    let finished = false;
    const timer = setTimeout(() => {
      if (!finished) {
        finished = true;
        popup.close();
        reject(Error('O login expirou. Tente novamente.'));
      }
    }, 3e5);
    const navigate = (event, url) => {
      const u = new URL(url);
      if (u.origin === 'https://login.live.com' && u.pathname === '/oauth20_desktop.srf') {
        event.preventDefault();
        if (finished) return;
        finished = true;
        clearTimeout(timer);
        if (u.searchParams.get('state') !== nonce || !u.searchParams.get('code'))
          reject(Error('O login não foi concluído.'));
        else resolve(u.searchParams.get('code'));
        popup.close();
      }
    };
    popup.webContents.on('will-redirect', navigate);
    popup.webContents.on('will-navigate', navigate);
    popup.on('closed', () => {
      clearTimeout(timer);
      if (!finished) {
        finished = true;
        reject(Error('Login cancelado.'));
      }
    });
    popup.loadURL(auth.createLink() + '&state=' + nonce).catch(() => {});
  });
  const xbox = await auth.login(code);
  const mc = await xbox.getMinecraft();
  if (mc.isDemo() || !mc.profile?.id) throw Error('Esta conta não tem um perfil ativo de Minecraft Java.');
  return persistAccount(mc, xbox);
}
function handle(channel, fn) {
  ipcMain.handle(channel, async (event, ...args) => {
    if (!win || event.sender.id !== win.webContents.id || event.senderFrame.url !== pathToFileURL(ui).href)
      throw Error('Janela não autorizada.');
    try {
      return { ok: true, value: await fn(...args) };
    } catch (error) {
      return {
        ok: false,
        error: typeof error.message === 'string' ? error.message : 'Não foi possível concluir a operação.',
      };
    }
  });
}
app.whenReady().then(() => {
  settings = loadSettings();
  loadAccount();
  win = new BrowserWindow({
    width: 1280,
    height: 830,
    minWidth: 1040,
    minHeight: 720,
    backgroundColor: '#151515',
    title: 'Antagon Client',
    ...(process.platform === 'darwin'
      ? { titleBarStyle: 'hiddenInset', trafficLightPosition: { x: 18, y: 17 } }
      : { titleBarStyle: 'hidden', titleBarOverlay: { color: '#191919', symbolColor: '#f0eee8', height: 56 } }),
    icon: path.join(assets, 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
    },
  });
  win.setMenu(null);
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (e, url) => {
    if (url !== pathToFileURL(ui).href) e.preventDefault();
  });
  handle('app:init', () => ({
    settings,
    account: accountInfo(),
    state,
    version: app.getVersion(),
    installed: fs.existsSync(path.join(root, 'installation.json')),
    optifine: optifine(),
    wallpaper: wallpaper(),
  }));
  handle('settings:save', (value) => saveSettings(value));
  handle('account:login', async () => {
    try {
      return await login();
    } catch (e) {
      if (e.message?.includes('cancelado') || e.message?.includes('expirou') || e.message?.includes('perfil ativo'))
        throw e;
      throw Error(
        'A Microsoft não concluiu a autenticação. Tente novamente e confira se a conta possui Minecraft Java.',
      );
    }
  });
  handle('account:logout', async () => {
    await communityReady;
    if (community.me) await community.signOut();
    account = null;
    sessionAccount = null;
    const file = path.join(root, 'account.enc');
    if (fs.existsSync(file)) fs.unlinkSync(file);
    return true;
  });
  handle('game:launch', () => startGame());
  community = new Community(root, safeStorage, communityEvent);
  connectCommunity = async () => {
    if (settings.mode !== 'microsoft' || !account) return community.me;
    if (community.me?.mcUuid === account.id) return community.me;
    if (community.me) await community.signOut();
    return community.signIn((await microsoftSession()).accessToken);
  };
  communityReady = community
    .restore()
    .catch(() => null)
    .then((me) => me || connectCommunity().catch(() => null));
  setInterval(gameActivity, 5e3).unref();
  setInterval(gameCosmetics, 15e3).unref();
  setInterval(gameAction, 250).unref();
  setInterval(gameUiSync, 500).unref();
  discord.configure(settings);
  gameActivity();
  handle('discord:state', () => discord.state());
  const needCommunity = () => {
    if (!community.me) throw Error('Entre na comunidade primeiro.');
  };
  handle('community:state', async () => (await communityReady, community.state()));
  handle('community:login', async () => {
    if (settings.mode !== 'microsoft' || !account)
      throw Error('A comunidade usa sua conta Microsoft. Entre com ela no seu perfil.');
    return connectCommunity();
  });
  handle('community:logout', () => community.signOut());
  handle('community:add', (name) => (needCommunity(), community.add(String(name || '').trim())));
  handle('community:accept', (id) => (needCommunity(), community.accept(String(id))));
  handle('community:remove', (id) => (needCommunity(), community.remove(String(id))));
  handle('community:messages', (id) => (needCommunity(), community.messages(String(id))));
  handle('community:send', (id, body) => (needCommunity(), community.send(String(id), body)));
  handle('community:join', (server) => startGame(String(server || '')));
  handle('store:state', () => (needCommunity(), community.store()));
  handle('store:purchase', (item) => (needCommunity(), community.purchase(String(item || ''))));
  handle('store:equip', (item) => (needCommunity(), community.equip(item === null ? null : String(item || ''))));
  handle('store:checkout', async (pack) => {
    needCommunity();
    const url = await community.checkout(String(pack || ''));
    await shell.openExternal(url);
    return true;
  });
  handle('admin:access', () => (needCommunity(), community.access()));
  handle('admin:find', (name) => (needCommunity(), community.adminFind(String(name || '').trim())));
  handle(
    'admin:change',
    (action, id, value) => (needCommunity(), community.adminChange(String(action || ''), String(id || ''), value)),
  );
  handle('optifine:install', () => {
    if (busy || runtime.child) throw Error('Feche o jogo antes de alterar o OptiFine.');
    return installOptifine();
  });
  handle('optifine:remove', () => {
    if (busy || runtime.child) throw Error('Feche o jogo antes de alterar o OptiFine.');
    const f = optifine();
    if (f) fs.unlinkSync(path.join(modsDir, f));
    return null;
  });
  handle('optifine:site', () => shell.openExternal('https://optifine.net/downloads'));
  handle('wallpaper:pick', async () => {
    const pick = await dialog.showOpenDialog(win, {
      title: 'Escolha o plano de fundo',
      defaultPath: app.getPath('pictures'),
      properties: ['openFile'],
      filters: [{ name: 'Imagens', extensions: ['png', 'jpg', 'jpeg', 'webp'] }],
    });
    if (pick.canceled || !pick.filePaths[0]) return void 0;
    const ext = path.extname(pick.filePaths[0]).slice(1).toLowerCase();
    if (!['png', 'jpg', 'jpeg', 'webp'].includes(ext)) throw Error('Use uma imagem PNG, JPG ou WEBP.');
    if (fs.statSync(pick.filePaths[0]).size > 25e6) throw Error('Imagem muito grande (máximo 25 MB).');
    clearWallpaper();
    fs.copyFileSync(pick.filePaths[0], path.join(root, 'wallpaper.' + ext));
    return wallpaper();
  });
  handle('wallpaper:reset', () => {
    clearWallpaper();
    return null;
  });
  let update = null;
  handle('update:check', async () => {
    if (!app.isPackaged) return { status: 'dev', current: app.getVersion() };
    try {
      update = await updater.check(app.getVersion());
    } catch {
      return { status: 'offline', current: app.getVersion() };
    }
    return update
      ? { status: 'available', version: update.version, current: app.getVersion() }
      : { status: 'latest', current: app.getVersion() };
  });
  handle('update:install', async () => {
    if (!update) throw Error('Nenhuma atualização disponível.');
    if (busy || runtime.child) throw Error('Feche o jogo antes de atualizar.');
    busy = true;
    try {
      await updater.install(update, (percent) =>
        emit({ phase: 'updating', message: `Baixando a versão ${update.version}`, percent }),
      );
      emit({ phase: 'updating', message: 'Reiniciando', percent: 100 });
      app.quit();
    } catch (error) {
      emit({ phase: 'error', message: error.message });
      throw error;
    } finally {
      busy = false;
    }
  });
  handle('app:folder', () => shell.openPath(path.join(root, 'minecraft')));
  handle('app:logs', async () => {
    const file = path.join(root, 'logs/game.log');
    if (!fs.existsSync(file)) return 'Nenhuma partida iniciada ainda.';
    return readTail(file);
  });
  handle('window:minimize', () => win.minimize());
  win.loadFile(ui);
});
let leaving = false;
app.on('before-quit', (event) => {
  discord.stop();
  community?.stop();
  if (leaving || !community?.me) return;
  event.preventDefault();
  leaving = true;
  Promise.race([community.setActivity('offline'), new Promise((r) => setTimeout(r, 2e3))]).finally(() => app.quit());
});
app.on('window-all-closed', () => app.quit());
