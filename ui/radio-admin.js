import { durationLabel } from './radio-model.mjs';

async function imageBytes(file) {
  if (!file) return null;
  if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type) || file.size > 10 * 1024 * 1024)
    throw Error('Escolha uma imagem PNG, JPG ou WebP de até 10 MB.');
  const image = await createImageBitmap(file);
  try {
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 640;
    const ctx = canvas.getContext('2d');
    const size = Math.min(image.width, image.height);
    ctx.drawImage(image, (image.width - size) / 2, (image.height - size) / 2, size, size, 0, 0, 640, 640);
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/png'));
    if (!blob) throw Error('Não foi possível ler a capa.');
    return new Uint8Array(await blob.arrayBuffer());
  } finally {
    image.close();
  }
}

export function mountRadioAdmin(api, { toast, refresh, getCatalog, esc }) {
  const $ = (s) => document.querySelector(s);
  let draft = null,
    queue = [],
    durationMs = 0,
    audioUrl = null,
    busy = false,
    dirty = false;
  let fileGeneration = 0;
  $('#admin-radio').innerHTML = `
    <div class="radio-admin-heading"><div><h2>Biblioteca de músicas</h2><p>Adicione faixas e monte a programação do Antagon.</p></div><button class="primary" id="radio-upload-open">+ Enviar música</button></div>
    <div class="radio-workspace"><aside class="radio-library"><div class="radio-library-head"><b>Músicas</b><span id="radio-library-count">0</span><button id="radio-admin-refresh" class="radio-refresh" aria-label="Atualizar biblioteca">↻</button></div><input class="radio-library-search" id="radio-library-search" placeholder="Buscar música ou artista" aria-label="Buscar música ou artista"/><div id="radio-library-items" class="radio-library-items"></div></aside>
    <form class="radio-editor" id="radio-editor"><label>Editar rádio ou playlist<select id="radio-edit-select"></select></label><div class="radio-editor-actions"><button type="button" class="secondary" data-radio-new="radio">+ Nova rádio</button><button type="button" class="secondary" data-radio-new="playlist">+ Nova playlist</button></div>
      <div class="radio-form-split"><label>Nome<input id="radio-edit-name" maxlength="80" required/></label><label>Estilo<input id="radio-edit-genre" maxlength="40" list="radio-genre-suggestions" required/></label></div>
      <label>Descrição<textarea id="radio-edit-description" maxlength="240" rows="2"></textarea></label>
      <label>Capa <span id="radio-edit-cover-state"></span><input id="radio-edit-cover" type="file" accept="image/png,image/jpeg,image/webp"/></label>
      <div class="radio-queue-label"><b id="radio-queue-title">Programação</b><span id="radio-queue-count"></span></div><ol id="radio-queue" class="radio-queue"></ol>
      <div class="radio-editor-footer"><button class="primary" id="radio-edit-save">Salvar programação</button><button type="button" id="radio-edit-delete" class="radio-delete">Excluir seleção</button></div>
      <p id="radio-editor-note" class="radio-admin-note"></p>
    </form></div>
    <datalist id="radio-genre-suggestions"><option>Mix</option><option>Lo-fi</option><option>Phonk</option><option>Eletrônica</option><option>Rock</option><option>Hip-hop</option><option>Pop</option><option>Funk</option></datalist>`;
  const modal = document.createElement('dialog');
  modal.className = 'radio-upload';
  modal.id = 'radio-upload-dialog';
  modal.innerHTML = `<form id="radio-upload-form"><h2>Adicionar música</h2><p>Envie uma faixa para a biblioteca. Depois, adicione às rádios e playlists que quiser.</p>
    <label>Arquivo de áudio · até 50 MB<input id="radio-upload-audio" type="file" accept=".mp3,.m4a,.ogg,.wav" required/></label><audio id="radio-upload-preview" controls preload="metadata" hidden></audio>
    <div class="radio-form-split"><label>Nome da música<input id="radio-upload-title" maxlength="120" required/></label><label>Artista<input id="radio-upload-artist" maxlength="120" required/></label></div>
    <label>Estilo<input id="radio-upload-genre" maxlength="40" list="radio-genre-suggestions" required/></label>
    <label>Capa da música · opcional<input id="radio-upload-cover" type="file" accept="image/png,image/jpeg,image/webp"/></label>
    <label>Créditos / licença · opcional<textarea id="radio-upload-credits" rows="2" maxlength="500" placeholder="Artista, origem da faixa e créditos"></textarea></label>
    <p class="radio-admin-note" id="radio-upload-status" role="status">Use faixas que você tem autorização para disponibilizar no client.</p>
    <div class="radio-upload-footer"><button type="button" class="secondary" id="radio-upload-cancel">Cancelar</button><button class="primary" id="radio-upload-submit">Enviar música</button></div></form>`;
  document.body.append(modal);

  function library() {
    const q = $('#radio-library-search').value.toLocaleLowerCase('pt-BR');
    const tracks = getCatalog().tracks;
    $('#radio-library-count').textContent = String(tracks.length);
    const shown = tracks.filter((t) => `${t.title} ${t.artist} ${t.genre}`.toLocaleLowerCase('pt-BR').includes(q));
    $('#radio-library-items').innerHTML = shown.length
      ? shown
          .map(
            (t) =>
              `<div class="radio-library-item"><div><b>${esc(t.title)}</b><small>${esc(t.artist)} · ${esc(t.genre)} · ${durationLabel(t.durationMs / 1000)}</small></div><button type="button" data-radio-add="${esc(t.id)}" aria-label="Adicionar ${esc(t.title)} à fila" ${queue.includes(t.id) || busy ? 'disabled' : ''}>+</button><button type="button" data-radio-remove="${esc(t.id)}" aria-label="Excluir ${esc(t.title)} da biblioteca" ${busy ? 'disabled' : ''}>×</button></div>`,
          )
          .join('')
      : `<p class="radio-admin-note">${tracks.length ? 'Nenhuma música encontrada.' : 'Sua biblioteca está vazia. Comece enviando a primeira música.'}</p>`;
  }
  function renderQueue() {
    const tracks = new Map(getCatalog().tracks.map((t) => [t.id, t]));
    $('#radio-queue-title').textContent = draft?.mode === 'radio' ? 'Programação ao vivo' : 'Ordem da playlist';
    $('#radio-queue-count').textContent = `${queue.length} músicas`;
    $('#radio-queue').innerHTML = queue.length
      ? queue
          .map((id, index) => {
            const t = tracks.get(id);
            return `<li><span>${index + 1}. ${esc(t?.title || 'Música indisponível')}<small>${esc(t?.artist || '')}</small></span><button type="button" data-queue-up="${index}" aria-label="Mover para cima" ${index === 0 ? 'disabled' : ''}>↑</button><button type="button" data-queue-down="${index}" aria-label="Mover para baixo" ${index === queue.length - 1 ? 'disabled' : ''}>↓</button><button type="button" data-queue-delete="${index}" aria-label="Remover da fila">×</button></li>`;
          })
          .join('')
      : '<li><span class="radio-admin-note">Use o + ao lado das músicas para montar sua seleção.</span></li>';
    library();
  }
  function choose(id, newMode) {
    const collections = getCatalog().collections;
    draft = structuredClone(
      collections.find((c) => c.id === id) || {
        id: null,
        mode: newMode || 'playlist',
        name: '',
        description: '',
        genre: '',
        coverKey: null,
        trackIds: [],
      },
    );
    queue = [...draft.trackIds];
    $('#radio-edit-select').innerHTML =
      `<option value="">Nova ${draft.mode === 'radio' ? 'rádio' : 'playlist'}</option>` +
      collections
        .map(
          (c) => `<option value="${esc(c.id)}">${c.mode === 'radio' ? 'Rádio' : 'Playlist'} · ${esc(c.name)}</option>`,
        )
        .join('');
    $('#radio-edit-select').value = draft.id || '';
    $('#radio-edit-name').value = draft.name;
    $('#radio-edit-description').value = draft.description;
    $('#radio-edit-genre').value = draft.genre;
    $('#radio-edit-genre').readOnly = draft.id === 'antagon';
    $('#radio-edit-cover').value = '';
    $('#radio-edit-cover-state').textContent = draft.coverKey ? '· já definida; envie outra para trocar' : '· opcional';
    $('#radio-edit-delete').hidden = !draft.id || draft.id === 'antagon';
    $('#radio-editor-note').textContent =
      draft.mode === 'radio'
        ? 'A fila se repete para todos. Alterações em uma rádio no ar entram em até 45 segundos, no mesmo momento para os ouvintes.'
        : 'Cada pessoa controla a reprodução desta playlist.';
    dirty = false;
    renderQueue();
  }
  function canChange() {
    return !busy && (!dirty || window.confirm('Descartar as alterações não salvas desta seleção?'));
  }
  $('#radio-edit-select').onchange = () => {
    if (canChange()) choose($('#radio-edit-select').value);
    else $('#radio-edit-select').value = draft?.id || '';
  };
  $('#radio-editor').addEventListener('input', () => {
    dirty = true;
  });
  $('#radio-editor').addEventListener('change', (e) => {
    if (e.target.id !== 'radio-edit-select') dirty = true;
  });
  for (const button of document.querySelectorAll('[data-radio-new]'))
    button.onclick = () => {
      if (canChange()) choose(null, button.dataset.radioNew);
    };
  $('#radio-library-search').oninput = library;
  $('#radio-library-items').onclick = async (event) => {
    const button = event.target.closest('button');
    if (!button || button.disabled || busy) return;
    if (button.dataset.radioAdd) {
      if (queue.length >= 300) return toast('Uma seleção pode ter até 300 músicas.');
      queue.push(button.dataset.radioAdd);
      dirty = true;
      renderQueue();
    } else if (button.dataset.radioRemove) {
      const track = getCatalog().tracks.find((t) => t.id === button.dataset.radioRemove);
      if (
        !track ||
        !window.confirm(
          `Excluir “${track.title}” da biblioteca? Músicas em uso nas seleções precisam ser removidas das filas primeiro.`,
        )
      )
        return;
      button.disabled = true;
      try {
        await api.deleteTrack(track.id);
        queue = queue.filter((id) => id !== track.id);
        await refresh();
        renderQueue();
        toast('Música excluída.');
      } catch (error) {
        toast(error.message);
      } finally {
        button.disabled = false;
      }
    }
  };
  $('#radio-queue').onclick = (event) => {
    const b = event.target.closest('button');
    if (!b || b.disabled || busy) return;
    if (b.dataset.queueDelete !== undefined) queue.splice(Number(b.dataset.queueDelete), 1);
    else {
      const index = Number(b.dataset.queueUp ?? b.dataset.queueDown);
      const next = index + (b.dataset.queueUp !== undefined ? -1 : 1);
      if (next >= 0 && next < queue.length) [queue[index], queue[next]] = [queue[next], queue[index]];
    }
    dirty = true;
    renderQueue();
  };
  $('#radio-editor').onsubmit = async (event) => {
    event.preventDefault();
    if (busy || !draft) return;
    busy = true;
    $('#radio-edit-save').disabled = true;
    $('#radio-edit-save').textContent = 'Salvando…';
    try {
      const saved = await api.saveCollection({
        ...draft,
        name: $('#radio-edit-name').value,
        genre: $('#radio-edit-genre').value,
        description: $('#radio-edit-description').value,
        trackIds: [...queue],
        cover: await imageBytes($('#radio-edit-cover').files[0]),
      });
      dirty = false;
      if (await refresh()) choose(saved.id);
      toast(saved.startsAt ? 'Programação salva. A rádio fará a troca no horário combinado.' : 'Seleção salva.');
    } catch (error) {
      toast(error.message);
    } finally {
      busy = false;
      $('#radio-edit-save').disabled = false;
      $('#radio-edit-save').textContent = 'Salvar programação';
      library();
    }
  };
  $('#radio-edit-delete').onclick = async () => {
    if (busy || !draft?.id || !window.confirm(`Excluir “${draft.name}”? As músicas continuam na biblioteca.`)) return;
    busy = true;
    try {
      await api.deleteCollection(draft.id);
      await refresh();
      choose('antagon');
      toast('Seleção excluída.');
    } catch (error) {
      toast(error.message);
    } finally {
      busy = false;
    }
  };
  $('#radio-admin-refresh').onclick = async () => {
    if (!canChange()) return;
    if (await refresh()) choose(draft?.id || 'antagon');
    else toast('Não foi possível atualizar a biblioteca.');
  };

  function releasePreview() {
    fileGeneration++;
    const preview = $('#radio-upload-preview');
    preview.pause();
    preview.removeAttribute('src');
    preview.load();
    preview.hidden = true;
    if (audioUrl) URL.revokeObjectURL(audioUrl);
    audioUrl = null;
    durationMs = 0;
  }
  $('#radio-upload-open').onclick = () => {
    if (busy) return;
    releasePreview();
    $('#radio-upload-form').reset();
    $('#radio-upload-status').textContent = 'Use faixas que você tem autorização para disponibilizar no client.';
    modal.showModal();
  };
  $('#radio-upload-cancel').onclick = () => {
    if (!busy) modal.close();
  };
  modal.addEventListener('cancel', (e) => {
    if (busy) e.preventDefault();
  });
  modal.addEventListener('close', releasePreview);
  $('#radio-upload-audio').onchange = () => {
    releasePreview();
    const file = $('#radio-upload-audio').files[0];
    if (!file) return;
    if (file.size > 50 * 1024 * 1024) {
      $('#radio-upload-status').textContent = 'O arquivo excede 50 MB.';
      return;
    }
    const generation = fileGeneration;
    const preview = $('#radio-upload-preview');
    audioUrl = URL.createObjectURL(file);
    $('#radio-upload-title').value = file.name.replace(/\.[^.]+$/, '');
    $('#radio-upload-status').textContent = 'Lendo duração do áudio…';
    preview.onloadedmetadata = () => {
      if (generation !== fileGeneration) return;
      durationMs = Math.round(preview.duration * 1000);
      if (!Number.isFinite(durationMs) || durationMs < 1000 || durationMs > 3600000) {
        durationMs = 0;
        $('#radio-upload-status').textContent = 'Use um áudio entre 1 segundo e 1 hora.';
      } else {
        preview.hidden = false;
        $('#radio-upload-status').textContent =
          `${durationLabel(preview.duration)} · ${(file.size / 1024 / 1024).toFixed(1)} MB · Pronta para enviar`;
      }
    };
    preview.onerror = () => {
      if (generation === fileGeneration) {
        durationMs = 0;
        $('#radio-upload-status').textContent = 'Não foi possível ler o áudio. Tente exportá-lo como MP3.';
      }
    };
    preview.src = audioUrl;
  };
  $('#radio-upload-form').onsubmit = async (event) => {
    event.preventDefault();
    if (busy) return;
    const file = $('#radio-upload-audio').files[0];
    if (!file || !durationMs) return toast('Escolha um áudio válido e aguarde a leitura da duração.');
    busy = true;
    $('#radio-upload-submit').disabled = $('#radio-upload-cancel').disabled = true;
    $('#radio-upload-status').textContent = 'Enviando música… mantenha o launcher aberto.';
    $('#radio-upload-preview').pause();
    try {
      await api.addTrack({
        title: $('#radio-upload-title').value,
        artist: $('#radio-upload-artist').value,
        genre: $('#radio-upload-genre').value,
        credits: $('#radio-upload-credits').value,
        durationMs,
        extension: file.name.split('.').pop().toLowerCase(),
        audio: new Uint8Array(await file.arrayBuffer()),
        cover: await imageBytes($('#radio-upload-cover').files[0]),
      });
      modal.close();
      await refresh();
      library();
      toast('Música enviada. Use o + para adicionar à programação.');
    } catch (error) {
      $('#radio-upload-status').textContent = error.message;
    } finally {
      busy = false;
      $('#radio-upload-submit').disabled = $('#radio-upload-cancel').disabled = false;
      library();
    }
  };
  return {
    open: async () => {
      if (busy) return;
      if (dirty) {
        library();
        return;
      }
      await refresh();
      choose(draft?.id || 'antagon');
    },
  };
}
