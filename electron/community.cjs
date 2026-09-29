const fs = require('node:fs');
const path = require('node:path');
const { createClient } = require('@supabase/supabase-js');

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
  const write = (data) => fs.writeFileSync(file, safeStorage.encryptString(JSON.stringify(data)), { mode: 0o600 });
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

class Community {
  constructor(root, safeStorage, emit) {
    this.emit = emit;
    this.me = null;
    this.activity = { activity: 'launcher', server: null };
    this.db = createClient(SUPABASE_URL, SUPABASE_KEY, {
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
    this.start();
    return this.me;
  }

  async signOut() {
    await this.setActivity('offline');
    this.stop();
    await this.db.auth.signOut();
    this.me = null;
  }

  start() {
    this.stop();
    this.publish();
    this.timer = setInterval(() => this.publish(), HEARTBEAT);
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
    if (this.channel) this.db.removeChannel(this.channel);
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
    const { data: links = [] } = await this.db.from('friendships').select('requester, addressee, status');
    const other = (link) => (link.requester === this.me.id ? link.addressee : link.requester);
    const ids = links.map(other);
    const [{ data: profiles = [] }, { data: presence = [] }] = await Promise.all([
      this.db.from('profiles').select('id, name, mc_uuid').in('id', ids),
      this.db.from('presence').select('user_id, activity, server, updated_at').in('user_id', ids),
    ]);
    const person = (id) => {
      const profile = profiles.find((p) => p.id === id) || {};
      const status = presence.find((p) => p.user_id === id);
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
      me: this.me,
      friends: links.filter((l) => l.status === 'accepted').map((l) => person(other(l))),
      incoming: links
        .filter((l) => l.status === 'pending' && l.addressee === this.me.id)
        .map((l) => person(l.requester)),
      outgoing: links
        .filter((l) => l.status === 'pending' && l.requester === this.me.id)
        .map((l) => person(l.addressee)),
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
    const { error } = await this.db
      .from('friendships')
      .update({ status: 'accepted' })
      .match({ requester: id, addressee: this.me.id });
    if (error) throw Error('Não foi possível aceitar o pedido.');
  }

  async remove(id) {
    await this.db.from('friendships').delete().match({ requester: id, addressee: this.me.id });
    await this.db.from('friendships').delete().match({ requester: this.me.id, addressee: id });
  }

  async messages(id) {
    const { data = [] } = await this.db
      .from('messages')
      .select('id, sender, recipient, body, created_at')
      .or(`and(sender.eq.${this.me.id},recipient.eq.${id}),and(sender.eq.${id},recipient.eq.${this.me.id})`)
      .order('created_at', { ascending: false })
      .limit(50);
    return data.reverse();
  }

  async send(id, body) {
    const text = String(body || '').trim();
    if (!text) return;
    if (text.length > 500) throw Error('Mensagem muito longa (máximo 500 caracteres).');
    const { error } = await this.db.from('messages').insert({ recipient: id, body: text });
    if (error) throw Error('Não foi possível enviar a mensagem.');
  }
}

module.exports = { Community };
