import type { ExpressionFeatures } from '../types';

export const EMOTION_LABELS = {
  good: '状态不错', focused: '专注', joyful: '愉悦', possibly_stressed: '可能压力较大',
  possibly_low: '可能低落', possibly_angry: '可能愤怒', unknown: '无法判断',
} as const;

const numerical = ['smile', 'browDown', 'frown', 'mouthPress', 'lookDown', 'eyeClosed'] as const;

export function expressionFeatures(blend: Map<string, number>, flags: Pick<ExpressionFeatures, 'faceVisible' | 'frontal' | 'lightingOk'>): ExpressionFeatures {
  const score = (key: string) => { const value = blend.get(key) ?? 0; return Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0; };
  const both = (key: string) => (score(`${key}Left`) + score(`${key}Right`)) / 2;
  return { ...flags, smile: both('mouthSmile'), browDown: both('browDown'), frown: both('mouthFrown'),
    mouthPress: both('mouthPress'), lookDown: both('eyeLookDown'), eyeClosed: both('eyeBlink') };
}

/** Numeric smoothing only; invalid visibility flags immediately invalidate the sample. */
export class ExpressionSmoother {
  private previous: ExpressionFeatures | null = null;
  private previousAt = 0;
  update(raw: ExpressionFeatures, now: number): ExpressionFeatures {
    if (!raw.faceVisible || !raw.frontal || !raw.lightingOk || !this.previous || now - this.previousAt > 3000) {
      this.previous = raw.faceVisible && raw.frontal && raw.lightingOk ? raw : null;
      this.previousAt = now;
      return raw;
    }
    const alpha = 1 - Math.exp(-Math.max(0, now - this.previousAt) / 2000);
    const result = { ...raw };
    for (const key of numerical) result[key] = this.previous[key] + alpha * (raw[key] - this.previous[key]);
    this.previous = result;
    this.previousAt = now;
    return result;
  }
}
