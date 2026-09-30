-- Admins see whether they own each item, and may delete capes made in the editor.
drop function public.admin_catalog();
create function public.admin_catalog()
returns table(id text, name text, kind text, price integer, active boolean, custom boolean, owners bigint, mine boolean)
language plpgsql stable security definer set search_path = '' as $$
begin
  perform public.require_antagon_admin();
  return query select c.id, c.name, c.kind, c.price, c.active, c.custom,
    (select count(*) from public.owned_cosmetics o where o.item_id = c.id),
    exists (select 1 from public.owned_cosmetics o where o.item_id = c.id and o.user_id = auth.uid())
    from public.cosmetic_catalog c order by c.created_at, c.id;
end $$;
revoke all on function public.admin_catalog() from public;
grant execute on function public.admin_catalog() to authenticated;

-- Removing the owned rows also unequips the cape (equipped_cosmetics cascades from owned_cosmetics).
create function public.admin_delete_item(p_item text) returns void
language plpgsql security definer set search_path = '' as $$
begin
  perform public.require_antagon_admin();
  if not exists (select 1 from public.cosmetic_catalog where id = p_item and custom) then
    raise exception 'Só capas criadas no editor podem ser excluídas';
  end if;
  delete from public.owned_cosmetics where item_id = p_item;
  delete from public.cosmetic_catalog where id = p_item;
  insert into public.admin_audit(actor, target, action, details) values (auth.uid(), auth.uid(),
    'delete_item', jsonb_build_object('item', p_item));
end $$;
revoke all on function public.admin_delete_item(text) from public;
grant execute on function public.admin_delete_item(text) to authenticated;

-- The Storage API needs select and delete on the object to remove the texture file.
create policy "admins read cosmetics" on storage.objects for select to authenticated
  using (bucket_id = 'cosmetics' and public.is_antagon_admin());
create policy "admins delete cosmetics" on storage.objects for delete to authenticated
  using (bucket_id = 'cosmetics' and name ~ '^custom_[a-f0-9]{16}\.png$' and public.is_antagon_admin());
