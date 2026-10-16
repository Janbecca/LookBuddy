// Android/backend event vocabulary; unknown labels must never imply an emotion.
function sceneAction(event = {}, fallback = 'pet-1') {
  const value = event.scene || event.category || event.kind || '';
  const scene = typeof value === 'string' ? value : '';
  const label = event.label || event.mood || event.suggestedLabel || '';
  const direct = { drink: 'drink', sedentary: 'stretch', stand: 'stretch', posture: 'posture', fatigue: 'fatigue',
    happy: 'happy', joyful: 'happy', good: 'happy', worried: 'concern',
    possibly_stressed: 'concern', possibly_low: 'concern', possibly_angry: 'concern' };
  if (Object.hasOwn(direct, scene)) return direct[scene];
  if (scene === 'emotion') {
    if (['joyful', 'good'].includes(label)) return 'happy';
    if (['possibly_stressed', 'possibly_low', 'possibly_angry'].includes(label)) return 'concern';
    return fallback;
  }
  if (scene.startsWith('morning_')) return 'morning';
  if (scene === 'evening' || scene === 'evening_rain' || scene === 'night') return 'night';
  if (scene === 'diary_ready') return fallback;
  return fallback;
}
module.exports = { sceneAction };
