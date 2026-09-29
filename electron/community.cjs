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
    this.activity = { activity: 'launcher', server: null };
    this.db = createClient(SUPABASE_URL, SUPABASE_KEY, {
      global: {
        fetch: (url, init = {}) =>
          fetch(url, {
            ...init,
            signal: AbortSignal.any([AbortSignal.timeout(15000), ...(init.signal ? [init.signal] : [])]),
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
      .on('postgres_changes', { event: 'INSERT', schema: 'public', table: 'messages' }, changed('message'))
      .subscribe();
  }

  stop() {
    clearInterval(this.timer);
    if (this.channel) this.db.removeChannel(this.channel).catch(() => {});
    this.channel = null;
  }

  async publish() {
    if (!this.me) return;
    await this.db
      .from('presence')
      .upsert({ user_id: this.me.id, ...this.activity, updated_at: new Date().toISOString() });
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
    return data;
  }
}

module.exports = { Community, validateId };
