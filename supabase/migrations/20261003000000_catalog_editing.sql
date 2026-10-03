-- Allow administrators to maintain every catalog item, including built-ins.
create function public.admin_update_item(p_item text, p_name text, p_price integer, p_active boolean)
returns void language plpgsql security definer set search_path = '' as $$
declare previous public.cosmetic_catalog;
begin
  perform public.require_antagon_admin();
  if char_length(trim(coalesce(p_name, ''))) not between 1 and 40 then raise exception 'Nome inválido'; end if;
  if p_price is null or p_price < 0 or p_price > 100000 then raise exception 'Preço inválido'; end if;
  if p_active is null then raise exception 'Visibilidade inválida'; end if;
  select * into previous from public.cosmetic_catalog where id = p_item for update;
  if not found then raise exception 'Item não encontrado'; end if;
  update public.cosmetic_catalog set name = trim(p_name), price = p_price, active = p_active where id = p_item;
  insert into public.admin_audit(actor, target, action, details) values (auth.uid(), auth.uid(), 'update_item',
    jsonb_build_object('item', p_item, 'before', jsonb_build_object('name', previous.name, 'price', previous.price, 'active', previous.active),
      'after', jsonb_build_object('name', trim(p_name), 'price', p_price, 'active', p_active)));
end $$;
revoke all on function public.admin_update_item(text,text,integer,boolean) from public, anon, authenticated;
grant execute on function public.admin_update_item(text,text,integer,boolean) to authenticated;

create or replace function public.admin_delete_item(p_item text) returns void
language plpgsql security definer set search_path = '' as $$
declare previous public.cosmetic_catalog; owners bigint;
begin
  perform public.require_antagon_admin();
  select * into previous from public.cosmetic_catalog where id = p_item for update;
  if not found then raise exception 'Item não encontrado'; end if;
  select count(*) into owners from public.owned_cosmetics where item_id = p_item;
  delete from public.owned_cosmetics where item_id = p_item;
  delete from public.cosmetic_catalog where id = p_item;
  insert into public.admin_audit(actor, target, action, details) values (auth.uid(), auth.uid(), 'delete_item',
    jsonb_build_object('item', p_item, 'name', previous.name, 'kind', previous.kind, 'custom', previous.custom, 'owners', owners));
end $$;
revoke all on function public.admin_delete_item(text) from public, anon, authenticated;
grant execute on function public.admin_delete_item(text) to authenticated;
