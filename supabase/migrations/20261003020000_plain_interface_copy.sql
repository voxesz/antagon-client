-- Replace the original promotional descriptions without overwriting later admin edits.
update public.radio_collections as c
set description = replacement.description, updated_at = now()
from (values
  ('antagon', 'Vários estilos. Uma frequência. Todo mundo junto.', 'Rádio com estilos variados.'),
  ('radio-lofi', 'Uma frequência para desacelerar.', 'Músicas lo-fi.'),
  ('radio-phonk', 'Graves para entrar no ritmo.', 'Músicas phonk.'),
  ('radio-electronic', 'Energia para a próxima partida.', 'Músicas eletrônicas.'),
  ('playlist-lofi', 'Seu tempo, seu ritmo.', 'Músicas lo-fi.'),
  ('playlist-phonk', 'Uma seleção para manter o foco.', 'Músicas phonk.'),
  ('playlist-electronic', 'Dê play e siga em frente.', 'Músicas eletrônicas.'),
  ('radio-geekfm', 'Rap geek, animes e games. A mesma frequência para todos.', 'Rap sobre animes e games.'),
  ('playlist-geekfm', 'Rap geek, animes e games. Sua seleção, no seu ritmo.', 'Rap sobre animes e games.')
) as replacement(id, previous_description, description)
where c.id = replacement.id and c.description = replacement.previous_description;
