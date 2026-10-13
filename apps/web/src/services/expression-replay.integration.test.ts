import { randomUUID } from 'node:crypto';
import { expect, it, vi } from 'vitest';
import type { TokenPair, User } from '../types';

const auth = vi.hoisted(() => ({ accessToken: null as string | null, refreshToken: null as string | null, user: null as User | null }));
vi.mock('../store/auth', () => ({ useAuth: { getState: () => ({ ...auth,
  setTokens: (tokens: TokenPair) => { auth.accessToken = tokens.accessToken; auth.refreshToken = tokens.refreshToken; auth.user = tokens.user; },
  clear: () => { auth.accessToken = null; auth.refreshToken = null; auth.user = null; },
}) } }));

const base = process.env.LOOKBUDDY_INTEGRATION_BASE_URL;

it.skipIf(!base)('runs all 12 Web rehearsals against HTTP and keeps live data isolated', async () => {
  const nativeFetch = globalThis.fetch.bind(globalThis);
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => nativeFetch(typeof input === 'string' ? new URL(input, base) : input, init));
  vi.stubGlobal('crypto', { randomUUID });
  const { api } = await import('./api');
  const { replayAction } = await import('./action-replay');
  let created = false;
  try {
    const tokens = await api.register(`expression-check-${randomUUID()}@example.com`, 'lookbuddy-integration', '表情回放验证');
    auth.accessToken = tokens.accessToken;
    auth.refreshToken = tokens.refreshToken;
    auth.user = tokens.user;
    created = true;
    const expected = {
      recovery: 'improved', recurrence: 'recurred', gap: 'unknown', chair: 'unknown',
      break: 'improved', return: 'interrupted', ignored: 'ignored', snoozed: 'snoozed',
      expression_relaxed: 'expression_relaxed', expression_tense: 'expression_tense',
      expression_unchanged: 'expression_unchanged', expression_gap: 'unknown',
    } as const;
    await api.updatePreferences({ breakMinutes: 5 });
    for (const scenario of Object.keys(expected) as (keyof typeof expected)[]) {
      const result = await replayAction(scenario, false);
      expect(result.status, scenario).toBe(expected[scenario]);
    }
    expect(await api.debugRequest('/v1/learning/rewards?namespace=live', 'GET')).toEqual([]);
    expect(await api.debugRequest('/v1/learning/rewards?namespace=demo', 'GET')).toEqual([]);
    // Separate learning from fixed-outcome fixtures: adaptive selection may be silent.
    expect((await replayAction('recovery', true)).status).toBe('improved');
    expect((await replayAction('expression_relaxed', true)).status).toBe('expression_relaxed');
    const rewards = await api.debugRequest('/v1/learning/rewards?namespace=demo', 'GET') as { key: string }[];
    expect(rewards.some((row) => row.key === 'expression_shift')).toBe(true);
    expect(await api.debugRequest('/v1/learning/states?namespace=demo', 'GET')).not.toEqual([]);
    expect(await api.debugRequest('/v1/learning/rewards?namespace=live', 'GET')).toEqual([]);
  } finally {
    if (created) await api.debugRequest('/v1/account', 'DELETE').catch(() => undefined);
    vi.unstubAllGlobals();
  }
}, 90_000);
