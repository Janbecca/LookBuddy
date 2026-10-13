import { beforeEach, expect, it, vi } from 'vitest';
const mock = vi.hoisted(() => ({
  createDevice: vi.fn(), createSession: vi.fn(), debugRequest: vi.fn(),
  sampleIntervention: vi.fn(), finishSession: vi.fn(),
}));
vi.mock('./api', () => ({ api: mock }));
import { replayAction } from './action-replay';

beforeEach(() => {
  vi.resetAllMocks();
  mock.createDevice.mockResolvedValue({ id: 'device' });
  mock.createSession.mockResolvedValue({ id: 'session' });
  mock.finishSession.mockResolvedValue(undefined);
});

it('finishes an already-created session when intervention creation fails', async () => {
  mock.debugRequest.mockRejectedValue(new Error('backend unavailable'));
  await expect(replayAction('recovery')).rejects.toThrow('backend unavailable');
  expect(mock.finishSession).toHaveBeenCalledExactlyOnceWith('session');
});

it('preserves the original replay error if cleanup also fails', async () => {
  mock.debugRequest.mockRejectedValue(new Error('original error'));
  mock.finishSession.mockRejectedValue(new Error('cleanup error'));
  await expect(replayAction('chair')).rejects.toThrow('original error');
  expect(mock.finishSession).toHaveBeenCalledOnce();
});

it('finishes a session if sampling fails', async () => {
  mock.debugRequest.mockResolvedValue({ id: 'intervention', displayedAt: '2026-10-10T00:00:00Z', context: {} });
  mock.sampleIntervention.mockRejectedValue(new Error('sample error'));
  await expect(replayAction('recovery')).rejects.toThrow('sample error');
  expect(mock.finishSession).toHaveBeenCalledExactlyOnceWith('session');
});

it('finishes ignored replay exactly once after returning its response', async () => {
  mock.debugRequest.mockResolvedValueOnce({ id: 'intervention', displayedAt: '2026-10-10T00:00:00Z', context: {} })
    .mockResolvedValueOnce({ status: 'ignored' });
  expect(await replayAction('ignored')).toEqual({ status: 'ignored' });
  expect(mock.finishSession).toHaveBeenCalledExactlyOnceWith('session');
});
