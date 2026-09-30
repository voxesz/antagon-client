import { createClient } from 'npm:@supabase/supabase-js@2';

const amounts: Record<string, number> = { '100': 490, '550': 1990, '1200': 3990 };
const encoder = new TextEncoder();
const reply = (message: string, status = 200) => new Response(message, { status });

async function validSignature(raw: string, header: string, secret: string): Promise<boolean> {
  const timestamp = Number(header.match(/(?:^|,)\s*t=(\d+)/)?.[1]);
  const signatures = header
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part.startsWith('v1='))
    .map((part) => part.slice(3));
  if (!Number.isSafeInteger(timestamp) || Math.abs(Date.now() / 1000 - timestamp) > 300 || !signatures.length)
    return false;
  const key = await crypto.subtle.importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, [
    'sign',
  ]);
  const bytes = new Uint8Array(await crypto.subtle.sign('HMAC', key, encoder.encode(`${timestamp}.${raw}`)));
  const digest = [...bytes].map((byte) => byte.toString(16).padStart(2, '0')).join('');
  return signatures.some(
    (signature) =>
      signature.length === digest.length &&
      [...signature].reduce((diff, char, index) => diff | (char.charCodeAt(0) ^ digest.charCodeAt(index)), 0) === 0,
  );
}

Deno.serve(async (req) => {
  if (req.method !== 'POST') return reply('Method not allowed', 405);
  const secret = Deno.env.get('STRIPE_WEBHOOK_SECRET');
  const raw = await req.text();
  if (!secret || !(await validSignature(raw, req.headers.get('stripe-signature') || '', secret)))
    return reply('Invalid signature', 400);
  const event = JSON.parse(raw);
  if (!['checkout.session.completed', 'checkout.session.async_payment_succeeded'].includes(event.type))
    return reply('Ignored');
  const session = event.data?.object;
  const coins = String(session?.metadata?.coins ?? '');
  if (
    session?.mode !== 'payment' ||
    session.payment_status !== 'paid' ||
    session.currency !== 'brl' ||
    session.amount_total !== amounts[coins] ||
    !/^cs_(test_|live_)[A-Za-z0-9]+$/.test(session.id || '') ||
    !/^[0-9a-f-]{36}$/i.test(session.client_reference_id || '')
  )
    return reply('Invalid session', 400);
  const admin = createClient(Deno.env.get('SUPABASE_URL')!, Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!, {
    auth: { persistSession: false },
  });
  const { error } = await admin.rpc('credit_coins', {
    p_user: session.client_reference_id,
    p_amount: Number(coins),
    p_payment: session.id,
  });
  if (error) return reply('Credit failed', 500);
  return reply('OK');
});
