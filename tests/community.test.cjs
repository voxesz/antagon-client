const test = require('node:test');
const assert = require('node:assert/strict');
const { Community, validateId } = require('../electron/community.cjs');

test('community errors do not become an empty friends list', async () => {
  const context = {
    me: { id: 'me' },
    db: { from: () => ({ select: async () => ({ data: null, error: { message: 'Offline' } }) }) },
  };
  await assert.rejects(Community.prototype.state.call(context), /carregar seus amigos/);
});

test('community skips profile queries when there are no friendships', async () => {
  let calls = 0;
  const context = {
    me: { id: 'me' },
    db: {
      from: () => {
        calls++;
        return { select: async () => ({ data: [], error: null }) };
      },
    },
  };
  assert.deepEqual(await Community.prototype.state.call(context), {
    me: context.me,
    friends: [],
    incoming: [],
    outgoing: [],
  });
  assert.equal(calls, 1);
});

test('friend identifiers cannot inject query filters', () => {
  validateId('0f8701dc-6dce-4155-8ad2-5c017c628883');
  for (const id of ['', 'me),or(id.eq.other', '../../path']) assert.throws(() => validateId(id));
});

test('community sign out clears only the local session after a presence failure', async () => {
  let stopped = false,
    scope;
  const context = {
    me: { id: 'me' },
    setActivity: async () => {
      throw Error('Offline');
    },
    stop: () => {
      stopped = true;
    },
    db: {
      auth: {
        signOut: async (options) => {
          scope = options.scope;
          return { error: null };
        },
      },
    },
  };
  await Community.prototype.signOut.call(context);
  assert.equal(context.me, null);
  assert.equal(scope, 'local');
  assert.equal(stopped, true);
});
