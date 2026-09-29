const { app, BrowserWindow, ipcMain, safeStorage, shell, dialog } = require('electron');
const { unzipSync } = require('fflate');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { pathToFileURL } = require('node:url');
const { Auth } = require('msmc');
const { Runtime } = require('./runtime.cjs');
const updater = require('./updater.cjs');
const { Community } = require('./community.cjs');
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
function atomic(file, text) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file + '.tmp', text, { mode: 384 });
  fs.renameSync(file + '.tmp', file);
}
function loadSettings() {
  try {
    return validateSettings(JSON.parse(fs.readFileSync(path.join(root, 'settings.json'), 'utf8')));
  } catch {
    return structuredClone(DEFAULTS);
  }
}
function saveSettings(next) {
  settings = validateSettings(next);
  atomic(path.join(root, 'settings.json'), JSON.stringify(settings, null, 2));
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
let community;
function communityEvent(event) {
  if (win && !win.isDestroyed()) win.webContents.send('community:event', event);
}
function gameActivity() {
  if (!community?.me) return;
  if (!runtime.child) return community.setActivity('launcher');
  let status = 'menu';
  try {
    status = fs.readFileSync(path.join(root, 'minecraft/antagon-status.txt'), 'utf8').trim();
  } catch {}
  const [activity, server] = status.split(' ');
  if (activity === 'server') community.setActivity(settings.shareServer ? 'server' : 'playing', server);
  else community.setActivity(['menu', 'singleplayer', 'playing'].includes(activity) ? activity : 'playing');
}
async function microsoftSession() {
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
    const result = await runtime.launch(settings, active, { server });
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
  handle('account:logout', () => {
    account = null;
    sessionAccount = null;
    const file = path.join(root, 'account.enc');
    if (fs.existsSync(file)) fs.unlinkSync(file);
    return true;
  });
  handle('game:launch', () => startGame());
  community = new Community(root, safeStorage, communityEvent);
  const communityReady = community.restore().catch(() => null);
  setInterval(gameActivity, 5e3);
  const needCommunity = () => {
    if (!community.me) throw Error('Entre na comunidade primeiro.');
  };
  handle('community:state', async () => (await communityReady, community.state()));
  handle('community:login', async () => {
    if (settings.mode !== 'microsoft' || !account)
      throw Error('A comunidade usa sua conta Microsoft. Entre com ela no seu perfil.');
    const session = await microsoftSession();
    return community.signIn(session.accessToken);
  });
  handle('community:logout', () => community.signOut());
  handle('community:add', (name) => (needCommunity(), community.add(String(name || '').trim())));
  handle('community:accept', (id) => (needCommunity(), community.accept(String(id))));
  handle('community:remove', (id) => (needCommunity(), community.remove(String(id))));
  handle('community:messages', (id) => (needCommunity(), community.messages(String(id))));
  handle('community:send', (id, body) => (needCommunity(), community.send(String(id), body)));
  handle('community:join', (server) => startGame(String(server || '')));
  handle('optifine:install', () => installOptifine());
  handle('optifine:remove', () => {
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
    return fs.readFileSync(file, 'utf8').slice(-16e3);
  });
  handle('window:minimize', () => win.minimize());
  win.loadFile(ui);
});
let leaving = false;
app.on('before-quit', (event) => {
  if (leaving || !community?.me) return;
  event.preventDefault();
  leaving = true;
  Promise.race([community.setActivity('offline'), new Promise((r) => setTimeout(r, 2e3))]).finally(() => app.quit());
});
app.on('window-all-closed', () => app.quit());
