const fs = require('node:fs');
const path = require('node:path');
const { atomicWrite } = require('./files.cjs');
const UUID = /^[0-9a-f]{32}$/;
const ITEM = /^[a-z0-9_]{1,40}$/;
class CosmeticsBridge {
  constructor(game, community, image, now = Date.now) {
    Object.assign(this, { game, community, image, now });
    this.cache = new Map();
    this.images = new Map();
    this.queue = [];
    this.downloading = 0;
    this.lastFetch = 0;
    this.lastWrite = 0;
  }
  texture(id) {
    if (this.images.has(id)) return this.images.get(id);
    const promise = new Promise((resolve) => this.queue.push({ id, resolve }));
    this.images.set(id, promise);
    this.drain();
    return promise;
  }
  drain() {
    while (this.downloading < 4 && this.queue.length) {
      const { id, resolve } = this.queue.shift();
      this.downloading++;
      Promise.resolve()
        .then(() => this.image(id))
        .then(
          () => resolve(true),
          () => resolve(false),
        )
        .finally(() => {
          this.images.delete(id);
          this.downloading--;
          this.drain();
        });
    }
  }
  write(ids) {
    const lines = [];
    for (const id of ids) {
      const entry = this.cache.get(id);
      if (!entry || this.now() - entry.at > 120000) continue;
      const row = entry.row,
        tokens = ['client'];
      if (ITEM.test(row.cape || '')) tokens.push('cape:' + row.cape);
      if (ITEM.test(row.hat || '')) tokens.push('hat:' + row.hat);
      if (row.is_admin) tokens.push('admin');
      lines.push(`${id}=${tokens.join(',')}`);
    }
    const text = lines.join('\n') + '\n';
    if (
      text !== this.lastText ||
      this.now() - this.lastWrite >= 30000 ||
      !fs.existsSync(path.join(this.game, 'antagon-cosmetics.properties'))
    ) {
      fs.mkdirSync(this.game, { recursive: true });
      atomicWrite(path.join(this.game, 'antagon-cosmetics.properties'), text);
      this.lastText = text;
      this.lastWrite = this.now();
    }
  }
  async sync({ force = false, ownOnly = false, waitForImages = false } = {}) {
    if (!this.community.me) return;
    if (this.busy) {
      if (force) this.refreshPending = true;
      return;
    }
    force = force || this.refreshPending;
    this.refreshPending = false;
    this.busy = true;
    try {
      const owner = this.community.me.id;
      if (this.owner !== owner) {
        this.owner = owner;
        this.cache.clear();
        this.lastKey = null;
      }
      const roster = ownOnly
        ? ''
        : await fs.promises.readFile(path.join(this.game, 'antagon-players.txt'), 'utf8').catch(() => '');
      const ids = [
        ...new Set([this.community.me.mcUuid, ...roster.split(/\s+/)].filter((id) => UUID.test(id || ''))),
      ].slice(0, 100);
      const key = ids.join(',');
      // Returning players can use fresh cached data while the server refreshes.
      this.write(ids);
      if (!force && key === this.lastKey && this.now() - this.lastFetch < 15000) return;
      if (!force && key === this.attemptKey && this.now() - this.attemptAt < 2000) return;
      this.attemptKey = key;
      this.attemptAt = this.now();
      const rows = await this.community.visibleCosmetics(ids);
      if (this.community.me?.id !== owner) return;
      for (const id of ids) this.cache.delete(id);
      for (const row of rows)
        if (row.active_client && ids.includes(row.mc_uuid)) this.cache.set(row.mc_uuid, { row, at: this.now() });
      for (const [id, entry] of this.cache) if (this.now() - entry.at > 120000) this.cache.delete(id);
      this.lastKey = key;
      this.lastFetch = this.now();
      // Tags and hats are available before a remote cape finishes downloading.
      this.write(ids);
      const images = [...new Set(rows.map((row) => row.cape).filter((id) => /^custom_[a-f0-9]{16}$/.test(id || '')))];
      const pending = images.map((id) => this.texture(id));
      if (waitForImages) await Promise.all(pending);
    } finally {
      this.busy = false;
    }
  }
}
module.exports = { CosmeticsBridge };
