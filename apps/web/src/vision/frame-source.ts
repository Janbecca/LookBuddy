export type FrameSourceKind = 'webcam' | 'eufy_web_sdk';

export interface FrameSource {
  readonly kind: FrameSourceKind;
  start(video: HTMLVideoElement): Promise<void>;
  stop(): Promise<void>;
  currentFrame(): CanvasImageSource;
  currentToken(): number;
}

export class WebcamFrameSource implements FrameSource {
  readonly kind = 'webcam';
  private stream: MediaStream | null = null;
  private video: HTMLVideoElement | null = null;
  private generation = 0;

  async start(video: HTMLVideoElement): Promise<void> {
    await this.stop();
    const generation = ++this.generation;
    this.video = video;
    const stream = await navigator.mediaDevices.getUserMedia({
      video: { width: { ideal: 1280 }, height: { ideal: 720 }, facingMode: 'user' },
      audio: false,
    });
    if (generation !== this.generation) {
      stream.getTracks().forEach((track) => track.stop());
      throw new Error('摄像头启动已取消');
    }
    this.stream = stream;
    video.srcObject = stream;
    try {
      await video.play();
      if (generation !== this.generation) throw new Error('摄像头启动已取消');
    } catch (cause) {
      stream.getTracks().forEach((track) => track.stop());
      if (generation === this.generation) { video.srcObject = null; this.stream = null; }
      throw cause;
    }
  }

  async stop(): Promise<void> {
    this.generation += 1;
    this.stream?.getTracks().forEach((track) => track.stop());
    if (this.video) this.video.srcObject = null;
    this.stream = null;
  }

  currentFrame(): CanvasImageSource {
    if (!this.video || this.video.readyState < 2) throw new Error('摄像头画面尚未就绪');
    return this.video;
  }

  currentToken(): number {
    return this.video?.currentTime ?? -1;
  }
}

