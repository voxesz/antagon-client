-- Catalog and balances are controlled by the database. The client can only read
-- them and call the atomic purchase/equip functions below.
create table public.cosmetic_catalog (
  id text primary key check (id ~ '^[a-z0-9_]{1,40}$'),
  name text not null,
  kind text not null check (kind in ('cape')),
  price integer not null check (price >= 0),
  active boolean not null default true
);
insert into public.cosmetic_catalog (id, name, kind, price)
values ('antagon_cape', 'Capa Antagon', 'cape', 100);

create table public.coin_wallets (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  balance integer not null default 0 check (balance >= 0),
  updated_at timestamptz not null default now()
);
create table public.coin_credits (
  payment_id text primary key,
  user_id uuid not null references public.profiles(id) on delete cascade,
  amount integer not null check (amount > 0),
  created_at timestamptz not null default now()
);
create table public.owned_cosmetics (
  user_id uuid not null references public.profiles(id) on delete cascade,
  item_id text not null references public.cosmetic_catalog(id),
  purchased_at timestamptz not null default now(),
  primary key (user_id, item_id)
);
create table public.equipped_cosmetics (
  user_id uuid not null references public.profiles(id) on delete cascade,
  kind text not null check (kind in ('cape')),
  item_id text not null references public.cosmetic_catalog(id),
  primary key (user_id, kind),
  foreign key (user_id, item_id) references public.owned_cosmetics(user_id, item_id) on delete cascade
);
create table public.client_presence (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  updated_at timestamptz not null default now()
);

alter table public.cosmetic_catalog enable row level security;
alter table public.coin_wallets enable row level security;
alter table public.coin_credits enable row level security;
alter table public.owned_cosmetics enable row level security;
alter table public.equipped_cosmetics enable row level security;
alter table public.client_presence enable row level security;
revoke all on public.cosmetic_catalog, public.coin_wallets, public.coin_credits,
  public.owned_cosmetics, public.equipped_cosmetics, public.client_presence from anon, authenticated;
grant select on public.cosmetic_catalog, public.coin_wallets, public.owned_cosmetics,
  public.equipped_cosmetics to authenticated;
create policy "catalog visible" on public.cosmetic_catalog for select to authenticated using (active);
create policy "own wallet" on public.coin_wallets for select to authenticated using (user_id = (select auth.uid()));
create policy "own inventory" on public.owned_cosmetics for select to authenticated using (user_id = (select auth.uid()));
create policy "own equipment" on public.equipped_cosmetics for select to authenticated using (user_id = (select auth.uid()));
create function public.heartbeat_client() returns void
language sql security definer set search_path = '' as $$
  insert into public.client_presence (user_id, updated_at) values (auth.uid(), now())
    on conflict (user_id) do update set updated_at = now();
$$;
revoke all on function public.heartbeat_client() from public;
grant execute on function public.heartbeat_client() to authenticated;

create function public.leave_client() returns void
language sql security definer set search_path = '' as $$
  delete from public.client_presence where user_id = auth.uid();
$$;
revoke all on function public.leave_client() from public;
grant execute on function public.leave_client() to authenticated;

create function public.purchase_cosmetic(p_item text) returns integer
language plpgsql security definer set search_path = '' as $$
declare v_price integer; v_balance integer;
begin
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
revoke all on function public.purchase_cosmetic(text) from public;
grant execute on function public.purchase_cosmetic(text) to authenticated;

create function public.equip_cosmetic(p_item text, p_kind text) returns void
language plpgsql security definer set search_path = '' as $$
begin
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
revoke all on function public.equip_cosmetic(text, text) from public;
grant execute on function public.equip_cosmetic(text, text) to authenticated;

-- The launcher sends only UUIDs visible in the current player list. Presence
-- expires, so the badge does not claim an indefinitely active client.
create function public.visible_cosmetics(p_uuids text[])
returns table(mc_uuid text, cape text, active_client boolean)
language sql stable security definer set search_path = '' as $$
  select p.mc_uuid, e.item_id,
    cp.updated_at > now() - interval '2 minutes'
  from public.profiles p
  join public.client_presence cp on cp.user_id = p.id
  left join public.equipped_cosmetics e on e.user_id = p.id and e.kind = 'cape'
  where p.mc_uuid = any(p_uuids[1:100]) and cp.updated_at > now() - interval '2 minutes';
$$;
revoke all on function public.visible_cosmetics(text[]) from public;
grant execute on function public.visible_cosmetics(text[]) to authenticated;

-- Only a verified payment webhook (service role) may grant coins. The unique
-- payment ID makes Stripe retries harmless.
create function public.credit_coins(p_user uuid, p_amount integer, p_payment text)
returns integer language plpgsql security definer set search_path = '' as $$
declare v_balance integer;
begin
  if p_amount not in (100, 550, 1200) or p_payment !~ '^cs_(test_|live_)[A-Za-z0-9]+$' then
    raise exception 'Crédito inválido';
  end if;
  insert into public.coin_credits (payment_id, user_id, amount) values (p_payment, p_user, p_amount)
    on conflict do nothing;
  if found then
    insert into public.coin_wallets (user_id, balance) values (p_user, p_amount)
      on conflict (user_id) do update set balance = public.coin_wallets.balance + excluded.balance,
        updated_at = now();
  end if;
  select balance into v_balance from public.coin_wallets where user_id = p_user;
  return v_balance;
end $$;
revoke all on function public.credit_coins(uuid, integer, text) from public, anon, authenticated;
grant execute on function public.credit_coins(uuid, integer, text) to service_role;
