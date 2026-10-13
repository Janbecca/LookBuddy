/** Time-weighted observations; missing frames never count as healthy posture. */
export class PostureWindow {
  private segments: { start: number; end: number; bad: boolean }[] = [];
  private last: { at: number; bad: boolean; valid: boolean } | null = null;
  private badSince: number | null = null;
  constructor(private readonly windowMs = 300_000, private readonly maximumGapMs = 1500) {}

  observe(now: number, bad: boolean, valid: boolean): void {
    if (this.last && now < this.last.at) throw new Error('Observation time must be monotonic');
    const continuous = this.last !== null && now - this.last.at <= this.maximumGapMs;
    if (this.last && continuous && this.last.valid && valid && now > this.last.at) {
      this.segments.push({ start: this.last.at, end: now, bad: this.last.bad });
    }
    if (!valid || !continuous || !bad) this.badSince = null;
    if (valid && bad) this.badSince ??= now;
    this.last = { at: now, bad, valid };
    this.segments = this.segments.filter((segment) => segment.end > now - this.windowMs);
  }

  summary(now: number): { badRatio: number; observedMs: number; badStreakMs: number } {
    let observedMs = 0;
    let badMs = 0;
    for (const segment of this.segments) {
      const duration = Math.max(0, Math.min(segment.end, now) - Math.max(segment.start, now - this.windowMs));
      observedMs += duration;
      if (segment.bad) badMs += duration;
    }
    return {
      badRatio: observedMs ? badMs / observedMs : 0,
      observedMs,
      badStreakMs: this.last?.valid && this.last.bad && now - this.last.at <= this.maximumGapMs && this.badSince !== null
        ? Math.max(0, this.last.at - this.badSince) : 0,
    };
  }
}
