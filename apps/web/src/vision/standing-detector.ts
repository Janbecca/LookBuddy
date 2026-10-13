import type { VisionMetrics } from '../types';

/** Conservative 2D heuristic: needs visible legs and a prior bent-knee baseline. */
export class StandingDetector {
  private seated: { hipY: number; torsoHeight: number } | null = null;
  private standingSince: number | null = null;
  private lastAt: number | null = null;

  observe(metrics: Pick<VisionMetrics, 'presence' | 'motion'>, now: number): 'seated' | 'standing' | 'present' | 'away' {
    if (this.lastAt !== null && now - this.lastAt > 1500) this.standingSince = null;
    this.lastAt = now;
    if (!metrics.presence) {
      this.standingSince = null;
      return 'away';
    }
    const motion = metrics.motion;
    if (!motion) {
      this.standingSince = null;
      return 'present';
    }
    if (motion.kneeAngle <= 135) {
      this.seated = { hipY: motion.hipY, torsoHeight: motion.torsoHeight };
      this.standingSince = null;
      return 'seated';
    }
    if (this.seated && motion.kneeAngle >= 155
      && this.seated.hipY - motion.hipY >= Math.max(0.04, this.seated.torsoHeight * 0.2)) {
      this.standingSince ??= now;
      return now - this.standingSince >= 1000 ? 'standing' : 'present';
    }
    this.standingSince = null;
    return 'present';
  }
}
