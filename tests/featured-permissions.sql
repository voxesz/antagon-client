-- Execute after the migration within a transaction that will be rolled back.
insert into public.cosmetic_catalog(id,name,kind,price,active,featured) values
  ('test_featured_public','Public','cape',101,true,true),
  ('test_featured_private','Private','cape',202,false,true),
  ('test_featured_unselected','Unselected','hat',303,true,false);
set local role anon;
do $$ begin
  if not exists(select 1 from public.featured_cosmetics() where id='test_featured_public')
    or exists(select 1 from public.featured_cosmetics() where id in ('test_featured_private','test_featured_unselected')) then
    raise exception 'Public selection leaked private or unselected items';
  end if;
  if has_function_privilege('anon','public.admin_update_item(text,text,integer,boolean,boolean)','execute')
    or has_function_privilege('anon','public.admin_catalog()','execute') then
    raise exception 'Anonymous administrator access';
  end if;
end $$;
reset role;
select set_config('request.jwt.claim.sub','00000000-0000-4000-8000-000000009999',true);
set local role authenticated;
do $$ declare denied boolean := false; begin
  begin perform public.admin_update_item('test_featured_unselected','Bad',0,true,true);
  exception when raise_exception then denied := sqlerrm='Acesso de administrador negado'; end;
  if not denied then raise exception 'Ordinary user could feature items'; end if;
end $$;
reset role;
do $$ declare owner_id uuid; begin
  select user_id into strict owner_id from public.admin_roles where is_owner;
  perform set_config('request.jwt.claim.sub',owner_id::text,true);
end $$;
set local role authenticated;
do $$ begin
  perform public.admin_update_item('test_featured_unselected','Featured hat',405,true,true);
  if not exists(select 1 from public.featured_cosmetics() where id='test_featured_unselected' and price=405 and name='Featured hat') then
    raise exception 'Admin edit was not reflected in public selection';
  end if;
  perform public.admin_update_item('test_featured_unselected','Legacy edit',406,true);
  if not exists(select 1 from public.admin_catalog() where id='test_featured_unselected' and featured) then
    raise exception 'Old client cleared the featured flag';
  end if;
  perform public.admin_set_active('test_featured_unselected',false);
  if exists(select 1 from public.featured_cosmetics() where id='test_featured_unselected') then
    raise exception 'Unlisted item still publicly featured';
  end if;
end $$;
reset role;
insert into public.banned_users(user_id,banned_by,reason) values(auth.uid(),auth.uid(),'featured-test') on conflict(user_id) do nothing;
set local role authenticated;
do $$ declare denied boolean := false; begin
  begin perform public.admin_update_item('test_featured_public','Bad',0,true,true);
  exception when raise_exception then denied := sqlerrm='Acesso de administrador negado'; end;
  if not denied then raise exception 'Banned administrator could feature items'; end if;
end $$;
reset role;
