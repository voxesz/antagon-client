const test = require('node:test');
const assert = require('node:assert/strict');
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((a, b) => {
    resolve = a;
    reject = b;
  });
  return { promise, resolve, reject };
};

test('rapid settings changes persist in order without losing later edits', async () => {
  const { createSettingsStore } = await import('../ui/state.mjs');
  const first = deferred(),
    calls = [];
  const store = createSettingsStore({ memory: 3, fullscreen: false }, async (value) => {
    calls.push(value);
    if (calls.length === 1) await first.promise;
    return value;
  });
  const one = store.update({ memory: 6 });
  const two = store.update({ fullscreen: true });
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(calls.length, 1);
  first.resolve();
  await one;
  assert.equal(store.value.fullscreen, true);
  await two;
  assert.deepEqual(calls, [
    { memory: 6, fullscreen: false },
    { memory: 6, fullscreen: true },
  ]);
});

test('failed settings writes do not prevent subsequent saves', async () => {
  const { createSettingsStore } = await import('../ui/state.mjs');
  let calls = 0;
  const store = createSettingsStore({ memory: 3 }, async (value) => {
    if (++calls === 1) throw Error('Disk full');
    return value;
  });
  await assert.rejects(store.update({ memory: 4 }), /Disk full/);
  await store.update({ memory: 5 });
  assert.equal(store.value.memory, 5);
});

const message = (id, sender, date = '2026-09-29T12:00:00Z') => ({
  id,
  sender,
  recipient: 'me',
  body: id,
  created_at: date,
});

test('late history cannot appear in a different conversation', async () => {
  const { createConversation } = await import('../ui/state.mjs');
  const chat = createConversation(),
    slow = deferred();
  const first = chat.open('alice', () => slow.promise);
  await chat.open('bob', async () => [message('b', 'bob')]);
  slow.resolve([message('a', 'alice')]);
  assert.equal(await first, false);
  assert.equal(chat.selected, 'bob');
  assert.deepEqual(
    chat.messages.map((m) => m.id),
    ['b'],
  );
});

test('realtime messages and history merge once and remain chronological', async () => {
  const { createConversation } = await import('../ui/state.mjs');
  const chat = createConversation(),
    history = deferred();
  const request = chat.open('alice', () => history.promise);
  assert.equal(chat.receive(message('new', 'alice', '2026-09-29T12:00:01Z')), true);
  assert.equal(chat.receive(message('new', 'alice')), false);
  assert.equal(chat.receive(message('wrong', 'bob')), false);
  history.resolve([message('old', 'alice'), message('new', 'alice', '2026-09-29T12:00:01Z')]);
  await request;
  assert.deepEqual(
    chat.messages.map((m) => m.id),
    ['old', 'new'],
  );
  chat.clear();
  assert.equal(chat.messages.length, 0);
});
