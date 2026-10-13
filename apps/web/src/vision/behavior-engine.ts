import type { DomainEvent, VisionMetrics } from '../types';

export class BehaviorEngine {
  private seq = 0;
  private presence = false;
  private presenceChangedAt = performance.now();
  private postureStartedAt: number | null = null;
  private fatigueStartedAt: number | null = null;
  private lastFatigueAt = 0;
  private drinkWasFar = false;
  private drinkNearAt: number | null = null;
  private lastDrinkAt = 0;
  private sessionId = '';
  private deviceId = '';

  configure(deviceId: string, sessionId: string): void {
    this.deviceId = deviceId;
    this.sessionId = sessionId;
  }

  update(metrics: VisionMetrics, now = performance.now()): DomainEvent[] {
    const events: DomainEvent[] = [];
    if (metrics.presence !== this.presence && now - this.presenceChangedAt >= 800) {
      this.presence = metrics.presence;
      this.presenceChangedAt = now;
      events.push(this.event(metrics.presence ? 'presence.entered' : 'presence.left', metrics.capturedAt, 0.8, {}));
    } else if (metrics.presence === this.presence) {
      this.presenceChangedAt = now;
    }

    if (metrics.badPosture) {
      this.postureStartedAt ??= now;
    } else if (this.postureStartedAt !== null) {
      const durationMs = Math.round(now - this.postureStartedAt);
      if (durationMs >= 10_000) {
        events.push(this.event('posture.bad_ended', metrics.capturedAt, metrics.postureConfidence, { durationMs }, durationMs));
      }
      this.postureStartedAt = null;
    }

    const fatigueSignal = metrics.eyeClosed >= 0.5 || metrics.jawOpen >= 0.5;
    if (fatigueSignal) {
      this.fatigueStartedAt ??= now;
      if (now - this.fatigueStartedAt >= 1200 && now - this.lastFatigueAt >= 30_000) {
        this.lastFatigueAt = now;
        events.push(this.event(metrics.eyeClosed >= 0.5 ? 'behavior.eyes_closed' : 'behavior.yawn_detected', metrics.capturedAt, 0.75, {}));
      }
    } else {
      this.fatigueStartedAt = null;
    }

    if (metrics.wristMouthDistance !== null) {
      if (metrics.wristMouthDistance >= 0.26) this.drinkWasFar = true;
      if (metrics.wristMouthDistance <= 0.11) {
        this.drinkNearAt ??= now;
        if (this.drinkWasFar && now - this.drinkNearAt >= 280 && now - this.lastDrinkAt >= 30_000) {
          this.lastDrinkAt = now;
          this.drinkWasFar = false;
          events.push(this.event('behavior.drink_detected', metrics.capturedAt, 0.72, {}));
        }
      } else {
        this.drinkNearAt = null;
      }
    }
    return events;
  }

  private event(type: string, occurredAt: string, confidence: number, payload: Record<string, unknown>, durationMs?: number): DomainEvent {
    this.seq += 1;
    return {
      schema: 'lookbuddy.event',
      version: 1,
      eventId: `evt_${crypto.randomUUID().replaceAll('-', '')}`,
      type,
      deviceId: this.deviceId,
      sessionId: this.sessionId,
      seq: this.seq,
      occurredAt,
      source: { kind: 'web_vision', provider: 'lookbuddy', sdk: 'mediapipe' },
      confidence,
      durationMs,
      payload,
      evidence: { method: 'mediapipe_temporal_rule' },
      privacy: { containsImage: false, retention: 'event_only' },
    };
  }
}

