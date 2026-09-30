-- Hats, admin-made capes and an inventory that outlives the store listing.
alter table public.cosmetic_catalog drop constraint cosmetic_catalog_kind_check;
alter table public.cosmetic_catalog add constraint cosmetic_catalog_kind_check check (kind in ('cape', 'hat'));
alter table public.cosmetic_catalog
  add column custom boolean not null default false,
  add column created_by uuid references public.profiles(id) on delete set null,
  add column created_at timestamptz not null default now();
alter table public.cosmetic_catalog add constraint cosmetic_catalog_custom_id
  check (custom = (id ~ '^custom_[a-f0-9]{16}$'));
alter table public.equipped_cosmetics drop constraint equipped_cosmetics_kind_check;
alter table public.equipped_cosmetics add constraint equipped_cosmetics_kind_check check (kind in ('cape', 'hat'));

insert into public.cosmetic_catalog (id, name, kind, price, active)
values ('antagon_crown', 'Coroa Antagon', 'hat', 250, true)
on conflict (id) do nothing;

-- Items taken out of the store stay visible to the players who own them.
drop policy "catalog visible" on public.cosmetic_catalog;
create policy "catalog visible" on public.cosmetic_catalog for select to authenticated using (
  active or exists (select 1 from public.owned_cosmetics o where o.item_id = cosmetic_catalog.id and o.user_id = (select auth.uid()))
);

create or replace function public.equip_cosmetic(p_item text, p_kind text) returns void
language plpgsql security definer set search_path = '' as $$
begin
  perform public.require_antagon_active();
  if p_kind not in ('cape', 'hat') then raise exception 'Tipo inválido'; end if;
  if p_item is null then
    delete from public.equipped_cosmetics where user_id = auth.uid() and kind = p_kind;
    return;
  end if;
  if not exists (select 1 from public.owned_cosmetics o join public.cosmetic_catalog c on c.id = o.item_id
      where o.user_id = auth.uid() and o.item_id = p_item and c.kind = p_kind) then
    raise exception 'Item não adquirido';
  end if;
  insert into public.equipped_cosmetics (user_id, kind, item_id) values (auth.uid(), p_kind, p_item)
    on conflict (user_id, kind) do update set item_id = excluded.item_id;
end $$;

create or replace function public.purchase_cosmetic(p_item text) returns integer
language plpgsql security definer set search_path = '' as $$
declare v_price integer; v_balance integer;
begin
  perform public.require_antagon_active();
  select price into v_price from public.cosmetic_catalog where id = p_item and active;
  if v_price is null then raise exception 'Item indisponível'; end if;
  if exists (select 1 from public.owned_cosmetics where user_id = auth.uid() and item_id = p_item) then
    raise exception 'Item já adquirido';
  end if;
  insert into public.coin_wallets (user_id) values (auth.uid()) on conflict do nothing;
  update public.coin_wallets set balance = balance - v_price, updated_at = now()
    where user_id = auth.uid() and balance >= v_price returning balance into v_balance;
  if not found then raise exception 'ANTAGOIN$ insuficientes'; end if;
  insert into public.owned_cosmetics (user_id, item_id) values (auth.uid(), p_item);
  return v_balance;
end $$;

-- Admins may give any catalog item, including retired ones, and may give items to themselves.
create or replace function public.admin_set_item(p_target uuid, p_item text, p_grant boolean) returns void
language plpgsql security definer set search_path = '' as $$
begin
  perform public.require_antagon_admin();
  if not exists (select 1 from public.profiles where id = p_target) then raise exception 'Jogador não encontrado'; end if;
  if p_target <> auth.uid() and exists (select 1 from public.admin_roles where user_id = p_target)
      and not public.is_antagon_owner() then
    raise exception 'Só o proprietário pode alterar administradores';
  end if;
  if not exists (select 1 from public.cosmetic_catalog where id = p_item) then raise exception 'Item inválido'; end if;
  if p_grant then
    insert into public.owned_cosmetics (user_id, item_id) values (p_target, p_item) on conflict do nothing;
  else
    delete from public.owned_cosmetics where user_id = p_target and item_id = p_item;
  end if;
  insert into public.admin_audit(actor, target, action, details) values (auth.uid(), p_target,
    case when p_grant then 'grant_item' else 'revoke_item' end, jsonb_build_object('item', p_item));
end $$;

drop function public.visible_cosmetics(text[]);
create function public.visible_cosmetics(p_uuids text[])
returns table(mc_uuid text, cape text, hat text, active_client boolean, is_admin boolean)
language plpgsql stable security definer set search_path = '' as $$
begin
  perform public.require_antagon_active();
  return query select p.mc_uuid, e.item_id, h.item_id, true, a.user_id is not null
    from public.profiles p
    join public.client_presence cp on cp.user_id = p.id
    left join public.equipped_cosmetics e on e.user_id = p.id and e.kind = 'cape'
    left join public.equipped_cosmetics h on h.user_id = p.id and h.kind = 'hat'
    left join public.admin_roles a on a.user_id = p.id
    where p.mc_uuid = any(p_uuids[1:100]) and cp.updated_at > now() - interval '2 minutes'
      and not exists (select 1 from public.banned_users b where b.user_id = p.id);
end $$;
revoke all on function public.visible_cosmetics(text[]) from public;
grant execute on function public.visible_cosmetics(text[]) to authenticated;

create function public.admin_catalog()
returns table(id text, name text, kind text, price integer, active boolean, custom boolean, owners bigint)
language plpgsql stable security definer set search_path = '' as $$
begin
  perform public.require_antagon_admin();
  return query select c.id, c.name, c.kind, c.price, c.active, c.custom,
    (select count(*) from public.owned_cosmetics o where o.item_id = c.id)
    from public.cosmetic_catalog c order by c.created_at, c.id;
end $$;
revoke all on function public.admin_catalog() from public;
grant execute on function public.admin_catalog() to authenticated;

create function public.admin_set_active(p_item text, p_active boolean) returns void
language plpgsql security definer set search_path = '' as $$
begin
  perform public.require_antagon_admin();
  update public.cosmetic_catalog set active = p_active where id = p_item;
  if not found then raise exception 'Item inválido'; end if;
  insert into public.admin_audit(actor, target, action, details) values (auth.uid(), auth.uid(),
    case when p_active then 'list_item' else 'unlist_item' end, jsonb_build_object('item', p_item));
end $$;
revoke all on function public.admin_set_active(text, boolean) from public;
grant execute on function public.admin_set_active(text, boolean) to authenticated;

-- The launcher uploads the texture first (admins only, fixed name pattern), then registers it here.
create function public.admin_create_cape(p_id text, p_name text, p_price integer, p_active boolean, p_target uuid)
returns void language plpgsql security definer set search_path = '' as $$
begin
  perform public.require_antagon_admin();
  if p_id !~ '^custom_[a-f0-9]{16}$' then raise exception 'Identificador inválido'; end if;
  if char_length(trim(coalesce(p_name, ''))) not between 1 and 40 then raise exception 'Nome inválido'; end if;
  if p_price is null or p_price < 0 or p_price > 100000 then raise exception 'Preço inválido'; end if;
  if not exists (select 1 from storage.objects where bucket_id = 'cosmetics' and name = p_id || '.png') then
    raise exception 'Imagem da capa não enviada';
  end if;
  insert into public.cosmetic_catalog (id, name, kind, price, active, custom, created_by)
    values (p_id, trim(p_name), 'cape', p_price, p_active, true, auth.uid());
  if p_target is not null then perform public.admin_set_item(p_target, p_id, true); end if;
  insert into public.admin_audit(actor, target, action, details) values (auth.uid(), coalesce(p_target, auth.uid()),
    'create_cape', jsonb_build_object('item', p_id, 'active', p_active));
end $$;
revoke all on function public.admin_create_cape(text, text, integer, boolean, uuid) from public;
grant execute on function public.admin_create_cape(text, text, integer, boolean, uuid) to authenticated;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('cosmetics', 'cosmetics', true, 2097152, array['image/png'])
on conflict (id) do nothing;
grant execute on function public.is_antagon_admin() to authenticated;
create policy "admins upload cosmetics" on storage.objects for insert to authenticated
  with check (bucket_id = 'cosmetics' and name ~ '^custom_[a-f0-9]{16}\.png$' and public.is_antagon_admin());
