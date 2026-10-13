import type { Intervention, VisionSample } from '../types';
import { api } from './api';
import { replayExpression, type ExpressionReplayScenario } from './expression-replay';

export type ReplayScenario = 'recovery' | 'recurrence' | 'gap' | 'chair' | 'break' | 'return' | 'ignored' | 'snoozed' | ExpressionReplayScenario;

/** Explicit synthetic clock: the same backend verifier runs against demo-only chains. */
export async function replayAction(scenario: ReplayScenario, learning = false): Promise<Intervention> {
  const device = await api.createDevice('动作闭环模拟回放');
  const session = await api.createSession(device.id);
  if (scenario.startsWith('expression_')) return replayExpression(session.id, scenario as ExpressionReplayScenario, learning);
  try {
    const category = ['recovery', 'recurrence', 'gap', 'ignored', 'snoozed'].includes(scenario) ? 'posture' : 'sedentary';
    let intervention = await api.debugRequest('/v1/debug/interventions', 'POST', { sessionId: session.id, category, learning }) as Intervention;
    // JS Date loses sub-millisecond precision; keep the first sample after display.
    const start = Date.parse(intervention.displayedAt ?? intervention.context.observationStartedAt!) + 1000;
    if (scenario === 'ignored' || scenario === 'snoozed') {
      return await api.debugRequest(`/v1/debug/interventions/${intervention.id}/respond`, 'POST', { action: scenario }) as Intervention;
    }
    const frames: { second: number; presence: VisionSample['presence']; posture: VisionSample['posture']; confidence?: number }[] = [];
    if (category === 'posture') {
      const end = scenario === 'recurrence' ? 82 : scenario === 'gap' ? 20 : 140;
      for (let second = 0; second <= end; second += 2) frames.push({ second, presence: 'present', posture: scenario === 'recurrence' && second >= 22 ? 'bad' : 'normal' });
      if (scenario === 'gap') frames.push({ second: 26, presence: 'present', posture: 'normal' });
    } else {
      frames.push({ second: 0, presence: scenario === 'chair' ? 'seated' : 'standing', posture: 'unknown', confidence: scenario === 'chair' ? 0 : 0.8 });
      if (scenario === 'break') {
        for (let second = 2; second <= intervention.context.preferences.breakMinutes * 60 + 2; second += 2) frames.push({ second, presence: 'away', posture: 'unknown' });
      } else {
        frames.push({ second: 2, presence: 'away', posture: 'unknown' });
        if (scenario === 'return') frames.push({ second: 4, presence: 'present', posture: 'unknown' });
      }
    }
    for (const frame of frames) {
      const response = await api.sampleIntervention(intervention.id, {
        frameId: `replay-${frame.second}`, source: 'replay', capturedAt: new Date(start + frame.second * 1000).toISOString(),
        cameraHealthy: true, quality: frame.presence === 'away' ? 'insufficient' : 'observable',
        posture: frame.posture, presence: frame.presence, standingConfidence: frame.confidence ?? 0,
      });
      intervention = response.intervention;
    }
    return intervention;
  } finally {
    await api.finishSession(session.id).catch(() => undefined);
  }
}
