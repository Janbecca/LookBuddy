const test = require('node:test');
const assert = require('node:assert/strict');
const { resizeBounds, normalizeSize, limitsFor, EDGES } = require('../electron/resize.cjs');
const area = { x: 0, y: 0, width: 2400, height: 1600 };
const start = { x: 900, y: 500, width: 460, height: 460 };

test('every edge and corner scales a square while keeping the opposite anchor fixed', () => {
  for (const edge of EDGES) {
    const hx = edge.includes('e') ? 1 : edge.includes('w') ? -1 : 0;
    const hy = edge.includes('s') ? 1 : edge.includes('n') ? -1 : 0;
    const result = resizeBounds(start, edge, { x: hx * 91, y: hy * 91 }, area);
    assert.equal(result.width, 551, edge);
    assert.equal(result.height, 551, edge);
    const ax = (1 - hx) / 2, ay = (1 - hy) / 2;
    assert.ok(Math.abs(result.x + result.width * ax - start.x - start.width * ax) <= 0.5, edge);
    assert.ok(Math.abs(result.y + result.height * ay - start.y - start.height * ay) <= 0.5, edge);
  }
});

test('limits clamp overshoot without distorting aspect ratio', () => {
  assert.equal(resizeBounds(start, 'se', { x: 10000, y: 10000 }, area).width, 920);
  assert.equal(resizeBounds(start, 'se', { x: -10000, y: -10000 }, area).width, 138);
  assert.equal(normalizeSize(100, area), 138);
  assert.equal(limitsFor(area).min / 460, 0.3);
  assert.equal(normalizeSize(NaN, area), 233);
  assert.equal(normalizeSize(321, area), 321);
  assert.equal(resizeBounds(start, 'e', { x: 1, y: 0 }, area).width, 461);
});

test('screen constraints include negative monitor coordinates and small displays', () => {
  const display = { x: -1280, y: -100, width: 1280, height: 900 };
  const bounds = { x: -1000, y: 100, width: 460, height: 460 };
  for (const edge of EDGES) {
    const result = resizeBounds(bounds, edge, { x: edge.includes('w') ? -10000 : 10000, y: edge.includes('n') ? -10000 : 10000 }, display);
    assert.equal(result.width, result.height);
    assert.ok(result.x >= display.x && result.y >= display.y);
    assert.ok(result.x + result.width <= display.x + display.width);
    assert.ok(result.y + result.height <= display.y + display.height);
  }
  assert.deepEqual(limitsFor({ width: 260, height: 400 }), { min: 138, max: 260, default: 233 });
  assert.deepEqual(limitsFor({ width: 100, height: 400 }), { min: 100, max: 100, default: 100 });
});
