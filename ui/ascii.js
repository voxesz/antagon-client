import { createScene, trackPointer } from './scene.js';

(() => {
  const asciiCanvas = document.getElementById('ascii');
  const asciiStage = document.getElementById('view-play');
  const ctx = asciiCanvas.getContext('2d');
  const GRID_W = 72;
  const GRID_H = 57;
  const DEPTH = 12;
  const LIGHT = (() => {
    const l = [-0.45, -0.55, 0.7];
    const n = Math.hypot(...l);
    return l.map((v) => v / n);
  })();
  let voxels = [];
  let ax = 0;
  let ay = 0;
  const pointer = trackPointer(asciiStage);
  let depth = new Float32Array(0),
    light = new Float32Array(0);
  const palette = Array.from({ length: 64 }, (_, index) => {
    const b = 0.18 + (index / 63) * 0.82;
    const red = Math.round(60 + 195 * b);
    const green = Math.min(190, Math.round(8 + (b > 0.82 ? (b - 0.82) * 900 : 12 * b)));
    return `rgb(${red},${green},${green})`;
  });
  const scene = createScene(asciiCanvas, 'ascii', drawAscii);

  function buildVoxels(image) {
    const mask = document.createElement('canvas');
    mask.width = GRID_W;
    mask.height = GRID_H;
    const m = mask.getContext('2d');
    m.drawImage(image, 0, 0, GRID_W, GRID_H);
    const alpha = m.getImageData(0, 0, GRID_W, GRID_H).data;
    const solid = (i, j) => i >= 0 && j >= 0 && i < GRID_W && j < GRID_H && alpha[(j * GRID_W + i) * 4 + 3] > 110;
    const list = [];
    for (let j = 0; j < GRID_H; j++)
      for (let i = 0; i < GRID_W; i++) {
        if (!solid(i, j)) continue;
        const nx = (solid(i - 1, j) ? 0 : -1) + (solid(i + 1, j) ? 0 : 1);
        const ny = (solid(i, j - 1) ? 0 : -1) + (solid(i, j + 1) ? 0 : 1);
        const edge = nx || ny;
        for (let k = 0; k < DEPTH; k++) {
          const face = k === 0 ? 1 : k === DEPTH - 1 ? -1 : 0;
          if (!face && !edge) continue;
          const n = face ? [0, 0, face] : [nx, ny, 0];
          const len = Math.hypot(...n);
          list.push([i - GRID_W / 2, j - GRID_H / 2, DEPTH / 2 - k - 0.5, n[0] / len, n[1] / len, n[2] / len]);
        }
      }
    voxels = list;
    scene.setReady(true);
  }

  const logo = new Image();
  logo.onload = () => buildVoxels(logo);
  logo.src = '../assets/g-light.svg';

  function drawAscii(now, delta, { w, h, ratio, reducedMotion }) {
    const font = Math.round(11 * ratio);
    ctx.font = `600 ${font}px ui-monospace, Menlo, monospace`;
    const cw = ctx.measureText('@').width;
    const ch = font * 1.05;
    const cols = Math.ceil(w / cw);
    const rows = Math.ceil(h / ch);
    const t = now / 1000;
    const smoothing = 1 - Math.exp(-delta / 270);
    ax = reducedMotion ? 0 : ax + (pointer.y * 0.55 + Math.sin(t * 0.37) * 0.08 - ax) * smoothing;
    ay = reducedMotion ? 0 : ay + (pointer.x * 0.9 + Math.sin(t * 0.5) * 0.22 - ay) * smoothing;
    const [sy, cy, sx, cx] = [Math.sin(ay), Math.cos(ay), Math.sin(ax), Math.cos(ax)];
    const scale = (h * 0.6) / GRID_H;
    if (depth.length !== cols * rows) {
      depth = new Float32Array(cols * rows);
      light = new Float32Array(cols * rows);
    }
    depth.fill(-Infinity);
    for (const [x, y, z, nx, ny, nz] of voxels) {
      const x1 = x * cy + z * sy;
      const z1 = -x * sy + z * cy;
      const y2 = y * cx - z1 * sx;
      const z2 = y * sx + z1 * cx;
      const perspective = 1 + z2 * 0.012;
      const col = Math.floor((w / 2 + x1 * scale * perspective) / cw);
      const row = Math.floor((h / 2 + y2 * scale * perspective - h * 0.04) / ch);
      if (col < 0 || row < 0 || col >= cols || row >= rows) continue;
      const cell = row * cols + col;
      if (z2 <= depth[cell]) continue;
      depth[cell] = z2;
      const n1x = nx * cy + nz * sy;
      const n1z = -nx * sy + nz * cy;
      const n2y = ny * cx - n1z * sx;
      const n2z = ny * sx + n1z * cx;
      light[cell] = Math.max(0, n1x * LIGHT[0] + n2y * LIGHT[1] + n2z * LIGHT[2]);
    }
    ctx.fillStyle = '#0d0d0d';
    ctx.fillRect(0, 0, w, h);
    ctx.textBaseline = 'top';
    for (let cell = 0; cell < depth.length; cell++) {
      if (depth[cell] === -Infinity) continue;
      ctx.fillStyle = palette[Math.min(63, Math.round(light[cell] * 63))];
      ctx.fillText('@', (cell % cols) * cw, Math.floor(cell / cols) * ch);
    }
  }
})();
