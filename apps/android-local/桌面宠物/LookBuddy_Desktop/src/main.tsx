import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';
import manifest from '../public/pets/manifest.json';

type Pet = { id: string; name: string; animation: string; still: string; width: number; height: number; bounds?: number[]; last?: string; durationMs?: number };
const bridge = window.lookbuddy;
const defaultState: PetState = { index: 0, width: 233, bubble: true, auto: false, paused: false };
const resizeEdges: ResizeEdge[] = ['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w'];
const edgeLabels: Record<ResizeEdge, string> = { nw: '左上角', n: '上边框', ne: '右上角', e: '右边框', se: '右下角', s: '下边框', sw: '左下角', w: '左边框' };

function App() {
  const [pets, setPets] = useState<Pet[]>([]);
  const [state, setState] = useState<PetState>(defaultState);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [loaded, setLoaded] = useState(false);
  const [finished, setFinished] = useState(false);
  const [editing, setEditing] = useState(false);
  const [geometry, setGeometry] = useState<WindowGeometry>({ width: 233, height: 233, limits: { min: 138, max: 920, default: 233 }, editing: false });
  const [viewport, setViewport] = useState({ width: window.innerWidth, height: window.innerHeight });
  const [remoteMessage, setRemoteMessage] = useState('');
  const [remoteScene, setRemoteScene] = useState({ action: '', event: '', completed: false });
  const mask = useRef<HTMLCanvasElement | null>(null);
  const dragging = useRef(false);
  const resizing = useRef(false);
  const editingRef = useRef(false);
  const lastHit = useRef(true);
  const pet = pets.find(item => item.id === remoteScene.action) || pets[state.index];
  useEffect(() => { setFinished(false); }, [pet?.id, remoteScene.event, state.paused]);
  useEffect(() => {
    if (!loaded || !pet?.last || !pet.durationMs || state.paused) return;
    const timer = window.setTimeout(() => setFinished(true), pet.durationMs);
    return () => window.clearTimeout(timer);
  }, [pet, remoteScene.event, state.paused, loaded]);
  const change = (offset: number) => { setRemoteScene(current => ({...current, action: ''})); setLoaded(false); setState(current => ({ ...current, index: (current.index + offset + pets.length) % pets.length })); };

  useEffect(() => {
    Promise.all([Promise.resolve(manifest as Pet[]), bridge.readState()])
      .then(([items, saved]) => {
        if (!items.length) throw new Error('尚未导入 GIF');
        setPets(items); setState({ ...defaultState, ...saved, index: Math.max(0, Math.min(saved.index || 0, items.length - 1)) }); setReady(true);
      }).catch(error => setError(String(error.message)));
  }, []);

  useEffect(() => {
    const applySync = (next: DesktopSyncSnapshot | null) => {
      if (!next) return;
      const action = next.scene_action_id || '';
      const event = next.scene_event_id || '';
      const completed = Boolean(next.scene_completed);
      setRemoteScene(current => current.action === action && current.event === event && current.completed === completed
        ? current : { action, event, completed });
      setRemoteMessage((next.scene_message ?? next.message ?? '').trim());
    };
    bridge.readSyncState().then(applySync).catch(() => {});
    const unsubscribe = bridge.onSyncUpdated(applySync);
    return unsubscribe;
  }, []);

  useEffect(() => { if (ready) bridge.saveState(state); }, [state, ready]);
  useEffect(() => {
    const update = (next: WindowGeometry) => {
      setGeometry(next); setEditing(next.editing); editingRef.current = next.editing;
      setState(current => ({ ...current, width: next.width }));
    };
    const unsubscribe = bridge.onGeometry(update);
    bridge.readGeometry().then(update).catch(error => setError(error.message));
    const resized = () => setViewport({ width: window.innerWidth, height: window.innerHeight });
    window.addEventListener('resize', resized);
    return () => { unsubscribe(); window.removeEventListener('resize', resized); };
  }, []);
  useEffect(() => {
    if (!editing) return;
    const finish = (event: KeyboardEvent) => {
      if (event.key === 'Escape' || event.key === 'Enter') { event.preventDefault(); bridge.resizeMode(false); }
    };
    window.addEventListener('keydown', finish);
    return () => window.removeEventListener('keydown', finish);
  }, [editing]);
  useEffect(() => {
    if (!ready) return;
    return bridge.onAction(action => {
      if (action.type === 'next') change(1);
      else if (action.type === 'previous') change(-1);
      else if (action.type === 'pause') setState(current => ({ ...current, paused: !current.paused }));
      else if (action.type === 'select' && action.index !== undefined && pets[action.index]) { setRemoteScene(current => ({...current, action: ''})); setLoaded(false); setState(current => ({ ...current, index: action.index! })); }
      else if (action.type === 'bubble') setState(current => ({ ...current, bubble: !current.bubble }));
      else if (action.type === 'auto') setState(current => ({ ...current, auto: !current.auto }));
      else if (action.type === 'resize-mode') { editingRef.current = Boolean(action.enabled); setEditing(Boolean(action.enabled)); lastHit.current = true; }
    });
  }, [ready, pets]);

  useEffect(() => {
    if (!ready || !state.auto || state.paused || editing || remoteScene.action) return;
    const timer = setInterval(() => change(1), 15000);
    return () => clearInterval(timer);
  }, [ready, state.auto, state.paused, pets, editing, remoteScene.action]);

  useEffect(() => {
    if (!pet) return;
    mask.current = null;
    const image = new Image();
    image.src = pet.still;
    image.onload = () => {
      const canvas = document.createElement('canvas'); canvas.width = image.naturalWidth; canvas.height = image.naturalHeight;
      if (pet.bounds) {
        const [left, top, right, bottom] = pet.bounds;
        canvas.width = right - left; canvas.height = bottom - top;
        canvas.getContext('2d', { willReadFrequently: true })!.drawImage(image, left, top, canvas.width, canvas.height, 0, 0, canvas.width, canvas.height);
      } else canvas.getContext('2d', { willReadFrequently: true })!.drawImage(image, 0, 0);
      mask.current = canvas;
    };
    return () => { image.onload = null; };
  }, [pet]);

  useEffect(() => bridge.onCursor(({ x, y }) => {
    if (dragging.current || resizing.current || editingRef.current) return;
    const element = document.elementFromPoint(x, y);
    let hit = Boolean(element?.closest('[data-interactive]'));
    const petElement = element?.closest('.pet-crop') || (element instanceof HTMLImageElement && element.classList.contains('pet-image') ? element : null);
    if (petElement && mask.current) {
      const rect = petElement.getBoundingClientRect();
      const mx = Math.min(mask.current.width - 1, Math.max(0, Math.floor((x - rect.left) / rect.width * mask.current.width)));
      const my = Math.min(mask.current.height - 1, Math.max(0, Math.floor((y - rect.top) / rect.height * mask.current.height)));
      // Add a small tolerance for movement between animation frames.
      const sx = Math.max(0, mx - 12), sy = Math.max(0, my - 12);
      const pixels = mask.current.getContext('2d')!.getImageData(sx, sy, Math.min(25, mask.current.width - sx), Math.min(25, mask.current.height - sy)).data;
      hit = pixels.some((value, index) => index % 4 === 3 && value > 30);
    }
    if (lastHit.current !== hit) { lastHit.current = hit; bridge.setHit(hit); }
  }), []);

  const startDrag = (event: React.PointerEvent<HTMLElement>) => {
    if (event.button !== 0) return;
    event.preventDefault(); event.currentTarget.setPointerCapture(event.pointerId);
    dragging.current = true; bridge.dragStart({ x: event.screenX, y: event.screenY });
  };
  const moveDrag = (event: React.PointerEvent<HTMLElement>) => { if (dragging.current) bridge.dragMove({ x: event.screenX, y: event.screenY }); };
  const endDrag = (event: React.PointerEvent<HTMLElement>) => { if (dragging.current) { dragging.current = false; bridge.dragEnd({ x: event.screenX, y: event.screenY }); } };
  const cancelDrag = () => { if (dragging.current) { dragging.current = false; bridge.dragEnd(); } };
  const startResize = (event: React.PointerEvent<HTMLElement>, edge: ResizeEdge) => {
    if (event.button !== 0) return;
    event.preventDefault(); event.stopPropagation(); event.currentTarget.setPointerCapture(event.pointerId);
    resizing.current = true; bridge.resizeStart(edge, { x: event.screenX, y: event.screenY });
  };
  const moveResize = (event: React.PointerEvent<HTMLElement>) => { if (resizing.current) bridge.resizeMove({ x: event.screenX, y: event.screenY }); };
  const endResize = (event: React.PointerEvent<HTMLElement>) => { if (resizing.current) { resizing.current = false; bridge.resizeEnd({ x: event.screenX, y: event.screenY }); } };
  const cancelResize = () => { if (resizing.current) { resizing.current = false; bridge.resizeEnd(); } };
  const nudgeResize = (event: React.KeyboardEvent, edge: ResizeEdge) => {
    if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return;
    event.preventDefault();
    const step = event.shiftKey ? 20 : 4;
    bridge.resizeNudge(edge, ['ArrowRight', 'ArrowUp'].includes(event.key) ? step : -step);
  };

  if (error) return <main className="error" data-interactive><p>宠物暂时没能出来</p><small>{error}</small><button onClick={() => location.reload()}>重新加载</button></main>;
  if (!pet) return null;
  const scale = Math.min(viewport.width, viewport.height) / 460;
  const percent = Math.round(geometry.width / 233 * 100);
  const bounds = pet.bounds;
  const cropScale = bounds ? Math.min(420 / (bounds[2] - bounds[0]), 330 / (bounds[3] - bounds[1])) : 1;
  const petImage = <img key={`${pet.id}:${remoteScene.event}:${state.paused}`} className={`pet-image ${loaded ? 'loaded' : ''}`} src={state.paused ? pet.still : (finished || remoteScene.completed) && pet.last ? pet.last : `${pet.animation}?event=${encodeURIComponent(remoteScene.event)}`} alt={pet.name} draggable={false}
    style={bounds ? { position: 'absolute', maxWidth: 'none', maxHeight: 'none', width: pet.width * cropScale, height: pet.height * cropScale, left: -bounds[0] * cropScale, top: -bounds[1] * cropScale } : undefined}
    width={pet.width} height={pet.height} onLoad={() => setLoaded(true)} onError={() => setError(`无法加载动作：${pet.name}`)}
    onPointerDown={startDrag} onPointerMove={moveDrag} onPointerUp={endDrag} onPointerCancel={cancelDrag} onLostPointerCapture={cancelDrag} />;
  return <div className={`viewport ${editing ? 'is-editing' : ''}`} onContextMenu={event => event.preventDefault()}>
  <main className="companion" style={{ transform: `scale(${scale})`, left: (viewport.width - 460 * scale) / 2, top: (viewport.height - 460 * scale) / 2 }}>
    <div className="speech-slot">
      {state.bubble && remoteMessage && <div className="bubble" data-interactive role="status" aria-live="polite" onPointerDown={startDrag} onPointerMove={moveDrag} onPointerUp={endDrag} onPointerCancel={cancelDrag} onLostPointerCapture={cancelDrag}>
        <span className="bubble-text">{remoteMessage}</span>
      </div>}
    </div>
    <div className="pet-stage">
      {bounds ? <div className="pet-crop" style={{ position: 'relative', overflow: 'hidden', pointerEvents: 'auto', width: (bounds[2] - bounds[0]) * cropScale, height: (bounds[3] - bounds[1]) * cropScale }}>{petImage}</div> : petImage}
    </div>
  </main>
  {editing && <section className="resize-overlay" aria-label="调整宠物大小">
    <div className="resize-outline" />
    <span className="resize-readout" role="status" aria-live="off">{percent}% <span>等比缩放</span></span>
    <button className="resize-done" data-interactive onClick={() => bridge.resizeMode(false)}>完成</button>
    <div className="resize-guide" data-interactive>
      <span><span className="resize-guide-label">拖动边框 · </span>{Math.round(geometry.limits.min / 233 * 100)}%–{Math.round(geometry.limits.max / 233 * 100)}%</span>
      <button onClick={() => bridge.resetSize()}>重置</button>
    </div>
    {resizeEdges.map(edge => <button key={edge} className={`resize-handle handle-${edge}`} data-interactive data-resize-edge={edge}
      aria-label={`拖动${edgeLabels[edge]}等比缩放`} title="拖动等比缩放；方向键微调，Shift 加速"
      onPointerDown={event => startResize(event, edge)} onPointerMove={moveResize} onPointerUp={endResize} onPointerCancel={cancelResize} onLostPointerCapture={cancelResize}
      onKeyDown={event => nudgeResize(event, edge)} />)}
  </section>}
  </div>;
}

createRoot(document.getElementById('root')!).render(<App />);
