-- WebRTC signalling for voice calls. Audio flows directly between the players' PCs; these
-- short-lived rows only carry invites, SDP and ICE candidates between friends.
create table public.call_signals (
  id bigint generated always as identity primary key,
  call_id uuid not null,
  sender uuid not null default auth.uid() references public.profiles(id) on delete cascade,
  recipient uuid not null references public.profiles(id) on delete cascade,
  kind text not null check (kind in ('invite', 'cancel', 'accept', 'decline', 'offer', 'answer', 'ice', 'leave', 'end', 'roster')),
  payload jsonb not null default '{}'::jsonb check (octet_length(payload::text) <= 16000),
  created_at timestamptz not null default now()
);
create index call_signals_recipient on public.call_signals (recipient, created_at);
alter table public.call_signals enable row level security;
revoke all on public.call_signals from anon, authenticated;
grant select, insert, delete on public.call_signals to authenticated;
grant usage on sequence public.call_signals_id_seq to authenticated;

create policy "receive own signals" on public.call_signals for select to authenticated
  using (recipient = (select auth.uid()));
create policy "clear own signals" on public.call_signals for delete to authenticated
  using (recipient = (select auth.uid()));
-- The host only calls friends; guests only answer the host who invited them.
create policy "signal friends" on public.call_signals for insert to authenticated
  with check (sender = (select auth.uid()) and public.are_friends(sender, recipient));
create policy "banned users cannot call" on public.call_signals as restrictive
  for all to authenticated using (not public.is_antagon_banned())
  with check (not public.is_antagon_banned());

alter publication supabase_realtime add table public.call_signals;
