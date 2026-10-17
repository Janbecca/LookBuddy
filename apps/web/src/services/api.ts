import { useAuth } from '../store/auth';
import type { DomainEvent, EmotionLabel, ExpressionInput, ExpressionObservation, Intervention, Preferences, Reminder, TokenPair, VisionSample } from '../types';

let refreshing: Promise<void> | null = null;
// Per-tab claim survives request retries; other consumers must win their own claim.
const displayClaimId = `web:${crypto.randomUUID()}`;

export class ApiError extends Error {
  constructor(public readonly status: number, detail: unknown) {
    super(typeof detail === 'string' ? detail : JSON.stringify(detail));
  }
}

async function refreshTokens(): Promise<void> {
  const previous = useAuth.getState().refreshToken;
  if (!previous) throw new Error('登录已过期，请重新登录');
  const response = await fetch('/v1/auth/refresh', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refreshToken: previous }),
  });
  if (!response.ok) {
    if (response.status === 401 && useAuth.getState().refreshToken === previous) useAuth.getState().clear();
    throw new Error('登录续期失败，请检查连接或重新登录');
  }
  const tokens = await response.json() as TokenPair;
  if (useAuth.getState().refreshToken !== previous) throw new Error('会话已切换，请重新执行请求');
  useAuth.getState().setTokens(tokens);
}

async function request<T>(path: string, init: RequestInit = {}, retried = false): Promise<T> {
  const token = useAuth.getState().accessToken;
  const userId = useAuth.getState().user?.id;
  const response = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init.headers,
    },
  });
  if (response.status === 401 && token && !retried && !path.startsWith('/v1/auth/')) {
    if (useAuth.getState().user?.id !== userId) throw new Error('会话已切换，请重新执行请求');
    if (useAuth.getState().accessToken === token) {
      refreshing ??= refreshTokens().finally(() => { refreshing = null; });
      await refreshing;
    }
    if (useAuth.getState().user?.id !== userId) throw new Error('会话已切换，请重新执行请求');
    return request<T>(path, init, true);
  }
  if (!response.ok) {
    const payload = await response.json().catch(() => null);
    throw new ApiError(response.status, payload?.detail ?? `请求失败：${response.status}`);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export const api = {
  sendExpression: (body: ExpressionInput) => request<{ accepted: boolean; observation: ExpressionObservation; intervention: Intervention | null }>('/v1/expressions', { method: 'POST', body: JSON.stringify(body) }),
  registerCapture: (captureId: string, expressionId: string, capturedAt: string) => request<{ captureId: string }>('/v1/emotions/captures', { method: 'POST', body: JSON.stringify({ captureId, expressionId, capturedAt }) }),
  correctEmotion: (captureId: string, correctionId: string, suggestedLabel: EmotionLabel, confirmedLabel: EmotionLabel) =>
    request('/v1/emotions/corrections', { method: 'POST', body: JSON.stringify({ captureId, correctionId, suggestedLabel, confirmedLabel }) }),
  preferences: () => request<Preferences>('/v1/preferences'),
  updatePreferences: (body: Partial<Omit<Preferences, 'updatedAt'>>) =>
    request<Preferences>('/v1/preferences', { method: 'PATCH', body: JSON.stringify(body) }),
  debugRequest: (path: string, method: string, body?: unknown) => {
    if (!path.startsWith('/v1/') || path.includes('..') || path.includes('\\')) throw new Error('仅允许本项目 /v1/ API');
    return request<unknown>(path, { method, body: method === 'GET' || body === undefined ? undefined : JSON.stringify(body) });
  },
  register: (email: string, password: string, displayName: string) =>
    request<TokenPair>('/v1/auth/username', { method: 'POST', body: JSON.stringify({ username: email, password, displayName }) }),
  login: (email: string, password: string) =>
    request<TokenPair>('/v1/auth/username', { method: 'POST', body: JSON.stringify({ username: email, password }) }),
  createDevice: (name = 'Web 感知端') =>
    request<{ id: string }>('/v1/devices', { method: 'POST', body: JSON.stringify({ name, platform: 'web' }) }),
  createSession: (deviceId: string) =>
    request<{ id: string }>('/v1/sessions', { method: 'POST', body: JSON.stringify({ deviceId }) }),
  finishSession: (sessionId: string) => request(`/v1/sessions/${sessionId}/finish`, { method: 'POST' }),
  sendEvents: (events: DomainEvent[]) =>
    request<{ accepted: number; duplicates: number }>('/v1/events/batch', { method: 'POST', body: JSON.stringify({ events }) }),
  sendSnapshot: (body: Record<string, unknown>) =>
    request<{ reminder: Reminder | null; intervention: Intervention | null }>('/v1/state-snapshots', { method: 'POST', body: JSON.stringify(body) }),
  interventions: (namespace: 'live' | 'demo' = 'live') => request<Intervention[]>(`/v1/interventions?namespace=${namespace}`),
  intervention: (id: string) => request<Intervention>(`/v1/interventions/${id}`),
  pendingReminders: () => request<Reminder[]>('/v1/reminders/pending'),
  displayIntervention: (id: string) => request<Intervention>(`/v1/interventions/${id}/displayed`, { method: 'POST', body: JSON.stringify({ claimId: displayClaimId }) }),
  sampleIntervention: (id: string, body: VisionSample) =>
    request<{ accepted: boolean; intervention: Intervention }>(`/v1/interventions/${id}/samples`, { method: 'POST', body: JSON.stringify(body) }),
  respondReminder: (id: string, action: 'accepted' | 'snoozed' | 'ignored') =>
    request<Reminder>(`/v1/reminders/${id}/respond`, { method: 'POST', body: JSON.stringify({ action, claimId: displayClaimId, snoozeMinutes: action === 'snoozed' ? 10 : undefined }) }),
  generateDiary: (date: string) => request<{ content: string; candidates: unknown[] }>(`/v1/diaries/${date}/generate`, { method: 'POST' }),
};

