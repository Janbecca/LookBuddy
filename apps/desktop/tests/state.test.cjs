const { test } = require('node:test');
const assert = require('node:assert/strict');
const { initialState, sanitizeState, sanitizeCommand } = require('../state.cjs');
test('state exports only safe bounded display fields', () => {
  const state = sanitizeState({ loggedIn: true, emotion: 'joyful', status: 'x'.repeat(1000), message: 'x'.repeat(2000), token: 'secret', image: 'data:fake' });
  assert.equal(state.emotion, 'joyful'); assert.equal(state.status.length, 100); assert.equal(state.message.length, 1000);
  assert.equal(state.token, undefined); assert.equal(state.image, undefined); assert.equal(state.posture, 'unknown');
});
test('malformed labels and oversized IDs become unknown', () => {
  const state = sanitizeState({ emotion: '<script>', posture: 'fake', reminderId: 'x'.repeat(81) });
  assert.equal(state.emotion, 'unknown'); assert.equal(state.reminderId, null);
  assert.throws(() => sanitizeState(null));
});
test('reminder commands require current reminder', () => {
  assert.throws(() => sanitizeCommand({ type: 'ignored', reminderId: 'old' }, initialState()));
  assert.deepEqual(sanitizeCommand({ type: 'accepted', reminderId: 'new', extra: 'secret' }, { reminderId: 'new' }), { type: 'accepted', reminderId: 'new' });
});
test('only explicit busy and pause control are permitted', () => {
  assert.deepEqual(sanitizeCommand({ type: 'pause', extra: 'secret' }, initialState()), { type: 'pause' });
  assert.deepEqual(sanitizeCommand({ type: 'busy', busy: true }, initialState()), { type: 'busy', busy: true });
  assert.throws(() => sanitizeCommand({ type: 'busy', busy: 'true' }, initialState()));
  assert.throws(() => sanitizeCommand({ type: 'start-camera' }, initialState()));
});
