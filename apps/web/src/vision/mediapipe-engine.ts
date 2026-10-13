import { FaceLandmarker, FilesetResolver, PoseLandmarker } from '@mediapipe/tasks-vision';
import type { VisionMetrics } from '../types';
import { expressionFeatures, ExpressionSmoother } from './expression-features';

type Point = { x: number; y: number; z?: number; visibility?: number };

function midpoint(a: Point, b: Point): Point {
  return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
}

function degrees(value: number): number {
  return (value * 180) / Math.PI;
}

export class MediaPipeVisionEngine {
  private face: FaceLandmarker | null = null;
  private pose: PoseLandmarker | null = null;
  private baseline: { shoulderTiltDeg: number; leanDeg: number; faceWidth: number } | null = null;
  private calibration: VisionMetrics[] = [];
  private expressionSmoother = new ExpressionSmoother();
  private lightCanvas: HTMLCanvasElement | null = null;
  private lastLightAt = -Infinity;
  private lightingOk = false;

  get isCalibrated(): boolean { return this.baseline !== null; }

  async initialize(): Promise<void> {
    const files = await FilesetResolver.forVisionTasks('/wasm');
    const create = async (delegate: 'GPU' | 'CPU') => {
      this.face = await FaceLandmarker.createFromOptions(files, {
        baseOptions: { modelAssetPath: '/models/face_landmarker.task', delegate },
        runningMode: 'VIDEO',
        numFaces: 1,
        outputFaceBlendshapes: true,
      });
      this.pose = await PoseLandmarker.createFromOptions(files, {
        baseOptions: { modelAssetPath: '/models/pose_landmarker_lite.task', delegate },
        runningMode: 'VIDEO',
        numPoses: 1,
      });
    };
    try {
      await create('GPU');
    } catch {
      this.face?.close();
      this.pose?.close();
      await create('CPU');
    }
  }

  detect(frame: CanvasImageSource, timestampMs: number): VisionMetrics {
    if (!this.face || !this.pose) throw new Error('视觉模型尚未初始化');
    const faceResult = this.face.detectForVideo(frame as HTMLVideoElement, timestampMs);
    const poseResult = this.pose.detectForVideo(frame as HTMLVideoElement, timestampMs);
    const face = faceResult.faceLandmarks[0] as Point[] | undefined;
    const pose = poseResult.landmarks[0] as Point[] | undefined;
    const blend = new Map((faceResult.faceBlendshapes[0]?.categories ?? []).map((item) => [item.categoryName, item.score]));

    let faceWidth: number | null = null;
    if (face?.length) {
      const xs = face.map((point) => point.x);
      faceWidth = Math.max(...xs) - Math.min(...xs);
    }

    let shoulderTiltDeg: number | null = null;
    let leanDeg: number | null = null;
    if (pose?.length && (pose[11]?.visibility ?? 1) > 0.4 && (pose[12]?.visibility ?? 1) > 0.4) {
      const leftShoulder = pose[11];
      const rightShoulder = pose[12];
      shoulderTiltDeg = Math.abs(degrees(Math.atan2(rightShoulder.y - leftShoulder.y, rightShoulder.x - leftShoulder.x)));
      if (pose[23] && pose[24]) {
        const shoulder = midpoint(leftShoulder, rightShoulder);
        const hip = midpoint(pose[23], pose[24]);
        leanDeg = Math.abs(degrees(Math.atan2(shoulder.x - hip.x, Math.max(Math.abs(hip.y - shoulder.y), 0.01))));
      }
    }

    let wristMouthDistance: number | null = null;
    if (face?.[13] && face?.[14] && pose?.[15] && pose?.[16]) {
      const mouth = midpoint(face[13], face[14]);
      const distance = (point: Point) => Math.hypot((point.x - mouth.x) * 16 / 9, point.y - mouth.y);
      wristMouthDistance = Math.min(distance(pose[15]), distance(pose[16]));
    }

    let motion: VisionMetrics['motion'] = null;
    const video = frame as HTMLVideoElement;
    const bitmap = frame as ImageBitmap;
    const width = video.videoWidth || bitmap.width || 0;
    const height = video.videoHeight || bitmap.height || 0;
    const aspect = width && height ? width / height : 16 / 9;
    if (pose && [11, 12, 23, 24].every((index) => (pose[index]?.visibility ?? 0) >= 0.6)) {
      const legAngles: number[] = [];
      for (const [hipIndex, kneeIndex, ankleIndex] of [[23, 25, 27], [24, 26, 28]]) {
        if (![hipIndex, kneeIndex, ankleIndex].every((index) => (pose[index]?.visibility ?? 0) >= 0.6)) continue;
        const hip = pose[hipIndex], knee = pose[kneeIndex], ankle = pose[ankleIndex];
        const ax = (hip.x - knee.x) * aspect, ay = hip.y - knee.y;
        const bx = (ankle.x - knee.x) * aspect, by = ankle.y - knee.y;
        const denominator = Math.hypot(ax, ay) * Math.hypot(bx, by);
        if (denominator > 0.001) legAngles.push(degrees(Math.acos(Math.max(-1, Math.min(1, (ax * bx + ay * by) / denominator)))));
      }
      const hipY = (pose[23].y + pose[24].y) / 2;
      const torsoHeight = Math.abs(hipY - (pose[11].y + pose[12].y) / 2);
      if (legAngles.length && torsoHeight > 0.05) motion = { hipY, torsoHeight, kneeAngle: Math.min(...legAngles) };
    }

    const postureConfidence = this.baseline && shoulderTiltDeg !== null && leanDeg !== null && faceWidth !== null
      ? Math.min(1, Math.max(
          Math.abs(shoulderTiltDeg - this.baseline.shoulderTiltDeg) / 9,
          Math.abs(leanDeg - this.baseline.leanDeg) / 11,
          faceWidth / this.baseline.faceWidth > 1.16 ? 0.8 : 0,
        ))
      : 0;

    if (timestampMs - this.lastLightAt >= 1000) {
      this.lastLightAt = timestampMs;
      this.lightCanvas ??= document.createElement('canvas');
      this.lightCanvas.width = this.lightCanvas.height = 32;
      try {
        const context = this.lightCanvas.getContext('2d', { willReadFrequently: true });
        if (!context) throw new Error('No image quality context');
        context.drawImage(frame, 0, 0, 32, 32);
        const pixels = context.getImageData(0, 0, 32, 32).data;
        let luminance = 0;
        for (let index = 0; index < pixels.length; index += 4) luminance += pixels[index] * 0.299 + pixels[index + 1] * 0.587 + pixels[index + 2] * 0.114;
        luminance /= 32 * 32;
        this.lightingOk = luminance >= 45 && luminance <= 215;
      } catch { this.lightingOk = false; }
    }
    const leftDistance = face?.[1] && face?.[33] ? Math.abs(face[1].x - face[33].x) : 0;
    const rightDistance = face?.[1] && face?.[263] ? Math.abs(face[263].x - face[1].x) : 0;
    const frontal = Boolean(faceWidth && faceWidth >= 0.08 && faceWidth <= 0.65 && rightDistance > 0.001
      && leftDistance / rightDistance >= 0.35 && leftDistance / rightDistance <= 2.8);
    const expression = this.expressionSmoother.update(expressionFeatures(blend, { faceVisible: Boolean(face), frontal, lightingOk: this.lightingOk }), timestampMs);

    return {
      capturedAt: new Date().toISOString(),
      presence: Boolean(face || pose),
      faceVisible: Boolean(face),
      poseVisible: Boolean(pose),
      faceWidth,
      shoulderTiltDeg,
      leanDeg,
      eyeClosed: ((blend.get('eyeBlinkLeft') ?? 0) + (blend.get('eyeBlinkRight') ?? 0)) / 2,
      jawOpen: blend.get('jawOpen') ?? 0,
      wristMouthDistance,
      badPosture: postureConfidence >= 0.55,
      postureConfidence,
      motion,
      expression: { features: expression, quality: expression.faceVisible && expression.frontal && expression.lightingOk ? 'observable' : 'insufficient' },
    };
  }

  addCalibrationSample(metrics: VisionMetrics): void {
    if (metrics.faceWidth && metrics.shoulderTiltDeg !== null && metrics.leanDeg !== null) this.calibration.push(metrics);
  }

  finishCalibration(): boolean {
    if (this.calibration.length < 20) return false;
    const median = (values: number[]) => values.sort((a, b) => a - b)[Math.floor(values.length / 2)];
    this.baseline = {
      faceWidth: median(this.calibration.map((item) => item.faceWidth!)),
      shoulderTiltDeg: median(this.calibration.map((item) => item.shoulderTiltDeg!)),
      leanDeg: median(this.calibration.map((item) => item.leanDeg!)),
    };
    this.calibration = [];
    return true;
  }

  close(): void {
    this.face?.close();
    this.pose?.close();
  }
}

