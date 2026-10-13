const LABELS = new Set(['good', 'focused', 'joyful', 'possibly_stressed', 'possibly_low', 'possibly_angry', 'unknown']);
const COMMANDS = new Set(['pause', 'busy', 'accepted', 'snoozed', 'ignored']);
const initialState = () => ({ loggedIn: false, running: false, busy: false, status: '请在调试窗口登录', emotion: 'unknown', posture: 'unknown', reminderId: null, message: '' });
function sanitizeState(value) {
  if (!value || typeof value !== 'object') throw new Error('Invalid desktop state');
  return {
    loggedIn: value.loggedIn === true, running: value.running === true, busy: value.busy === true,
    status: typeof value.status === 'string' ? value.status.slice(0, 100) : '',
    emotion: LABELS.has(value.emotion) ? value.emotion : 'unknown',
    posture: ['normal', 'bad', 'unknown'].includes(value.posture) ? value.posture : 'unknown',
    reminderId: typeof value.reminderId === 'string' && value.reminderId.length <= 80 ? value.reminderId : null,
    message: typeof value.message === 'string' ? value.message.slice(0, 1000) : '',
  };
}
function sanitizeCommand(value, state) {
  if (!value || !COMMANDS.has(value.type)) throw new Error('Invalid desktop command');
  if (['accepted', 'snoozed', 'ignored'].includes(value.type)) {
    if (!state.reminderId || value.reminderId !== state.reminderId) throw new Error('Reminder has changed');
    return { type: value.type, reminderId: state.reminderId };
  }
  if (value.type === 'busy' && typeof value.busy !== 'boolean') throw new Error('Invalid busy state');
  return value.type === 'busy' ? { type: 'busy', busy: value.busy } : { type: 'pause' };
}
module.exports = { initialState, sanitizeState, sanitizeCommand };
