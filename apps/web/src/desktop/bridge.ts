import type { EmotionLabel } from '../types';
export interface DesktopState {
  loggedIn: boolean; running: boolean; busy: boolean; status: string;
  emotion: EmotionLabel; posture: 'normal' | 'bad' | 'unknown'; reminderId: string | null; message: string;
}
export type DesktopCommand = { type: 'pause' } | { type: 'busy'; busy: boolean } |
  { type: 'accepted' | 'snoozed' | 'ignored'; reminderId: string };
export interface DesktopBridge {
  role: 'dashboard' | 'pet';
  smoke: boolean;
  state(): Promise<DesktopState>;
  onState(callback: (state: DesktopState) => void): () => void;
  onCommand(callback: (command: DesktopCommand) => void): () => void;
  publish(state: DesktopState): Promise<boolean>;
  command(command: DesktopCommand): Promise<boolean>;
  showDashboard(): Promise<void>; hideDashboard(): Promise<void>; quit(): Promise<void>;
}
declare global { interface Window { lookbuddyDesktop?: DesktopBridge; lookbuddyModelProbe?: () => Promise<boolean> } }
export const desktop = typeof window === 'undefined' ? undefined : window.lookbuddyDesktop;
export const INITIAL_DESKTOP_STATE: DesktopState = { loggedIn: false, running: false, busy: false, status: '请在调试窗口登录', emotion: 'unknown', posture: 'unknown', reminderId: null, message: '' };
