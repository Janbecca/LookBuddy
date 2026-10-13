import type { FrameSource } from './frame-source';
import { loadEufyWebSdk } from '../eufy/sdk-loader';

const START_TIMEOUT_MS = 30_000;
const RETRY_MS = 500;

export class EufyWebSdkFrameSource implements FrameSource {
  readonly kind = 'eufy_web_sdk';
  private latest: ImageBitmap | null = null;
  private unsubscribe: (() => void) | null = null;
  private generation = 0;
  private token = 0;
  private converting = false;

  constructor(private readonly stationSn: string, private readonly intervalMs = 300) {}

  async start(_video: HTMLVideoElement): Promise<void> {
    await this.stop();
    const generation = ++this.generation;
    const sdk = await loadEufyWebSdk();
    const startedAt = Date.now();

    await new Promise<void>((resolve, reject) => {
      let settled = false;
      let timeout = 0;
      const fail = (cause: unknown) => {
        if (settled) return;
        settled = true;
        window.clearTimeout(timeout);
        this.unsubscribe?.();
        this.unsubscribe = null;
        reject(cause instanceof Error ? cause : new Error(String(cause)));
      };
      timeout = window.setTimeout(() => fail(new Error('等待 eufy 画面出流超时')), START_TIMEOUT_MS);
      const subscribe = () => {
        if (generation !== this.generation) return fail(new Error('eufy 裸帧启动已取消'));
        if (Date.now() - startedAt >= START_TIMEOUT_MS) return fail(new Error('等待 eufy 画面出流超时'));
        try {
          const unsubscribe = sdk.subscribeMediaChannelFrames(this.stationSn, async (frame) => {
            if (generation !== this.generation || this.converting) return;
            this.converting = true;
            try {
              const image = await createImageBitmap(frame.blob);
              if (generation !== this.generation) { image.close(); return; }
              const previous = this.latest;
              this.latest = image;
              this.token += 1;
              previous?.close();
              if (!settled) {
                settled = true;
                window.clearTimeout(timeout);
                resolve();
              }
            } catch (cause) {
              if (!settled) fail(cause);
            } finally {
              this.converting = false;
            }
          }, {
            intervalMs: this.intervalMs,
            mimeType: 'image/jpeg',
            quality: 0.85,
            onError: (error) => { if (!settled) fail(error); },
          });
          if (settled) unsubscribe();
          else this.unsubscribe = unsubscribe;
        } catch (cause) {
          const message = cause instanceof Error ? cause.message : String(cause);
          const permanent = message.includes('仅支持 MediaChannel')
            || message.includes('不支持 MediaStreamTrackProcessor')
            || message.includes('无法获取设备');
          if (permanent) fail(cause);
          else window.setTimeout(subscribe, RETRY_MS);
        }
      };
      subscribe();
    });
  }

  async stop(): Promise<void> {
    this.generation += 1;
    this.unsubscribe?.();
    this.unsubscribe = null;
    this.latest?.close();
    this.latest = null;
    this.converting = false;
  }

  currentFrame(): CanvasImageSource {
    if (!this.latest) throw new Error('eufy 画面尚未就绪');
    return this.latest;
  }

  currentToken(): number {
    return this.token;
  }
}
