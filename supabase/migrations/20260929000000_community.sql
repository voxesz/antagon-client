create table public.profiles (
  id uuid primary key references auth.users on delete cascade,
  mc_uuid text not null unique check (mc_uuid ~ '^[0-9a-f]{32}$'),
  name text not null check (name ~ '^[A-Za-z0-9_]{1,16}$'),
  created_at timestamptz not null default now()
);
create index profiles_name on public.profiles (lower(name));

create table public.friendships (
  requester uuid not null references public.profiles on delete cascade,
  addressee uuid not null references public.profiles on delete cascade,
  status text not null default 'pending' check (status in ('pending', 'accepted')),
  created_at timestamptz not null default now(),
  primary key (requester, addressee),
  check (requester <> addressee)
);
create unique index friendships_pair on public.friendships (least(requester, addressee), greatest(requester, addressee));

create table public.presence (
  user_id uuid primary key references public.profiles on delete cascade,
  activity text not null check (activity in ('launcher', 'menu', 'singleplayer', 'server', 'playing', 'offline')),
  server text check (char_length(server) <= 255),
  updated_at timestamptz not null default now()
);

create table public.messages (
  id bigint generated always as identity primary key,
  sender uuid not null default auth.uid() references public.profiles on delete cascade,
  recipient uuid not null references public.profiles on delete cascade,
  body text not null check (char_length(body) between 1 and 500),
  created_at timestamptz not null default now()
);
create index messages_pair on public.messages (least(sender, recipient), greatest(sender, recipient), created_at desc);

create function public.are_friends(a uuid, b uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.friendships f
    where f.status = 'accepted'
      and ((f.requester = a and f.addressee = b) or (f.requester = b and f.addressee = a))
  );
$$;

alter table public.profiles enable row level security;
alter table public.friendships enable row level security;
alter table public.presence enable row level security;
alter table public.messages enable row level security;

revoke all on public.profiles, public.friendships, public.presence, public.messages from anon;
revoke insert, update, delete on public.profiles from authenticated;
revoke update on public.friendships from authenticated;
grant update (status) on public.friendships to authenticated;
revoke update on public.messages from authenticated;

create policy "players can look each other up" on public.profiles
  for select to authenticated using (true);

create policy "see own friendships" on public.friendships
  for select to authenticated using ((select auth.uid()) in (requester, addressee));
create policy "send friend requests" on public.friendships
  for insert to authenticated with check (requester = (select auth.uid()) and status = 'pending');
create policy "accept requests sent to me" on public.friendships
  for update to authenticated using (addressee = (select auth.uid())) with check (status = 'accepted');
create policy "leave friendships" on public.friendships
  for delete to authenticated using ((select auth.uid()) in (requester, addressee));

create policy "friends see presence" on public.presence
  for select to authenticated using (user_id = (select auth.uid()) or public.are_friends((select auth.uid()), user_id));
create policy "publish own presence" on public.presence
  for insert to authenticated with check (user_id = (select auth.uid()));
create policy "update own presence" on public.presence
  for update to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

create policy "read own conversations" on public.messages
  for select to authenticated using ((select auth.uid()) in (sender, recipient));
create policy "message friends" on public.messages
  for insert to authenticated with check (sender = (select auth.uid()) and public.are_friends(sender, recipient));

alter publication supabase_realtime add table public.presence, public.friendships, public.messages;
