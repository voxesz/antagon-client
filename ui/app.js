import { setWallpaper } from './wallpaper.js';
import { createSettingsStore, createConversation } from './state.mjs';
import { mountRadio } from './radio.js';

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
const radio = mountRadio(api.radio, toast);
async function persist(patch) {
  const request = settingsStore.update(patch);
  settings = settingsStore.value;
  await request;
  settings = settingsStore.value;
  renderQuickSettings();
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
      $$('.tab').forEach((t) => t.classList.toggle('active', t.dataset.view === tab.dataset.view));
      $$('.view').forEach((v) => v.classList.toggle('active', v.id === 'view-' + tab.dataset.view));
      if (tab.dataset.view === 'friends' && conversation.selected) {
        unread.delete(conversation.selected);
        renderCommunity();
        renderMessages();
      }
      if (tab.dataset.view === 'store') refreshStore();
      if (tab.dataset.view === 'play') refreshFeatured();
      if (tab.dataset.view === 'radio') radio.open();
      if (tab.dataset.view === 'admin') refreshAdminAccess();
    }),
);
api.onOpenView((view) => {
  if (!['settings', 'friends', 'store', 'admin', 'radio'].includes(view)) return;
  const tab = $(`[data-view="${view}"]`);
  if (tab && !tab.hidden) tab.click();
});
function renderProfile() {
  const microsoft = settings.mode === 'microsoft' && account,
    name = microsoft ? account.name : settings.nickname;
  $('#profile-name').textContent = name;
  $('#home-account-name').textContent = name;
  $('#home-account-mode').textContent = microsoft ? 'Microsoft' : 'Offline';
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
  for (const input of $$('#game-version, #home-memory, #home-fullscreen, #home-account, #home-mods'))
    input.disabled = working;
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
$('#game-version').onchange = async (event) => {
  try {
    await persist({ gameVersion: event.target.value });
    renderVersion(settings.gameVersion);
  } catch (e) {
    toast(e.message);
  }
};
setting('#fullscreen', 'fullscreen', (input) => input.checked);
setting('#home-memory', 'memory', (input) => Number(input.value));
setting('#home-fullscreen', 'fullscreen', (input) => input.checked);
function renderQuickSettings() {
  for (const id of ['#memory', '#home-memory']) $(id).value = settings.memory;
  $('#memory-value').textContent = settings.memory + ' GB';
  for (const id of ['#fullscreen', '#home-fullscreen']) $(id).checked = settings.fullscreen;
}
$('#home-account').onclick = () => $('#profile-open').click();
$('#home-settings').onclick = () => $('.tab[data-view="settings"]').click();
$('#home-store').onclick = () => $('.tab[data-view="store"]').click();
$('#home-mods').onclick = () => {
  $('.tab[data-view="settings"]').click();
  $(settings.gameVersion === 'latest-26' ? '#fabric-row' : '#optifine-row').scrollIntoView({ block: 'center' });
};
setting('#share-server', 'shareServer', (input) => input.checked);
setting('#discord-presence', 'discordPresence', (input) => input.checked);
api.discord.onState((state) => {
  $('#discord-state').textContent = state.message;
});
let optifineFile = null;
function renderOptifine(file) {
  optifineFile = file;
  renderHomeMods();
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
const ITEMS = {
  antagon_cape: {
    name: 'Capa Antagon',
    kind: 'cape',
    image: '../assets/antagon-cape.png',
    description: 'Capa preta com a logo Antagon em vermelho.',
  },
  antagon_logo_cape: {
    name: 'Capa Logo Antagon',
    kind: 'cape',
    image: '../assets/antagon-logo-cape.png',
    description: 'Capa vermelha com a logo Antagon em alta resolução.',
  },
  antagon_crown: {
    name: 'Coroa Antagon',
    kind: 'hat',
    image: '../assets/antagon-crown.png',
    description: 'Coroa dourada em 3D sobre a cabeça.',
  },
};
const cosmeticUrl = (id) =>
  /^custom_[a-f0-9]{16}$/.test(id)
    ? `https://pnlemlqvuqftantunwcw.supabase.co/storage/v1/object/public/cosmetics/${id}.png`
    : '';
function itemPreview(item) {
  if (item.custom) return `<div class="cape-face" style="background-image: url('${cosmeticUrl(item.id)}')"></div>`;
  const meta = ITEMS[item.id] || ITEMS.antagon_cape;
  return `<img class="${item.kind === 'hat' ? 'hat' : ''}" src="${meta.image}" alt="" />`;
}
const itemDescription = (item) =>
  ITEMS[item.id]?.description || (item.kind === 'hat' ? 'Acessório para a cabeça.' : 'Capa exclusiva.');
let featuredRequest = null;
function refreshFeatured() {
  if (featuredRequest) return featuredRequest;
  featuredRequest = (async () => {
    const target = $('#featured-items');
    try {
      const items = await api.store.featured();
      target.innerHTML = items.length
        ? items
            .map(
              (item) =>
                `<button class="featured-card" data-featured-item="${escape(item.id)}" aria-label="Ver ${escape(item.name)} na loja"><span class="featured-art"><span class="featured-kind">${item.kind === 'hat' ? 'Acessório' : 'Capa'}</span>${itemPreview(item)}</span><span class="featured-info"><span><b>${escape(item.name)}</b><span class="featured-price"><img src="../assets/antagon-coin.png" alt="" />${Number(item.price).toLocaleString('pt-BR')} ANTAGOIN$</span></span><span class="featured-arrow" aria-hidden="true">↗</span></span></button>`,
            )
            .join('')
        : '<p class="featured-empty">Novos destaques em breve. Explore os cosméticos na loja.</p>';
    } catch {
      target.innerHTML =
        '<p class="featured-empty">Não foi possível carregar os destaques. <button id="featured-retry" class="text-button">Tentar novamente</button></p>';
    }
  })().finally(() => {
    featuredRequest = null;
  });
  return featuredRequest;
}
$('#featured-items').onclick = async (event) => {
  if (event.target.closest('#featured-retry')) return refreshFeatured();
  const card = event.target.closest('[data-featured-item]');
  if (!card) return;
  storeTab = 'shop';
  $('.tab[data-view="store"]').click();
  await refreshStore();
  const item = [...$$('#store-items [data-store-item]')].find(
    (button) => button.dataset.storeItem === card.dataset.featuredItem,
  );
  if (item) {
    item.scrollIntoView({ block: 'center' });
    item.focus({ preventScroll: true });
  }
};
let storeTab = 'shop';
function storeCard(item, available) {
  const owned = storeState?.owned?.includes(item.id);
  const equipped = storeState?.equipped?.[item.kind] === item.id;
  const action = owned ? (equipped ? 'unequip' : 'equip') : 'purchase';
  const label = !available ? 'Em breve' : owned ? (equipped ? 'Desequipar' : 'Equipar') : 'Comprar';
  const price = owned
    ? `<span class="item-owned">${equipped ? 'Equipado' : 'No inventário'}</span>`
    : `<span class="item-price"><img src="../assets/antagon-coin.png" alt="" />${item.price} ANTAGOIN$</span>`;
  return `<article class="store-item"><div class="cape-preview">${itemPreview(item)}</div><div><h3>${escape(item.name)}</h3><p>${escape(itemDescription(item))}</p>${price}</div><button class="${equipped ? 'secondary' : 'primary'}" data-store-item="${escape(item.id)}" data-store-kind="${item.kind}" data-store-action="${action}" ${available ? '' : 'disabled'}>${label}</button></article>`;
}
function renderStore() {
  const available = Array.isArray(storeState?.catalog);
  const catalog = available
    ? storeState.catalog
    : Object.entries(ITEMS).map(([id, item]) => ({ id, name: item.name, kind: item.kind, price: 100, active: true }));
  $('#coin-balance').textContent = (storeState?.balance || 0).toLocaleString('pt-BR');
  $('#store-message').hidden = available;
  $('#store-message').textContent = available ? '' : 'Prévia do item. O catálogo ainda não está ativo neste servidor.';
  $$('.coin-packs button').forEach((button) => (button.disabled = !available));
  $$('#store-tabs [data-store-tab]').forEach((b) => b.classList.toggle('active', b.dataset.storeTab === storeTab));
  $('#store-items').hidden = $('#store-packs').hidden = storeTab !== 'shop';
  $('#store-inventory').hidden = storeTab !== 'inventory';
  $('#store-items').innerHTML = catalog
    .filter((item) => item.active)
    .map((item) => storeCard(item, available))
    .join('');
  const owned = catalog.filter((item) => storeState?.owned?.includes(item.id));
  $('#store-inventory').innerHTML = owned.length
    ? owned.map((item) => storeCard(item, true)).join('')
    : '<p class="admin-empty">Você ainda não tem cosméticos. Os itens comprados ou recebidos aparecem aqui, mesmo se saírem da loja.</p>';
}
$('#store-tabs').onclick = (event) => {
  const button = event.target.closest('[data-store-tab]');
  if (!button) return;
  storeTab = button.dataset.storeTab;
  renderStore();
};

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
  radio.access(adminAccess);
  $('#profile-admin').hidden = !adminAccess.isAdmin;
  if (adminAccess.isAdmin) refreshCatalog();
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
  $('#admin-items').innerHTML = adminCatalog
    .map((item) => {
      const owned = user.items.includes(item.id);
      return `<div class="admin-item"><span>${escape(item.name)}<small>${owned ? 'No inventário' : 'Não possui'}</small></span><button class="${owned ? 'secondary' : 'primary'}" data-admin-action="item" data-item="${escape(item.id)}">${owned ? 'Remover' : 'Dar item'}</button></div>`;
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
let adminCatalog = [];
async function refreshCatalog() {
  try {
    adminCatalog = await api.admin.catalog();
  } catch (error) {
    toast(error.message);
  }
  renderCatalog();
  renderAdminTarget();
}
function renderCatalog() {
  $('#admin-catalog').innerHTML = adminCatalog.length
    ? adminCatalog
        .map((item) => {
          const id = escape(item.id);
          const remove = `<button class="secondary" data-catalog-action="edit" data-catalog-item="${id}">Editar</button><button class="danger" data-catalog-action="delete" data-catalog-item="${id}">Excluir</button>`;
          return `<div class="catalog-row"><div class="catalog-thumb">${itemPreview(item)}</div><span><b>${escape(item.name)}</b><small>${item.kind === 'hat' ? 'Acessório' : 'Capa'} · ${item.price} ANTAGOIN$ · ${item.owners} ${item.owners === 1 ? 'dono' : 'donos'}</small></span><span class="catalog-state ${item.active ? 'on' : ''}">${item.active ? 'Na loja' : 'Fora da loja'}${item.featured ? '<small>★ Destaque</small>' : ''}</span><div class="catalog-actions"><button class="${item.mine ? 'secondary' : 'primary'}" data-catalog-action="take" data-catalog-item="${id}" data-mine="${item.mine ? 1 : 0}">${item.mine ? 'Devolver' : 'Pegar'}</button><button class="secondary" data-catalog-action="active" data-catalog-item="${id}" data-active="${item.active ? 1 : 0}">${item.active ? 'Tirar da loja' : 'Colocar na loja'}</button>${remove}</div></div>`;
        })
        .join('')
    : '<p class="admin-empty">Nenhum item cadastrado.</p>';
}
$('#admin-catalog').onclick = async (event) => {
  const button = event.target.closest('[data-catalog-action]');
  if (!button || button.disabled) return;
  const { catalogAction: action, catalogItem: item } = button.dataset;
  const name = adminCatalog.find((entry) => entry.id === item)?.name || item;
  if (action === 'edit') {
    const entry = adminCatalog.find((entry) => entry.id === item);
    if (!entry) return;
    $('#catalog-edit-form').dataset.item = item;
    $('#catalog-edit-name').value = entry.name;
    $('#catalog-edit-price').value = entry.price;
    $('#catalog-edit-active').checked = entry.active;
    $('#catalog-edit-featured').checked = !!entry.featured;
    $('#catalog-edit-error').textContent = '';
    $('#catalog-edit-dialog').showModal();
    return;
  }
  if (action === 'delete' && !confirm(`Excluir "${name}"? O item sai da loja e do inventário de todos os jogadores.`))
    return;
  button.disabled = true;
  try {
    if (action === 'take') {
      adminCatalog = await api.admin.take(item, button.dataset.mine !== '1');
      toast(button.dataset.mine === '1' ? 'Item removido do seu inventário.' : 'Item adicionado ao seu inventário.');
    } else if (action === 'delete') {
      adminCatalog = await api.admin.remove(item);
      toast('Item excluído.');
    } else {
      adminCatalog = await api.admin.setActive(item, button.dataset.active !== '1');
      toast(
        button.dataset.active === '1'
          ? 'Item retirado da loja. Quem já tem continua com ele.'
          : 'Item de volta à loja.',
      );
    }
    renderCatalog();
    renderAdminTarget();
  } catch (error) {
    toast(error.message);
    button.disabled = false;
  }
};
$('#catalog-edit-form').onsubmit = async (event) => {
  event.preventDefault();
  const fields = $('#catalog-edit-fields');
  if (fields.disabled) return;
  const item = event.currentTarget.dataset.item;
  fields.disabled = true;
  $('#catalog-edit-error').textContent = '';
  try {
    adminCatalog = await api.admin.update(item, {
      name: $('#catalog-edit-name').value.trim(),
      price: Number($('#catalog-edit-price').value),
      active: $('#catalog-edit-active').checked,
      featured: $('#catalog-edit-featured').checked,
    });
    renderCatalog();
    renderAdminTarget();
    $('#catalog-edit-dialog').close();
    toast('Item atualizado.');
    refreshFeatured();
  } catch (error) {
    $('#catalog-edit-error').textContent = error.message;
  } finally {
    fields.disabled = false;
  }
};
$('#admin-tabs').onclick = (event) => {
  const button = event.target.closest('[data-admin-tab]');
  if (!button) return;
  $$('#admin-tabs [data-admin-tab]').forEach((b) => b.classList.toggle('active', b === button));
  for (const tab of ['players', 'editor', 'catalog', 'radio'])
    $(`#admin-${tab}`).hidden = tab !== button.dataset.adminTab;
  if (button.dataset.adminTab === 'radio') radio.admin();
  if (button.dataset.adminTab === 'catalog') refreshCatalog();
  if (button.dataset.adminTab === 'editor') drawCape();
};

// Cape editor. The canvas is the visible face of the cape (10x16 Minecraft pixels at 16x).
const cape = {
  canvas: $('#cape-canvas'),
  image: null,
  text: { x: 80, y: 128 },
  picture: { x: 80, y: 128 },
  drag: null,
  destination: 'store',
};
function drawCape() {
  const g = cape.canvas.getContext('2d');
  g.fillStyle = $('#cape-bg').value;
  g.fillRect(0, 0, 160, 256);
  if (cape.image) {
    const w = (160 * Number($('#cape-image-size').value)) / 100,
      h = (w * cape.image.naturalHeight) / cape.image.naturalWidth;
    g.drawImage(cape.image, cape.picture.x - w / 2, cape.picture.y - h / 2, w, h);
  }
  const text = $('#cape-text').value.trim();
  if (text) {
    g.font = `${$('#cape-text-size').value}px Pixelify`;
    g.textAlign = 'center';
    g.textBaseline = 'middle';
    g.fillStyle = $('#cape-text-color').value;
    g.fillText(text, cape.text.x, cape.text.y);
  }
}
function shade(hex, amount) {
  const n = parseInt(hex.slice(1), 16);
  return `rgb(${[16, 8, 0].map((bit) => Math.round(((n >> bit) & 255) * amount)).join(',')})`;
}
function capeTexture() {
  const out = document.createElement('canvas');
  out.width = 1024;
  out.height = 512;
  const g = out.getContext('2d');
  g.fillStyle = shade($('#cape-bg').value, 0.6);
  g.fillRect(0, 0, 22 * 16, 17 * 16);
  g.drawImage(cape.canvas, 16, 16);
  return new Promise((resolve) => out.toBlob(resolve, 'image/png'));
}
function capePoint(event) {
  const rect = cape.canvas.getBoundingClientRect();
  return { x: ((event.clientX - rect.left) * 160) / rect.width, y: ((event.clientY - rect.top) * 256) / rect.height };
}
cape.canvas.onpointerdown = (event) => {
  const p = capePoint(event),
    g = cape.canvas.getContext('2d'),
    text = $('#cape-text').value.trim();
  g.font = `${$('#cape-text-size').value}px Pixelify`;
  const size = Number($('#cape-text-size').value),
    onText =
      text &&
      Math.abs(p.x - cape.text.x) < g.measureText(text).width / 2 + 4 &&
      Math.abs(p.y - cape.text.y) < size / 2 + 4;
  const target = onText || !cape.image ? cape.text : cape.picture;
  cape.drag = { target, dx: target.x - p.x, dy: target.y - p.y };
  cape.canvas.setPointerCapture(event.pointerId);
};
cape.canvas.onpointermove = (event) => {
  if (!cape.drag) return;
  const p = capePoint(event);
  cape.drag.target.x = Math.round(p.x + cape.drag.dx);
  cape.drag.target.y = Math.round(p.y + cape.drag.dy);
  drawCape();
};
cape.canvas.onpointerup = cape.canvas.onpointercancel = () => (cape.drag = null);
for (const id of ['#cape-bg', '#cape-text', '#cape-text-color', '#cape-text-size', '#cape-image-size'])
  $(id).oninput = drawCape;
$('#cape-image-pick').onclick = () => $('#cape-image').click();
$('#cape-image').onchange = () => {
  const file = $('#cape-image').files[0];
  if (!file) return;
  const image = new Image();
  image.onload = () => {
    cape.image = image;
    cape.picture = { x: 80, y: 128 };
    $('#cape-image-size').value = 100;
    $('#cape-image-clear').hidden = false;
    drawCape();
  };
  image.onerror = () => toast('Não foi possível abrir a imagem.');
  image.src = URL.createObjectURL(file);
};
$('#cape-image-clear').onclick = () => {
  cape.image = null;
  $('#cape-image').value = '';
  $('#cape-image-clear').hidden = true;
  drawCape();
};
$('#cape-destination').onclick = (event) => {
  const button = event.target.closest('[data-destination]');
  if (!button) return;
  cape.destination = button.dataset.destination;
  $$('#cape-destination button').forEach((b) => b.classList.toggle('active', b === button));
  $('#cape-price-field').hidden = cape.destination !== 'store';
  $('#cape-target-field').hidden = cape.destination !== 'player';
};
action('#cape-create', async () => {
  const name = $('#cape-name').value.trim();
  if (!name) return toast('Dê um nome para a capa.');
  if (cape.destination === 'player' && !$('#cape-target').value.trim()) return toast('Informe o nick do jogador.');
  const png = new Uint8Array(await (await capeTexture()).arrayBuffer());
  await api.admin.createCape({
    name,
    price: Number($('#cape-price').value),
    destination: cape.destination,
    target: $('#cape-target').value.trim(),
    png,
  });
  toast(
    cape.destination === 'store'
      ? 'Capa criada e colocada na loja.'
      : cape.destination === 'player'
        ? 'Capa criada e enviada ao jogador.'
        : 'Capa criada no seu inventário.',
  );
  $('#cape-name').value = '';
  refreshCatalog();
});
document.fonts?.load('40px Pixelify').then(drawCape, drawCape);

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
$('#store-content').onclick = async (event) => {
  const button = event.target.closest('[data-store-item]');
  if (!button || button.disabled) return;
  button.disabled = true;
  try {
    storeState =
      button.dataset.storeAction === 'purchase'
        ? await api.store.purchase(button.dataset.storeItem)
        : await api.store.equip(
            button.dataset.storeAction === 'unequip' ? null : button.dataset.storeItem,
            button.dataset.storeKind,
          );
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
  if (friend) {
    $('#chat-head').innerHTML = `${head(friend)}<span class="who"><b>${escape(friend.name)}</b></span>`;
  } else {
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
community.onNotify((notice) => {
  const card = document.createElement('div');
  card.className = 'notice';
  const head = document.createElement('img');
  head.alt = '';
  head.src = /^[0-9a-f]{32}$/.test(notice.uuid || '')
    ? `https://mc-heads.net/avatar/${notice.uuid}/32`
    : '../assets/g-light.svg';
  const text = document.createElement('span');
  const name = document.createElement('b');
  name.textContent = notice.name;
  text.append(name, document.createTextNode(' ' + notice.text));
  card.append(head, text);
  card.onclick = () => ($('[data-view="friends"]').click(), card.remove());
  $('#notices').append(card);
  while ($('#notices').children.length > 3) $('#notices').firstChild.remove();
  setTimeout(() => card.classList.add('leaving'), 5e3);
  setTimeout(() => card.remove(), 5.4e3);
});
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
    renderQuickSettings();
    refreshFeatured();
    $('#version').textContent = data.version;
    checkUpdate();
    $('#memory').value = settings.memory;
    $('#memory-value').textContent = settings.memory + ' GB';
    $('#fullscreen').checked = settings.fullscreen;
    $('#game-version').value = settings.gameVersion;
    renderVersion(settings.gameVersion, data.installed);
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

function renderVersion(version, installed = false) {
  const modern = version === 'latest-26';
  $('#install-state').textContent = modern
    ? 'Fabric · ' + (installed ? 'perfil separado' : 'será preparado ao jogar')
    : 'Forge · Antagon · ' + (installed ? 'pronto para jogar' : 'instalação automática');
  $('#optifine-row').hidden = modern;
  $('#fabric-row').hidden = !modern;
  renderHomeMods();
}

function renderHomeMods() {
  const modern = settings?.gameVersion === 'latest-26';
  $('#home-mods-name').textContent = modern ? 'Fabric + Sodium' : 'OptiFine';
  $('#home-mods-state').textContent = modern ? 'Mods compatíveis' : optifineFile ? 'Instalado' : 'Configurar';
}
