/**
 * heatmap.js — Canvas path renderer for The P.A.C.K.
 *
 * Renders robot position paths on the field canvas.
 * Matches the gradient style of the original Visualization.java:
 *   Auto:   pink → red
 *   Teleop: cyan → blue
 *
 * For multi-team overlays, hue-shifts the palette per team index.
 */

// Team palette — each entry: [autoStart, autoEnd, teleStart, teleEnd]
// Colors are CSS color strings accepted by CanvasGradient
const TEAM_PALETTES = [
  // Team 0 (primary): pink→red / cyan→blue  (matches original Java viz)
  ['#ff80ab', '#c62828', '#80deea', '#0d47a1'],
  // Team 1: lime→dark-green / yellow→orange
  ['#ccff90', '#1b5e20', '#fff176', '#e65100'],
  // Team 2: lavender→purple / mint→teal
  ['#e040fb', '#4a148c', '#69f0ae', '#004d40'],
  // Team 3: peach→brown / sky→navy
  ['#ffcc80', '#4e342e', '#81d4fa', '#0d47a1'],
];

/**
 * renderHeatmap(ctx, data, mode, W, H, teamIndex, totalTeams)
 *
 * @param {CanvasRenderingContext2D} ctx
 * @param {{auto: {x,y}[], tele: {x,y}[]}} data
 * @param {'auto'|'tele'|'both'} mode
 * @param {number} W   canvas width in pixels
 * @param {number} H   canvas height in pixels
 * @param {number} teamIndex  0-based index for palette selection
 * @param {number} totalTeams total teams being rendered (unused, kept for API compat)
 */
function renderHeatmap(ctx, data, mode, W, H, teamIndex = 0, totalTeams = 1) {
  const pal = TEAM_PALETTES[teamIndex % TEAM_PALETTES.length];
  const [autoStart, autoEnd, teleStart, teleEnd] = pal;

  if ((mode === 'auto' || mode === 'both') && data.auto && data.auto.length > 1) {
    drawPath(ctx, data.auto, autoStart, autoEnd, W, H);
  }
  if ((mode === 'tele' || mode === 'both') && data.tele && data.tele.length > 1) {
    drawPath(ctx, data.tele, teleStart, teleEnd, W, H);
  }
}

/**
 * Draw a sequence of points as gradient line segments.
 * @param {CanvasRenderingContext2D} ctx
 * @param {{x,y}[]} points   normalized 0-1 coordinates
 * @param {string} colorStart  CSS color at beginning of path
 * @param {string} colorEnd    CSS color at end of path
 * @param {number} W
 * @param {number} H
 */
function drawPath(ctx, points, colorStart, colorEnd, W, H) {
  if (points.length < 2) return;

  // Skip every other point to reduce clutter (matches Java: i+=2)
  const pts = points.filter((_, i) => i % 2 === 0);
  if (pts.length < 2) return;

  ctx.save();
  ctx.lineWidth = Math.max(2, W / 300);
  ctx.lineCap   = 'round';
  ctx.lineJoin  = 'round';

  // Parse start/end colors to interpolate per-segment
  const c0 = parseColor(colorStart);
  const c1 = parseColor(colorEnd);

  for (let i = 0; i < pts.length - 1; i++) {
    const t0 = i / (pts.length - 1);
    const t1 = (i + 1) / (pts.length - 1);
    const r0 = lerp(c0.r, c1.r, t0), g0 = lerp(c0.g, c1.g, t0), b0 = lerp(c0.b, c1.b, t0);
    const r1 = lerp(c0.r, c1.r, t1), g1 = lerp(c0.g, c1.g, t1), b1 = lerp(c0.b, c1.b, t1);

    const x0 = pts[i].x   * W;
    const y0 = pts[i].y   * H;
    const x1 = pts[i+1].x * W;
    const y1 = pts[i+1].y * H;

    const grad = ctx.createLinearGradient(x0, y0, x1, y1);
    grad.addColorStop(0, `rgb(${r0|0},${g0|0},${b0|0})`);
    grad.addColorStop(1, `rgb(${r1|0},${g1|0},${b1|0})`);

    ctx.strokeStyle = grad;
    ctx.globalAlpha = 0.8;
    ctx.beginPath();
    ctx.moveTo(x0, y0);
    ctx.lineTo(x1, y1);
    ctx.stroke();
  }

  // Draw start dot
  const sc = c0;
  ctx.globalAlpha = 1;
  ctx.fillStyle = `rgb(${sc.r},${sc.g},${sc.b})`;
  ctx.beginPath();
  ctx.arc(pts[0].x * W, pts[0].y * H, ctx.lineWidth * 1.5, 0, Math.PI * 2);
  ctx.fill();

  ctx.restore();
}

// ── Utilities ──────────────────────────────────────────────────────────────────

function lerp(a, b, t) { return a + (b - a) * t; }

/** Parse a CSS hex color (#rrggbb or #rgb) into {r,g,b} */
function parseColor(css) {
  const s = css.trim();
  if (s[0] === '#') {
    let hex = s.slice(1);
    if (hex.length === 3) hex = hex.split('').map(c => c+c).join('');
    return {
      r: parseInt(hex.slice(0,2), 16),
      g: parseInt(hex.slice(2,4), 16),
      b: parseInt(hex.slice(4,6), 16),
    };
  }
  // Fallback: white
  return { r: 255, g: 255, b: 255 };
}
