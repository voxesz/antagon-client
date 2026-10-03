const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { atomicWrite } = require('./files.cjs');
const clean = (s) =>
  String(s ?? '')
    .slice(0, 500)
    .replace(/[\\\r\n\t]/g, (c) => ({ '\\': '\\\\', '\r': '', '\n': '\\n', '\t': '\\t' })[c]);

class RadioBridge {
  constructor(directory, command, resizeImage) {
    this.directory = directory;
    this.command = command;
    this.resizeImage = resizeImage;
    this.status = { state: 'off', volume: 65 };
    this.reportedAt = 0;
    this.art = '';
    this.artUrl = '';
    this.busy = false;
  }
  report(state, catalog) {
    if (!state || !['off', 'playing', 'paused', 'buffering', 'waiting', 'error'].includes(state.state)) return;
    const track = catalog?.tracks.find((t) => t.id === state.trackId);
    const collection = catalog?.collections.find((c) => c.id === state.collectionId);
    this.reportedAt = Date.now();
    this.status = {
      state: state.state,
      title: track?.title || '',
      artist: track?.artist || '',
      collection: collection?.name || '',
      mode: collection?.mode || '',
      position: Math.max(0, Math.min(Number(state.position) || 0, track?.durationMs / 1000 || 0)),
      duration: track?.durationMs / 1000 || 0,
      volume: Math.round(Math.max(0, Math.min(100, Number(state.volume) || 0))),
    };
    const url = track?.coverUrl || collection?.coverUrl || '';
    if (url !== this.artUrl) {
      this.artUrl = url;
      this.art = '';
      if (url) this.cacheArt(url).catch(() => {});
    }
  }
  async cacheArt(url) {
    // URLs originate from the validated catalog, never from game commands.
    const filename = `antagon-radio-art-${crypto.createHash('sha256').update(url).digest('hex').slice(0, 16)}.png`;
    const file = path.join(this.directory, filename);
    if (!fs.existsSync(file)) {
      const res = await fetch(url, { signal: AbortSignal.timeout(10000) });
      if (!res.ok || Number(res.headers.get('content-length')) > 2 * 1024 * 1024) return;
      const chunks = [];
      let length = 0;
      for await (const chunk of res.body) {
        length += chunk.length;
        if (length > 2 * 1024 * 1024) return;
        chunks.push(chunk);
      }
      const png = this.resizeImage(Buffer.concat(chunks));
      if (!png) return;
      atomicWrite(file, png);
    }
    if (this.artUrl === url) this.art = filename;
  }
  async sync() {
    if (this.busy) return;
    this.busy = true;
    try {
      const files = await fs.promises.readdir(this.directory);
      for (const name of files
        .filter((f) => /^antagon-radio-cmd-\d+\.txt$/.test(f))
        .sort()
        .slice(0, 30)) {
        const file = path.join(this.directory, name);
        const stat = await fs.promises.stat(file);
        let line = '';
        if (stat.size < 128 && Date.now() - stat.mtimeMs < 10000) line = await fs.promises.readFile(file, 'utf8');
        await fs.promises.unlink(file);
        const [action, raw] = line.trim().split('\t');
        if (['toggle', 'next', 'previous', 'stop'].includes(action)) this.command({ action });
        else if (action === 'volume' && /^\d{1,3}$/.test(raw || '') && Number(raw) <= 100)
          this.command({ action, value: Number(raw) });
      }
      const state = Date.now() - this.reportedAt < 5000 ? this.status : { state: 'off', volume: 65 };
      const lines = { ...state, updated: Date.now(), art: this.art };
      atomicWrite(
        path.join(this.directory, 'antagon-radio-state.properties'),
        Object.entries(lines)
          .map(([key, value]) => `${key}=${clean(value)}`)
          .join('\n') + '\n',
      );
    } catch (error) {
      if (error.code !== 'ENOENT') console.error('[ANTAGON] Radio bridge:', error.message);
    } finally {
      this.busy = false;
    }
  }
}
module.exports = { RadioBridge };
