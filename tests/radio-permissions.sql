-- Run inside a transaction after applying the radio migration. Always ROLLBACK.
-- These fixtures exist only in this transaction; they never upload real objects.
set local role anon;
do $$ begin
  if jsonb_array_length(public.radio_catalog()->'collections') < 1 then raise exception 'Missing stations'; end if;
  if has_function_privilege('anon', 'public.radio_add_track(uuid,text,text,text,integer,text,text,text)', 'execute') then
    raise exception 'Anonymous upload privilege';
  end if;
  if has_table_privilege('anon', 'public.radio_tracks', 'insert') then raise exception 'Anonymous direct write'; end if;
end $$;
reset role;
do $$ begin perform set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000009999', true); end $$;
set local role authenticated;
do $$ declare rejected boolean := false; begin
  begin
    perform public.radio_delete_collection('antagon');
  exception when raise_exception then rejected := sqlerrm = 'Acesso de administrador negado'; end;
  if not rejected then raise exception 'Non-admin mutation permitted'; end if;
end $$;
reset role;
do $$ declare owner_id uuid; begin
  select user_id into owner_id from public.admin_roles where is_owner limit 1;
  if owner_id is null then raise exception 'No owner available for role tests'; end if;
  perform set_config('request.jwt.claim.sub', owner_id::text, true);
end $$;
set local role authenticated;
insert into storage.objects (bucket_id, name) values
  ('radio-media', 'tracks/00000000-0000-4000-8000-000000009001.wav'),
  ('radio-media', 'tracks/00000000-0000-4000-8000-000000009002.wav'),
  ('radio-media', 'tracks/00000000-0000-4000-8000-000000009003.wav');
do $$ declare v jsonb; rev timestamptz; rejected boolean; begin
  perform public.radio_add_track('00000000-0000-4000-8000-000000009001', 'A', 'Teste', 'Lo-fi', 10000,
    'tracks/00000000-0000-4000-8000-000000009001.wav', null, 'transaction fixture');
  perform public.radio_add_track('00000000-0000-4000-8000-000000009002', 'B', 'Teste', 'Rock', 20000,
    'tracks/00000000-0000-4000-8000-000000009002.wav', null, 'transaction fixture');
  perform public.radio_add_track('00000000-0000-4000-8000-000000009003', 'C', 'Teste', 'Rock', 20000,
    'tracks/00000000-0000-4000-8000-000000009003.wav', null, 'transaction fixture');
  perform public.radio_save_collection('test-radio-permissions', 'radio', 'Test', '', 'Mix', null,
    array['00000000-0000-4000-8000-000000009001'::uuid, '00000000-0000-4000-8000-000000009002'::uuid]);
  select item into v from jsonb_array_elements(public.radio_catalog()->'collections') item where item->>'id' = 'test-radio-permissions';
  if jsonb_array_length(v->'schedules') <> 1 then raise exception 'Initial program missing'; end if;
  rev := (v->>'revision')::timestamptz;
  perform public.radio_save_collection('test-radio-permissions', 'radio', 'Test', '', 'Mix', null,
    array['00000000-0000-4000-8000-000000009002'::uuid, '00000000-0000-4000-8000-000000009001'::uuid], rev);
  select item into v from jsonb_array_elements(public.radio_catalog()->'collections') item where item->>'id' = 'test-radio-permissions';
  if jsonb_array_length(v->'schedules') <> 2 then raise exception 'Old program was not retained'; end if;
  if (v->'schedules'->1->>'startsAt')::numeric < extract(epoch from statement_timestamp()) * 1000 + 44000 then
    raise exception 'Program changed without advance notice';
  end if;
  rejected := false;
  begin
    perform public.radio_save_collection('test-radio-permissions', 'radio', 'Test', '', 'Mix', null, '{}'::uuid[], rev);
  exception when raise_exception then rejected := sqlerrm like '%outro admin%'; end;
  if not rejected then raise exception 'Stale revision overwrote newer program'; end if;
  rejected := false;
  begin perform public.radio_delete_track('00000000-0000-4000-8000-000000009001');
  exception when raise_exception then rejected := sqlerrm like '%Remova a música%'; end;
  if not rejected then raise exception 'A scheduled track was deleted'; end if;
  if cardinality(public.radio_delete_track('00000000-0000-4000-8000-000000009003')) <> 1 then
    raise exception 'Unused file cleanup failed';
  end if;
  -- Storage RLS must refuse deletion of a file still referenced by a song.
  if public.radio_media_unused('tracks/00000000-0000-4000-8000-000000009001.wav') then
    raise exception 'An in-use file was marked as safe to delete';
  end if;
  begin
    delete from storage.objects where bucket_id = 'radio-media' and name = 'tracks/00000000-0000-4000-8000-000000009001.wav';
    if found then raise exception 'In-use audio file deleted'; end if;
  exception when insufficient_privilege then null; -- Storage also protects direct SQL deletes.
  end;
end $$;
reset role;
insert into public.banned_users (user_id, banned_by, reason)
values (auth.uid(), auth.uid(), 'radio-permission-test') on conflict (user_id) do nothing;
set local role authenticated;
do $$ declare rejected boolean := false; begin
  begin perform public.radio_delete_collection('test-radio-permissions');
  exception when raise_exception then rejected := sqlerrm = 'Acesso de administrador negado'; end;
  if not rejected then raise exception 'Banned admin mutation permitted'; end if;
  rejected := false;
  begin insert into storage.objects (bucket_id, name) values ('radio-media', 'covers/00000000-0000-4000-8000-000000009999.png');
  exception when insufficient_privilege then rejected := true; end;
  if not rejected then raise exception 'Banned admin upload permitted'; end if;
end $$;
reset role;
select 'Radio permissions and shared schedules OK (rolled back)' as result;
