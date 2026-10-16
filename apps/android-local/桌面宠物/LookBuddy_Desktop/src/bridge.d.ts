type ResizeEdge = 'n' | 'ne' | 'e' | 'se' | 's' | 'sw' | 'w' | 'nw';
type WindowGeometry = { width: number; height: number; limits: { min: number; max: number; default: number }; editing: boolean };
type PetState = { index: number; width: number; bubble: boolean; auto: boolean; paused: boolean };
type PetAction = { type: 'next' | 'previous' | 'pause' | 'bubble' | 'auto' | 'select' | 'resize-mode'; index?: number; enabled?: boolean };
type DesktopSyncSnapshot = {
  revision: number;
  connected: boolean;
  client_id: string;
  scene: string;
  working: boolean;
  mood: string;
  message: string;
  last_seen_at: number | null;
  visible: boolean;
  action_id: string;
  paused: boolean;
  bubble: boolean;
  display_message?: string;
  scene_action_id?: string;
  scene_event_id?: string;
  scene_message?: string;
  scene_completed?: boolean;
};
interface Window {
  lookbuddy: {
    readState(): Promise<PetState>;
    saveState(state: PetState): void;
    readSyncState(): Promise<DesktopSyncSnapshot | null>;
    onSyncUpdated(callback: (snapshot: DesktopSyncSnapshot) => void): () => void;
    readGeometry(): Promise<WindowGeometry>;
    resizeMode(enabled: boolean): void;
    resizeStart(edge: ResizeEdge, point: { x: number; y: number }): void;
    resizeMove(point: { x: number; y: number }): void;
    resizeEnd(point?: { x: number; y: number }): void;
    resizeNudge(edge: ResizeEdge, delta: number): void;
    resetSize(): void;
    onGeometry(callback: (geometry: WindowGeometry) => void): () => void;
    dragStart(point: { x: number; y: number }): void;
    dragMove(point: { x: number; y: number }): void;
    dragEnd(point?: { x: number; y: number }): void;
    setHit(hit: boolean): void;
    onCursor(callback: (point: { x: number; y: number }) => void): () => void;
    onAction(callback: (action: PetAction) => void): () => void;
  };
}
