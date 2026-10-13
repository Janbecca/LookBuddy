import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { EufyWebSdkFrameSource } from './eufy-frame-source';

const mocks = vi.hoisted(() => ({ subscribe: vi.fn() }));

vi.mock('../eufy/sdk-loader', () => ({
  loadEufyWebSdk: vi.fn(async () => ({ subscribeMediaChannelFrames: mocks.subscribe })),
}));

beforeEach(() => {
  vi.stubGlobal('window', { setTimeout, clearTimeout });
});

afterEach(() => {
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});

describe('eufy Web SDK frame source', () => {
  it('publishes the latest decoded frame and releases it on stop', async () => {
    let onFrame!: (frame: { blob: Blob; width: number; height: number; timestamp: number; capturedAt: number }) => void;
    const unsubscribe = vi.fn();
    const image = { width: 1920, height: 1080, close: vi.fn() };
    mocks.subscribe.mockImplementation((_stationSn, callback) => { onFrame = callback; return unsubscribe; });
    vi.stubGlobal('createImageBitmap', vi.fn(async () => image));
    const source = new EufyWebSdkFrameSource('T8214-test');

    const started = source.start({} as HTMLVideoElement);
    await vi.waitFor(() => expect(mocks.subscribe).toHaveBeenCalledOnce());
    onFrame({ blob: new Blob(), width: 1920, height: 1080, timestamp: 1, capturedAt: 1 });
    await started;

    expect(source.currentFrame()).toBe(image);
    expect(source.currentToken()).toBe(1);
    await source.stop();
    expect(unsubscribe).toHaveBeenCalledOnce();
    expect(image.close).toHaveBeenCalledOnce();
  });

  it('propagates subscription errors and cleans up synchronous subscriptions', async () => {
    const unsubscribe = vi.fn();
    mocks.subscribe.mockImplementation((_stationSn, _callback, options) => {
      options.onError(new Error('transport failed'));
      return unsubscribe;
    });
    const source = new EufyWebSdkFrameSource('T8171-test');

    await expect(source.start({} as HTMLVideoElement)).rejects.toThrow('transport failed');
    expect(unsubscribe).toHaveBeenCalledOnce();
  });
});
