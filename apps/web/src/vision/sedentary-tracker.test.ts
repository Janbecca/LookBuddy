import { describe, expect, it } from 'vitest';
import { SedentaryTracker } from './sedentary-tracker';

describe('observed sedentary time', () => {
  it('does not erase sitting history just because a chair leaves view', () => {
    const tracker = new SedentaryTracker();
    tracker.observe(0, 'seated');
    tracker.observe(1000, 'seated');
    tracker.observe(2000, 'away');
    tracker.observe(3000, 'seated');
    expect(tracker.sedentaryMs).toBe(1000);
    tracker.observe(4000, 'seated');
    expect(tracker.sedentaryMs).toBe(2000);
  });
  it('starts a new sitting segment after verified standing', () => {
    const tracker = new SedentaryTracker();
    tracker.observe(0, 'present');
    tracker.observe(1000, 'present');
    expect(tracker.observe(2000, 'standing')).toBe(0);
    tracker.observe(3000, 'away');
    tracker.observe(4000, 'seated');
    expect(tracker.observe(5000, 'seated')).toBe(1000);
  });
  it('does not accumulate time across camera or processing gaps', () => {
    const tracker = new SedentaryTracker();
    tracker.observe(0, 'seated');
    expect(tracker.observe(10_000, 'seated')).toBe(0);
  });
});
