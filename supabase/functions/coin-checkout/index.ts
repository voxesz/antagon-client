import { createClient } from 'npm:@supabase/supabase-js@2';

const packs: Record<string, { coins: number; cents: number }> = {
  small: { coins: 100, cents: 490 },
  medium: { coins: 550, cents: 1990 },
  large: { coins: 1200, cents: 3990 },
};
const reply = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

Deno.serve(async (req) => {
  if (req.method !== 'POST') return reply({ error: 'Método não permitido.' }, 405);
  const key = Deno.env.get('STRIPE_SECRET_KEY');
  const returnUrl = Deno.env.get('CHECKOUT_RETURN_URL');
  if (!key || !returnUrl || !/^https:\/\//.test(returnUrl)) return reply({ error: 'Loja indisponível.' }, 503);
  const { pack } = await req.json().catch(() => ({}));
  const chosen = packs[pack];
  if (!chosen) return reply({ error: 'Pacote inválido.' }, 400);
  const bearer = req.headers.get('authorization');
  if (!bearer?.startsWith('Bearer ')) return reply({ error: 'Entre com Microsoft.' }, 401);
  const db = createClient(Deno.env.get('SUPABASE_URL')!, Deno.env.get('SUPABASE_ANON_KEY')!, {
    global: { headers: { Authorization: bearer } },
    auth: { persistSession: false },
  });
  const {
    data: { user },
    error,
  } = await db.auth.getUser(bearer.slice(7));
  if (error || !user) return reply({ error: 'Sessão inválida.' }, 401);
  const access = await db.rpc('account_status').single();
  if (access.error) return reply({ error: 'Não foi possível verificar a conta.' }, 503);
  if ((access.data as { is_banned: boolean } | null)?.is_banned)
    return reply({ error: 'Conta banida do Antagon Client.' }, 403);
  const form = new URLSearchParams({
    mode: 'payment',
    client_reference_id: user.id,
    'metadata[coins]': String(chosen.coins),
    'line_items[0][quantity]': '1',
    'line_items[0][price_data][currency]': 'brl',
    'line_items[0][price_data][unit_amount]': String(chosen.cents),
    'line_items[0][price_data][product_data][name]': `${chosen.coins} ANTAGOIN$`,
    success_url: returnUrl,
    cancel_url: returnUrl,
  });
  const checkout = await fetch('https://api.stripe.com/v1/checkout/sessions', {
    method: 'POST',
    headers: { authorization: `Bearer ${key}`, 'content-type': 'application/x-www-form-urlencoded' },
    body: form,
  });
  const session = await checkout.json();
  if (!checkout.ok || typeof session.url !== 'string')
    return reply({ error: 'Não foi possível abrir o pagamento.' }, 502);
  return reply({ url: session.url });
});
