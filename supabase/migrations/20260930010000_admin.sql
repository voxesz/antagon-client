-- Roles are tied to the verified Minecraft UUID, never to a mutable nickname.
create table public.admin_roles (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  is_owner boolean not null default false,
  granted_by uuid references public.profiles(id),
  granted_at timestamptz not null default now()
);
create table public.banned_users (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  reason text not null check (char_length(reason) between 1 and 240),
  banned_by uuid not null references public.profiles(id),
  banned_at timestamptz not null default now()
);
create table public.admin_audit (
  id bigint generated always as identity primary key,
  actor uuid not null references public.profiles(id),
  target uuid not null references public.profiles(id),
  action text not null,
  details jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
alter table public.admin_roles enable row level security;
alter table public.banned_users enable row level security;
alter table public.admin_audit enable row level security;
revoke all on public.admin_roles, public.banned_users, public.admin_audit from anon, authenticated;

create function public.seed_antagon_owner() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if new.mc_uuid = '670ffb629dfc42768a228f51b90691bb' then
    insert into public.admin_roles (user_id, is_owner) values (new.id, true)
      on conflict (user_id) do update set is_owner = true;
  end if;
  return new;
end $$;
create trigger antagon_owner_profile after insert or update of mc_uuid on public.profiles
  for each row execute function public.seed_antagon_owner();
insert into public.admin_roles (user_id, is_owner)
  select id, true from public.profiles where mc_uuid = '670ffb629dfc42768a228f51b90691bb'
  on conflict (user_id) do update set is_owner = true;

create function public.is_antagon_admin() returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.admin_roles where user_id = auth.uid());
$$;
create function public.is_antagon_owner() returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.admin_roles where user_id = auth.uid() and is_owner);
$$;
create function public.is_antagon_banned() returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.banned_users where user_id = auth.uid());
$$;
revoke all on function public.is_antagon_admin(), public.is_antagon_owner(), public.is_antagon_banned() from public;
grant execute on function public.is_antagon_banned() to authenticated;

create function public.account_status()
returns table(is_admin boolean, is_owner boolean, is_banned boolean, ban_reason text)
language sql stable security definer set search_path = '' as $$
  select public.is_antagon_admin(), public.is_antagon_owner(), public.is_antagon_banned(),
    (select reason from public.banned_users where user_id = auth.uid());
$$;
revoke all on function public.account_status() from public;
grant execute on function public.account_status() to authenticated;

create function public.require_antagon_admin() returns void
language plpgsql security definer set search_path = '' as $$
begin
  if not public.is_antagon_admin() or public.is_antagon_banned() then
    raise exception 'Acesso de administrador negado';
  end if;
end $$;
create function public.require_antagon_active() returns void
language plpgsql security definer set search_path = '' as $$
begin
  if public.is_antagon_banned() then raise exception 'Conta banida do Antagon Client'; end if;
end $$;
revoke all on function public.require_antagon_admin(), public.require_antagon_active() from public;

-- Deny existing sessions as well as future sign-ins. SECURITY DEFINER store
-- functions perform the same check explicitly below.
create policy "banned users cannot use profiles" on public.profiles as restrictive
  for all to authenticated using (not public.is_antagon_banned())
  with check (not public.is_antagon_banned());
create policy "banned users cannot use friendships" on public.friendships as restrictive
  for all to authenticated using (not public.is_antagon_banned())
  with check (not public.is_antagon_banned());
create policy "banned users cannot use presence" on public.presence as restrictive
  for all to authenticated using (not public.is_antagon_banned())
  with check (not public.is_antagon_banned());
create policy "banned users cannot use messages" on public.messages as restrictive
  for all to authenticated using (not public.is_antagon_banned())
  with check (not public.is_antagon_banned());
create policy "banned users cannot use catalog" on public.cosmetic_catalog as restrictive
  for all to authenticated using (not public.is_antagon_banned())
  with check (not public.is_antagon_banned());
create policy "banned users cannot use wallets" on public.coin_wallets as restrictive
  for all to authenticated using (not public.is_antagon_banned())
  with check (not public.is_antagon_banned());
create policy "banned users cannot use inventory" on public.owned_cosmetics as restrictive
  for all to authenticated using (not public.is_antagon_banned())
  with check (not public.is_antagon_banned());
create policy "banned users cannot use equipment" on public.equipped_cosmetics as restrictive
  for all to authenticated using (not public.is_antagon_banned())
  with check (not public.is_antagon_banned());

create or replace function public.heartbeat_client() returns void
language plpgsql security definer set search_path = '' as $$
begin
  perform public.require_antagon_active();
  insert into public.client_presence (user_id, updated_at) values (auth.uid(), now())
    on conflict (user_id) do update set updated_at = now();
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
  if not found then raise exception 'Moedas insuficientes'; end if;
  insert into public.owned_cosmetics (user_id, item_id) values (auth.uid(), p_item);
  return v_balance;
end $$;
create or replace function public.equip_cosmetic(p_item text, p_kind text) returns void
language plpgsql security definer set search_path = '' as $$
begin
  perform public.require_antagon_active();
  if p_kind not in ('cape') then raise exception 'Tipo inválido'; end if;
  if p_item is null then
    delete from public.equipped_cosmetics where user_id = auth.uid() and kind = p_kind;
    return;
  end if;
  if not exists (select 1 from public.owned_cosmetics o join public.cosmetic_catalog c on c.id = o.item_id
      where o.user_id = auth.uid() and o.item_id = p_item and c.kind = p_kind and c.active) then
    raise exception 'Item não adquirido';
  end if;
  insert into public.equipped_cosmetics (user_id, kind, item_id) values (auth.uid(), p_kind, p_item)
    on conflict (user_id, kind) do update set item_id = excluded.item_id;
end $$;
drop function public.visible_cosmetics(text[]);
create function public.visible_cosmetics(p_uuids text[])
returns table(mc_uuid text, cape text, active_client boolean, is_admin boolean)
language plpgsql stable security definer set search_path = '' as $$
begin
  perform public.require_antagon_active();
  return query select p.mc_uuid, e.item_id, true, a.user_id is not null
    from public.profiles p
    join public.client_presence cp on cp.user_id = p.id
    left join public.equipped_cosmetics e on e.user_id = p.id and e.kind = 'cape'
    left join public.admin_roles a on a.user_id = p.id
    where p.mc_uuid = any(p_uuids[1:100]) and cp.updated_at > now() - interval '2 minutes'
      and not exists (select 1 from public.banned_users b where b.user_id = p.id);
end $$;
revoke all on function public.visible_cosmetics(text[]) from public;
grant execute on function public.visible_cosmetics(text[]) to authenticated;

create function public.admin_find_user(p_name text)
returns table(user_id uuid, name text, mc_uuid text, is_admin boolean, is_owner boolean,
  is_banned boolean, ban_reason text, coins integer, items text[])
language plpgsql stable security definer set search_path = '' as $$
begin
  perform public.require_antagon_admin();
  if p_name !~ '^[A-Za-z0-9_]{1,16}$' and p_name !~ '^[0-9a-fA-F]{32}$' then
    raise exception 'Nick ou UUID inválido';
  end if;
  if p_name !~ '^[0-9a-fA-F]{32}$' and
    (select count(*) from public.profiles p2 where lower(p2.name) = lower(p_name)) > 1 then
    raise exception 'Nick ambíguo; busque pelo UUID do Minecraft';
  end if;
  return query select p.id, p.name, p.mc_uuid, a.user_id is not null, coalesce(a.is_owner, false),
    b.user_id is not null, b.reason, coalesce(w.balance, 0),
    array(select o.item_id from public.owned_cosmetics o where o.user_id = p.id order by o.item_id)
    from public.profiles p
    left join public.admin_roles a on a.user_id = p.id
    left join public.banned_users b on b.user_id = p.id
    left join public.coin_wallets w on w.user_id = p.id
    where lower(p.name) = lower(p_name) or p.mc_uuid = lower(p_name) limit 1;
end $$;
revoke all on function public.admin_find_user(text) from public;
grant execute on function public.admin_find_user(text) to authenticated;

create function public.admin_set_role(p_target uuid, p_admin boolean) returns void
language plpgsql security definer set search_path = '' as $$
begin
  perform public.require_antagon_admin();
  if not public.is_antagon_owner() then raise exception 'Só o proprietário pode alterar administradores'; end if;
  if p_target = auth.uid() or exists (select 1 from public.admin_roles where user_id = p_target and is_owner) then
    raise exception 'Cargo do proprietário não pode ser alterado';
  end if;
  if not exists (select 1 from public.profiles where id = p_target) then raise exception 'Jogador não encontrado'; end if;
  if p_admin then
    if exists (select 1 from public.banned_users where user_id = p_target) then raise exception 'Desbana primeiro'; end if;
    insert into public.admin_roles (user_id, granted_by) values (p_target, auth.uid()) on conflict do nothing;
  else
    delete from public.admin_roles where user_id = p_target;
  end if;
  insert into public.admin_audit(actor, target, action) values (auth.uid(), p_target,
    case when p_admin then 'grant_admin' else 'revoke_admin' end);
end $$;
revoke all on function public.admin_set_role(uuid, boolean) from public;
grant execute on function public.admin_set_role(uuid, boolean) to authenticated;

create function public.admin_set_ban(p_target uuid, p_banned boolean, p_reason text default null) returns void
language plpgsql security definer set search_path = '' as $$
begin
  perform public.require_antagon_admin();
  if p_target = auth.uid() or exists (select 1 from public.admin_roles where user_id = p_target) then
    raise exception 'Remova o cargo de admin antes de banir';
  end if;
  if not exists (select 1 from public.profiles where id = p_target) then raise exception 'Jogador não encontrado'; end if;
  if p_banned then
    if char_length(trim(coalesce(p_reason, ''))) not between 1 and 240 then raise exception 'Informe um motivo'; end if;
    insert into public.banned_users (user_id, reason, banned_by) values (p_target, trim(p_reason), auth.uid())
      on conflict (user_id) do update set reason = excluded.reason, banned_by = excluded.banned_by, banned_at = now();
    delete from public.client_presence where user_id = p_target;
    delete from public.presence where user_id = p_target;
  else
    delete from public.banned_users where user_id = p_target;
  end if;
  insert into public.admin_audit(actor, target, action, details) values (auth.uid(), p_target,
    case when p_banned then 'ban' else 'unban' end,
    jsonb_build_object('reason', case when p_banned then trim(p_reason) else null end));
end $$;
revoke all on function public.admin_set_ban(uuid, boolean, text) from public;
grant execute on function public.admin_set_ban(uuid, boolean, text) to authenticated;

create function public.admin_set_coins(p_target uuid, p_balance integer) returns void
language plpgsql security definer set search_path = '' as $$
declare v_old integer;
begin
  perform public.require_antagon_admin();
  if p_balance is null or p_balance < 0 or p_balance > 10000000 then raise exception 'Saldo inválido'; end if;
  if not exists (select 1 from public.profiles where id = p_target) then raise exception 'Jogador não encontrado'; end if;
  if exists (select 1 from public.admin_roles where user_id = p_target) and not public.is_antagon_owner() then
    raise exception 'Só o proprietário pode alterar administradores';
  end if;
  insert into public.coin_wallets (user_id) values (p_target) on conflict do nothing;
  select balance into v_old from public.coin_wallets where user_id = p_target for update;
  update public.coin_wallets set balance = p_balance, updated_at = now() where user_id = p_target;
  insert into public.admin_audit(actor, target, action, details) values
    (auth.uid(), p_target, 'set_coins', jsonb_build_object('from', v_old, 'to', p_balance));
end $$;
revoke all on function public.admin_set_coins(uuid, integer) from public;
grant execute on function public.admin_set_coins(uuid, integer) to authenticated;

create function public.admin_set_item(p_target uuid, p_item text, p_grant boolean) returns void
language plpgsql security definer set search_path = '' as $$
begin
  perform public.require_antagon_admin();
  if not exists (select 1 from public.profiles where id = p_target) then raise exception 'Jogador não encontrado'; end if;
  if exists (select 1 from public.admin_roles where user_id = p_target) and not public.is_antagon_owner() then
    raise exception 'Só o proprietário pode alterar administradores';
  end if;
  if not exists (select 1 from public.cosmetic_catalog where id = p_item and active) then
    raise exception 'Item inválido';
  end if;
  if p_grant then
    insert into public.owned_cosmetics (user_id, item_id) values (p_target, p_item) on conflict do nothing;
  else
    delete from public.owned_cosmetics where user_id = p_target and item_id = p_item;
  end if;
  insert into public.admin_audit(actor, target, action, details) values (auth.uid(), p_target,
    case when p_grant then 'grant_item' else 'revoke_item' end, jsonb_build_object('item', p_item));
end $$;
revoke all on function public.admin_set_item(uuid, text, boolean) from public;
grant execute on function public.admin_set_item(uuid, text, boolean) to authenticated;

-- Also repairs environments where the catalog table exists but its seed row was omitted.
insert into public.cosmetic_catalog (id, name, kind, price, active)
values ('antagon_cape', 'Capa Antagon', 'cape', 100, true)
on conflict (id) do update set name = excluded.name, kind = excluded.kind,
  price = excluded.price, active = true;
