import { describe, expect, it } from 'vitest';
import { PostureWindow } from './posture-window';

describe('time-weighted posture evidence', () => {
  it('weights elapsed observation time instead of confidence or frame count', () => {
    const window = new PostureWindow(10_000);
    window.observe(0, true, true);
    window.observe(1000, true, true);
    window.observe(2000, false, true);
    window.observe(2500, false, true);
    expect(window.summary(2500).badRatio).toBeCloseTo(0.8);
    expect(window.summary(2500).observedMs).toBe(2500);
  });
  it('does not count gaps or unobservable frames as normal', () => {
    const window = new PostureWindow();
    window.observe(0, true, true);
    window.observe(1000, true, true);
    window.observe(2000, false, false);
    window.observe(10_000, false, true);
    expect(window.summary(10_000).badRatio).toBe(1);
    expect(window.summary(10_000).observedMs).toBe(1000);
    expect(window.summary(10_000).badStreakMs).toBe(0);
  });
  it('clips partially expired intervals to the window', () => {
    const window = new PostureWindow(1500);
    window.observe(0, true, true);
    window.observe(1000, false, true);
    window.observe(2000, false, true);
    expect(window.summary(2000).observedMs).toBe(1500);
    expect(window.summary(2000).badRatio).toBeCloseTo(1 / 3);
  });
  it('resets abnormal streak after missing observation and rejects backwards time', () => {
    const window = new PostureWindow();
    window.observe(0, true, true);
    window.observe(1000, true, true);
    expect(window.summary(1000).badStreakMs).toBe(1000);
    window.observe(4000, true, true);
    expect(window.summary(4000).badStreakMs).toBe(0);
    expect(() => window.observe(3000, true, true)).toThrow();
  });
});
