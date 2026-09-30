// Three launchers in one page, a fake microphone and an in-memory signal bus: checks that the
// host connects both guests and forwards each guest's audio to the other.
const { app, BrowserWindow } = require('electron');
const path = require('node:path');
const assert = require('node:assert/strict');

app.commandLine.appendSwitch('use-fake-device-for-media-stream');
app.commandLine.appendSwitch('use-fake-ui-for-media-stream');
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
app.whenReady().then(async () => {
  const win = new BrowserWindow({ show: false });
  try {
    await win.loadFile(path.join(__dirname, '../tests/call/index.html'));
    const run = (code) => win.webContents.executeJavaScript(code);
    await run('new Promise((r) => { const t = setInterval(() => window.clients && (clearInterval(t), r()), 20); })');
    const [host, one, two] = ['clients[0]', 'clients[1]', 'clients[2]'];
    await run(`${host}.calls.start({ id: ${one}.id, name: ${one}.name })`);
    await wait(300);
    assert.equal(await run(`${one}.calls.state().status`), 'incoming');
    await run(`${one}.calls.accept()`);
    await run(`${host}.calls.start({ id: ${two}.id, name: ${two}.name })`);
    await wait(300);
    await run(`${two}.calls.accept()`);
    await wait(4000);
    const stats = await run('clients.map((c) => c.calls.stats())');
    assert.deepEqual(stats[0].connections, ['connected', 'connected'], JSON.stringify(stats));
    assert.deepEqual(
      stats.map((s) => s.tracks),
      [2, 2, 2],
      JSON.stringify(stats),
    );
    assert.deepEqual((await run(`${two}.calls.state().people.map((p) => p.name)`)).sort(), [
      'Guest1',
      'Guest2',
      'Host',
    ]);
    await run(`${two}.calls.end()`);
    await wait(1500);
    assert.equal(await run(`${host}.calls.state().people.length`), 2);
    assert.equal((await run(`${host}.calls.stats()`)).tracks, 1);
    await run(`${host}.calls.end()`);
    await wait(500);
    assert.equal(await run(`${one}.calls.state().status`), null);
    await run(`${two}.calls.start({ id: ${host}.id, name: ${host}.name })`);
    await wait(300);
    await run(`${host}.calls.decline()`);
    await wait(300);
    assert.match((await run('log')).join('\n'), /Host recusou a chamada/);
    console.log('Call OK: convite, host com 2 convidados, áudio encaminhado, saída, fim e recusa.');
    app.exit(0);
  } catch (error) {
    console.error(error);
    app.exit(1);
  }
});
