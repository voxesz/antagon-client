export function createScene(canvas, mode, draw) {
  const stage = document.getElementById('view-play');
  const motion = matchMedia('(prefers-reduced-motion: reduce)');
  let frame = 0;
  let previous = 0;
  let ready = false;
  let disposed = false;
  let width = 0;
  let height = 0;
  const visible = () =>
    ready &&
    !disposed &&
    !document.hidden &&
    stage.classList.contains('active') &&
    (document.body.dataset.background || 'scene') === mode &&
    !['preparing', 'launching', 'running', 'updating'].includes(document.body.dataset.phase);

  function render(now) {
    frame = 0;
    if (!visible() || !width || !height) return;
    const delta = previous ? Math.min(now - previous, 50) : 1000 / 60;
    if (!motion.matches && previous && delta < 1000 / 60 - 1) {
      frame = requestAnimationFrame(render);
      return;
    }
    previous = now;
    const ratio = Math.min(devicePixelRatio, mode === 'ascii' ? 2 : 1.5);
    const w = Math.max(1, Math.floor(width * ratio));
    const h = Math.max(1, Math.floor(height * ratio));
    const resized = canvas.width !== w || canvas.height !== h;
    if (resized) {
      canvas.width = w;
      canvas.height = h;
    }
    draw(motion.matches ? 0 : now, delta, { w, h, ratio, resized, reducedMotion: motion.matches });
    if (!motion.matches) frame = requestAnimationFrame(render);
    canvas.dataset.animating = String(!!frame);
  }

  function invalidate() {
    if (!visible()) {
      cancelAnimationFrame(frame);
      frame = 0;
      previous = 0;
      canvas.dataset.animating = 'false';
    } else if (!frame) frame = requestAnimationFrame(render);
  }

  const resize = new ResizeObserver(([entry]) => {
    width = entry.contentRect.width;
    height = entry.contentRect.height;
    invalidate();
  });
  const attributes = new MutationObserver(invalidate);
  resize.observe(canvas);
  attributes.observe(document.body, { attributes: true, attributeFilter: ['data-background', 'data-phase'] });
  attributes.observe(stage, { attributes: true, attributeFilter: ['class'] });
  document.addEventListener('visibilitychange', invalidate);
  motion.addEventListener('change', invalidate);
  window.addEventListener(
    'pagehide',
    () => {
      disposed = true;
      invalidate();
      resize.disconnect();
      attributes.disconnect();
      document.removeEventListener('visibilitychange', invalidate);
      motion.removeEventListener('change', invalidate);
    },
    { once: true },
  );

  return {
    invalidate,
    setReady(value) {
      ready = value;
      invalidate();
    },
  };
}

export function trackPointer(stage) {
  const pointer = { x: 0, y: 0 };
  stage.addEventListener('pointermove', (event) => {
    const bounds = stage.getBoundingClientRect();
    pointer.x = ((event.clientX - bounds.left) / bounds.width) * 2 - 1;
    pointer.y = ((event.clientY - bounds.top) / bounds.height) * 2 - 1;
  });
  stage.addEventListener('pointerleave', () => {
    pointer.x = pointer.y = 0;
  });
  return pointer;
}
