import { describe, expect, it } from 'vitest';
import { StandingDetector } from './standing-detector';

describe('conservative standing evidence', () => {
  it('requires a bent-knee baseline, hip rise, straight legs and a sustained transition', () => {
    const detector = new StandingDetector();
    expect(detector.observe({ presence: true, motion: { hipY: 0.7, torsoHeight: 0.3, kneeAngle: 110 } }, 0)).toBe('seated');
    expect(detector.observe({ presence: true, motion: { hipY: 0.55, torsoHeight: 0.3, kneeAngle: 170 } }, 200)).toBe('present');
    expect(detector.observe({ presence: true, motion: { hipY: 0.55, torsoHeight: 0.3, kneeAngle: 170 } }, 1200)).toBe('standing');
    expect(detector.observe({ presence: false, motion: null }, 1400)).toBe('away');
  });
  it('does not validate rolling away in a chair or missing legs', () => {
    const detector = new StandingDetector();
    detector.observe({ presence: true, motion: { hipY: 0.7, torsoHeight: 0.3, kneeAngle: 110 } }, 0);
    expect(detector.observe({ presence: true, motion: { hipY: 0.7, torsoHeight: 0.3, kneeAngle: 110 } }, 1000)).toBe('seated');
    expect(detector.observe({ presence: true, motion: null }, 2000)).toBe('present');
    expect(detector.observe({ presence: false, motion: null }, 2200)).toBe('away');
  });
  it('does not accept a standing-only view or bridge an observation gap', () => {
    const detector = new StandingDetector();
    expect(detector.observe({ presence: true, motion: { hipY: 0.55, torsoHeight: 0.3, kneeAngle: 170 } }, 0)).toBe('present');
    detector.observe({ presence: true, motion: { hipY: 0.7, torsoHeight: 0.3, kneeAngle: 110 } }, 1000);
    detector.observe({ presence: true, motion: { hipY: 0.55, torsoHeight: 0.3, kneeAngle: 170 } }, 1200);
    expect(detector.observe({ presence: true, motion: { hipY: 0.55, torsoHeight: 0.3, kneeAngle: 170 } }, 4000)).toBe('present');
  });
});
