// ICE servers for voice calls. STUN is enough for most home networks; players behind CGNAT need
// a TURN relay, enabled by setting CLOUDFLARE_TURN_KEY_ID and CLOUDFLARE_TURN_KEY_API_TOKEN.
const STUN = [{ urls: ['stun:stun.cloudflare.com:3478', 'stun:stun.l.google.com:19302'] }];
const reply = (body: unknown) =>
  new Response(JSON.stringify(body), { headers: { 'content-type': 'application/json' } });

Deno.serve(async () => {
  const id = Deno.env.get('CLOUDFLARE_TURN_KEY_ID');
  const token = Deno.env.get('CLOUDFLARE_TURN_KEY_API_TOKEN');
  if (!id || !token) return reply({ iceServers: STUN });
  const res = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${id}/credentials/generate-ice-servers`, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify({ ttl: 86400 }),
  }).catch(() => null);
  const data = res?.ok ? await res.json().catch(() => null) : null;
  return reply({ iceServers: data?.iceServers ? [...STUN, ...[data.iceServers].flat()] : STUN });
});
