-- Run after the migration, inside a transaction that ends in ROLLBACK.
do $$ begin
  if has_function_privilege('anon', 'public.admin_update_item(text,text,integer,boolean)', 'execute')
    or has_function_privilege('anon', 'public.admin_delete_item(text)', 'execute') then
    raise exception 'Anonymous catalog mutation privilege';
  end if;
  perform set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000009999', true);
end $$;
set local role authenticated;
do $$ declare denied boolean := false; begin
  begin perform public.admin_update_item('antagon_crown', 'Bad', 0, true);
  exception when raise_exception then denied := sqlerrm = 'Acesso de administrador negado'; end;
  if not denied then raise exception 'Non-admin edit permitted'; end if;
  denied := false;
  begin perform public.admin_delete_item('antagon_crown');
  exception when raise_exception then denied := sqlerrm = 'Acesso de administrador negado'; end;
  if not denied then raise exception 'Non-admin delete permitted'; end if;
end $$;
reset role;
do $$ declare owner_id uuid; begin
  select user_id into strict owner_id from public.admin_roles where is_owner;
  perform set_config('request.jwt.claim.sub', owner_id::text, true);
end $$;
insert into public.cosmetic_catalog(id, name, kind, price, custom) values
  ('test_catalog_cape', 'Test cape', 'cape', 100, false),
  ('test_catalog_hat', 'Test hat', 'hat', 250, false),
  ('custom_f00df00df00df00d', 'Test custom', 'cape', 50, true);
insert into public.owned_cosmetics(user_id,item_id) values
  (auth.uid(),'test_catalog_cape'), (auth.uid(),'test_catalog_hat');
insert into public.equipped_cosmetics(user_id,kind,item_id) values
  (auth.uid(),'cape','test_catalog_cape'), (auth.uid(),'hat','test_catalog_hat')
  on conflict(user_id,kind) do update set item_id=excluded.item_id;
set local role authenticated;
do $$ declare rejected boolean; item record; begin
  perform public.admin_update_item('test_catalog_cape', '  Renamed cape  ', 321, false);
  select * into item from public.admin_catalog() where id='test_catalog_cape';
  if item.name <> 'Renamed cape' or item.price <> 321 or item.active or item.kind <> 'cape' then
    raise exception 'Edit did not preserve the item identity and update the metadata';
  end if;
  if not exists(select 1 from public.equipped_cosmetics where user_id=auth.uid() and item_id='test_catalog_cape') then
    raise exception 'Editing removed ownership or equipment';
  end if;
  rejected := false;
  begin perform public.admin_update_item('test_catalog_cape','',1,true);
  exception when raise_exception then rejected := true; end;
  if not rejected then raise exception 'Empty name accepted'; end if;
  rejected := false;
  begin perform public.admin_update_item('test_catalog_cape','Name',-1,true);
  exception when raise_exception then rejected := true; end;
  if not rejected then raise exception 'Negative price accepted'; end if;
  perform public.admin_delete_item('test_catalog_cape');
  perform public.admin_delete_item('test_catalog_hat');
  perform public.admin_delete_item('custom_f00df00df00df00d');
  if exists(select 1 from public.admin_catalog() where id in ('test_catalog_cape','test_catalog_hat','custom_f00df00df00df00d')) then
    raise exception 'Catalog item was not deleted';
  end if;
  if exists(select 1 from public.owned_cosmetics where item_id in ('test_catalog_cape','test_catalog_hat'))
    or exists(select 1 from public.equipped_cosmetics where item_id in ('test_catalog_cape','test_catalog_hat')) then
    raise exception 'Delete did not remove ownership/equipment';
  end if;
end $$;
reset role;
do $$ begin
  if not exists(select 1 from public.admin_audit where actor=auth.uid() and action='update_item' and details->>'item'='test_catalog_cape') then
    raise exception 'Edit audit is missing';
  end if;
end $$;
insert into public.banned_users(user_id,banned_by,reason) values(auth.uid(),auth.uid(),'catalog-test') on conflict(user_id) do nothing;
set local role authenticated;
do $$ declare denied boolean := false; begin
  begin perform public.admin_update_item('antagon_crown','Bad',0,true);
  exception when raise_exception then denied := sqlerrm='Acesso de administrador negado'; end;
  if not denied then raise exception 'Banned admin edit permitted'; end if;
  denied := false;
  begin perform public.admin_delete_item('antagon_crown');
  exception when raise_exception then denied := sqlerrm='Acesso de administrador negado'; end;
  if not denied then raise exception 'Banned admin deletion permitted'; end if;
end $$;
reset role;
