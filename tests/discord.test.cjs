const test = require('node:test');
const assert = require('node:assert/strict');
const net = require('node:net');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { once } = require('node:events');
const { DiscordPresence, activityFor, ipcPaths, frame, decoder } = require('../electron/discord.cjs');
const { validateSettings, DEFAULTS } = require('../electron/settings.cjs');

async function until(predicate, timeout = 3000) {
  const end = Date.now() + timeout;
  while (!predicate()) {
    if (Date.now() > end) throw Error('Timed out');
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

test('Discord presence never exposes the server when sharing is disabled', () => {
  const hidden = activityFor({ activity: 'server', server: 'private.example', shareServer: false });
  assert.ok(!JSON.stringify(hidden).includes('private.example'));
  assert.equal(
    activityFor({ activity: 'server', server: 'play.example', shareServer: true }).state,
    'Servidor: play.example',
  );
  assert.equal(activityFor({ activity: 'singleplayer' }).state, 'Mundo singleplayer');
});

test('Discord application IDs remain strings and invalid IDs are discarded', () => {
  assert.equal(
    validateSettings({ discordApplicationId: '1234567890123456789' }).discordApplicationId,
    '1234567890123456789',
  );
  for (const value of ['abc', '<script>', 1234567890123456789])
    assert.equal(validateSettings({ discordApplicationId: value }).discordApplicationId, DEFAULTS.discordApplicationId);
  assert.equal(validateSettings({ discordPresence: false }).discordPresence, false);
});

test('IPC framing handles fragmented and consecutive messages', () => {
  const received = [];
  const decode = decoder((op, body) => received.push([op, JSON.parse(body)]));
  const data = Buffer.concat([frame(1, { evt: 'READY' }), frame(3, { ping: 1 })]);
  for (const byte of data) decode(Buffer.from([byte]));
  assert.deepEqual(received, [
    [1, { evt: 'READY' }],
    [3, { ping: 1 }],
  ]);
  const invalid = Buffer.alloc(8);
  invalid.writeUInt32LE(2 ** 30, 4);
  assert.throws(() => decoder(() => {})(invalid), /limite/);
  assert.ok(ipcPaths('win32', {})[0].endsWith('discord-ipc-0'));
  assert.ok(ipcPaths('darwin', { TMPDIR: '/private/test' }).includes('/private/test/discord-ipc-9'));
});

test('Discord reconnects, deduplicates updates, responds to pings and clears on disable', async (t) => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'antagon-rpc-'));
  const socketPath =
    process.platform === 'win32' ? `\\\\?\\pipe\\antagon-test-${process.pid}` : path.join(directory, 'rpc');
  const messages = [],
    connections = [];
  const server = net.createServer((socket) => {
    connections.push(socket);
    socket.on('error', () => {});
    socket.on(
      'data',
      decoder((opcode, body) => {
        const value = JSON.parse(body);
        messages.push({ opcode, value });
        if (opcode === 0) socket.write(frame(1, { evt: 'READY' }));
        if (opcode === 1) socket.write(frame(1, { cmd: 'SET_ACTIVITY', nonce: value.nonce }));
      }),
    );
  });
  const rpc = new DiscordPresence(() => {}, { paths: [socketPath], reconnectDelay: 20 });
  t.after(async () => {
    rpc.stop();
    connections.forEach((socket) => socket.destroy());
    await new Promise((resolve) => server.close(resolve));
    await fs.rm(directory, { recursive: true, force: true });
  });
  server.listen(socketPath);
  await once(server, 'listening');
  rpc.configure({ discordPresence: true, discordApplicationId: '' });
  assert.equal(rpc.state().status, 'unconfigured');
  rpc.configure({ discordPresence: true, discordApplicationId: '123456789012345678' });
  await until(() => rpc.state().status === 'connected');
  assert.equal(messages[0].value.client_id, '123456789012345678');
  const updates = () => messages.filter((item) => item.value.cmd === 'SET_ACTIVITY');
  rpc.setActivity({ activity: 'singleplayer' });
  rpc.setActivity({ activity: 'singleplayer' });
  await until(() => updates().length === 2);
  connections[0].write(frame(3, { ping: 42 }));
  await until(() => messages.some((item) => item.opcode === 4));
  connections[0].destroy();
  await until(() => connections.length === 2 && rpc.state().status === 'connected');
  assert.equal(updates().at(-1).value.args.activity.state, 'Mundo singleplayer');
  rpc.configure({ discordPresence: false, discordApplicationId: '123456789012345678' });
  await until(() => updates().at(-1).value.args.activity === null);
  assert.equal(rpc.state().status, 'disabled');
});
