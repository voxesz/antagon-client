const net = require('node:net');
const path = require('node:path');
const crypto = require('node:crypto');

const APPLICATION_ID = /^[0-9]{17,20}$/;
const MAX_FRAME = 1024 * 1024;
const STATUS = {
  disabled: 'Desativado',
  unconfigured: 'Adicione o Application ID para conectar',
  waiting: 'Aguardando o Discord no computador',
  connecting: 'Conectando ao Discord…',
  connected: 'Conectado ao Discord',
  error: 'O Discord recusou a presença. Confira o Application ID.',
};

function ipcPaths(platform = process.platform, env = process.env) {
  if (platform === 'win32') return Array.from({ length: 10 }, (_, i) => `\\\\?\\pipe\\discord-ipc-${i}`);
  const roots = [...new Set([env.XDG_RUNTIME_DIR, env.TMPDIR, env.TMP, env.TEMP, '/tmp'].filter(Boolean))];
  return roots.flatMap((root) => Array.from({ length: 10 }, (_, i) => path.join(root, `discord-ipc-${i}`)));
}

function frame(opcode, value) {
  const body = Buffer.isBuffer(value) ? value : Buffer.from(JSON.stringify(value));
  const header = Buffer.alloc(8);
  header.writeUInt32LE(opcode, 0);
  header.writeUInt32LE(body.length, 4);
  return Buffer.concat([header, body]);
}

function decoder(receive) {
  let buffer = Buffer.alloc(0);
  return (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    while (buffer.length >= 8) {
      const opcode = buffer.readUInt32LE(0),
        length = buffer.readUInt32LE(4);
      if (length > MAX_FRAME) throw Error('Resposta do Discord excedeu o limite.');
      if (buffer.length < length + 8) return;
      const payload = buffer.subarray(8, 8 + length);
      buffer = buffer.subarray(8 + length);
      receive(opcode, payload);
    }
  };
}

function activityFor({ activity = 'launcher', server = null, shareServer = false, startedAt }) {
  const labels = {
    launcher: 'No launcher',
    menu: 'No menu',
    singleplayer: 'Mundo singleplayer',
    playing: 'Em uma partida',
  };
  const state =
    activity === 'server' && shareServer && server ? `Servidor: ${server}` : labels[activity] || labels.playing;
  return {
    details: 'Minecraft 1.8.9 · Antagon Client',
    state: [...state].slice(0, 120).join(''),
    ...(startedAt ? { timestamps: { start: Math.floor(startedAt / 1000) } } : {}),
    instance: false,
  };
}

class DiscordPresence {
  constructor(emit = () => {}, { paths = ipcPaths(), reconnectDelay = 15000 } = {}) {
    this.emit = emit;
    this.paths = paths;
    this.reconnectDelay = reconnectDelay;
    this.status = 'disabled';
    this.enabled = false;
    this.applicationId = '';
    this.activity = activityFor({});
    this.socket = null;
    this.ready = false;
    this.generation = 0;
    this.lastSent = '';
  }

  state() {
    return { status: this.status, message: STATUS[this.status] };
  }

  setStatus(status) {
    if (this.status === status) return;
    this.status = status;
    this.emit(this.state());
  }

  configure({ discordPresence, discordApplicationId }) {
    const id = APPLICATION_ID.test(discordApplicationId) ? discordApplicationId : '';
    if (this.enabled === discordPresence && this.applicationId === id) return;
    this.disconnect();
    this.enabled = discordPresence;
    this.applicationId = id;
    if (!this.enabled) this.setStatus('disabled');
    else if (!id) this.setStatus('unconfigured');
    else this.connect();
  }

  setActivity(activity) {
    this.activity = activityFor(activity);
    this.publish();
  }

  connect(index = 0, generation = this.generation) {
    if (!this.enabled || !this.applicationId || generation !== this.generation) return;
    if (index >= this.paths.length) {
      this.setStatus('waiting');
      this.retry = setTimeout(() => this.connect(), this.reconnectDelay);
      this.retry.unref();
      return;
    }
    this.setStatus('connecting');
    const socket = net.createConnection(this.paths[index]);
    this.socket = socket;
    let connected = false;
    const timeout = setTimeout(() => socket.destroy(), 5000);
    timeout.unref();
    const receive = decoder((opcode, payload) => {
      if (generation !== this.generation) return;
      if (opcode === 3) {
        socket.write(frame(4, payload));
        return;
      }
      if (opcode === 2) {
        socket.destroy();
        return;
      }
      if (opcode !== 1) return;
      const message = JSON.parse(payload.toString('utf8'));
      if (message.evt === 'READY') {
        clearTimeout(timeout);
        connected = true;
        this.ready = true;
        this.lastSent = '';
        this.publish();
      } else if (message.evt === 'ERROR') {
        this.setStatus('error');
      } else if (message.cmd === 'SET_ACTIVITY') {
        this.setStatus('connected');
      }
    });
    socket.on('connect', () => socket.write(frame(0, { v: 1, client_id: this.applicationId })));
    socket.on('data', (chunk) => {
      try {
        receive(chunk);
      } catch {
        socket.destroy();
      }
    });
    socket.on('error', () => {});
    socket.once('close', () => {
      clearTimeout(timeout);
      if (generation !== this.generation) return;
      this.socket = null;
      this.ready = false;
      if (!connected) this.connect(index + 1, generation);
      else {
        this.setStatus('waiting');
        this.retry = setTimeout(() => this.connect(), this.reconnectDelay);
        this.retry.unref();
      }
    });
  }

  publish() {
    if (!this.ready || !this.socket || this.socket.destroyed) return;
    const serialized = JSON.stringify(this.activity);
    if (serialized === this.lastSent) return;
    this.lastSent = serialized;
    this.socket.write(
      frame(1, {
        cmd: 'SET_ACTIVITY',
        args: { pid: process.pid, activity: this.activity },
        nonce: crypto.randomUUID(),
      }),
    );
  }

  disconnect() {
    this.generation++;
    clearTimeout(this.retry);
    const socket = this.socket;
    if (socket && !socket.destroyed) {
      if (this.ready) {
        socket.end(
          frame(1, { cmd: 'SET_ACTIVITY', args: { pid: process.pid, activity: null }, nonce: crypto.randomUUID() }),
        );
        const timeout = setTimeout(() => socket.destroy(), 250);
        timeout.unref();
        socket.once('close', () => clearTimeout(timeout));
      } else socket.destroy();
    }
    this.socket = null;
    this.ready = false;
    this.lastSent = '';
  }

  stop() {
    this.enabled = false;
    this.disconnect();
    this.setStatus('disabled');
  }
}

module.exports = { DiscordPresence, APPLICATION_ID, activityFor, ipcPaths, frame, decoder };
