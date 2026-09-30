-- Qualify profile fields in the lookup to avoid PL/pgSQL output-column ambiguity.
create or replace function public.admin_find_user(p_name text)
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
