export interface User {
  id: string;
  email: string;
  displayName: string;
  timezone: string;
  username?: string | null;
}

export interface Preferences {
  sedentaryMinutes: 30 | 60 | 90;
  breakMinutes: 5 | 10 | 15 | 20;
  busy: boolean;
  postureEnabled: boolean;
  companionEnabled: boolean;
  updatedAt: string | null;
}

export interface Intervention {
  id: string;
  sessionId: string;
  namespace: 'live' | 'demo';
  category: 'posture' | 'sedentary' | 'drink' | 'fatigue' | 'emotion';
  strategy: string;
  status: string;
  displayedAt: string | null;
  context: { preferences: Preferences; verification: Record<string, unknown>; observationStartedAt?: string };
  decision: Record<string, unknown>;
}

export type EmotionLabel = 'good' | 'focused' | 'joyful' | 'possibly_stressed' | 'possibly_low' | 'possibly_angry' | 'unknown';

export interface ExpressionFeatures {
  faceVisible: boolean;
  frontal: boolean;
  lightingOk: boolean;
  smile: number;
  browDown: number;
  frown: number;
  mouthPress: number;
  lookDown: number;
  eyeClosed: number;
}

export interface ExpressionObservation {
  id: string;
  sessionId: string;
  namespace: 'live' | 'demo';
  capturedAt: string;
  quality: 'observable' | 'insufficient' | 'unavailable';
  suggestedLabel: EmotionLabel;
  confidence: number;
  stableDurationMs: number;
  reasonCodes: string[];
  interpretation: 'visible_expression_hypothesis';
  features: ExpressionFeatures;
}

export interface ExpressionInput {
  expressionId: string;
  sessionId: string;
  namespace: 'live' | 'demo';
  source: 'webcam' | 'eufy_web_sdk' | 'simulated' | 'replay';
  capturedAt: string;
  cameraHealthy: boolean;
  quality: ExpressionObservation['quality'];
  features: ExpressionFeatures;
}

export interface VisionSample {
  frameId: string;
  capturedAt: string;
  source: 'webcam' | 'eufy_web_sdk' | 'simulated' | 'replay';
  cameraHealthy: boolean;
  quality: 'observable' | 'insufficient' | 'unavailable';
  posture: 'normal' | 'bad' | 'unknown';
  presence: 'present' | 'seated' | 'standing' | 'away' | 'unknown';
  standingConfidence: number;
}

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  user: User;
}

export interface Reminder {
  id: string;
  sessionId: string;
  category: string;
  priority: number;
  status: string;
  message: string;
  reasonCodes: string[];
  createdAt: string;
  expiresAt: string;
}

export interface DomainEvent {
  schema: 'lookbuddy.event';
  version: 1;
  eventId: string;
  type: string;
  deviceId?: string;
  sessionId?: string;
  seq?: number;
  occurredAt: string;
  source: {
    kind: string;
    provider: string;
    sdk?: string;
  };
  confidence?: number;
  durationMs?: number;
  payload: Record<string, unknown>;
  evidence?: { method: string; mediaRef?: string };
  privacy: { containsImage: boolean; retention: string };
}

export interface VisionMetrics {
  capturedAt: string;
  presence: boolean;
  faceVisible: boolean;
  poseVisible: boolean;
  faceWidth: number | null;
  shoulderTiltDeg: number | null;
  leanDeg: number | null;
  eyeClosed: number;
  jawOpen: number;
  wristMouthDistance: number | null;
  badPosture: boolean;
  postureConfidence: number;
  motion: { hipY: number; torsoHeight: number; kneeAngle: number } | null;
  expression: { features: ExpressionFeatures; quality: ExpressionObservation['quality'] };
}

