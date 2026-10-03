const fs = require('node:fs');
const path = require('node:path');
const { createClient } = require('@supabase/supabase-js');
const { atomicWrite } = require('./files.cjs');

const SUPABASE_URL = 'https://pnlemlqvuqftantunwcw.supabase.co';
const SUPABASE_KEY = 'sb_publishable_cavp7w5y09iRpEOq5F3x5Q_GE4Uwz-X';
const HEARTBEAT = 3e4;
const ONLINE = 9e4;

function encryptedStorage(file, safeStorage) {
  const read = () => {
    try {
      return JSON.parse(safeStorage.decryptString(fs.readFileSync(file)));
    } catch {
      return {};
    }
  };
  const write = (data) => atomicWrite(file, safeStorage.encryptString(JSON.stringify(data)));
  return {
    getItem: (key) => read()[key] ?? null,
    setItem: (key, value) => write({ ...read(), [key]: value }),
    removeItem: (key) => {
      const data = read();
      delete data[key];
      write(data);
    },
  };
}

function validateId(id) {
  if (!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(id)) throw Error('Jogador inválido.');
}

class Community {
  constructor(root, safeStorage, emit) {
    this.emit = emit;
    this.me = null;
    this.messageIds = new Set();
    this.activity = { activity: 'launcher', server: null };
    this.db = createClient(SUPABASE_URL, SUPABASE_KEY, {
      global: {
        fetch: (url, init = {}) =>
          fetch(url, {
            ...init,
            signal: AbortSignal.any([
              AbortSignal.timeout(String(url).includes('/storage/v1/object/radio-media/') ? 120000 : 15000),
              ...(init.signal ? [init.signal] : []),
            ]),
          }),
      },
      auth: {
        storage: encryptedStorage(path.join(root, 'community.enc'), safeStorage),
        persistSession: safeStorage.isEncryptionAvailable(),
        autoRefreshToken: true,
        detectSessionInUrl: false,
      },
    });
  }

  async restore() {
    const { data } = await this.db.auth.getSession();
    if (!data.session) return null;
    const { data: profile } = await this.db
      .from('profiles')
      .select('id, name, mc_uuid')
      .eq('id', data.session.user.id)
      .single();
    if (!profile) return null;
    this.me = { id: profile.id, name: profile.name, mcUuid: profile.mc_uuid };
    this.activity = { activity: 'launcher', server: null };
    this.start();
    return this.me;
  }

  async signIn(minecraftToken) {
    const res = await fetch(`${SUPABASE_URL}/functions/v1/minecraft-auth`, {
      method: 'POST',
      headers: { apikey: SUPABASE_KEY, 'content-type': 'application/json' },
      body: JSON.stringify({ token: minecraftToken }),
      signal: AbortSignal.timeout(2e4),
    });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) throw Error(body.error || 'Não foi possível entrar na comunidade.');
    const { error } = await this.db.auth.setSession(body.session);
    if (error) throw Error('Não foi possível entrar na comunidade.');
    this.me = body.profile;
    this.activity = { activity: 'launcher', server: null };
    this.start();
    return this.me;
  }

  async signOut() {
    await this.setActivity('offline').catch(() => {});
    this.stop();
    const { error } = await this.db.auth.signOut({ scope: 'local' });
    if (error) throw Error('Não foi possível encerrar a sessão da comunidade.');
    this.me = null;
  }

  start() {
    this.stop();
    this.publish().catch(() => {});
    this.timer = setInterval(() => this.publish().catch(() => {}), HEARTBEAT);
    this.timer.unref();
    const changed = (type) => (payload) => this.emit({ type, payload: payload.new });
    this.channel = this.db
      .channel('community')
      .on('postgres_changes', { event: '*', schema: 'public', table: 'presence' }, changed('presence'))
      .on('postgres_changes', { event: '*', schema: 'public', table: 'friendships' }, changed('friendships'))
      .on('postgres_changes', { event: 'INSERT', schema: 'public', table: 'messages' }, (payload) =>
        this.deliverMessage(payload.new),
      )
      .subscribe();
  }

  stop() {
    clearInterval(this.timer);
    if (this.channel) this.db.removeChannel(this.channel).catch(() => {});
    this.channel = null;
  }

  deliverMessage(message) {
    if (message?.id == null) return;
    const id = String(message.id);
    if (this.messageIds.has(id)) return;
    this.messageIds.add(id);
    if (this.messageIds.size > 1000) this.messageIds.delete(this.messageIds.values().next().value);
    this.emit({ type: 'message', payload: { ...message, id } });
  }

  async publish() {
    if (!this.me) return;
    await Promise.all([
      this.db.from('presence').upsert({ user_id: this.me.id, ...this.activity, updated_at: new Date().toISOString() }),
      this.db.rpc(this.activity.activity === 'offline' ? 'leave_client' : 'heartbeat_client'),
    ]);
  }

  async access() {
    if (!this.me) return { isAdmin: false, isOwner: false, isBanned: false, banReason: null };
    const { data, error } = await this.db.rpc('account_status').single();
    if (error?.code === 'PGRST202' || error?.code === 'PGRST205') {
      const catalog = await this.db.from('cosmetic_catalog').select('id').limit(1);
      if (catalog.error?.code === 'PGRST205' || catalog.error?.code === '42P01')
        return { isAdmin: false, isOwner: false, isBanned: false, banReason: null };
    }
    if (error) throw Error('Não foi possível verificar o acesso à conta.');
    return {
      isAdmin: !!data.is_admin,
      isOwner: !!data.is_owner,
      isBanned: !!data.is_banned,
      banReason: data.ban_reason || null,
    };
  }

  async requireActive() {
    const access = await this.access();
    if (access.isBanned) throw Error(`Conta banida do Antagon Client: ${access.banReason || 'sem motivo informado'}`);
    return access;
  }

  async adminFind(name) {
    if (!/^(?:[A-Za-z0-9_]{1,16}|[0-9a-fA-F]{32})$/.test(name || '')) throw Error('Digite um nick ou UUID válido.');
    const { data, error } = await this.db.rpc('admin_find_user', { p_name: name });
    if (error) throw Error(error.message || 'Não foi possível buscar o jogador.');
    if (!data?.length) throw Error('Jogador ainda não registrado no Antagon Client.');
    return data[0];
  }

  async adminChange(action, id, value) {
    validateId(id);
    const commands = {
      role: ['admin_set_role', { p_target: id, p_admin: value === true }],
      ban: ['admin_set_ban', { p_target: id, p_banned: value?.banned === true, p_reason: value?.reason || null }],
      coins: ['admin_set_coins', { p_target: id, p_balance: value }],
      item: ['admin_set_item', { p_target: id, p_item: value?.item, p_grant: value?.grant === true }],
    };
    if (!Object.hasOwn(commands, action)) throw Error('Ação inválida.');
    const [rpc, args] = commands[action];
    const { error } = await this.db.rpc(rpc, args);
    if (error) throw Error(error.message || 'Não foi possível salvar a alteração.');
    return true;
  }

  async adminCatalog() {
    const { data, error } = await this.db.rpc('admin_catalog');
    if (error) throw Error(error.message || 'Não foi possível carregar os itens.');
    return data || [];
  }

  async adminSetActive(item, active) {
    if (!/^[a-z0-9_]{1,40}$/.test(item)) throw Error('Item inválido.');
    const { error } = await this.db.rpc('admin_set_active', { p_item: item, p_active: active === true });
    if (error) throw Error(error.message || 'Não foi possível alterar o item.');
    return this.adminCatalog();
  }

  async adminTake(item, take) {
    const { error } = await this.db.rpc('admin_set_item', {
      p_target: this.me.id,
      p_item: item,
      p_grant: take === true,
    });
    if (error) throw Error(error.message || 'Não foi possível alterar seu inventário.');
    return this.adminCatalog();
  }

  async adminDelete(item) {
    if (!/^[a-z0-9_]{1,40}$/.test(item)) throw Error('Item inválido.');
    const { error } = await this.db.rpc('admin_delete_item', { p_item: item });
    if (error) throw Error(error.message || 'Não foi possível excluir o item.');
    if (/^custom_[a-f0-9]{16}$/.test(item)) await this.db.storage.from('cosmetics').remove([`${item}.png`]);
    return this.adminCatalog();
  }

  async adminUpdate(item, input) {
    if (!/^[a-z0-9_]{1,40}$/.test(item)) throw Error('Item inválido.');
    const name = String(input?.name || '').trim();
    if (!name || name.length > 40) throw Error('Dê um nome de até 40 caracteres.');
    if (!Number.isInteger(input?.price) || input.price < 0 || input.price > 100000) throw Error('Preço inválido.');
    if (typeof input.active !== 'boolean') throw Error('Visibilidade inválida.');
    if (typeof input.featured !== 'boolean') throw Error('Destaque inválido.');
    const { error } = await this.db.rpc('admin_update_item', {
      p_item: item,
      p_name: name,
      p_price: input.price,
      p_active: input.active,
      p_featured: input.featured,
    });
    if (error) throw Error(error.message || 'Não foi possível editar o item.');
    return this.adminCatalog();
  }

  /** png is a 1024x512 cape texture rendered by the editor; the id is only registered after the upload. */
  async adminCreateCape({ name, price, destination, target, png }) {
    if (!isCapeTexture(png)) throw Error('Textura inválida.');
    name = String(name || '').trim();
    if (!name || name.length > 40) throw Error('Dê um nome de até 40 caracteres.');
    price = destination === 'store' ? Number(price) : 0;
    if (!Number.isInteger(price) || price < 0 || price > 100000) throw Error('Preço inválido.');
    if (!['store', 'player', 'me'].includes(destination)) throw Error('Destino inválido.');
    const owner =
      destination === 'player'
        ? (await this.adminFind(String(target || '').trim())).user_id
        : destination === 'me'
          ? this.me.id
          : null;
    const id = 'custom_' + require('node:crypto').randomBytes(8).toString('hex');
    const upload = await this.db.storage
      .from('cosmetics')
      .upload(`${id}.png`, png, { contentType: 'image/png', upsert: false });
    if (upload.error) throw Error('Não foi possível enviar a imagem.');
    const { error } = await this.db.rpc('admin_create_cape', {
      p_id: id,
      p_name: name,
      p_price: price,
      p_active: destination === 'store',
      p_target: owner,
    });
    if (error) throw Error(error.message || 'Não foi possível criar a capa.');
    return id;
  }

  async featured() {
    const { data, error } = await this.db.rpc('featured_cosmetics');
    if (error) throw Error('Não foi possível carregar os destaques.');
    return data || [];
  }

  async store() {
    if (!this.me) throw Error('Entre na comunidade primeiro.');
    await this.requireActive();
    const [catalog, wallet, owned, equipped] = await Promise.all([
      this.db.from('cosmetic_catalog').select('id, name, kind, price, active, custom').order('price'),
      this.db.from('coin_wallets').select('balance').eq('user_id', this.me.id).maybeSingle(),
      this.db.from('owned_cosmetics').select('item_id').eq('user_id', this.me.id),
      this.db.from('equipped_cosmetics').select('kind, item_id').eq('user_id', this.me.id),
    ]);
    if ([catalog, wallet, owned, equipped].some((result) => result.error))
      throw Error('Não foi possível carregar a loja.');
    return {
      balance: wallet.data?.balance || 0,
      catalog: catalog.data || [],
      owned: (owned.data || []).map((item) => item.item_id),
      equipped: Object.fromEntries((equipped.data || []).map((item) => [item.kind, item.item_id])),
    };
  }

  async purchase(item) {
    if (!/^[a-z0-9_]{1,40}$/.test(item)) throw Error('Item inválido.');
    const { error } = await this.db.rpc('purchase_cosmetic', { p_item: item });
    if (error) throw Error(error.message || 'Não foi possível comprar o item.');
    return this.store();
  }

  async equip(item, kind = 'cape') {
    if (!['cape', 'hat'].includes(kind) || (item !== null && !/^[a-z0-9_]{1,40}$/.test(item)))
      throw Error('Item inválido.');
    const { error } = await this.db.rpc('equip_cosmetic', { p_item: item, p_kind: kind });
    if (error) throw Error('Não foi possível equipar o item.');
    return this.store();
  }

  async checkout(pack) {
    if (!['small', 'medium', 'large'].includes(pack)) throw Error('Pacote inválido.');
    const { data, error } = await this.db.functions.invoke('coin-checkout', { body: { pack } });
    if (error || !/^https:\/\/checkout\.stripe\.com\//.test(data?.url || ''))
      throw Error(data?.error || 'Não foi possível abrir o pagamento.');
    return data.url;
  }

  async visibleCosmetics(uuids) {
    const valid = [...new Set(uuids.filter((id) => /^[0-9a-f]{32}$/.test(id)))].slice(0, 100);
    if (!this.me || !valid.length) return [];
    const { data, error } = await this.db.rpc('visible_cosmetics', { p_uuids: valid });
    if (error) throw Error('Não foi possível sincronizar os cosméticos.');
    return data || [];
  }

  async setActivity(activity, server = null) {
    const next = { activity, server: activity === 'server' ? server : null };
    if (next.activity === this.activity.activity && next.server === this.activity.server) return;
    this.activity = next;
    await this.publish();
  }

  async state() {
    if (!this.me) return { me: null, friends: [], incoming: [], outgoing: [] };
    const me = this.me;
    const empty = { me, friends: [], incoming: [], outgoing: [] };
    const linksResult = await this.db.from('friendships').select('requester, addressee, status');
    if (linksResult.error) throw Error('Não foi possível carregar seus amigos. Tente novamente.');
    const links = linksResult.data || [];
    const other = (link) => (link.requester === me.id ? link.addressee : link.requester);
    const ids = [...new Set(links.map(other))];
    if (!ids.length) return empty;
    const [profilesResult, presenceResult] = await Promise.all([
      this.db.from('profiles').select('id, name, mc_uuid').in('id', ids),
      this.db.from('presence').select('user_id, activity, server, updated_at').in('user_id', ids),
    ]);
    if (profilesResult.error || presenceResult.error) throw Error('Não foi possível atualizar seus amigos.');
    const profiles = new Map((profilesResult.data || []).map((profile) => [profile.id, profile]));
    const presence = new Map((presenceResult.data || []).map((status) => [status.user_id, status]));
    const person = (id) => {
      const profile = profiles.get(id) || { name: 'Jogador' };
      const status = presence.get(id);
      const online = !!status && status.activity !== 'offline' && Date.now() - Date.parse(status.updated_at) < ONLINE;
      return {
        id,
        name: profile.name,
        mcUuid: profile.mc_uuid,
        online,
        activity: online ? status.activity : 'offline',
        server: online ? status.server : null,
      };
    };
    return {
      me,
      friends: links.filter((l) => l.status === 'accepted').map((l) => person(other(l))),
      incoming: links.filter((l) => l.status === 'pending' && l.addressee === me.id).map((l) => person(l.requester)),
      outgoing: links.filter((l) => l.status === 'pending' && l.requester === me.id).map((l) => person(l.addressee)),
    };
  }

  async add(name) {
    if (!/^[A-Za-z0-9_]{1,16}$/.test(name || '')) throw Error('Digite um nick válido.');
    const { data: target } = await this.db.from('profiles').select('id, name').ilike('name', name).maybeSingle();
    if (!target) throw Error(`${name} ainda não entrou na comunidade do Antagon Client.`);
    if (target.id === this.me.id) throw Error('Esse é você.');
    const pending = await this.db
      .from('friendships')
      .update({ status: 'accepted' })
      .match({ requester: target.id, addressee: this.me.id, status: 'pending' })
      .select();
    if (pending.data?.length) return `Agora você e ${target.name} são amigos.`;
    const { error } = await this.db.from('friendships').insert({ requester: this.me.id, addressee: target.id });
    if (error?.code === '23505') throw Error(`Você já tem um pedido ou amizade com ${target.name}.`);
    if (error) throw Error('Não foi possível enviar o pedido.');
    return `Pedido enviado para ${target.name}.`;
  }

  async accept(id) {
    validateId(id);
    const { error } = await this.db
      .from('friendships')
      .update({ status: 'accepted' })
      .match({ requester: id, addressee: this.me.id });
    if (error) throw Error('Não foi possível aceitar o pedido.');
  }

  async remove(id) {
    validateId(id);
    const results = await Promise.all([
      this.db.from('friendships').delete().match({ requester: id, addressee: this.me.id }),
      this.db.from('friendships').delete().match({ requester: this.me.id, addressee: id }),
    ]);
    if (results.some((result) => result.error)) throw Error('Não foi possível remover a amizade.');
  }

  async messages(id) {
    validateId(id);
    const { data, error } = await this.db
      .from('messages')
      .select('id, sender, recipient, body, created_at')
      .or(`and(sender.eq.${this.me.id},recipient.eq.${id}),and(sender.eq.${id},recipient.eq.${this.me.id})`)
      .order('created_at', { ascending: false })
      .limit(50);
    if (error) throw Error('Não foi possível carregar as mensagens.');
    return (data || []).reverse();
  }

  async send(id, body) {
    validateId(id);
    const text = String(body || '').trim();
    if (!text) return;
    if (text.length > 500) throw Error('Mensagem muito longa (máximo 500 caracteres).');
    const { data, error } = await this.db
      .from('messages')
      .insert({ recipient: id, body: text })
      .select('id, sender, recipient, body, created_at')
      .single();
    if (error) throw Error('Não foi possível enviar a mensagem.');
    this.deliverMessage(data);
    return data;
  }
}

function cosmeticUrl(id) {
  if (!/^custom_[a-f0-9]{16}$/.test(id)) throw Error('Item inválido.');
  return `${SUPABASE_URL}/storage/v1/object/public/cosmetics/${id}.png`;
}

function isCapeTexture(png) {
  return (
    Buffer.isBuffer(png) &&
    png.length > 33 &&
    png.length <= 2 * 1024 * 1024 &&
    png.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])) &&
    png.readUInt32BE(16) === 1024 &&
    png.readUInt32BE(20) === 512
  );
}

module.exports = { Community, validateId, cosmeticUrl, isCapeTexture };
