import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const auth = vi.hoisted(() => ({
  accessToken: 'expired' as string | null,
  refreshToken: 'refresh' as string | null,
  user: { id: 'usr' } as { id: string } | null,
  setTokens: vi.fn(), clear: vi.fn(),
}));
vi.mock('../store/auth', () => ({ useAuth: { getState: () => auth } }));

describe('long-running camera session authentication', () => {
  beforeEach(() => {
    vi.resetModules();
    auth.accessToken = 'expired';
    auth.refreshToken = 'refresh';
    auth.user = { id: 'usr' };
    auth.setTokens.mockImplementation((tokens) => {
      auth.accessToken = tokens.accessToken;
      auth.refreshToken = tokens.refreshToken;
      auth.user = tokens.user;
    });
    auth.clear.mockImplementation(() => { auth.accessToken = null; auth.refreshToken = null; auth.user = null; });
    vi.clearAllMocks();
  });
  afterEach(() => vi.unstubAllGlobals());

  it('rotates refresh tokens once for concurrent expired requests then retries', async () => {
    const fetch = vi.fn(async (path: string, init: RequestInit) => {
      if (path === '/v1/auth/refresh') return new Response(JSON.stringify({ accessToken: 'new', refreshToken: 'rotated', user: { id: 'usr' } }), { status: 200 });
      if ((init.headers as Record<string, string>).Authorization === 'Bearer expired') return new Response('{}', { status: 401 });
      return new Response(JSON.stringify({ busy: false }), { status: 200 });
    });
    vi.stubGlobal('fetch', fetch);
    const { api } = await import('./api');
    const results = await Promise.all([api.preferences(), api.preferences()]);
    expect(results.map((result) => result.busy)).toEqual([false, false]);
    expect(fetch.mock.calls.filter(([path]) => path === '/v1/auth/refresh')).toHaveLength(1);
    expect(auth.refreshToken).toBe('rotated');
  });

  it('ends an invalid refresh session without an infinite retry', async () => {
    const fetch = vi.fn(async () => new Response('{}', { status: 401 }));
    vi.stubGlobal('fetch', fetch);
    const { api } = await import('./api');
    await expect(api.preferences()).rejects.toThrow('登录续期失败');
    expect(auth.user).toBeNull();
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('logs into the chosen username without renewing or migrating the old session', async () => {
    const fetch = vi.fn(async () => new Response(JSON.stringify({ accessToken: 'chosen', refreshToken: 'chosen-refresh', user: { id: 'target', username: 'shared' } })));
    vi.stubGlobal('fetch', fetch);
    const { api } = await import('./api');
    const result = await api.register('shared', 'x', 'old');
    expect(result.user.id).toBe('target');
    await api.login('shared', '');
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(fetch).toHaveBeenNthCalledWith(1, '/v1/auth/username', expect.objectContaining({ method: 'POST', body: JSON.stringify({ username: 'shared', password: 'x', displayName: 'old' }) }));
    expect(fetch).toHaveBeenNthCalledWith(2, '/v1/auth/username', expect.objectContaining({ method: 'POST', body: JSON.stringify({ username: 'shared', password: '' }) }));
  });

  it('keeps the session on temporary refresh connection failure', async () => {
    vi.stubGlobal('fetch', vi.fn(async (path: string) => {
      if (path === '/v1/auth/refresh') throw new Error('network offline');
      return new Response('{}', { status: 401 });
    }));
    const { api } = await import('./api');
    await expect(api.preferences()).rejects.toThrow('network offline');
    expect(auth.refreshToken).toBe('refresh');
  });
});
