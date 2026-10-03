-- GeekFM selections requested by the owner. The Spotify link provides the
-- reference track list only; playable tracks are added once audio is available.
-- Reference: https://open.spotify.com/playlist/5DvWGFtbUa1178eCReJ5Bq
-- Preserve any existing selections and their queues if this seed is reused.
insert into public.radio_collections (id, mode, name, genre, description)
values
  ('radio-geekfm', 'radio', 'GeekFM', 'Geek',
   'Rap geek, animes e games. A mesma frequência para todos.'),
  ('playlist-geekfm', 'playlist', 'GeekFM', 'Geek',
   'Rap geek, animes e games. Sua seleção, no seu ritmo.')
on conflict (id) do nothing;
