const { DEFAULT_SIZE } = require('./resize.cjs');
// Migrate exactly once: halving an old user size must not halve again on restart.
function migratePreferences(saved = {}) {
  const legacy = { small: 360, medium: 460, large: 580 };
  if (saved.sizeVersion >= 1) return { ...saved, auto: false };
  const oldWidth = Number.isFinite(saved.width) ? saved.width : legacy[saved.size] || 460;
  const width = Math.max(138, Math.floor(oldWidth / 2));
  return { ...saved, width: Number.isFinite(oldWidth) ? width : DEFAULT_SIZE, sizeVersion: 1, auto: false,
    ...(Number.isFinite(saved.x) ? {x: saved.x + oldWidth - width} : {}),
    ...(Number.isFinite(saved.y) ? {y: saved.y + oldWidth - width} : {}) };
}
module.exports = { migratePreferences };
