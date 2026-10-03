-- Audio lives in Storage. Only metadata and the shared broadcast clock live here.
create table public.radio_tracks (
  id uuid primary key,
  title text not null check (char_length(title) between 1 and 120),
  artist text not null check (char_length(artist) between 1 and 120),
  genre text not null check (char_length(genre) between 1 and 40),
  duration_ms integer not null check (duration_ms between 1000 and 3600000),
  media_key text not null unique check (media_key ~ '^tracks/[a-f0-9-]{36}\.(mp3|m4a|ogg|wav)$'),
  cover_key text check (cover_key ~ '^covers/[a-f0-9-]{36}\.png$'),
  credits text not null default '' check (char_length(credits) <= 500),
  created_at timestamptz not null default now()
);
create table public.radio_collections (
  id text primary key check (id ~ '^[a-z0-9_-]{1,60}$'),
  mode text not null check (mode in ('radio', 'playlist')),
  name text not null check (char_length(name) between 1 and 80),
  description text not null default '' check (char_length(description) <= 240),
  genre text not null check (char_length(genre) between 1 and 40),
  cover_key text check (cover_key ~ '^covers/[a-f0-9-]{36}\.png$'),
  track_ids uuid[] not null default '{}',
  updated_at timestamptz not null default now(),
  check (cardinality(track_ids) <= 300)
);
create table public.radio_schedules (
  id bigint generated always as identity primary key,
  collection_id text not null references public.radio_collections(id) on delete cascade,
  starts_at timestamptz not null,
  track_ids uuid[] not null,
  unique (collection_id, starts_at)
);
alter table public.radio_tracks enable row level security;
alter table public.radio_collections enable row level security;
alter table public.radio_schedules enable row level security;
revoke all on public.radio_tracks, public.radio_collections, public.radio_schedules from anon, authenticated;

insert into public.radio_collections (id, mode, name, genre, description) values
  ('antagon', 'radio', 'Rádio Antagon', 'Mix', 'Vários estilos. Uma frequência. Todo mundo junto.'),
  ('radio-lofi', 'radio', 'Lo-fi Radio', 'Lo-fi', 'Uma frequência para desacelerar.'),
  ('radio-phonk', 'radio', 'Phonk Radio', 'Phonk', 'Graves para entrar no ritmo.'),
  ('radio-electronic', 'radio', 'Electronic Radio', 'Eletrônica', 'Energia para a próxima partida.'),
  ('playlist-lofi', 'playlist', 'Depois da meia-noite', 'Lo-fi', 'Seu tempo, seu ritmo.'),
  ('playlist-phonk', 'playlist', 'No limite', 'Phonk', 'Uma seleção para manter o foco.'),
  ('playlist-electronic', 'playlist', 'Próxima fase', 'Eletrônica', 'Dê play e siga em frente.');

create function public.radio_catalog() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
begin
  if auth.uid() is not null then perform public.require_antagon_active(); end if;
  return jsonb_build_object(
    'serverTime', floor(extract(epoch from statement_timestamp()) * 1000),
    'tracks', coalesce((select jsonb_agg(jsonb_build_object(
      'id', t.id, 'title', t.title, 'artist', t.artist, 'genre', t.genre,
      'durationMs', t.duration_ms, 'mediaKey', t.media_key, 'coverKey', t.cover_key,
      'credits', t.credits) order by t.created_at) from public.radio_tracks t), '[]'::jsonb),
    'collections', coalesce((select jsonb_agg(jsonb_build_object(
      'id', c.id, 'mode', c.mode, 'name', c.name, 'description', c.description,
      'genre', c.genre, 'coverKey', c.cover_key, 'trackIds', c.track_ids,
      'revision', c.updated_at,
      'schedules', coalesce((select jsonb_agg(jsonb_build_object(
        'startsAt', floor(extract(epoch from s.starts_at) * 1000), 'trackIds', s.track_ids
      ) order by s.starts_at) from public.radio_schedules s where s.collection_id = c.id), '[]'::jsonb)
    ) order by (c.id = 'antagon') desc, c.name) from public.radio_collections c), '[]'::jsonb)
  );
end $$;
revoke all on function public.radio_catalog() from public, anon, authenticated;
grant execute on function public.radio_catalog() to anon, authenticated;

create function public.radio_save_collection(
  p_id text, p_mode text, p_name text, p_description text, p_genre text,
  p_cover text, p_tracks uuid[], p_revision timestamptz default null
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare old public.radio_collections; changed boolean; starts timestamptz;
begin
  perform public.require_antagon_admin();
  -- Serialize track deletion and program edits, including creation of a new collection.
  perform pg_advisory_xact_lock(20261002, 1);
  if p_id = 'antagon' and (p_mode <> 'radio' or p_genre <> 'Mix') then
    raise exception 'A Rádio Antagon deve permanecer como rádio de estilos misturados';
  end if;
  if p_tracks is null or cardinality(p_tracks) > 300 or array_position(p_tracks, null) is not null then
    raise exception 'Fila inválida';
  end if;
  if exists (select 1 from unnest(p_tracks) as t(id)
      where not exists (select 1 from public.radio_tracks r where r.id = t.id)) then
    raise exception 'Uma das músicas não está mais disponível';
  end if;
  if p_cover is not null and not exists (
    select 1 from storage.objects where bucket_id = 'radio-media' and name = p_cover
  ) then raise exception 'Envie a capa antes de salvar'; end if;
  select * into old from public.radio_collections where id = p_id for update;
  if old.id is not null and (p_revision is null or p_revision <> old.updated_at) then
    raise exception 'Esta seleção foi alterada por outro admin. Recarregue antes de salvar';
  end if;
  if old.id is not null and old.mode <> p_mode then raise exception 'Crie uma nova seleção para mudar o modo'; end if;
  changed := old.id is null or old.track_ids is distinct from p_tracks;
  insert into public.radio_collections (id, mode, name, description, genre, cover_key, track_ids)
    values (p_id, p_mode, trim(p_name), trim(p_description), trim(p_genre), p_cover, p_tracks)
    on conflict (id) do update set name = excluded.name, description = excluded.description,
      genre = excluded.genre, cover_key = excluded.cover_key, track_ids = excluded.track_ids,
      updated_at = clock_timestamp();
  if p_mode = 'radio' and changed then
    -- Clients fetch every 15s. Publish changes 45s ahead so they receive the new
    -- schedule before the shared switch, instead of changing tracks at each poll.
    starts := statement_timestamp() + interval '45 seconds';
    if not exists (select 1 from public.radio_schedules where collection_id = p_id) then
      starts := statement_timestamp();
    end if;
    delete from public.radio_schedules where collection_id = p_id and starts_at > statement_timestamp();
    delete from public.radio_schedules where collection_id = p_id and id <> (
      select id from public.radio_schedules where collection_id = p_id order by starts_at desc limit 1
    );
    insert into public.radio_schedules (collection_id, starts_at, track_ids) values (p_id, starts, p_tracks);
  end if;
  return jsonb_build_object('startsAt', starts);
end $$;

create function public.radio_add_track(
  p_id uuid, p_title text, p_artist text, p_genre text, p_duration integer,
  p_media text, p_cover text, p_credits text
) returns uuid
language plpgsql security definer set search_path = '' as $$
begin
  perform public.require_antagon_admin();
  if p_media !~ ('^tracks/' || p_id::text || '\.(mp3|m4a|ogg|wav)$') or not exists (
    select 1 from storage.objects where bucket_id = 'radio-media' and name = p_media
  ) then raise exception 'Envie o áudio antes de salvar'; end if;
  if p_cover is not null and not exists (
    select 1 from storage.objects where bucket_id = 'radio-media' and name = p_cover
  ) then raise exception 'Envie a capa antes de salvar'; end if;
  insert into public.radio_tracks (id, title, artist, genre, duration_ms, media_key, cover_key, credits)
    values (p_id, trim(p_title), trim(p_artist), trim(p_genre), p_duration, p_media, p_cover, trim(p_credits));
  return p_id;
end $$;

create function public.radio_delete_collection(p_id text) returns void
language plpgsql security definer set search_path = '' as $$
begin
  perform public.require_antagon_admin();
  perform pg_advisory_xact_lock(20261002, 1);
  if p_id = 'antagon' then raise exception 'A Rádio Antagon não pode ser excluída'; end if;
  delete from public.radio_collections where id = p_id;
end $$;

create function public.radio_delete_track(p_id uuid) returns text[]
language plpgsql security definer set search_path = '' as $$
declare keys text[];
begin
  perform public.require_antagon_admin();
  perform pg_advisory_xact_lock(20261002, 1);
  if exists (select 1 from public.radio_collections where p_id = any(track_ids)) or exists (
    select 1 from public.radio_schedules s where p_id = any(s.track_ids) and (
      s.starts_at > statement_timestamp() or s.id = (select s2.id from public.radio_schedules s2
        where s2.collection_id = s.collection_id and s2.starts_at <= statement_timestamp()
        order by s2.starts_at desc limit 1)
    )
  ) then raise exception 'Remova a música das seleções e aguarde a troca da programação antes de excluí-la'; end if;
  delete from public.radio_tracks where id = p_id returning array_remove(array[media_key, cover_key], null) into keys;
  return coalesce(keys, '{}');
end $$;

revoke all on function public.radio_save_collection(text,text,text,text,text,text,uuid[],timestamptz),
  public.radio_add_track(uuid,text,text,text,integer,text,text,text),
  public.radio_delete_collection(text), public.radio_delete_track(uuid) from public, anon, authenticated;
grant execute on function public.radio_save_collection(text,text,text,text,text,text,uuid[],timestamptz),
  public.radio_add_track(uuid,text,text,text,integer,text,text,text),
  public.radio_delete_collection(text), public.radio_delete_track(uuid) to authenticated;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('radio-media', 'radio-media', true, 52428800,
  array['audio/mpeg','audio/mp4','audio/ogg','audio/wav','image/png']);
create policy "radio admins upload media" on storage.objects for insert to authenticated
with check (bucket_id = 'radio-media' and public.is_antagon_admin() and not public.is_antagon_banned()
  and (name ~ '^tracks/[a-f0-9-]{36}\.(mp3|m4a|ogg|wav)$' or name ~ '^covers/[a-f0-9-]{36}\.png$'));
create policy "radio admins inspect media" on storage.objects for select to authenticated
using (bucket_id = 'radio-media' and public.is_antagon_admin() and not public.is_antagon_banned());
-- Prevent overwriting in-use audio (which would invalidate the shared duration).
-- Uploads always use a new UUID. Cleanup only permits unreferenced objects.
create function public.radio_media_unused(p_key text) returns boolean
language sql stable security definer set search_path = '' as $$
  select not exists (select 1 from public.radio_tracks where media_key = p_key or cover_key = p_key)
    and not exists (select 1 from public.radio_collections where cover_key = p_key);
$$;
revoke all on function public.radio_media_unused(text) from public, anon, authenticated;
grant execute on function public.radio_media_unused(text) to authenticated;
create policy "radio admins remove unused media" on storage.objects for delete to authenticated
using (bucket_id = 'radio-media' and public.is_antagon_admin() and not public.is_antagon_banned()
  and public.radio_media_unused(name));
