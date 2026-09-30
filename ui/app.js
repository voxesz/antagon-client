import { setWallpaper } from './wallpaper.js';
import { createSettingsStore, createConversation } from './state.mjs';

const $ = (s) => document.querySelector(s),
  $$ = (s) => [...document.querySelectorAll(s)];
const api = window.antagon;
document.body.classList.add(api.platform);
let settings, settingsStore, account, toastTimer;
let adminAccess = { isAdmin: false, isOwner: false, isBanned: false },
  adminTarget = null;
function toast(message) {
  $('#toast').textContent = message;
  $('#toast').classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => $('#toast').classList.remove('show'), 4e3);
}
async function persist(patch) {
  const request = settingsStore.update(patch);
  settings = settingsStore.value;
  await request;
  settings = settingsStore.value;
}

function action(selector, handler, error = (e) => toast(e.message)) {
  const button = $(selector);
  button.onclick = async () => {
    if (button.disabled) return;
    button.disabled = true;
    try {
      await handler();
    } catch (e) {
      error(e);
    } finally {
      button.disabled = false;
    }
  };
}

function setting(selector, key, read) {
  $(selector).onchange = async (event) => {
    try {
      await persist({ [key]: read(event.target) });
    } catch (e) {
      toast(e.message);
    }
  };
}

$$('[data-view]').forEach(
  (tab) =>
    (tab.onclick = () => {
      $$('.tab').forEach((t) => t.classList.toggle('active', t === tab));
      $$('.view').forEach((v) => v.classList.toggle('active', v.id === 'view-' + tab.dataset.view));
      if (tab.dataset.view === 'friends' && conversation.selected) {
        unread.delete(conversation.selected);
        renderCommunity();
        renderMessages();
      }
      if (tab.dataset.view === 'store') refreshStore();
      if (tab.dataset.view === 'admin') refreshAdminAccess();
    }),
);
api.onOpenView((view) => {
  if (!['settings', 'friends', 'store', 'admin'].includes(view)) return;
  const tab = $(`[data-view="${view}"]`);
  if (tab && !tab.hidden) tab.click();
});
function renderProfile() {
  const microsoft = settings.mode === 'microsoft' && account,
    name = microsoft ? account.name : settings.nickname;
  $('#profile-name').textContent = name;
  $('#profile-mode').textContent = microsoft ? 'Microsoft' : 'Offline';
  $('#profile-admin').hidden = !adminAccess.isAdmin;
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
action(
  '#save-nick',
  async () => {
    const nick = $('#nickname').value.trim();
    if (!/^[A-Za-z0-9_]{3,16}$/.test(nick)) {
      $('#account-error').textContent = 'Use 3 a 16 letras, números ou _.';
      return;
    }
    await persist({ nickname: nick, mode: 'offline' });
    renderProfile();
    $('#account-dialog').close();
  },
  (e) => {
    $('#account-error').textContent = e.message;
  },
);
$('#nickname').onkeydown = (e) => {
  if (e.key === 'Enter') $('#save-nick').click();
};
$('#login-microsoft').onclick = async () => {
  const button = $('#login-microsoft');
  button.disabled = true;
  $('#account-error').textContent = '';
  try {
    if (!account) account = await api.login();
    await persist({ mode: 'microsoft' });
    renderProfile();
    $('#account-dialog').close();
    community
      .login()
      .catch(() => null)
      .then(refreshCommunity);
  } catch (e) {
    $('#account-error').textContent = e.message;
  } finally {
    button.disabled = false;
  }
};
action(
  '#logout-microsoft',
  async () => {
    await api.logout();
    account = null;
    await persist({ mode: 'offline' });
    renderProfile();
    resetCommunity();
  },
  (e) => {
    $('#account-error').textContent = e.message;
  },
);
function updateState(state) {
  gameRunning = ['launching', 'running'].includes(state.phase);
  document.body.dataset.phase = state.phase;
  const working = ['preparing', 'launching', 'running', 'updating'].includes(state.phase);
  $('#launch-status').textContent = state.message;
  $('#launch').disabled = working;
  $('#launch').textContent =
    state.phase === 'running'
      ? 'NO JOGO'
      : state.phase === 'updating'
        ? 'ATUALIZANDO…'
        : working
          ? 'ABRINDO…'
          : 'JOGAR';
  for (const button of $$('#optifine-install, #optifine-remove')) button.disabled = working;
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
    $('#launch').disabled = true;
    await settingsStore.flush();
    await api.launch();
  } catch (e) {
    toast(e.message);
    $('#launch').disabled = false;
  }
};
$('#memory').oninput = (e) => ($('#memory-value').textContent = e.target.value + ' GB');
setting('#memory', 'memory', (input) => Number(input.value));
setting('#fullscreen', 'fullscreen', (input) => input.checked);
setting('#pack-toggle', 'pack', (input) => input.checked);
setting('#share-server', 'shareServer', (input) => input.checked);
setting('#discord-presence', 'discordPresence', (input) => input.checked);
api.discord.onState((state) => {
  $('#discord-state').textContent = state.message;
});
function renderOptifine(file) {
  $('#optifine-state').textContent = file || 'Não instalado';
  $('#optifine-install').textContent = file ? 'Trocar' : 'Instalar';
  $('#optifine-remove').hidden = !file;
}
action('#optifine-site', async () => {
  await api.optifineSite();
  toast('Baixe a versão 1.8.9 (HD U M5) e depois clique em Instalar.');
});
$('#optifine-install').onclick = async () => {
  try {
    renderOptifine(await api.installOptifine());
  } catch (e) {
    toast(e.message);
  }
};
action('#optifine-remove', async () => renderOptifine(await api.removeOptifine()));
async function renderWallpaper(custom) {
  $('#wallpaper-state').textContent = custom ? 'Imagem personalizada' : 'Padrão';
  $('#wallpaper-reset').hidden = !custom;
  return setWallpaper(custom);
}
$('#wallpaper-pick').onclick = async () => {
  try {
    const custom = await api.pickWallpaper();
    if (custom !== void 0) await renderWallpaper(custom);
  } catch (e) {
    toast(e.message);
  }
};
action('#wallpaper-reset', async () => renderWallpaper(await api.resetWallpaper()));
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
action('#background-switch', async () => {
  const background = settings.background === 'ascii' ? 'scene' : 'ascii';
  renderBackground(background);
  await persist({ background });
});
action('#open-folder', async () => {
  if (await api.openFolder()) toast('A pasta será criada ao abrir o jogo pela primeira vez.');
});
action('#open-logs', async () => {
  $('#logs-text').textContent = await api.logs();
  $('#logs-dialog').showModal();
});
const community = api.community;
let storeState = null;
async function refreshStore() {
  const connected = !!people.me;
  $('#store-gate').hidden = connected;
  $('#store-content').hidden = !connected;
  if (!connected) return;
  try {
    storeState = await api.store.state();
    renderStore();
  } catch (error) {
    storeState = null;
    renderStore();
  }
}
const CAPES = {
  antagon_cape: {
    name: 'Capa Antagon',
    image: '../assets/antagon-cape.png',
    description: 'Capa preta com a logo Antagon em vermelho.',
  },
  antagon_logo_cape: {
    name: 'Capa Logo Antagon',
    image: '../assets/antagon-logo-cape.png',
    description: 'Capa vermelha com a logo Antagon em alta resolução.',
  },
};
function renderStore() {
  const available = !!storeState?.catalog?.length;
  const catalog = available
    ? storeState.catalog
    : Object.entries(CAPES).map(([id, cape]) => ({ id, name: cape.name, kind: 'cape', price: 100 }));
  $('#coin-balance').textContent = (storeState?.balance || 0).toLocaleString('pt-BR');
  $('#store-message').hidden = available;
  $('#store-message').textContent = available ? '' : 'Prévia do item. O catálogo ainda não está ativo neste servidor.';
  $$('.coin-packs button').forEach((button) => (button.disabled = !available));
  $('#store-items').innerHTML = catalog
    .map((item) => {
      const owned = storeState?.owned?.includes(item.id);
      const equipped = storeState?.equipped?.[item.kind] === item.id;
      const cape = CAPES[item.id] || CAPES.antagon_cape;
      return `<article class="store-item"><div class="cape-preview"><img src="${cape.image}" alt="${escape(cape.description)}" /></div><div><h3>${escape(item.name)}</h3><p>${escape(cape.description)}</p><span class="item-price"><img src="../assets/antagon-coin.png" alt="" />${item.price} moedas</span></div><button class="primary" data-store-item="${escape(item.id)}" data-store-action="${owned ? (equipped ? 'unequip' : 'equip') : 'purchase'}" ${available ? '' : 'disabled'}>${available ? (owned ? (equipped ? 'Desequipar' : 'Equipar') : 'Comprar') : 'Em breve'}</button></article>`;
    })
    .join('');
}

async function refreshAdminAccess() {
  if (!people.me) {
    adminAccess = { isAdmin: false, isOwner: false, isBanned: false };
  } else {
    try {
      adminAccess = await api.admin.access();
    } catch {
      adminAccess = { isAdmin: false, isOwner: false, isBanned: false };
    }
  }
  $('#admin-tab').hidden = !adminAccess.isAdmin;
  $('#profile-admin').hidden = !adminAccess.isAdmin;
  if (!adminAccess.isAdmin) {
    adminTarget = null;
    $('#admin-result').hidden = true;
    if ($('#view-admin').classList.contains('active')) $('[data-view="play"]').click();
  }
  return adminAccess;
}

function renderAdminTarget() {
  const user = adminTarget;
  $('#admin-result').hidden = !user;
  $('#admin-empty').hidden = !!user;
  if (!user) return;
  $('#admin-target-name').textContent = user.name;
  $('#admin-target-id').textContent = user.mc_uuid;
  $('#admin-head').src = `https://mc-heads.net/avatar/${user.mc_uuid}/48`;
  $('#admin-target-role').hidden = !user.is_admin;
  $('#admin-coins').value = user.coins;
  $('#admin-items').innerHTML = Object.entries(CAPES)
    .map(([id, cape]) => {
      const owned = user.items.includes(id);
      return `<div class="admin-item"><span>${escape(cape.name)}<small>${owned ? 'No inventário' : 'Não adquirida'}</small></span><button class="${owned ? 'secondary' : 'primary'}" data-admin-action="item" data-item="${id}">${owned ? 'Remover' : 'Dar capa'}</button></div>`;
    })
    .join('');
  $('#admin-role-card').hidden = !adminAccess.isOwner || user.user_id === people.me?.id || user.is_owner;
  $('#admin-role-button').textContent = user.is_admin ? 'Remover admin' : 'Tornar admin';
  $('#admin-ban-status').textContent = user.is_banned ? `Banido: ${user.ban_reason}` : 'Conta ativa';
  $('#admin-ban-button').textContent = user.is_banned ? 'Desbanir usuário' : 'Banir usuário';
  $('#admin-ban-button').disabled = user.is_admin || user.user_id === people.me?.id;
  $('#admin-ban-reason').hidden = user.is_banned;
  $('#admin-ban-reason').value = '';
}

$('#admin-search').onsubmit = async (event) => {
  event.preventDefault();
  try {
    adminTarget = await api.admin.find($('#admin-name').value.trim());
    renderAdminTarget();
  } catch (error) {
    adminTarget = null;
    renderAdminTarget();
    toast(error.message);
  }
};
$('#admin-result').onclick = async (event) => {
  const button = event.target.closest('[data-admin-action]');
  if (!button || button.disabled || !adminTarget) return;
  const action = button.dataset.adminAction;
  let value;
  if (action === 'coins') {
    value = Number($('#admin-coins').value);
    if (!Number.isInteger(value) || value < 0 || value > 10000000)
      return toast('Digite um saldo entre 0 e 10.000.000.');
  } else if (action === 'item') {
    value = { item: button.dataset.item, grant: !adminTarget.items.includes(button.dataset.item) };
  } else if (action === 'role') {
    value = !adminTarget.is_admin;
  } else if (action === 'ban') {
    value = { banned: !adminTarget.is_banned, reason: $('#admin-ban-reason').value.trim() };
    if (value.banned && !value.reason) return toast('Informe o motivo do banimento.');
    if (value.banned && !confirm(`Banir ${adminTarget.name} do Antagon Client?`)) return;
  }
  button.disabled = true;
  try {
    await api.admin.change(action, adminTarget.user_id, value);
    adminTarget = await api.admin.find(adminTarget.name);
    renderAdminTarget();
    toast('Alteração salva.');
  } catch (error) {
    toast(error.message);
  } finally {
    button.disabled = false;
  }
};
$('#store-login').onclick = async () => {
  if (settings?.mode !== 'microsoft' || !account) return $('#profile-open').click();
  try {
    await community.login();
    await refreshCommunity();
    await refreshStore();
  } catch (error) {
    toast(error.message);
  }
};
action('#store-refresh', refreshStore);
$('#store-items').onclick = async (event) => {
  const button = event.target.closest('[data-store-item]');
  if (!button || button.disabled) return;
  button.disabled = true;
  try {
    storeState =
      button.dataset.storeAction === 'purchase'
        ? await api.store.purchase(button.dataset.storeItem)
        : await api.store.equip(button.dataset.storeAction === 'unequip' ? null : button.dataset.storeItem);
    renderStore();
  } catch (error) {
    toast(error.message);
    button.disabled = false;
  }
};
$('.coin-packs').onclick = async (event) => {
  const button = event.target.closest('[data-pack]');
  if (!button || button.disabled) return;
  button.disabled = true;
  try {
    await api.store.checkout(button.dataset.pack);
  } catch (error) {
    toast(error.message);
  } finally {
    button.disabled = false;
  }
};
const ACTIVITY = {
  launcher: () => 'No launcher',
  menu: () => 'No menu do jogo',
  singleplayer: () => 'Jogando singleplayer',
  server: (p) => 'Jogando em ' + p.server,
  playing: () => 'Jogando',
  offline: () => 'Offline',
};
let people = { me: null, friends: [], incoming: [], outgoing: [] },
  unread = new Set(),
  loaded = false,
  gameRunning = false,
  refreshTimer,
  refreshRequest = null,
  communityRevision = 0;
const conversation = createConversation();
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
  const microsoft = settings?.mode === 'microsoft' && account;
  $('#community-gate p').textContent = microsoft
    ? 'Conectando com a sua conta ' + account.name + '…'
    : 'A comunidade usa a sua conta Microsoft. Entre com ela no seu perfil para adicionar amigos e conversar.';
  $('#community-login').textContent = microsoft ? 'Tentar de novo' : 'Entrar com Microsoft';
  $('#community').hidden = !me;
  $('#community-account').textContent = me ? 'Conectado como ' + me.name : 'Não conectado';
  $('#community-logout').hidden = !me;
  const pending = people.incoming.length + unread.size;
  $('#friends-badge').textContent = pending;
  $('#friends-badge').hidden = !pending;
  if (!me) {
    conversation.clear();
    $('#messages').replaceChildren();
    return;
  }
  const row = (p, detail, actions = '', extra = '') =>
    `<div class="person ${p.online ? 'online' : ''} ${p.id === conversation.selected ? 'active' : ''}" data-person="${escape(p.id)}">${head(p)}<span class="who"><b>${escape(p.name)}</b><small>${escape(detail)}</small></span>${extra}<span class="actions">${actions}</span></div>`;
  $('#requests').innerHTML =
    (people.incoming.length
      ? '<h3>Pedidos</h3>' +
        people.incoming
          .map((p) =>
            row(
              p,
              'Quer ser seu amigo',
              `<button class="primary" data-accept="${escape(p.id)}">Aceitar</button><button class="secondary" data-remove="${escape(p.id)}">Recusar</button>`,
            ),
          )
          .join('')
      : '') +
    (people.outgoing.length
      ? '<h3>Enviados</h3>' +
        people.outgoing
          .map((p) =>
            row(p, 'Pedido enviado', `<button class="secondary" data-remove="${escape(p.id)}">Cancelar</button>`),
          )
          .join('')
      : '');
  const friends = [...people.friends].sort((a, b) => b.online - a.online || (a.name || '').localeCompare(b.name || ''));
  $('#friend-list').innerHTML =
    `<h3>Amigos · ${friends.filter((f) => f.online).length} online</h3>` +
    (friends.length
      ? friends
          .map((p) =>
            row(
              p,
              (ACTIVITY[p.activity] || ACTIVITY.offline)(p),
              p.activity === 'server' && !gameRunning
                ? `<button class="primary" data-join="${escape(p.server)}">Entrar</button>`
                : '',
              unread.has(p.id) ? '<span class="unread"></span>' : '',
            ),
          )
          .join('')
      : '<p class="chat-empty">Adicione um amigo pelo nick do Minecraft.</p>');
  const friend = people.friends.find((f) => f.id === conversation.selected);
  $('#chat-empty').hidden = !!friend;
  $('#chat-head').hidden = $('#chat-form').hidden = !friend;
  if (friend) $('#chat-head').innerHTML = `${head(friend)}<span class="who"><b>${escape(friend.name)}</b></span>`;
  else {
    conversation.clear();
    $('#messages').replaceChildren();
  }
}

function resetCommunity() {
  communityRevision++;
  people = { me: null, friends: [], incoming: [], outgoing: [] };
  loaded = false;
  unread.clear();
  conversation.clear();
  renderCommunity();
  refreshAdminAccess();
  refreshStore();
}

function refreshCommunity() {
  if (refreshRequest) return refreshRequest;
  const revision = communityRevision;
  refreshRequest = (async () => {
    try {
      const next = await community.state();
      if (revision !== communityRevision) return;
      if (loaded) {
        const previousFriends = new Map(people.friends.map((person) => [person.id, person]));
        const previousRequests = new Set(people.incoming.map((person) => person.id));
        for (const friend of next.friends)
          if (friend.online && !previousFriends.get(friend.id)?.online) toast(friend.name + ' está online');
        for (const person of next.incoming)
          if (!previousRequests.has(person.id)) toast(person.name + ' quer ser seu amigo');
      }
      people = next;
      loaded = true;
      renderCommunity();
      refreshAdminAccess();
      if ($('#view-store').classList.contains('active')) refreshStore();
    } catch (error) {
      if (revision === communityRevision) toast(error.message);
    } finally {
      refreshRequest = null;
    }
  })();
  return refreshRequest;
}
const refreshSoon = () => {
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(refreshCommunity, 300);
};

function renderMessages() {
  const list = $('#messages');
  const atBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 48;
  const fragment = document.createDocumentFragment();
  for (const message of conversation.messages) {
    const bubble = document.createElement('div');
    bubble.className = 'message' + (message.sender === people.me?.id ? ' mine' : '');
    bubble.textContent = message.body;
    fragment.append(bubble);
  }
  list.replaceChildren(fragment);
  list.setAttribute('aria-busy', String(conversation.loading));
  if (atBottom) list.scrollTop = list.scrollHeight;
}

async function openChat(id) {
  const request = conversation.open(id, community.messages);
  unread.delete(id);
  renderCommunity();
  $('#chat-input').value = '';
  renderMessages();
  try {
    if (await request) {
      renderMessages();
      $('#messages').scrollTop = $('#messages').scrollHeight;
      $('#chat-input').focus();
    }
  } catch (error) {
    if (conversation.selected === id) {
      renderMessages();
      toast(error.message);
    }
  }
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
action('#community-logout', async () => {
  await community.logout();
  resetCommunity();
});
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
  const recipient = conversation.selected;
  if (!text || !recipient) return;
  $('#chat-input').value = '';
  try {
    const message = await community.send(recipient, text);
    if (message && conversation.receive(message)) renderMessages();
  } catch (error) {
    toast(error.message);
    if (conversation.selected === recipient && !$('#chat-input').value) $('#chat-input').value = text;
  }
};
community.onEvent((event) => {
  if (event.type !== 'message') return refreshSoon();
  const m = event.payload;
  const other = m.sender === people.me?.id ? m.recipient : m.sender;
  if (other === conversation.selected) {
    if (conversation.receive(m)) renderMessages();
    if (!$('#view-friends').classList.contains('active') && m.sender !== people.me?.id) {
      unread.add(other);
      renderCommunity();
    }
  } else if (m.sender !== people.me?.id) {
    unread.add(other);
    toast((people.friends.find((f) => f.id === other)?.name || 'Amigo') + ': ' + m.body.slice(0, 60));
    renderCommunity();
  }
});
const communityTimer = setInterval(() => {
  if (!document.hidden && people.me) refreshCommunity();
}, 30000);
document.addEventListener('visibilitychange', () => {
  if (!document.hidden && settings) refreshSoon();
});
window.addEventListener('pagehide', () => {
  clearInterval(communityTimer);
  clearTimeout(refreshTimer);
  clearTimeout(toastTimer);
});

(async () => {
  try {
    const data = await api.init();
    settingsStore = createSettingsStore(data.settings, api.saveSettings);
    settings = settingsStore.value;
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
    renderWallpaper(data.wallpaper).catch((error) => toast(error.message));
    $('#share-server').checked = settings.shareServer;
    $('#discord-presence').checked = settings.discordPresence;
    api.discord
      .state()
      .then((state) => {
        $('#discord-state').textContent = state.message;
      })
      .catch((error) => toast(error.message));
    refreshCommunity();
    updateState(data.state);
    api.onState(updateState);
  } catch (e) {
    toast('Não foi possível carregar o launcher: ' + e.message);
  }
})();
