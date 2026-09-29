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
  gameRunning = ['launching', 'running'].includes(state.phase);
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
let updateStatus = null;
const UPDATE_LABEL = {
  dev: () => 'Versão de desenvolvimento',
  latest: (u) => '✓ Atualizado · v' + u.current,
  offline: () => 'Não foi possível verificar atualizações',
  available: (u) => 'Atualizar para ' + u.version,
};
async function checkUpdate() {
  $('#update').className = 'update-status';
  $('#update').textContent = 'Verificando atualizações…';
  updateStatus = await api.checkUpdate().catch(() => ({ status: 'offline' }));
  $('#update').className = updateStatus.status === 'available' ? 'update' : 'update-status';
  $('#update').textContent = UPDATE_LABEL[updateStatus.status](updateStatus);
}
$('#update').onclick = async () => {
  if (updateStatus?.status !== 'available') return checkUpdate();
  try {
    await api.installUpdate();
  } catch (e) {
    toast(e.message);
  }
};
function renderBackground(mode) {
  document.body.dataset.background = mode;
  $('#background-switch').textContent = 'Fundo: ' + (mode === 'ascii' ? 'Logo' : 'Paisagem');
}
$('#background-switch').onclick = () => {
  settings.background = settings.background === 'ascii' ? 'scene' : 'ascii';
  renderBackground(settings.background);
  persist();
};
$('#open-folder').onclick = async () => {
  if (await api.openFolder()) toast('A pasta será criada ao abrir o jogo pela primeira vez.');
};
$('#open-logs').onclick = async () => {
  $('#logs-text').textContent = await api.logs();
  $('#logs-dialog').showModal();
};
const community = api.community;
const ACTIVITY = {
  launcher: () => 'No launcher',
  menu: () => 'No menu do jogo',
  singleplayer: () => 'Jogando singleplayer',
  server: (p) => 'Jogando em ' + p.server,
  playing: () => 'Jogando',
  offline: () => 'Offline',
};
let people = { me: null, friends: [], incoming: [], outgoing: [] },
  chatWith = null,
  unread = new Set(),
  loaded = false,
  gameRunning = false,
  refreshTimer;
const escape = (text) =>
  String(text ?? '').replace(
    /[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c],
  );
const head = (p) =>
  /^[0-9a-f]{32}$/.test(p.mcUuid || '')
    ? `<img class="head" src="https://mc-heads.net/avatar/${p.mcUuid}/32" alt="" />`
    : '<span class="head"></span>';

function renderCommunity() {
  const me = people.me;
  $('#community-gate').hidden = !!me;
  $('#community').hidden = !me;
  $('#community-account').textContent = me ? 'Conectado como ' + me.name : 'Não conectado';
  $('#community-logout').hidden = !me;
  const pending = people.incoming.length + unread.size;
  $('#friends-badge').textContent = pending;
  $('#friends-badge').hidden = !pending;
  if (!me) return;
  const row = (p, detail, actions = '', extra = '') =>
    `<div class="person ${p.online ? 'online' : ''} ${p.id === chatWith ? 'active' : ''}" data-person="${p.id}">${head(p)}<span class="who"><b>${escape(p.name)}</b><small>${escape(detail)}</small></span>${extra}<span class="actions">${actions}</span></div>`;
  $('#requests').innerHTML =
    (people.incoming.length
      ? '<h3>Pedidos</h3>' +
        people.incoming
          .map((p) =>
            row(
              p,
              'Quer ser seu amigo',
              `<button class="primary" data-accept="${p.id}">Aceitar</button><button class="secondary" data-remove="${p.id}">Recusar</button>`,
            ),
          )
          .join('')
      : '') +
    (people.outgoing.length
      ? '<h3>Enviados</h3>' +
        people.outgoing
          .map((p) => row(p, 'Pedido enviado', `<button class="secondary" data-remove="${p.id}">Cancelar</button>`))
          .join('')
      : '');
  const friends = [...people.friends].sort((a, b) => b.online - a.online || a.name.localeCompare(b.name));
  $('#friend-list').innerHTML =
    `<h3>Amigos · ${friends.filter((f) => f.online).length} online</h3>` +
    (friends.length
      ? friends
          .map((p) =>
            row(
              p,
              ACTIVITY[p.activity](p),
              p.activity === 'server' && !gameRunning
                ? `<button class="primary" data-join="${escape(p.server)}">Entrar</button>`
                : '',
              unread.has(p.id) ? '<span class="unread"></span>' : '',
            ),
          )
          .join('')
      : '<p class="chat-empty">Adicione um amigo pelo nick do Minecraft.</p>');
  const friend = people.friends.find((f) => f.id === chatWith);
  $('#chat-empty').hidden = !!friend;
  $('#chat-head').hidden = $('#chat-form').hidden = !friend;
  if (friend) $('#chat-head').innerHTML = `${head(friend)}<span class="who"><b>${escape(friend.name)}</b></span>`;
  else $('#messages').innerHTML = '';
}

async function refreshCommunity() {
  try {
    const next = await community.state();
    if (loaded)
      for (const f of next.friends)
        if (f.online && !people.friends.find((p) => p.id === f.id)?.online) toast(f.name + ' está online');
    for (const p of next.incoming)
      if (loaded && !people.incoming.find((i) => i.id === p.id)) toast(p.name + ' quer ser seu amigo');
    people = next;
    loaded = true;
    renderCommunity();
  } catch (e) {
    toast(e.message);
  }
}
const refreshSoon = () => {
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(refreshCommunity, 300);
};

function appendMessage(message) {
  const bubble = document.createElement('div');
  bubble.className = 'message' + (message.sender === people.me?.id ? ' mine' : '');
  bubble.textContent = message.body;
  $('#messages').append(bubble);
  $('#messages').scrollTop = $('#messages').scrollHeight;
}

async function openChat(id) {
  chatWith = id;
  unread.delete(id);
  renderCommunity();
  $('#messages').innerHTML = '';
  try {
    (await community.messages(id)).forEach(appendMessage);
  } catch (e) {
    toast(e.message);
  }
  $('#chat-input').focus();
}

$('#community-login').onclick = async () => {
  if (settings.mode !== 'microsoft' || !account) {
    toast('A comunidade usa sua conta Microsoft. Entre com ela no seu perfil.');
    $('#profile-open').click();
    accountTab('microsoft');
    return;
  }
  const button = $('#community-login');
  button.disabled = true;
  try {
    await community.login();
    await refreshCommunity();
  } catch (e) {
    toast(e.message);
  } finally {
    button.disabled = false;
  }
};
$('#community-logout').onclick = async () => {
  await community.logout();
  people = { me: null, friends: [], incoming: [], outgoing: [] };
  chatWith = null;
  renderCommunity();
};
$('#add-friend').onsubmit = async (e) => {
  e.preventDefault();
  try {
    toast(await community.add($('#friend-name').value));
    $('#friend-name').value = '';
    refreshCommunity();
  } catch (error) {
    toast(error.message);
  }
};
$('#view-friends').addEventListener('click', async (e) => {
  const target = e.target.closest('[data-accept],[data-remove],[data-join],[data-person]');
  if (!target) return;
  try {
    if (target.dataset.accept) await community.accept(target.dataset.accept);
    else if (target.dataset.remove) await community.remove(target.dataset.remove);
    else if (target.dataset.join) {
      await community.join(target.dataset.join);
      return;
    } else if (people.friends.some((f) => f.id === target.dataset.person)) return openChat(target.dataset.person);
    else return;
    refreshCommunity();
  } catch (error) {
    toast(error.message);
  }
});
$('#chat-form').onsubmit = async (e) => {
  e.preventDefault();
  const text = $('#chat-input').value.trim();
  if (!text || !chatWith) return;
  $('#chat-input').value = '';
  try {
    await community.send(chatWith, text);
  } catch (error) {
    toast(error.message);
    $('#chat-input').value = text;
  }
};
community.onEvent((event) => {
  if (event.type !== 'message') return refreshSoon();
  const m = event.payload;
  const other = m.sender === people.me?.id ? m.recipient : m.sender;
  if (other === chatWith && $('#view-friends').classList.contains('active')) appendMessage(m);
  else if (m.sender !== people.me?.id) {
    unread.add(other);
    toast((people.friends.find((f) => f.id === other)?.name || 'Amigo') + ': ' + m.body.slice(0, 60));
    renderCommunity();
  }
});
setInterval(refreshCommunity, 3e4);
$('#share-server').onchange = (e) => {
  settings.shareServer = e.target.checked;
  persist();
};

(async () => {
  try {
    const data = await api.init();
    settings = data.settings;
    account = data.account;
    renderProfile();
    $('#version').textContent = data.version;
    checkUpdate();
    renderBackground(settings.background);
    $('#memory').value = settings.memory;
    $('#memory-value').textContent = settings.memory + ' GB';
    $('#fullscreen').checked = settings.fullscreen;
    $('#pack-toggle').checked = settings.pack;
    $('#install-state').textContent = data.installed ? 'Forge · instalado' : 'Forge · será instalado ao jogar';
    renderOptifine(data.optifine);
    renderWallpaper(data.wallpaper);
    $('#share-server').checked = settings.shareServer;
    refreshCommunity();
    updateState(data.state);
    api.onState(updateState);
  } catch (e) {
    toast('Não foi possível carregar o launcher: ' + e.message);
  }
})();
