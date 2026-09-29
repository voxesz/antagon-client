const test = require('node:test');
const assert = require('node:assert/strict');
const { validateSettings, offlineAccount } = require('../electron/settings.cjs');
const { allowed, checkURL, mavenPath, redact } = require('../electron/runtime.cjs');
test('offline UUID matches Minecraft name based UUID', () => {
  assert.equal(offlineAccount('Notch').id, 'b50ad385829d3141a2167e7d7539ba7f');
  assert.equal(offlineAccount('Notch').accessToken, '0');
  assert.equal(offlineAccount('Notch').offline, true);
});
test('invalid names cannot become command arguments', () => {
  for (const n of ['abc def', '../../abc', 'ab', 'abcdefghijklmnopq', '--accessToken'])
    assert.throws(() => offlineAccount(n));
});
test('settings accept known fields and clamp game limits', () => {
  const x = validateSettings({ memory: 80, nickname: 'abc;pwd', javaPath: '/tmp/bad', fullscreen: 'yes' });
  assert.equal(x.memory, 8);
  assert.equal(x.nickname, 'Player');
  assert.equal(x.javaPath, void 0);
  assert.equal(x.fullscreen, false);
});
test('library rules honor allow and deny on macOS', () => {
  assert.equal(allowed({}), true);
  assert.equal(allowed({ rules: [{ action: 'allow', os: { name: 'windows' } }] }), false);
  assert.equal(allowed({ rules: [{ action: 'allow' }, { action: 'disallow', os: { name: 'osx' } }] }), false);
  assert.equal(allowed({ rules: [{ action: 'allow', os: { name: 'osx' } }] }), true);
});
test('only official HTTPS download origins are permitted', () => {
  assert.equal(checkURL('https://libraries.minecraft.net/a.jar'), 'https://libraries.minecraft.net/a.jar');
  for (const u of [
    'http://libraries.minecraft.net/a.jar',
    'https://evil.test/a.jar',
    'file:///etc/passwd',
    'https://libraries.minecraft.net.evil.test/a',
  ])
    assert.throws(() => checkURL(u));
});
test('tokens are removed from logs', () => {
  const secret = 'sensitive.token.123';
  assert.equal(redact('foo ' + secret, { accessToken: secret }), 'foo [TOKEN]');
  assert.equal(redact('--accessToken abc --uuid xyz', {}), '--accessToken [TOKEN] --uuid xyz');
});
test('Maven coordinates become expected library path', () => {
  assert.equal(mavenPath('org.ow2.asm:asm-all:5.0.3'), 'org/ow2/asm/asm-all/5.0.3/asm-all-5.0.3.jar');
});
