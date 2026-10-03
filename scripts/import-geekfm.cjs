// One-off, resumable import of the local GeekFM files. No credentials are saved.
// Prerequisites: build/geekfm-import-plan.json (ffprobe + complete decode checks)
// and an authenticated Supabase CLI. Preview by default; --publish uploads.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const assert = require('node:assert/strict');
const { createClient } = require('@supabase/supabase-js');
const { audioFile, png } = require('../electron/radio.cjs');
const root = path.resolve(__dirname, '..');
const project = 'pnlemlqvuqftantunwcw';
const url = `https://${project}.supabase.co`;
const cli = process.env.SUPABASE_CLI || 'supabase';
const stateFile = path.join(root, 'build/geekfm-import-state.json');
const plan = JSON.parse(fs.readFileSync(path.join(root, 'build/geekfm-import-plan.json'), 'utf8'));
const hash = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');
const uuid = (value) => {
  const h = hash(Buffer.from(`antagon-geekfm-v1:${value}`));
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-5${h.slice(13, 16)}-a${h.slice(17, 20)}-${h.slice(20, 32)}`;
};
// These are DISTINCT songs. Their supplied files currently have identical audio
// to another song, so keep their reference slots pending a corrected file.
// A replacement with different audio becomes importable on the next plan scan.
const needsCorrectAudio = new Map(
  [
    [2, 27],
    [72, 34],
  ].filter(
    ([a, b]) =>
      plan.tracks.find((t) => t.position === a)?.audioSha256 === plan.tracks.find((t) => t.position === b)?.audioSha256,
  ),
);
const tracks = plan.tracks
  .filter((t) => !needsCorrectAudio.has(t.position))
  .map((t) => ({
    ...t,
    id: uuid(t.sha256),
    mediaKey: `tracks/${uuid(t.sha256)}.mp3`,
    coverKey: `covers/${uuid(`cover:${t.sha256}`)}.png`,
  }));
for (const t of tracks) {
  assert(t.titleMatches && t.decodeValid, `Invalid audio at position ${t.position}`);
  assert(t.title.length <= 120 && t.artist.length <= 120);
  assert(t.durationMs >= 1000 && t.durationMs <= 3600000);
  assert.equal(hash(audioFile(fs.readFileSync(t.path), 'mp3')), t.sha256);
  png(fs.readFileSync(t.coverPath));
}
function runCli(args) {
  try {
    return JSON.parse(
      execFileSync(cli, [...args, '--output', 'json'], {
        cwd: root,
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'pipe'],
        timeout: 90000,
      }),
    );
  } catch (error) {
    // execFile errors include captured output. Never propagate API key output.
    throw Error(`Supabase CLI failed (${error.code || error.status || 'unknown'}): ${args.slice(0, 2).join(' ')}`);
  }
}
function findServiceKey(value) {
  if (!value || typeof value !== 'object') return null;
  if (value.name === 'service_role' && typeof value.api_key === 'string' && value.api_key.startsWith('eyJ'))
    return value.api_key;
  for (const nested of Object.values(value)) {
    const match = typeof nested === 'object' && findServiceKey(nested);
    if (match) return match;
  }
  return null;
}
const quote = (value) => `'${String(value).replaceAll("'", "''")}'`;
const state = fs.existsSync(stateFile) ? JSON.parse(fs.readFileSync(stateFile, 'utf8')) : { project, files: {} };
assert.equal(state.project, project);
function checkpoint() {
  fs.writeFileSync(`${stateFile}.tmp`, JSON.stringify(state, null, 2) + '\n');
  fs.renameSync(`${stateFile}.tmp`, stateFile);
}
async function catalog() {
  const source = fs.readFileSync(path.join(root, 'electron/community.cjs'), 'utf8');
  const key = source.match(/const SUPABASE_KEY = '([^']+)'/)[1];
  const res = await fetch(`${url}/rest/v1/rpc/radio_catalog`, {
    method: 'POST',
    headers: { apikey: key, 'content-type': 'application/json' },
    body: '{}',
    signal: AbortSignal.timeout(20000),
  });
  assert(res.ok, `Catalog HTTP ${res.status}`);
  return res.json();
}
async function main() {
  console.log(
    JSON.stringify({
      ready: tracks.length,
      needsCorrectAudio: [...needsCorrectAudio],
      missing: plan.missing.map((t) => t.position),
      publish: process.argv.includes('--publish'),
    }),
  );
  if (!process.argv.includes('--publish') && !process.argv.includes('--upload-only')) return;
  const before = await catalog();
  fs.writeFileSync(path.join(root, 'build/geekfm-catalog-before.json'), JSON.stringify(before, null, 2));
  const collections = ['radio-geekfm', 'playlist-geekfm'].map((id) => {
    const c = before.collections.find((c) => c.id === id);
    assert(c, `Collection missing: ${id}`);
    assert(
      c.trackIds.every((id) => tracks.some((t) => t.id === id)),
      'Collection has other tracks; review before replacing',
    );
    return c;
  });
  const serviceKey = findServiceKey(runCli(['projects', 'api-keys', '--project-ref', project, '--reveal']));
  assert(serviceKey, 'CLI did not return the service key for this project');
  const storage = createClient(url, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
    global: { fetch: (input, init = {}) => fetch(input, { ...init, signal: AbortSignal.timeout(120000) }) },
  }).storage.from('radio-media');
  async function upload(key, file, type) {
    const bytes = fs.readFileSync(file),
      sha = hash(bytes);
    if (state.files[key]?.sha256 === sha) return;
    // Reconcile a completed upload if the process stopped before checkpointing.
    const publicUrl = storage.getPublicUrl(key).data.publicUrl;
    const head = await fetch(publicUrl, { method: 'HEAD', signal: AbortSignal.timeout(20000) });
    if (head.ok) {
      const res = await fetch(publicUrl, { signal: AbortSignal.timeout(120000) });
      assert(res.ok && hash(Buffer.from(await res.arrayBuffer())) === sha, 'Existing media differs');
    } else {
      assert([400, 404].includes(head.status), `Storage check HTTP ${head.status}`);
      let lastError;
      for (let attempt = 0; attempt < 3; attempt++) {
        const { error } = await storage.upload(key, bytes, {
          contentType: type,
          cacheControl: '31536000',
          upsert: false,
        });
        if (!error) {
          lastError = null;
          break;
        }
        lastError = error;
        if (String(error.statusCode) === '409' || /already exists/i.test(error.message)) {
          const res = await fetch(publicUrl, { signal: AbortSignal.timeout(120000) });
          if (res.ok && hash(Buffer.from(await res.arrayBuffer())) === sha) {
            lastError = null;
            break;
          }
        }
        await new Promise((resolve) => setTimeout(resolve, 1000 * (attempt + 1)));
      }
      if (lastError) throw Error(`Upload failed: ${key} (${lastError.statusCode || 'network'})`);
    }
    state.files[key] = { sha256: sha, bytes: bytes.length };
    checkpoint();
  }
  let next = 0,
    complete = 0;
  await Promise.all(
    Array.from({ length: 3 }, async () => {
      while (next < tracks.length) {
        const t = tracks[next++];
        await upload(t.mediaKey, t.path, 'audio/mpeg');
        await upload(t.coverKey, t.coverPath, 'image/png');
        console.log(`Uploaded ${++complete}/${tracks.length}: ${t.position}. ${t.title}`);
      }
    }),
  );
  if (process.argv.includes('--upload-only')) {
    console.log('Audio and covers uploaded. Catalog publication pending.');
    return;
  }
  const ids = `array[${tracks.map((t) => `${quote(t.id)}::uuid`).join(',')}]`;
  // Publish all metadata and both queues together. Use the existing RPCs so
  // media validation, revision checks, and the shared radio clock still apply.
  const sql = `begin;
do $geekfm_import$
declare owner_id uuid;
begin
  select p.id into strict owner_id from public.profiles p join public.admin_roles a on a.user_id = p.id
    where a.is_owner and p.mc_uuid = '670ffb629dfc42768a228f51b90691bb';
  perform set_config('request.jwt.claim.sub', owner_id::text, true);
  perform public.require_antagon_admin();
${tracks
  .map(
    (t) => `  if not exists (select 1 from public.radio_tracks where id = ${quote(t.id)}::uuid) then
    perform public.radio_add_track(${quote(t.id)}::uuid, ${quote(t.title)}, ${quote(t.artist)}, 'Geek', ${t.durationMs}, ${quote(t.mediaKey)}, ${quote(t.coverKey)}, 'Arquivo de áudio e capa fornecidos pelo administrador.');
  end if;`,
  )
  .join('\n')}
${collections.map((c) => `  perform public.radio_save_collection(${quote(c.id)}, ${quote(c.mode)}, ${quote(c.name)}, ${quote(c.description)}, ${quote(c.genre)}, ${quote(c.coverKey || tracks[0].coverKey)}, ${ids}, ${quote(c.revision)}::timestamptz);`).join('\n')}
end $geekfm_import$;
commit;
select id, cardinality(track_ids) as track_count from public.radio_collections where id in ('radio-geekfm','playlist-geekfm');`;
  const sqlFile = path.join(root, 'build/geekfm-publish.sql');
  fs.writeFileSync(sqlFile, sql);
  const result = runCli(['db', 'query', '--linked', '--file', sqlFile]);
  console.log(JSON.stringify({ published: result.rows || result }));
  const after = await catalog();
  for (const c of collections)
    assert.deepEqual(
      after.collections.find((a) => a.id === c.id).trackIds,
      tracks.map((t) => t.id),
    );
  const live = after.collections.find((c) => c.id === 'radio-geekfm');
  assert(live.schedules.some((s) => JSON.stringify(s.trackIds) === JSON.stringify(tracks.map((t) => t.id))));
  for (const t of tracks) {
    const remote = after.tracks.find((r) => r.id === t.id);
    assert(
      remote && remote.durationMs === t.durationMs && remote.mediaKey === t.mediaKey && remote.coverKey === t.coverKey,
    );
  }
  state.publishedAt = new Date().toISOString();
  state.tracks = tracks.map(({ position, id, mediaKey, coverKey, sha256, audioSha256, durationMs }) => ({
    position,
    id,
    mediaKey,
    coverKey,
    sha256,
    audioSha256,
    durationMs,
  }));
  state.needsCorrectAudio = [...needsCorrectAudio].map(([position, sameAudioAs]) => ({ position, sameAudioAs }));
  state.missing = plan.missing.map((t) => t.position);
  checkpoint();
  fs.writeFileSync(path.join(root, 'build/geekfm-catalog-after.json'), JSON.stringify(after, null, 2));
  console.log(`Verified: ${tracks.length} tracks in both GeekFM collections.`);
}
main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
