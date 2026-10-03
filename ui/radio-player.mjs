import { livePosition } from './radio-model.mjs';

export class RadioPlayer {
  constructor(audio, { clock = () => performance.now(), change = () => {} } = {}) {
    this.audio = audio;
    this.clock = clock;
    this.change = change;
    this.catalog = { collections: [], tracks: [] };
    this.tracks = new Map();
    this.selected = null;
    this.index = 0;
    this.track = null;
    this.key = null;
    this.wantPlay = false;
    this.ready = false;
    this.error = '';
    this.state = 'off';
    this.generation = 0;
    this.audio.volume = 0.65;
    this.audio.preload = 'auto';
    audio.addEventListener('loadedmetadata', () => this.loaded());
    audio.addEventListener('ended', () => {
      if (!this.wantPlay) return;
      if (this.collection?.mode === 'radio') this.tick();
      else if (this.index + 1 < (this.collection?.trackIds.length || 0)) this.next();
      else {
        this.wantPlay = false;
        this.state = 'paused';
        this.change(this.snapshot());
      }
    });
    audio.addEventListener('playing', () => {
      if (this.wantPlay) {
        this.state = 'playing';
        this.error = '';
        this.change(this.snapshot());
      }
    });
    audio.addEventListener('waiting', () => {
      if (this.wantPlay) {
        this.state = 'buffering';
        this.change(this.snapshot());
      }
    });
    audio.addEventListener('error', () => {
      if (this.track) this.fail('Não foi possível reproduzir esta música. Tente novamente.');
    });
  }
  get collection() {
    return this.catalog.collections.find((c) => c.id === this.selected);
  }
  get now() {
    return this.serverTime + this.clock() - this.sampleAt;
  }
  setCatalog(catalog) {
    this.catalog = catalog;
    this.tracks = new Map(catalog.tracks.map((t) => [t.id, t]));
    this.serverTime = catalog.serverTime;
    this.sampleAt = this.clock();
    if (this.selected && !this.collection) this.stop();
    if (this.collection?.mode === 'playlist' && this.track) {
      const nextIndex = this.collection.trackIds.indexOf(this.track.id);
      if (nextIndex < 0) this.stop();
      else this.index = nextIndex;
    }
    this.tick();
  }
  select(id, index = 0) {
    const collection = this.catalog.collections.find((c) => c.id === id);
    if (!collection) return;
    if (collection.mode === 'playlist' && (!Number.isInteger(index) || !collection.trackIds[index])) return;
    this.audio.pause();
    this.generation++;
    this.selected = id;
    this.index = index;
    this.key = null;
    this.error = '';
    this.wantPlay = true;
    if (collection.mode === 'radio') this.tick();
    else this.load(this.tracks.get(collection.trackIds[index]), `playlist:${index}`);
    this.change(this.snapshot());
  }
  load(track, key) {
    if (!track?.url) {
      this.fail('Esta seleção ainda não tem músicas disponíveis.');
      return;
    }
    this.generation++;
    this.audio.pause();
    this.track = track;
    this.key = key;
    this.ready = false;
    this.state = 'buffering';
    this.error = '';
    this.audio.src = track.url;
    this.audio.load();
    this.change(this.snapshot());
  }
  loaded() {
    if (!this.track || this.audio.getAttribute('src') !== this.track.url) return;
    this.ready = true;
    if (this.collection?.mode === 'radio') {
      const live = livePosition(this.collection, this.tracks, this.now);
      if (!live || live.key !== this.key) {
        this.tick();
        return;
      }
      this.audio.currentTime = live.offset;
    }
    if (this.wantPlay) this.play();
  }
  async play() {
    const generation = this.generation;
    try {
      await this.audio.play();
      if (generation === this.generation && !this.wantPlay) this.audio.pause();
    } catch (error) {
      if (generation === this.generation && this.wantPlay && error.name !== 'AbortError')
        this.fail('O áudio não iniciou. Clique em ouvir para tentar novamente.');
    }
  }
  toggle() {
    if (!this.collection) return;
    if (this.wantPlay) {
      this.wantPlay = false;
      this.audio.pause();
      this.state = 'paused';
    } else {
      this.wantPlay = true;
      this.error = '';
      if (this.collection.mode === 'radio') {
        this.key = null; // Rejoin the current point; never resume yesterday's buffer.
        this.tick();
      } else if (!this.ready || this.audio.error) this.load(this.track, this.key);
      else {
        if (this.audio.ended) this.audio.currentTime = 0;
        this.play();
      }
    }
    this.change(this.snapshot());
  }
  next(delta = 1) {
    if (this.collection?.mode !== 'playlist') return;
    const count = this.collection.trackIds.length;
    if (count) this.select(this.selected, (this.index + delta + count) % count);
  }
  seek(seconds) {
    if (this.collection?.mode !== 'playlist' || !this.ready || !Number.isFinite(seconds)) return;
    this.audio.currentTime = Math.max(0, Math.min(seconds, this.audio.duration || this.track.durationMs / 1000));
    this.change(this.snapshot());
  }
  volume(value) {
    if (Number.isFinite(value)) this.audio.volume = Math.max(0, Math.min(1, value));
    this.change(this.snapshot());
  }
  fail(message) {
    this.wantPlay = false;
    this.audio.pause();
    this.error = message;
    this.state = 'error';
    this.change(this.snapshot());
  }
  stop() {
    this.generation++;
    this.wantPlay = false;
    this.audio.pause();
    this.audio.removeAttribute('src');
    this.audio.load();
    this.track = null;
    this.key = null;
    this.selected = null;
    this.state = 'off';
    this.change(this.snapshot());
  }
  tick() {
    if (this.collection?.mode === 'radio' && this.wantPlay) {
      if (this.clock() - this.sampleAt > 90000) {
        this.fail('Sem conexão com a rádio. Atualize para voltar ao vivo.');
        return;
      }
      const live = livePosition(this.collection, this.tracks, this.now);
      if (!live) {
        this.audio.pause();
        this.track = null;
        this.key = null;
        this.state = 'waiting';
      } else if (this.key !== live.key) this.load(live.track, live.key);
      else if (this.ready && Math.abs(this.audio.currentTime - live.offset) > 1.5) this.audio.currentTime = live.offset;
    }
    this.change(this.snapshot());
  }
  snapshot() {
    return {
      state: this.state,
      error: this.error,
      listening: this.wantPlay,
      collection: this.collection || null,
      track: this.track,
      position: this.track ? this.audio.currentTime || 0 : 0,
      duration: this.track?.durationMs / 1000 || 0,
      volume: Math.round(this.audio.volume * 100),
    };
  }
}
