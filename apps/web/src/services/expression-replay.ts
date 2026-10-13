import type { ExpressionFeatures, ExpressionObservation, Intervention } from '../types';
import { api } from './api';

export type ExpressionReplayScenario = 'expression_relaxed' | 'expression_tense' | 'expression_unchanged' | 'expression_gap';

export async function replayExpression(sessionId: string, scenario: ExpressionReplayScenario, learning: boolean): Promise<Intervention> {
  try {
    const previous = await api.debugRequest('/v1/expressions?namespace=demo&limit=1', 'GET') as ExpressionObservation[];
    const clock = Math.max(Date.now(), previous.length ? Date.parse(previous[0].capturedAt) + 1000 : 0);
    const before: ExpressionFeatures = { faceVisible: true, frontal: true, lightingOk: true,
      smile: 0.05, browDown: 0.4, frown: 0.1, mouthPress: 0.4, lookDown: 0.1, eyeClosed: 0.05 };
    for (let second = 0; second <= 30; second++) {
      await api.sendExpression({ expressionId: `exp_${crypto.randomUUID()}`, sessionId, namespace: 'demo', source: 'replay',
        capturedAt: new Date(clock + second * 1000).toISOString(), cameraHealthy: true, quality: 'observable', features: before });
    }
    let intervention = await api.debugRequest('/v1/debug/interventions', 'POST', { sessionId, category: 'emotion', learning }) as Intervention;
    const anchor = Date.parse(intervention.displayedAt ?? intervention.context.observationStartedAt!) + 1000;
    const after: ExpressionFeatures = scenario === 'expression_relaxed'
      ? { ...before, smile: 0.55, browDown: 0.1, mouthPress: 0.1, frown: 0.05 }
      : scenario === 'expression_tense' ? { ...before, browDown: 0.9, mouthPress: 0.8 } : before;
    const end = scenario === 'expression_gap' ? 30 : 150;
    for (let second = 0; second <= end; second += 2) {
      const response = await api.sendExpression({ expressionId: `exp_${crypto.randomUUID()}`, sessionId, namespace: 'demo', source: 'replay',
        capturedAt: new Date(anchor + second * 1000).toISOString(), cameraHealthy: true, quality: 'observable', features: after });
      if (response.intervention) intervention = response.intervention;
    }
    if (scenario === 'expression_gap') {
      const response = await api.sendExpression({ expressionId: `exp_${crypto.randomUUID()}`, sessionId, namespace: 'demo', source: 'replay',
        capturedAt: new Date(anchor + 36_000).toISOString(), cameraHealthy: true, quality: 'observable', features: after });
      if (response.intervention) intervention = response.intervention;
    }
    return intervention;
  } finally { await api.finishSession(sessionId).catch(() => undefined); }
}
