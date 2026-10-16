// All coordinates are Electron logical pixels (DIP), independent of Windows scaling.
const MIN_SIZE = 138; // 460 DIP × 30%.
const MAX_SIZE = 920;
const DEFAULT_SIZE = 233;
const EDGES = new Set(['n', 'ne', 'e', 'se', 's', 'sw', 'w', 'nw']);

function limitsFor(area) {
  const max = Math.max(1, Math.floor(Math.min(MAX_SIZE, area.width, area.height)));
  return { min: Math.min(MIN_SIZE, max), max, default: Math.min(DEFAULT_SIZE, max) };
}

function normalizeSize(value, area) {
  const limits = limitsFor(area);
  return Math.round(Math.max(limits.min, Math.min(limits.max, Number.isFinite(value) ? value : limits.default)));
}

function resizeBounds(start, edge, delta, area) {
  if (!EDGES.has(edge)) return { ...start };
  const horizontal = edge.includes('e') ? 1 : edge.includes('w') ? -1 : 0;
  const vertical = edge.includes('s') ? 1 : edge.includes('n') ? -1 : 0;
  // Edges fix the opposite edge's midpoint. Corners fix the opposite corner.
  const ax = (1 - horizontal) / 2;
  const ay = (1 - vertical) / 2;
  const anchorX = start.x + start.width * ax;
  const anchorY = start.y + start.height * ay;
  const change = (horizontal * delta.x + vertical * delta.y) / (Math.abs(horizontal) + Math.abs(vertical));
  const available = [
    ax > 0 ? (anchorX - area.x) / ax : Infinity,
    ax < 1 ? (area.x + area.width - anchorX) / (1 - ax) : Infinity,
    ay > 0 ? (anchorY - area.y) / ay : Infinity,
    ay < 1 ? (area.y + area.height - anchorY) / (1 - ay) : Infinity,
  ];
  const limits = limitsFor(area);
  const max = Math.max(1, Math.floor(Math.min(limits.max, ...available)));
  const side = Math.round(Math.max(Math.min(limits.min, max), Math.min(max, start.width + change)));
  return { x: Math.round(anchorX - side * ax), y: Math.round(anchorY - side * ay), width: side, height: side };
}

module.exports = { MIN_SIZE, MAX_SIZE, DEFAULT_SIZE, EDGES, limitsFor, normalizeSize, resizeBounds };
