-- A public storefront selection; ownership, balances and admin data stay private.
alter table public.cosmetic_catalog add column featured boolean not null default false;

create function public.featured_cosmetics()
returns table(id text, name text, kind text, price integer, custom boolean)
language sql stable security definer set search_path = '' as $$
  select c.id, c.name, c.kind, c.price, c.custom
  from public.cosmetic_catalog c where c.active and c.featured
  order by c.created_at, c.id;
$$;
revoke all on function public.featured_cosmetics() from public, anon, authenticated;
grant execute on function public.featured_cosmetics() to anon, authenticated;

drop function public.admin_catalog();
create function public.admin_catalog()
returns table(id text, name text, kind text, price integer, active boolean, custom boolean, owners bigint, mine boolean, featured boolean)
language plpgsql stable security definer set search_path = '' as $$
begin
  perform public.require_antagon_admin();
  return query select c.id, c.name, c.kind, c.price, c.active, c.custom,
    (select count(*) from public.owned_cosmetics o where o.item_id = c.id),
    exists (select 1 from public.owned_cosmetics o where o.item_id = c.id and o.user_id = auth.uid()),
    c.featured from public.cosmetic_catalog c order by c.created_at, c.id;
end $$;
revoke all on function public.admin_catalog() from public, anon, authenticated;
grant execute on function public.admin_catalog() to authenticated;

-- Keep the existing four-argument RPC for older launchers. It preserves featured.
create function public.admin_update_item(p_item text, p_name text, p_price integer, p_active boolean, p_featured boolean)
returns void language plpgsql security definer set search_path = '' as $$
declare previous public.cosmetic_catalog;
begin
  perform public.require_antagon_admin();
  if char_length(trim(coalesce(p_name, ''))) not between 1 and 40 then raise exception 'Nome inválido'; end if;
  if p_price is null or p_price < 0 or p_price > 100000 then raise exception 'Preço inválido'; end if;
  if p_active is null or p_featured is null then raise exception 'Visibilidade inválida'; end if;
  select * into previous from public.cosmetic_catalog where id = p_item for update;
  if not found then raise exception 'Item não encontrado'; end if;
  update public.cosmetic_catalog set name = trim(p_name), price = p_price, active = p_active, featured = p_featured where id = p_item;
  insert into public.admin_audit(actor, target, action, details) values (auth.uid(), auth.uid(), 'update_item',
    jsonb_build_object('item', p_item,
      'before', jsonb_build_object('name', previous.name, 'price', previous.price, 'active', previous.active, 'featured', previous.featured),
      'after', jsonb_build_object('name', trim(p_name), 'price', p_price, 'active', p_active, 'featured', p_featured)));
end $$;
revoke all on function public.admin_update_item(text,text,integer,boolean,boolean) from public, anon, authenticated;
grant execute on function public.admin_update_item(text,text,integer,boolean,boolean) to authenticated;

-- Start with up to three available cosmetics; never re-create or re-list removed items.
update public.cosmetic_catalog set featured = true where id in (
  select id from public.cosmetic_catalog where active
  order by case when id in ('antagon_cape','antagon_logo_cape','antagon_crown') then 0 else 1 end,
    created_at, id limit 3
);
