import { RadioPlayer } from './radio-player.mjs';
import { EMPTY_CATALOG, livePosition, durationLabel } from './radio-model.mjs';
import { mountRadioAdmin } from './radio-admin.js';

const $ = (s) => document.querySelector(s);
const esc = (s) =>
  String(s ?? '').replace(
    /[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c],
  );
const paths = {
  play: '<path d="m9 5 11 7-11 7Z" fill="currentColor" stroke="none"/>',
  pause: '<path d="M9 5v14M16 5v14" stroke-width="4"/>',
  next: '<path d="m5 5 10 7-10 7Z" fill="currentColor" stroke="none"/><path d="M19 5v14"/>',
  previous: '<path d="m19 5-10 7 10 7Z" fill="currentColor" stroke="none"/><path d="M5 5v14"/>',
  volume: '<path d="M11 5 6 9H3v6h3l5 4Z M15 8a6 6 0 0 1 0 8M18 5a10 10 0 0 1 0 14"/>',
  radio:
    '<circle cx="12" cy="12" r="2" fill="currentColor"/><path d="M7 7a7 7 0 0 0 0 10M17 7a7 7 0 0 1 0 10M4 4a11 11 0 0 0 0 16M20 4a11 11 0 0 1 0 16"/>',
  close: '<path d="m6 6 12 12M6 18 18 6"/>',
};
export const icon = (name) =>
  `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] || paths.play}</svg>`;
const theme = (genre) =>
  ({ 'Lo-fi': 'lofi', Phonk: 'phonk', Eletrônica: 'electronic', Rock: 'rock', 'Hip-hop': 'hiphop' })[genre] || 'mix';
export function cover(collection, className = '') {
  return `<span class="radio-art ${theme(collection.genre)} ${className}">${
    collection.coverUrl
      ? `<img src="${esc(collection.coverUrl)}" alt="" loading="lazy" />`
      : `<span class="radio-orbit"></span><span class="radio-orbit second"></span><img class="radio-logo" src="../assets/g-light.svg" alt="" />`
  }</span>`;
}

export function mountRadio(api, toast) {
  let catalog = structuredClone(EMPTY_CATALOG),
    mode = 'radio',
    genre = 'Todos',
    detail = null;
  let initialized = false,
    fetching = null,
    lastReport = 0,
    message = '',
    reportBusy = false;
  $('#view-radio').innerHTML = `
    <div class="radio-page">
      <header class="radio-heading"><h1>Rádio</h1><button id="radio-manage" class="secondary" hidden>Gerenciar músicas</button></header>
      <div class="radio-hero" id="radio-hero"></div>
      <div class="radio-toolbar"><div class="radio-modes" role="group" aria-label="Modo de reprodução"><button data-radio-mode="radio" class="active">${icon('radio')} Ao vivo</button><button data-radio-mode="playlist">${icon('play')} Playlists</button></div><button id="radio-refresh" class="radio-refresh">Atualizar catálogo ↻</button></div>
      <div class="radio-genres" id="radio-genres" role="group" aria-label="Estilo musical"></div>
      <p class="radio-notice" id="radio-notice" role="status" hidden></p>
      <div class="radio-grid" id="radio-grid"></div>
      <section class="radio-detail" id="radio-detail" aria-label="Músicas da seleção" hidden></section>
      <p class="radio-footnote" id="radio-footnote">Nas rádios, todos ouvem a mesma música ao mesmo tempo.</p>
    </div>`;
  const footer = document.createElement('footer');
  footer.className = 'music-player';
  footer.hidden = true;
  footer.innerHTML = `
    <button class="music-now" id="music-now" aria-label="Ver seleção atual"><span id="music-art"></span><span class="music-label"><b id="music-title"></b><span id="music-artist"></span><small id="music-collection"></small></span></button>
    <div class="music-center"><div class="music-controls"><button id="music-previous" aria-label="Música anterior">${icon('previous')}</button><button id="music-toggle" class="music-toggle" aria-label="Reproduzir">${icon('play')}</button><button id="music-next" aria-label="Próxima música">${icon('next')}</button><span id="music-live" class="radio-live" hidden><i></i> AO VIVO</span></div><div class="music-progress"><span id="music-elapsed">0:00</span><input id="music-seek" type="range" min="0" max="1" value="0" step="0.1" aria-label="Posição da música"/><span id="music-duration">0:00</span></div></div>
    <div class="music-volume">${icon('volume')}<input id="music-volume" type="range" min="0" max="100" value="65" aria-label="Volume da música"/><button id="music-stop" aria-label="Fechar player e parar música" title="Parar música">${icon('close')}</button></div>`;
  $('main').after(footer);
  const audio = new Audio();
  audio.id = 'antagon-audio';
  audio.hidden = true;
  footer.append(audio);
  const player = new RadioPlayer(audio, { change: renderPlayer });
  try {
    player.volume(Number(localStorage.getItem('antagon-radio-volume') ?? 65) / 100);
  } catch {}
  const admin = mountRadioAdmin(api, { toast, refresh, getCatalog: () => catalog, cover, esc });
  let renderedTrack = '',
    renderedPlaying = null;

  function renderPlayer(state) {
    footer.hidden = !state.collection;
    if (!state.collection) return;
    const live = state.collection.mode === 'radio';
    const key = `${state.track?.id}:${state.track?.coverUrl}:${state.collection.id}:${state.collection.coverUrl}`;
    if (key !== renderedTrack) {
      renderedTrack = key;
      $('#music-art').innerHTML = cover({
        ...state.collection,
        coverUrl: state.track?.coverUrl || state.collection.coverUrl,
      });
    }
    $('#music-title').textContent = state.track?.title || 'Aguardando programação';
    $('#music-artist').textContent =
      state.error ||
      (state.state === 'buffering' ? 'Carregando áudio…' : state.track?.artist || 'Nenhuma música na programação.');
    $('#music-collection').textContent = state.collection.name;
    $('#music-toggle').setAttribute(
      'aria-label',
      state.listening ? (live ? 'Parar de ouvir ao vivo' : 'Pausar') : live ? 'Voltar ao vivo' : 'Reproduzir',
    );
    if (renderedPlaying !== state.listening) {
      renderedPlaying = state.listening;
      $('#music-toggle').innerHTML = icon(state.listening ? 'pause' : 'play');
    }
    $('#music-previous').disabled = $('#music-next').disabled = live || !state.track;
    $('#music-live').hidden = !live;
    $('#music-live').classList.toggle('listening', state.listening && state.state === 'playing');
    $('#music-seek').disabled = live || !state.track;
    $('#music-seek').max = Math.max(1, state.duration);
    if (document.activeElement !== $('#music-seek')) $('#music-seek').value = state.position;
    $('#music-elapsed').textContent = durationLabel(state.position);
    $('#music-duration').textContent = durationLabel(state.duration);
    if (document.activeElement !== $('#music-volume')) $('#music-volume').value = state.volume;
  }
  function available(c) {
    return c.mode === 'playlist' ? c.trackIds.length > 0 : !!livePosition(c, player.tracks, player.now);
  }
  function render() {
    const main = catalog.collections.find((c) => c.id === 'antagon');
    $('#radio-hero').hidden = mode !== 'radio' || !main;
    if (main)
      $('#radio-hero').innerHTML =
        `<div class="radio-hero-copy"><span class="radio-eyebrow"><span class="radio-dot"></span> ${available(main) ? 'AO VIVO' : 'RÁDIO'}</span><h2>${esc(main.name)}</h2><p>${esc(main.description)}</p><button class="radio-listen" data-radio-play="${esc(main.id)}" ${available(main) ? '' : 'disabled'}>${icon('play')} ${available(main) ? 'Ouvir ao vivo' : 'Em preparação'}</button></div>${cover(main, 'hero-art')}`;
    const list = catalog.collections.filter((c) => c.mode === mode);
    const genres = ['Todos', ...new Set(list.map((c) => c.genre))];
    if (!genres.includes(genre)) genre = 'Todos';
    $('#radio-genres').innerHTML = genres
      .map(
        (g) =>
          `<button data-radio-genre="${esc(g)}" class="${genre === g ? 'active' : ''}" aria-pressed="${genre === g}">${esc(g)}</button>`,
      )
      .join('');
    const shown = list.filter((c) => genre === 'Todos' || c.genre === genre);
    $('#radio-grid').innerHTML = shown.length
      ? shown
          .map(
            (c) =>
              `<button class="radio-card" data-radio-open="${esc(c.id)}">${cover(c)}<span class="radio-card-meta"><small>${esc(c.genre)}${c.mode === 'radio' ? ' · RÁDIO' : ''}</small><b>${esc(c.name)}</b><span class="radio-card-now" data-now="${esc(c.id)}">${c.trackIds.length ? `${c.trackIds.length} músicas` : 'Em preparação'}</span></span><span class="radio-card-arrow">↗</span></button>`,
          )
          .join('')
      : '<div class="radio-empty">Nenhuma seleção disponível.</div>';
    $('#radio-footnote').textContent =
      mode === 'radio'
        ? 'Nas rádios, todos ouvem a mesma música ao mesmo tempo. Ao retomar, você acompanha o ponto atual da transmissão.'
        : 'Nas playlists, você pode pausar, voltar e avançar músicas.';
    $('#radio-notice').hidden = !message;
    $('#radio-notice').textContent = message;
    renderDetail();
    updateNow();
  }
  function renderDetail() {
    const c = catalog.collections.find((entry) => entry.id === detail);
    $('#radio-detail').hidden = !c;
    if (!c) return;
    const ids =
      c.mode === 'radio'
        ? [...c.schedules].sort((a, b) => b.startsAt - a.startsAt).find((s) => s.startsAt <= player.now)?.trackIds ||
          c.trackIds
        : c.trackIds;
    const tracks = ids.map((id) => player.tracks.get(id)).filter(Boolean);
    $('#radio-detail').innerHTML =
      `<div class="radio-detail-heading">${cover(c)}<div><small>${c.mode === 'radio' ? 'AO VIVO' : 'PLAYLIST'} · ${esc(c.genre)}</small><h2>${esc(c.name)}</h2><p>${esc(c.description)}</p></div><button class="primary" data-radio-play="${esc(c.id)}" ${available(c) ? '' : 'disabled'}>${c.mode === 'radio' ? 'Ouvir ao vivo' : 'Reproduzir'}</button><button class="radio-icon" id="radio-detail-close" aria-label="Fechar detalhes">${icon('close')}</button></div>
      <div class="radio-track-list">${tracks.length ? tracks.map((t, i) => `<button class="radio-track-row" data-track-index="${i}" ${c.mode === 'radio' ? 'disabled' : ''}><span class="radio-track-number">${String(i + 1).padStart(2, '0')}</span><span><b>${esc(t.title)}</b><small>${esc(t.artist)}</small></span><span>${esc(t.genre)}</span><span>${durationLabel(t.durationMs / 1000)}</span></button>`).join('') : '<p class="radio-empty">Esta seleção ainda não recebeu músicas.</p>'}</div>`;
    $('#radio-detail-close').onclick = () => {
      detail = null;
      renderDetail();
    };
  }
  function updateNow() {
    for (const element of document.querySelectorAll('[data-now]')) {
      const c = catalog.collections.find((entry) => entry.id === element.dataset.now);
      if (c?.mode !== 'radio') continue;
      const live = livePosition(c, player.tracks, player.now);
      element.textContent = live ? `${live.track.title} · ${live.track.artist}` : 'Em preparação';
      element.classList.toggle('on-air', !!live);
    }
  }
  async function refresh() {
    initialized = true;
    if (fetching) return fetching;
    $('#radio-refresh').disabled = true;
    fetching = (async () => {
      try {
        const data = await api.catalog();
        catalog = data;
        player.setCatalog(data);
        message = '';
        render();
        return true;
      } catch (error) {
        message = error.message;
        $('#radio-notice').hidden = false;
        $('#radio-notice').textContent = message;
        return false;
      } finally {
        $('#radio-refresh').disabled = false;
        fetching = null;
      }
    })();
    return fetching;
  }
  $('#radio-refresh').onclick = refresh;
  $('#view-radio').addEventListener('click', (event) => {
    const button = event.target.closest('button');
    if (!button || button.disabled) return;
    if (button.dataset.radioMode) {
      mode = button.dataset.radioMode;
      genre = 'Todos';
      detail = null;
      document.querySelectorAll('[data-radio-mode]').forEach((b) => b.classList.toggle('active', b === button));
      render();
    } else if (button.dataset.radioGenre) {
      genre = button.dataset.radioGenre;
      render();
    } else if (button.dataset.radioOpen) {
      detail = button.dataset.radioOpen;
      renderDetail();
      $('#radio-detail').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    } else if (button.dataset.radioPlay) player.select(button.dataset.radioPlay);
    else if (button.dataset.trackIndex !== undefined) player.select(detail, Number(button.dataset.trackIndex));
  });
  $('#radio-manage').onclick = () => {
    $('[data-view="admin"]').click();
    $('[data-admin-tab="radio"]').click();
  };
  $('#music-toggle').onclick = () => player.toggle();
  $('#music-previous').onclick = () => player.next(-1);
  $('#music-next').onclick = () => player.next();
  $('#music-stop').onclick = () => {
    player.stop();
    api.report(player.snapshot()).catch(() => {});
  };
  $('#music-volume').oninput = (e) => {
    player.volume(Number(e.target.value) / 100);
    try {
      localStorage.setItem('antagon-radio-volume', e.target.value);
    } catch {}
  };
  $('#music-seek').oninput = (e) => player.seek(Number(e.target.value));
  $('#music-now').onclick = () => {
    $('[data-view="radio"]').click();
    detail = player.selected;
    renderDetail();
    $('#radio-detail').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  };
  api.onCommand(async ({ action, value }) => {
    if (action === 'toggle') {
      if (!player.collection) {
        await refresh();
        player.select('antagon');
      } else player.toggle();
    } else if (action === 'next') player.next();
    else if (action === 'previous') player.next(-1);
    else if (action === 'stop') player.stop();
    else if (action === 'volume') player.volume(value / 100);
  });
  const tick = setInterval(() => {
    player.tick();
    if ($('#view-radio').classList.contains('active')) updateNow();
    if (performance.now() - lastReport > 900 && !reportBusy) {
      lastReport = performance.now();
      reportBusy = true;
      api
        .report(player.snapshot())
        .catch(() => {})
        .finally(() => {
          reportBusy = false;
        });
    }
  }, 500);
  const poll = setInterval(() => {
    if (initialized) refresh();
  }, 15000);
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && initialized) refresh();
  });
  window.addEventListener('pagehide', () => {
    clearInterval(tick);
    clearInterval(poll);
    player.stop();
  });
  render();
  return {
    open: () => refresh(),
    admin: () => admin.open(),
    access: (access) => {
      $('#radio-manage').hidden = !access.isAdmin;
    },
  };
}
