const $ = (s) => document.querySelector(s),
  $$ = (s) => [...document.querySelectorAll(s)];
const api = window.antagon;
document.body.classList.add(api.platform);
let settings, account, toastTimer;
function toast(message) {
  $('#toast').textContent = message;
  $('#toast').classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => $('#toast').classList.remove('show'), 4e3);
}
async function persist() {
  try {
    settings = await api.saveSettings(settings);
  } catch (e) {
    toast(e.message);
  }
}
$$('[data-view]').forEach(
  (tab) =>
    (tab.onclick = () => {
      $$('.tab').forEach((t) => t.classList.toggle('active', t === tab));
      $$('.view').forEach((v) => v.classList.toggle('active', v.id === 'view-' + tab.dataset.view));
    }),
);
function renderProfile() {
  const microsoft = settings.mode === 'microsoft' && account,
    name = microsoft ? account.name : settings.nickname;
  $('#profile-name').textContent = name;
  $('#profile-mode').textContent = microsoft ? 'Microsoft' : 'Offline';
  $('#avatar').textContent = name[0].toUpperCase();
  $('#nickname').value = settings.nickname;
  $('#connected-account').textContent = account ? account.name : '';
  $('#login-microsoft').textContent = account ? 'Usar esta conta' : 'Entrar com Microsoft';
  $('#logout-microsoft').hidden = !account;
}
function accountTab(mode) {
  $$('[data-account-mode]').forEach((b) => b.classList.toggle('active', b.dataset.accountMode === mode));
  $('#offline-form').hidden = mode !== 'offline';
  $('#microsoft-form').hidden = mode !== 'microsoft';
  $('#account-error').textContent = '';
}
$$('[data-account-mode]').forEach((b) => (b.onclick = () => accountTab(b.dataset.accountMode)));
$('#profile-open').onclick = () => {
  renderProfile();
  accountTab(settings.mode);
  $('#account-dialog').showModal();
};
$$('[data-close]').forEach((b) => (b.onclick = () => $('#' + b.dataset.close).close()));
$('#save-nick').onclick = async () => {
  const nick = $('#nickname').value.trim();
  if (!/^[A-Za-z0-9_]{3,16}$/.test(nick)) {
    $('#account-error').textContent = 'Use 3 a 16 letras, números ou _.';
    return;
  }
  settings.nickname = nick;
  settings.mode = 'offline';
  await persist();
  renderProfile();
  $('#account-dialog').close();
};
$('#nickname').onkeydown = (e) => {
  if (e.key === 'Enter') $('#save-nick').click();
};
$('#login-microsoft').onclick = async () => {
  const button = $('#login-microsoft');
  button.disabled = true;
  $('#account-error').textContent = '';
  try {
    if (!account) account = await api.login();
    settings.mode = 'microsoft';
    await persist();
    renderProfile();
    $('#account-dialog').close();
  } catch (e) {
    $('#account-error').textContent = e.message;
  } finally {
    button.disabled = false;
  }
};
$('#logout-microsoft').onclick = async () => {
  await api.logout();
  account = null;
  settings.mode = 'offline';
  await persist();
  renderProfile();
};
function updateState(state) {
  document.body.dataset.phase = state.phase;
  const working = ['preparing', 'launching', 'running', 'updating'].includes(state.phase);
  $('#launch-status').textContent = state.message;
  $('#launch').disabled = working;
  $('#launch').textContent = state.phase === 'running' ? 'NO JOGO' : working ? 'ABRINDO…' : 'JOGAR';
  $('#progress-track').style.display = ['preparing', 'updating'].includes(state.phase) ? 'block' : 'none';
  $('#update').disabled = working;
  $('#progress-bar').style.width = Math.max(0, Math.min(100, state.percent || 0)) + '%';
}
$('#launch').onclick = async () => {
  if (settings.mode === 'microsoft' && !account) {
    $('#profile-open').click();
    accountTab('microsoft');
    return;
  }
  try {
    await api.launch();
  } catch (e) {
    toast(e.message);
  }
};
$('#memory').oninput = (e) => ($('#memory-value').textContent = e.target.value + ' GB');
$('#memory').onchange = (e) => {
  settings.memory = Number(e.target.value);
  persist();
};
$('#fullscreen').onchange = (e) => {
  settings.fullscreen = e.target.checked;
  persist();
};
$('#pack-toggle').onchange = (e) => {
  settings.pack = e.target.checked;
  persist();
};
function renderOptifine(file) {
  $('#optifine-state').textContent = file || 'Não instalado';
  $('#optifine-install').textContent = file ? 'Trocar' : 'Instalar';
  $('#optifine-remove').hidden = !file;
}
$('#optifine-site').onclick = () => {
  api.optifineSite();
  toast('Baixe a versão 1.8.9 (HD U M5) e depois clique em Instalar.');
};
$('#optifine-install').onclick = async () => {
  try {
    renderOptifine(await api.installOptifine());
  } catch (e) {
    toast(e.message);
  }
};
$('#optifine-remove').onclick = async () => renderOptifine(await api.removeOptifine());
function renderWallpaper(custom) {
  $('#wallpaper-state').textContent = custom ? 'Imagem personalizada' : 'Padrão';
  $('#wallpaper-reset').hidden = !custom;
  window.setWallpaper(custom);
}
$('#wallpaper-pick').onclick = async () => {
  try {
    const custom = await api.pickWallpaper();
    if (custom !== void 0) renderWallpaper(custom);
  } catch (e) {
    toast(e.message);
  }
};
$('#wallpaper-reset').onclick = async () => renderWallpaper(await api.resetWallpaper());
$('#update').onclick = async () => {
  try {
    await api.installUpdate();
  } catch (e) {
    toast(e.message);
  }
};
$('#open-folder').onclick = async () => {
  if (await api.openFolder()) toast('A pasta será criada ao abrir o jogo pela primeira vez.');
};
$('#open-logs').onclick = async () => {
  $('#logs-text').textContent = await api.logs();
  $('#logs-dialog').showModal();
};
(async () => {
  try {
    const data = await api.init();
    settings = data.settings;
    account = data.account;
    renderProfile();
    $('#version').textContent = data.version;
    api.checkUpdate().then((update) => {
      if (!update) return;
      $('#update').textContent = 'Atualizar para ' + update.version;
      $('#update').hidden = false;
    });
    $('#memory').value = settings.memory;
    $('#memory-value').textContent = settings.memory + ' GB';
    $('#fullscreen').checked = settings.fullscreen;
    $('#pack-toggle').checked = settings.pack;
    $('#install-state').textContent = data.installed ? 'Forge · instalado' : 'Forge · será instalado ao jogar';
    renderOptifine(data.optifine);
    renderWallpaper(data.wallpaper);
    updateState(data.state);
    api.onState(updateState);
  } catch (e) {
    toast('Não foi possível carregar o launcher: ' + e.message);
  }
})();
