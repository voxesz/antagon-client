const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { Transform } = require('node:stream');
const { pipeline } = require('node:stream/promises');

function atomicWrite(file, contents) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const temporary = file + '.' + crypto.randomUUID() + '.tmp';
  try {
    fs.writeFileSync(temporary, contents, { mode: 0o600 });
    fs.renameSync(temporary, file);
  } finally {
    fs.rmSync(temporary, { force: true });
  }
}

async function hashFile(file, algorithm = 'sha1') {
  const hash = crypto.createHash(algorithm);
  for await (const chunk of fs.createReadStream(file)) hash.update(chunk);
  return hash.digest('hex');
}

async function writeDownload(response, file, { algorithm = 'sha1', expected, progress = () => {} } = {}) {
  const temporary = file + '.' + crypto.randomUUID() + '.part';
  const hash = crypto.createHash(algorithm);
  const total = Number(response.headers.get('content-length')) || 0;
  let bytes = 0,
    lastUpdate = 0;
  await fs.promises.mkdir(path.dirname(file), { recursive: true });
  const meter = new Transform({
    transform(chunk, encoding, callback) {
      hash.update(chunk);
      bytes += chunk.length;
      const now = Date.now();
      if (now - lastUpdate >= 100) {
        lastUpdate = now;
        progress(bytes, total);
      }
      callback(null, chunk);
    },
  });
  try {
    await pipeline(response.body, meter, fs.createWriteStream(temporary, { mode: 0o600 }));
    const actual = hash.digest('hex');
    if (expected && !(Array.isArray(expected) ? expected : [expected]).includes(actual))
      throw Error('Checksum inválido: ' + path.basename(file));
    await fs.promises.rename(temporary, file);
    progress(bytes, total);
  } finally {
    await fs.promises.rm(temporary, { force: true });
  }
}

async function readTail(file, limit = 16000) {
  const handle = await fs.promises.open(file, 'r');
  try {
    const { size } = await handle.stat();
    const buffer = Buffer.alloc(Math.min(size, limit));
    const { bytesRead } = await handle.read(buffer, 0, buffer.length, Math.max(0, size - limit));
    return buffer.subarray(0, bytesRead).toString('utf8');
  } finally {
    await handle.close();
  }
}

module.exports = { atomicWrite, hashFile, writeDownload, readTail };
