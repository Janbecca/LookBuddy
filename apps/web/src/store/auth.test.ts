import { afterEach, expect, it, vi } from 'vitest';

afterEach(() => vi.unstubAllGlobals());

async function restore(value: unknown) {
  vi.resetModules();
  vi.stubGlobal('localStorage', { getItem: () => JSON.stringify(value), setItem: vi.fn(), removeItem: vi.fn() });
  return (await import('./auth')).useAuth.getState();
}

it('does not reuse a legacy session with no unified username', async () => {
  const auth = await restore({ accessToken: 'old', refreshToken: 'old-refresh', user: { id: 'legacy', email: 'old@example.com' } });
  expect(auth.user).toBeNull();
  expect(auth.accessToken).toBeNull();
});

it('restores the unified account session without changing the original UI flow', async () => {
  const auth = await restore({ accessToken: 'current', refreshToken: 'refresh', user: { id: 'unified', username: 'shared' } });
  expect(auth.user?.id).toBe('unified');
  expect(auth.accessToken).toBe('current');
});
