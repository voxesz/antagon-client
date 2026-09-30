insert into public.cosmetic_catalog (id, name, kind, price, active)
values ('antagon_logo_cape', 'Capa Logo Antagon', 'cape', 100, true)
on conflict (id) do nothing;
