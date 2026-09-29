const test = require('node:test');
const assert = require('node:assert/strict');
const { validateSettings, offlineAccount } = require('../electron/settings.cjs');
const { allowed, checkURL, mavenPath, redact, extract } = require('../electron/runtime.cjs');
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
test('library rules follow the target platform', () => {
  const macOnly = { rules: [{ action: 'allow', os: { name: 'osx' } }] };
  const notMac = { rules: [{ action: 'allow' }, { action: 'disallow', os: { name: 'osx' } }] };
  assert.equal(allowed(macOnly, 'osx'), true);
  assert.equal(allowed(macOnly, 'windows'), false);
  assert.equal(allowed(notMac, 'windows'), true);
  assert.equal(allowed(notMac, 'osx'), false);
});
test('zip extraction writes files and rejects paths outside the target', async () => {
  const fs = require('node:fs'),
    os = require('node:os'),
    path = require('node:path');
  const { zipSync } = require('fflate');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'antagon-'));
  const good = path.join(dir, 'good.zip');
  fs.writeFileSync(good, zipSync({ 'jre/bin/java.exe': new Uint8Array([1, 2]) }));
  await extract(good, path.join(dir, 'out'));
  assert.deepEqual([...fs.readFileSync(path.join(dir, 'out/jre/bin/java.exe'))], [1, 2]);
  const evil = path.join(dir, 'evil.zip');
  fs.writeFileSync(evil, zipSync({ '../escape.txt': new Uint8Array([1]) }));
  await assert.rejects(extract(evil, path.join(dir, 'out2')));
  assert.equal(fs.existsSync(path.join(dir, 'escape.txt')), false);
  fs.rmSync(dir, { recursive: true, force: true });
});
