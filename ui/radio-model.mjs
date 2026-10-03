// The schedule and its epoch come from the server. Every listener uses the same
// ordered durations; their local wall clock is never used for live playback.
export function livePosition(collection, tracks, now) {
  if (collection?.mode !== 'radio' || !Number.isFinite(now)) return null;
  const schedule = [...(collection.schedules || [])]
    .filter((s) => Number.isFinite(s.startsAt) && s.startsAt <= now)
    .sort((a, b) => b.startsAt - a.startsAt)[0];
  if (!schedule?.trackIds?.length) return null;
  const queue = schedule.trackIds.map((id) => tracks.get(id));
  // Never silently remove a missing track: that would shift everyone else's clock.
  if (queue.some((t) => !t || !Number.isFinite(t.durationMs) || t.durationMs <= 0)) return null;
  const length = queue.reduce((sum, t) => sum + t.durationMs, 0);
  const cycle = Math.floor((now - schedule.startsAt) / length);
  let offset = (now - schedule.startsAt) % length;
  for (let index = 0; index < queue.length; index++) {
    const track = queue[index];
    if (offset < track.durationMs)
      return { track, index, offset: offset / 1000, key: `${schedule.startsAt}:${cycle}:${index}` };
    offset -= track.durationMs;
  }
  return null;
}

export function durationLabel(seconds) {
  const n = Math.max(0, Math.floor(Number(seconds) || 0));
  return `${Math.floor(n / 60)}:${String(n % 60).padStart(2, '0')}`;
}

export const EMPTY_CATALOG = {
  serverTime: 0,
  tracks: [],
  collections: [
    {
      id: 'antagon',
      name: 'Rádio Antagon',
      mode: 'radio',
      genre: 'Mix',
      description: 'Vários estilos. Uma frequência. Todo mundo junto.',
      coverUrl: '',
      trackIds: [],
      schedules: [],
    },
  ],
};
