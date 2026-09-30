// Voice calls between friends. The player who calls hosts the call on their PC: every guest
// connects only to the host (WebRTC), and the host forwards each guest's audio to the others.
// Signals (invite, SDP, ICE) travel through the launcher's main process and Supabase.
const MAX_PEOPLE = 5;
const RING_MS = 30e3;

export function createCalls({ api, me, nameOf, toast, onChange }) {
  let call = null; // hosting or joined
  let incoming = null; // { id, host, hostName, timer }
  let iceServers = null;
  let queue = Promise.resolve(); // signals are applied in arrival order
  const audio = document.createElement('div');
  audio.hidden = true;
  document.body.append(audio);

  const signal = (to, kind, payload = {}, id = call?.id) =>
    api.call.signal(to, id, kind, payload).catch((error) => toast(error.message));

  function state() {
    if (incoming && !call) return { status: 'incoming', hostName: incoming.hostName, people: [], muted: false };
    if (!call) return { status: null, people: [] };
    const people =
      call.role === 'host'
        ? [{ id: me().id, name: me().name }, ...[...call.peers].map(([id, peer]) => ({ id, name: peer.name }))]
        : call.roster;
    return {
      status: 'active',
      role: call.role,
      hostName: call.hostName,
      muted: call.muted,
      people,
      invited: [...call.invited.values()],
    };
  }
  function changed() {
    const value = state();
    api.call.report(value).catch(() => {});
    onChange(value);
  }

  async function microphone() {
    if (!(await api.call.microphone()))
      throw Error('Permita o uso do microfone para o Antagon Client nas configurações do sistema.');
    try {
      return await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      });
    } catch {
      throw Error('Nenhum microfone disponível.');
    }
  }

  function play(track) {
    const element = document.createElement('audio');
    element.autoplay = true;
    element.srcObject = new MediaStream([track]);
    element.dataset.track = track.id;
    audio.append(element);
    element.play().catch(() => {});
    track.addEventListener('ended', () => element.remove());
  }

  async function peer(id, name) {
    iceServers ||= await api.call.iceServers().catch(() => [{ urls: 'stun:stun.l.google.com:19302' }]);
    const pc = new RTCPeerConnection({ iceServers });
    const entry = { pc, name, pending: [], tracks: [], sent: false };
    pc.onicecandidate = (event) => event.candidate && signal(id, 'ice', { candidate: event.candidate.toJSON() });
    pc.ontrack = (event) => {
      play(event.track);
      if (call?.role !== 'host') return;
      entry.tracks.push(event.track);
      for (const [other, target] of call.peers)
        if (other !== id) target.forwarded.set(event.track.id, target.pc.addTrack(event.track));
    };
    pc.onconnectionstatechange = () => {
      if (pc.connectionState !== 'failed') return;
      toast(`Não foi possível conectar com ${name}. A rede de um de vocês bloqueia chamadas diretas.`);
      if (call?.role === 'host') drop(id);
      else end(false);
    };
    return entry;
  }

  async function flush(entry) {
    for (const candidate of entry.pending.splice(0)) await entry.pc.addIceCandidate(candidate).catch(() => {});
  }

  // Host side.
  async function start(friend) {
    if (call?.role === 'guest') return toast('Saia da call atual para chamar alguém.');
    if (!call) {
      const mic = await microphone();
      call = {
        id: crypto.randomUUID(),
        role: 'host',
        hostName: me().name,
        mic,
        muted: false,
        peers: new Map(),
        invited: new Map(),
      };
    }
    if (call.peers.has(friend.id) || call.invited.has(friend.id)) return;
    if (1 + call.peers.size + call.invited.size >= MAX_PEOPLE) return toast(`A call aceita até ${MAX_PEOPLE} pessoas.`);
    call.invited.set(friend.id, friend.name);
    signal(friend.id, 'invite', { name: me().name });
    const id = call.id;
    setTimeout(() => {
      if (call?.id !== id || !call.invited.delete(friend.id)) return;
      signal(friend.id, 'cancel');
      toast(`${friend.name} não atendeu.`);
      changed();
    }, RING_MS);
    changed();
  }

  async function connect(id, name) {
    const entry = await peer(id, name);
    entry.forwarded = new Map();
    for (const track of call.mic.getAudioTracks()) entry.pc.addTrack(track, call.mic);
    for (const [, other] of call.peers)
      for (const track of other.tracks) entry.forwarded.set(track.id, entry.pc.addTrack(track));
    entry.pc.onnegotiationneeded = async () => {
      await entry.pc.setLocalDescription();
      signal(id, 'offer', { sdp: entry.pc.localDescription.toJSON() });
    };
    call.peers.set(id, entry);
    roster();
  }

  function drop(id) {
    const entry = call?.peers.get(id);
    if (!entry) return;
    call.peers.delete(id);
    entry.pc.close();
    for (const track of entry.tracks) {
      audio.querySelector(`[data-track="${CSS.escape(track.id)}"]`)?.remove();
      for (const [, other] of call.peers) {
        const sender = other.forwarded.get(track.id);
        if (sender) other.pc.removeTrack(sender);
        other.forwarded.delete(track.id);
      }
    }
    roster();
  }

  function roster() {
    const people = state().people;
    for (const id of call.peers.keys()) signal(id, 'roster', { people });
    changed();
  }

  // Guest side.
  async function accept() {
    if (!incoming) return;
    const invite = incoming;
    clearTimeout(invite.timer);
    incoming = null;
    try {
      const mic = await microphone();
      call = {
        id: invite.id,
        role: 'guest',
        host: invite.host,
        hostName: invite.hostName,
        mic,
        muted: false,
        roster: [],
        invited: new Map(),
        peers: new Map(),
      };
      call.peers.set(invite.host, await peer(invite.host, invite.hostName));
      signal(invite.host, 'accept');
    } catch (error) {
      signal(invite.host, 'decline', {}, invite.id);
      call = null;
      toast(error.message);
    }
    changed();
  }

  function decline() {
    if (!incoming) return;
    clearTimeout(incoming.timer);
    signal(incoming.host, 'decline', {}, incoming.id);
    incoming = null;
    changed();
  }

  function end(notify = true) {
    if (!call) return;
    if (notify)
      if (call.role === 'host') {
        for (const id of call.peers.keys()) signal(id, 'end');
        for (const id of call.invited.keys()) signal(id, 'cancel');
      } else signal(call.host, 'leave');
    for (const [, entry] of call.peers) entry.pc.close();
    for (const track of call.mic.getTracks()) track.stop();
    audio.replaceChildren();
    call = null;
    changed();
  }

  function mute(value = !call?.muted) {
    if (!call) return;
    call.muted = value;
    for (const track of call.mic.getAudioTracks()) track.enabled = !value;
    changed();
  }

  async function receive({ call_id: id, sender, kind, payload }) {
    if (kind === 'invite') {
      if (call || incoming) return signal(sender, 'decline', { busy: true }, id);
      incoming = { id, host: sender, hostName: nameOf(sender) || payload.name || 'Amigo' };
      incoming.timer = setTimeout(() => incoming?.id === id && ((incoming = null), changed()), RING_MS);
      return changed();
    }
    if (kind === 'cancel' && incoming?.id === id) {
      clearTimeout(incoming.timer);
      incoming = null;
      return changed();
    }
    if (call?.id !== id) return;
    if (call.role === 'host') {
      const name = call.invited.get(sender);
      if (kind === 'accept' && name) {
        call.invited.delete(sender);
        return connect(sender, name);
      }
      if (kind === 'decline' && name) {
        call.invited.delete(sender);
        toast(payload.busy ? `${name} já está em outra call.` : `${name} recusou a chamada.`);
        return changed();
      }
      const entry = call.peers.get(sender);
      if (!entry) return;
      if (kind === 'answer') {
        await entry.pc.setRemoteDescription(payload.sdp);
        await flush(entry);
      } else if (kind === 'ice') {
        if (entry.pc.remoteDescription) await entry.pc.addIceCandidate(payload.candidate).catch(() => {});
        else entry.pending.push(payload.candidate);
      } else if (kind === 'leave') {
        toast(`${entry.name} saiu da call.`);
        drop(sender);
      }
      return;
    }
    if (sender !== call.host) return;
    const entry = call.peers.get(sender);
    if (kind === 'offer') {
      await entry.pc.setRemoteDescription(payload.sdp);
      if (!entry.sent) {
        for (const track of call.mic.getAudioTracks()) entry.pc.addTrack(track, call.mic);
        entry.sent = true;
      }
      await entry.pc.setLocalDescription();
      signal(sender, 'answer', { sdp: entry.pc.localDescription.toJSON() });
      await flush(entry);
    } else if (kind === 'ice') {
      if (entry.pc.remoteDescription) await entry.pc.addIceCandidate(payload.candidate).catch(() => {});
      else entry.pending.push(payload.candidate);
    } else if (kind === 'roster') {
      call.roster = Array.isArray(payload.people) ? payload.people.slice(0, MAX_PEOPLE) : [];
      changed();
    } else if (kind === 'end') {
      toast('A call foi encerrada.');
      end(false);
    }
  }

  window.addEventListener('beforeunload', () => end());
  return {
    start: (friend) => start(friend).catch((error) => toast(error.message)),
    accept,
    decline,
    end,
    mute,
    receive: (row) => (queue = queue.then(() => receive(row)).catch(() => {})),
    state,
    stats: () => ({
      tracks: audio.children.length,
      connections: call ? [...call.peers.values()].map((entry) => entry.pc.connectionState) : [],
    }),
  };
}
