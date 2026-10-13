/** Only observed presence accumulates sitting time; disappearance is not a break. */
export class SedentaryTracker {
  private previous: { at: number; presence: string } | null = null;
  private elapsed = 0;

  observe(now: number, presence: 'present' | 'seated' | 'standing' | 'away'): number {
    if (presence === 'standing') this.elapsed = 0;
    else if (this.previous && ['present', 'seated'].includes(presence)
      && ['present', 'seated'].includes(this.previous.presence)) {
      const duration = now - this.previous.at;
      if (duration >= 0 && duration <= 1500) this.elapsed += duration;
    }
    this.previous = { at: now, presence };
    return this.elapsed;
  }

  get sedentaryMs(): number { return Math.round(this.elapsed); }
}
