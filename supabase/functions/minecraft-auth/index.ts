import { createClient } from 'npm:@supabase/supabase-js@2';

const url = Deno.env.get('SUPABASE_URL')!;
const admin = createClient(url, Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!, { auth: { persistSession: false } });
const anon = createClient(url, Deno.env.get('SUPABASE_ANON_KEY')!, { auth: { persistSession: false } });

const reply = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

Deno.serve(async (req) => {
  if (req.method !== 'POST') return reply({ error: 'Método não permitido.' }, 405);
  const { token } = await req.json().catch(() => ({}));
  if (typeof token !== 'string' || token.length < 20 || token.length > 4096)
    return reply({ error: 'Token inválido.' }, 400);

  const res = await fetch('https://api.minecraftservices.com/minecraft/profile', {
    headers: { authorization: `Bearer ${token}` },
  });
  if (!res.ok) return reply({ error: 'Não foi possível confirmar sua conta do Minecraft.' }, 401);
  const profile = await res.json();
  if (!/^[0-9a-f]{32}$/.test(profile.id) || !/^[A-Za-z0-9_]{1,16}$/.test(profile.name))
    return reply({ error: 'Perfil do Minecraft inválido.' }, 401);

  const email = `${profile.id}@players.antagon.invalid`;
  const created = await admin.auth.admin.createUser({ email, email_confirm: true });
  if (created.error && created.error.code !== 'email_exists') return reply({ error: 'Falha ao criar a conta.' }, 500);

  const link = await admin.auth.admin.generateLink({ type: 'magiclink', email });
  if (link.error) return reply({ error: 'Falha ao iniciar a sessão.' }, 500);
  const { data, error } = await anon.auth.verifyOtp({
    type: 'magiclink',
    token_hash: link.data.properties.hashed_token,
  });
  if (error || !data.session || !data.user) return reply({ error: 'Falha ao iniciar a sessão.' }, 500);

  const saved = await admin.from('profiles').upsert({ id: data.user.id, mc_uuid: profile.id, name: profile.name });
  if (saved.error) return reply({ error: 'Falha ao salvar o perfil.' }, 500);

  return reply({ session: data.session, profile: { id: data.user.id, name: profile.name, mcUuid: profile.id } });
});
