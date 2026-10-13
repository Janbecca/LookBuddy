import { afterEach, describe, expect, it, vi } from 'vitest';
import { WebcamFrameSource } from './frame-source';
afterEach(() => vi.unstubAllGlobals());
describe('camera lifecycle', () => {
  it('stops a late permission stream without attaching after pause', async () => {
    let resolve!: (stream: MediaStream) => void;
    const pending = new Promise<MediaStream>((done) => { resolve = done; });
    vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: vi.fn(() => pending) } });
    const track = { stop: vi.fn() };
    const video = { srcObject: null, play: vi.fn() };
    const source = new WebcamFrameSource();
    const started = source.start(video as unknown as HTMLVideoElement);
    const rejection = expect(started).rejects.toThrow('已取消');
    await Promise.resolve();
    await source.stop();
    resolve({ getTracks: () => [track] } as unknown as MediaStream);
    await rejection;
    expect(track.stop).toHaveBeenCalledOnce(); expect(video.play).not.toHaveBeenCalled(); expect(video.srcObject).toBeNull();
  });
  it('releases stream on playback failure', async () => {
    const track = { stop: vi.fn() };
    vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: vi.fn(async () => ({ getTracks: () => [track] })) } });
    const video = { srcObject: null, play: vi.fn(async () => { throw new Error('playback failed'); }) };
    await expect(new WebcamFrameSource().start(video as unknown as HTMLVideoElement)).rejects.toThrow('playback failed');
    expect(track.stop).toHaveBeenCalledOnce(); expect(video.srcObject).toBeNull();
  });
  it('stop clears video and every active camera track', async () => {
    const track = { stop: vi.fn() };
    vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: vi.fn(async () => ({ getTracks: () => [track] })) } });
    const video = { srcObject: null, play: vi.fn(async () => undefined) };
    const source = new WebcamFrameSource();
    await source.start(video as unknown as HTMLVideoElement); await source.stop();
    expect(track.stop).toHaveBeenCalledOnce(); expect(video.srcObject).toBeNull();
  });
});
