-- Invited players (besides admins) who get the bundled Baritone on the modern profile.
create table public.bot_access (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  granted_by uuid references public.profiles(id),
  granted_at timestamptz not null default now()
);
alter table public.bot_access enable row level security;
revoke all on public.bot_access from anon, authenticated;

create function public.has_bot_access() returns boolean
language sql stable security definer set search_path = '' as $$
  select not public.is_antagon_banned() and (public.is_antagon_admin()
    or exists (select 1 from public.bot_access where user_id = auth.uid()));
$$;
revoke all on function public.has_bot_access() from public;
grant execute on function public.has_bot_access() to authenticated;

create function public.admin_bot_access(p_target uuid) returns boolean
language plpgsql stable security definer set search_path = '' as $$
begin
  perform public.require_antagon_admin();
  return exists (select 1 from public.bot_access where user_id = p_target);
end $$;
revoke all on function public.admin_bot_access(uuid) from public;
grant execute on function public.admin_bot_access(uuid) to authenticated;

create function public.admin_set_bot(p_target uuid, p_allowed boolean) returns void
language plpgsql security definer set search_path = '' as $$
begin
  perform public.require_antagon_admin();
  if not exists (select 1 from public.profiles where id = p_target) then raise exception 'Jogador não encontrado'; end if;
  if p_allowed then
    insert into public.bot_access (user_id, granted_by) values (p_target, auth.uid()) on conflict do nothing;
  else
    delete from public.bot_access where user_id = p_target;
  end if;
  insert into public.admin_audit(actor, target, action) values (auth.uid(), p_target,
    case when p_allowed then 'grant_bot' else 'revoke_bot' end);
end $$;
revoke all on function public.admin_set_bot(uuid, boolean) from public;
grant execute on function public.admin_set_bot(uuid, boolean) to authenticated;
