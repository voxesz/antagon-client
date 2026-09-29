const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { atomicWrite, writeDownload, hashFile, readTail } = require('../electron/files.cjs');

async function fixture(t) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'antagon-files-'));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  return { directory, file: path.join(directory, 'data.bin') };
}

test('downloads verify checksums before replacing an existing file', async (t) => {
  const { directory, file } = await fixture(t);
  atomicWrite(file, 'previous');
  await assert.rejects(writeDownload(new Response('corrupt'), file, { expected: 'bad' }), /Checksum/);
  assert.equal(await fs.readFile(file, 'utf8'), 'previous');
  assert.deepEqual(await fs.readdir(directory), ['data.bin']);
  const expected = crypto.createHash('sha256').update('verified').digest('hex');
  await writeDownload(new Response('verified'), file, { algorithm: 'sha256', expected });
  assert.equal(await hashFile(file, 'sha256'), expected);
});

test('interrupted downloads release files and remove partial data', async (t) => {
  const { directory, file } = await fixture(t);
  const body = new ReadableStream({
    pull(controller) {
      controller.error(Error('Disconnected'));
    },
  });
  await assert.rejects(writeDownload(new Response(body), file), /Disconnected/);
  assert.deepEqual(await fs.readdir(directory), []);
});

test('log diagnostics only return the requested tail', async (t) => {
  const { file } = await fixture(t);
  atomicWrite(file, 'old messages\nlatest');
  assert.equal(await readTail(file, 6), 'latest');
});
