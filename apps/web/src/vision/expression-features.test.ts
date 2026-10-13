import { describe, expect, it } from 'vitest';
import { expressionFeatures, ExpressionSmoother } from './expression-features';

describe('local semantic expression features', () => {
  const flags = { faceVisible: true, frontal: true, lightingOk: true };
  it('combines paired blendshapes and bounds numerical values', () => {
    const features = expressionFeatures(new Map([['mouthSmileLeft', 0.6], ['mouthSmileRight', 0.4], ['browDownLeft', 2]]), flags);
    expect(features.smile).toBe(0.5);
    expect(features.browDown).toBe(0.5);
    expect(features.frown).toBe(0);
  });
  it('smooths brief changes but never smooths over lost visibility', () => {
    const smoother = new ExpressionSmoother();
    const before = expressionFeatures(new Map(), flags);
    smoother.update(before, 0);
    expect(smoother.update({ ...before, smile: 1 }, 200).smile).toBeLessThan(0.1);
    expect(smoother.update({ ...before, faceVisible: false }, 400).faceVisible).toBe(false);
    expect(smoother.update({ ...before, smile: 1 }, 600).smile).toBe(1);
  });
  it('does not carry expression history across long gaps', () => {
    const smoother = new ExpressionSmoother();
    const before = expressionFeatures(new Map(), flags);
    smoother.update(before, 0);
    expect(smoother.update({ ...before, smile: 1 }, 5000).smile).toBe(1);
  });
});
