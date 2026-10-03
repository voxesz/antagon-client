const crypto = require('node:crypto');
const MAX_AUDIO = 50 * 1024 * 1024;
const FORMATS = { mp3: 'audio/mpeg', m4a: 'audio/mp4', ogg: 'audio/ogg', wav: 'audio/wav' };
const COVER_KEY = /^covers\/[a-f0-9-]{36}\.png$/;
const MEDIA_KEY = /^tracks\/[a-f0-9-]{36}\.(mp3|m4a|ogg|wav)$/;

function text(value, max, label, optional = false) {
  if (typeof value !== 'string' || value.trim().length > max || (!optional && !value.trim()))
    throw Error(`${label} inválido.`);
  return value.trim();
}
function bytes(value, limit) {
  if (!(value instanceof Uint8Array) || !value.length || value.length > limit)
    throw Error('Arquivo vazio ou acima do tamanho permitido.');
  return Buffer.from(value);
}
function png(value) {
  const b = bytes(value, 2 * 1024 * 1024);
  if (
    b.length < 33 ||
    b.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a' ||
    b.readUInt32BE(16) > 1024 ||
    b.readUInt32BE(20) > 1024 ||
    !b.readUInt32BE(16) ||
    !b.readUInt32BE(20)
  )
    throw Error('Use uma capa PNG de até 1024 × 1024 pixels.');
  return b;
}
function audioFile(value, extension) {
  if (!Object.hasOwn(FORMATS, extension)) throw Error('Use MP3, M4A, OGG ou WAV.');
  const b = bytes(value, MAX_AUDIO);
  const valid =
    b.length >= 12 &&
    ((extension === 'mp3' && (b.subarray(0, 3).toString() === 'ID3' || (b[0] === 255 && (b[1] & 224) === 224))) ||
      (extension === 'm4a' && b.subarray(4, 8).toString() === 'ftyp') ||
      (extension === 'ogg' && b.subarray(0, 4).toString() === 'OggS') ||
      (extension === 'wav' && b.subarray(0, 4).toString() === 'RIFF' && b.subarray(8, 12).toString() === 'WAVE'));
  if (!valid) throw Error('O arquivo não corresponde ao formato de áudio escolhido.');
  return b;
}

class RadioService {
  constructor(community) {
    this.community = community;
    this.db = community.db;
    this.bucket = this.db.storage.from('radio-media');
  }
  async admin() {
    if (!this.community.me || !(await this.community.requireActive()).isAdmin)
      throw Error('Somente administradores podem editar a rádio.');
  }
  url(key, cover = false) {
    if (!(cover ? COVER_KEY : MEDIA_KEY).test(key || '')) return '';
    return this.bucket.getPublicUrl(key).data.publicUrl;
  }
  async catalog() {
    const start = performance.now();
    const { data, error } = await this.db.rpc('radio_catalog');
    if (error) {
      if (['PGRST202', 'PGRST205', '42P01'].includes(error.code))
        throw Error('A rádio está em preparação. O catálogo ainda não está disponível.');
      throw Error('Não foi possível atualizar a rádio. Confira sua conexão e tente novamente.');
    }
    const elapsed = performance.now() - start;
    // Reject a clock sample too old to join a synchronized broadcast reliably.
    if (
      elapsed > 10000 ||
      !Number.isFinite(data?.serverTime) ||
      !Array.isArray(data?.tracks) ||
      !Array.isArray(data?.collections)
    )
      throw Error('A conexão está lenta. Tente atualizar a rádio novamente.');
    this.latest = {
      ...data,
      serverTime: data.serverTime + elapsed / 2,
      tracks: data.tracks.map((t) => ({ ...t, url: this.url(t.mediaKey), coverUrl: this.url(t.coverKey, true) })),
      collections: data.collections.map((c) => ({ ...c, coverUrl: this.url(c.coverKey, true) })),
    };
    return this.latest;
  }
  async upload(key, buffer, type) {
    const { error } = await this.bucket.upload(key, buffer, {
      contentType: type,
      cacheControl: '31536000',
      upsert: false,
    });
    if (error) throw Error('Não foi possível enviar o arquivo: ' + error.message);
    return key;
  }
  async cover(buffer) {
    if (!buffer) return null;
    return this.upload(`covers/${crypto.randomUUID()}.png`, png(buffer), 'image/png');
  }
  async addTrack(input) {
    await this.admin();
    const title = text(input?.title, 120, 'Título'),
      artist = text(input?.artist, 120, 'Artista');
    const genre = text(input?.genre, 40, 'Estilo'),
      credits = text(input?.credits || '', 500, 'Créditos', true);
    if (!Number.isInteger(input.durationMs) || input.durationMs < 1000 || input.durationMs > 3600000)
      throw Error('A música deve ter entre 1 segundo e 1 hora.');
    const buffer = audioFile(input.audio, input.extension);
    if (input.cover) png(input.cover);
    const id = crypto.randomUUID(),
      uploaded = [];
    try {
      const media = await this.upload(`tracks/${id}.${input.extension}`, buffer, FORMATS[input.extension]);
      uploaded.push(media);
      const cover = await this.cover(input.cover);
      if (cover) uploaded.push(cover);
      const { error } = await this.db.rpc('radio_add_track', {
        p_id: id,
        p_title: title,
        p_artist: artist,
        p_genre: genre,
        p_duration: input.durationMs,
        p_media: media,
        p_cover: cover,
        p_credits: credits,
      });
      if (error) throw Error(error.message);
      return id;
    } catch (error) {
      // RLS protects files referenced by a committed record if the RPC response was lost.
      if (uploaded.length) await this.bucket.remove(uploaded).catch(() => {});
      throw error;
    }
  }
  async saveCollection(input) {
    await this.admin();
    const id = input?.id || crypto.randomUUID();
    if (!/^[a-z0-9_-]{1,60}$/.test(id) || !['radio', 'playlist'].includes(input?.mode))
      throw Error('Seleção inválida.');
    const name = text(input.name, 80, 'Nome'),
      genre = text(input.genre, 40, 'Estilo');
    const description = text(input.description || '', 240, 'Descrição', true);
    if (
      !Array.isArray(input.trackIds) ||
      input.trackIds.length > 300 ||
      input.trackIds.some((t) => typeof t !== 'string' || !/^[a-f0-9-]{36}$/.test(t))
    )
      throw Error('Fila inválida.');
    let cover = input.coverKey || null,
      uploaded;
    if (cover && !COVER_KEY.test(cover)) throw Error('Capa inválida.');
    if (input.cover) cover = uploaded = await this.cover(input.cover);
    try {
      const { data, error } = await this.db.rpc('radio_save_collection', {
        p_id: id,
        p_mode: input.mode,
        p_name: name,
        p_description: description,
        p_genre: genre,
        p_cover: cover,
        p_tracks: input.trackIds,
        p_revision: input.revision || null,
      });
      if (error) throw Error(error.message);
      return { id, ...data };
    } catch (error) {
      if (uploaded) await this.bucket.remove([uploaded]).catch(() => {});
      throw error;
    }
  }
  async deleteCollection(id) {
    await this.admin();
    const { error } = await this.db.rpc('radio_delete_collection', { p_id: String(id) });
    if (error) throw Error(error.message);
  }
  async deleteTrack(id) {
    await this.admin();
    const { data, error } = await this.db.rpc('radio_delete_track', { p_id: String(id) });
    if (error) throw Error(error.message);
    if (data?.length) await this.bucket.remove(data);
  }
}
module.exports = { RadioService, audioFile, png };
